package com.patbaumgartner.socialcrossposter.notify;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.springframework.stereotype.Component;

/**
 * Sends one notification mail over a minimal hand-rolled SMTP client (UC-12 third slice,
 * step 4) — deliberately no mail dependency (keeps the Dependency Review clean). Supports
 * the three canonical modes enforced by {@link OutboundSmtpGuard}: NONE (plain,
 * allow-listed hosts only), STARTTLS (587) and IMPLICIT_TLS (465). The flow is EHLO,
 * optional STARTTLS upgrade (then re-EHLO), optional AUTH LOGIN (base64), MAIL FROM, RCPT
 * TO per recipient, DATA with a dot-stuffed CRLF body, QUIT.
 *
 * <p>
 * The target is re-validated by the SSRF guard immediately before connecting (DNS
 * re-resolved — rebinding protection, arc42 section 8). TLS sessions validate the
 * certificate chain against the JVM trust store and verify the hostname (endpoint
 * identification) against the configured SMTP host. All header values are sanitized to
 * printable ASCII (CR/LF stripped — no header injection); the body is the shared redacted
 * {@link NotificationPayload} as UTF-8 {@code text/plain}. Per RFC 5321, 5xx replies are
 * permanent failures and 4xx replies plus transport errors are transient (retryable);
 * server reply text is never surfaced — details carry only the status code and stage.
 */
@Component
public class SmtpSender {

	private static final String EHLO_DOMAIN = "social-cross-poster";

	private final OutboundSmtpGuard guard;

	private final int connectTimeoutMs;

	private final int readTimeoutMs;

	public SmtpSender(NotifyProperties properties, OutboundSmtpGuard guard) {
		this.guard = guard;
		this.connectTimeoutMs = properties.connectTimeoutMs().intValue();
		this.readTimeoutMs = properties.readTimeoutMs().intValue();
	}

	/**
	 * Sends {@code payload} to the SMTP channel. Never throws for expected failure modes
	 * — the result carries a redacted detail (SMTP status code + stage, or guard reason).
	 */
	public SendResult send(NotificationChannel channel, String password, NotificationPayload payload) {
		try {
			// re-validate at send time — DNS re-resolved (arc42 section 8)
			this.guard.validate(channel.smtpHost(), channel.smtpPort() == null ? 0 : channel.smtpPort(),
					channel.smtpTlsMode());
		}
		catch (NotificationException ex) {
			return SendResult.blocked(ex.getMessage());
		}
		List<String> recipients = channel.recipients();
		if (recipients.isEmpty()) {
			return SendResult.permanent("SMTP channel has no recipients");
		}
		try {
			return doSend(channel, password, payload, recipients);
		}
		catch (SmtpFailure failure) {
			return failure.permanent ? SendResult.permanent(failure.getMessage())
					: SendResult.retryable(failure.getMessage());
		}
		catch (IOException | GeneralSecurityException | RuntimeException ex) {
			return SendResult.retryable("transport error");
		}
	}

	private SendResult doSend(NotificationChannel channel, String password, NotificationPayload payload,
			List<String> recipients) throws IOException, GeneralSecurityException {
		String host = channel.smtpHost();
		int port = channel.smtpPort();
		String tlsMode = channel.smtpTlsMode() == null ? OutboundSmtpGuard.TLS_NONE : channel.smtpTlsMode();
		Socket raw = new Socket();
		try {
			raw.connect(new InetSocketAddress(host, port), this.connectTimeoutMs);
			raw.setSoTimeout(this.readTimeoutMs);
			Session session;
			if (OutboundSmtpGuard.TLS_IMPLICIT.equals(tlsMode)) {
				session = new Session(raw).tlsUpgrade(host); // TLS from the first byte
				expect(session.readReply(), 220, "greeting");
				ehlo(session);
			}
			else {
				session = new Session(raw);
				expect(session.readReply(), 220, "greeting");
				String extensions = ehlo(session);
				if (OutboundSmtpGuard.TLS_STARTTLS.equals(tlsMode)) {
					if (!extensions.contains("STARTTLS")) {
						throw new SmtpFailure("SMTP server did not offer STARTTLS", true);
					}
					expect(session.command("STARTTLS"), 220, "STARTTLS");
					session = session.tlsUpgrade(host);
					ehlo(session); // re-EHLO on the encrypted channel
				}
			}
			try {
				authenticate(session, channel, password);
				int mail = session.command("MAIL FROM:<" + sanitize(channel.smtpFrom()) + ">");
				if (mail != 250) {
					throw failure(mail, "MAIL FROM");
				}
				for (String recipient : recipients) {
					int rcpt = session.command("RCPT TO:<" + sanitize(recipient) + ">");
					if (rcpt != 250 && rcpt != 251) {
						throw failure(rcpt, "RCPT TO");
					}
				}
				expect(session.command("DATA"), 354, "DATA");
				session.writeRaw(message(channel, payload, recipients));
				int accepted = session.readReply();
				if (accepted != 250) {
					throw failure(accepted, "message body");
				}
				return SendResult.sent();
			}
			finally {
				try {
					session.command("QUIT");
				}
				catch (IOException ignored) {
					// best effort
				}
				try {
					session.socket.close();
				}
				catch (IOException ignored) {
					// best effort
				}
			}
		}
		finally {
			try {
				raw.close();
			}
			catch (IOException ignored) {
				// best effort
			}
		}
	}

	private void authenticate(Session session, NotificationChannel channel, String password) throws IOException {
		String username = channel.smtpUsername();
		if (username == null || username.isBlank()) {
			return; // no AUTH
		}
		expect(session.command("AUTH LOGIN"), 334, "AUTH");
		expect(session.command(Base64.getEncoder().encodeToString(username.getBytes(StandardCharsets.UTF_8))), 334,
				"AUTH username");
		int code = session.command(Base64.getEncoder()
			.encodeToString((password == null ? "" : password).getBytes(StandardCharsets.UTF_8)));
		if (code != 235) {
			throw failure(code, "AUTH");
		}
	}

	/** EHLO; returns the upper-cased extension keywords of the multi-line 250 reply. */
	private static String ehlo(Session session) throws IOException {
		session.writeLine("EHLO " + EHLO_DOMAIN);
		List<String> lines = session.readReplyLines();
		int code = replyCode(lines);
		if (code != 250) {
			throw failure(code, "EHLO");
		}
		StringBuilder extensions = new StringBuilder();
		for (int i = 1; i < lines.size(); i++) { // line 0 is the server greeting/domain
			String line = lines.get(i);
			extensions.append(line.length() >= 4 ? line.substring(4) : "").append(' ');
		}
		return extensions.toString().toUpperCase(java.util.Locale.ROOT);
	}

	private static void expect(int code, int wanted, String stage) throws IOException {
		if (code != wanted) {
			throw failure(code, stage);
		}
	}

	private static int replyCode(List<String> lines) throws IOException {
		String first = lines.get(0);
		try {
			return Integer.parseInt(first.substring(0, 3));
		}
		catch (NumberFormatException | StringIndexOutOfBoundsException ex) {
			throw new IOException("malformed SMTP reply");
		}
	}

	private static SmtpFailure failure(int code, String stage) {
		// RFC 5321: 5xx = permanent failure, 4xx = transient (retryable)
		boolean permanent = code >= 500 && code < 600;
		return new SmtpFailure("SMTP " + code + " (" + stage + ")", permanent);
	}

	/** Headers (ASCII-sanitized) plus the dot-stuffed CRLF body. */
	private static byte[] message(NotificationChannel channel, NotificationPayload payload, List<String> recipients) {
		StringBuilder msg = new StringBuilder(1024);
		msg.append("From: ").append(header(channel.smtpFrom())).append("\r\n");
		msg.append("To: ").append(header(String.join(", ", recipients))).append("\r\n");
		msg.append("Subject: ")
			.append(header("SCP notification " + payload.category() + " (workspace #" + payload.workspaceId() + ")"))
			.append("\r\n");
		msg.append("Date: ")
			.append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneId.of("GMT"))))
			.append("\r\n");
		msg.append("Message-ID: <")
			.append(payload.eventId())
			.append('-')
			.append(System.currentTimeMillis())
			.append("@social-cross-poster>\r\n");
		msg.append("MIME-Version: 1.0\r\n");
		msg.append("Content-Type: text/plain; charset=utf-8\r\n");
		msg.append("Content-Transfer-Encoding: 8bit\r\n");
		msg.append("X-SCP-Event-Id: ").append(payload.eventId()).append("\r\n");
		msg.append("\r\n");
		String body = payload.toJson().replace("\r\n", "\n").replace('\r', '\n');
		for (String line : body.split("\n", -1)) {
			msg.append(line.startsWith(".") ? "." + line : line).append("\r\n"); // dot-stuffing
		}
		msg.append(".\r\n");
		return msg.toString().getBytes(StandardCharsets.UTF_8);
	}

	/** Strips CR/LF and non-ASCII from a header value (header-injection safe). */
	private static String header(String value) {
		if (value == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			out.append(c == '\r' || c == '\n' || c < 0x20 || c > 0x7E ? ' ' : c);
		}
		return out.toString();
	}

	/** Command-argument sanitizer (addresses are pre-validated; defense in depth). */
	private static String sanitize(String address) {
		return header(address).replace("<", "").replace(">", "").trim();
	}

	/**
	 * A classified protocol failure: only the status code and stage, never server text.
	 * Unchecked so the protocol helpers stay free of {@code throws} clutter;
	 * {@link #send} catches it and maps it to a redacted {@link SendResult}.
	 */
	private static final class SmtpFailure extends RuntimeException {

		private final boolean permanent;

		SmtpFailure(String message, boolean permanent) {
			super(message);
			this.permanent = permanent;
		}

	}

	/** One SMTP session: byte-exact line reading (safe for the STARTTLS upgrade). */
	private static final class Session {

		private final Socket socket;

		private final InputStream in;

		private final OutputStream out;

		Session(Socket socket) throws IOException {
			this.socket = socket;
			this.in = socket.getInputStream();
			this.out = socket.getOutputStream();
		}

		int command(String command) throws IOException {
			writeLine(command);
			return readReply();
		}

		void writeLine(String line) throws IOException {
			this.out.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
			this.out.flush();
		}

		void writeRaw(byte[] bytes) throws IOException {
			this.out.write(bytes);
			this.out.flush();
		}

		/** Reads one reply (multi-line aware) and returns the 3-digit status code. */
		int readReply() throws IOException {
			return replyCode(readReplyLines());
		}

		/** Reads one full reply, returning every line including continuations. */
		List<String> readReplyLines() throws IOException {
			List<String> lines = new ArrayList<>();
			String line = readLine();
			if (line == null) {
				throw new IOException("connection closed by server");
			}
			lines.add(line);
			while (line.length() >= 4 && line.charAt(3) == '-') {
				line = readLine();
				if (line == null) {
					throw new IOException("connection closed mid-reply");
				}
				lines.add(line);
			}
			return lines;
		}

		/** Byte-by-byte line read — never over-reads, so a TLS upgrade loses no bytes. */
		private String readLine() throws IOException {
			ByteArrayOutputStream buf = new ByteArrayOutputStream(80);
			int c;
			while ((c = this.in.read()) != -1) {
				if (c == '\n') {
					break;
				}
				buf.write(c);
			}
			if (c == -1 && buf.size() == 0) {
				return null;
			}
			byte[] b = buf.toByteArray();
			int len = b.length;
			if (len > 0 && b[len - 1] == '\r') {
				len--;
			}
			return new String(b, 0, len, StandardCharsets.ISO_8859_1);
		}

		/** Upgrades the connected socket to TLS, verifying the chain and the hostname. */
		Session tlsUpgrade(String peerHost) throws IOException, GeneralSecurityException {
			SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
			SSLSocket ssl = (SSLSocket) factory.createSocket(this.socket, peerHost, this.socket.getPort(), true);
			SSLParameters params = ssl.getSSLParameters();
			params.setEndpointIdentificationAlgorithm("HTTPS"); // hostname check against
																// the configured SMTP
																// host
			ssl.setSSLParameters(params);
			ssl.setUseClientMode(true);
			ssl.startHandshake();
			return new Session(ssl);
		}

	}

}
