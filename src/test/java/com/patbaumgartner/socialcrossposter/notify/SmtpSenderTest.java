package com.patbaumgartner.socialcrossposter.notify;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SmtpSender} (UC-12 third slice, step 4) against a scripted fake SMTP
 * server on 127.0.0.1 (allow-listed in the test guard): happy path with AUTH LOGIN
 * verifies the base64 exchange, MAIL/RCPT/DATA sequencing, CRLF line endings, the
 * {@code X-SCP-Event-Id} header and the dot-terminator; multiple RCPT TO recipients; 4xx
 * = permanent, 5xx = retryable; STARTTLS required but not offered = permanent; STARTTLS
 * offered then the server closes = retryable; IMPLICIT_TLS sends a TLS ClientHello first;
 * guard-blocked targets never hit the network; connection refused = retryable transport
 * error.
 */
class SmtpSenderTest {

	private static final List<String> ALLOW = List.of("127.0.0.1");

	private final SmtpSender sender = newSender(ALLOW);

	private final SmtpSender strictSender = newSender(List.of());

	private static SmtpSender newSender(List<String> allowList) {
		NotifyProperties props = new NotifyProperties(null, null, null, null, null, null, 2000L, 2000L, allowList);
		return new SmtpSender(props, new OutboundSmtpGuard(props));
	}

	private static NotificationChannel channel(String host, int port, String tlsMode, String username) {
		return new NotificationChannel(1L, 1L, "mail", null, "cipher", "nonce", "test-v1", "", true,
				OffsetDateTime.now(), NotificationChannel.TYPE_SMTP, host, port, tlsMode, "alerts@example.com",
				"ops@example.com, oncall@example.com", username);
	}

	private static NotificationPayload payload() {
		return new NotificationPayload(42L, "delivery:5:failed", 1L, "Private", "DELIVERY_FAILED", "redacted summary",
				100L, 5L, null, null, OffsetDateTime.now());
	}

	@Test
	void happyPathAuthLoginMultiRecipientDotStuffedBody() throws Exception {
		List<Step> steps = List.of(new Step("250-fake greets\r\n250-8BITMIME\r\n250 AUTH LOGIN\r\n"), // EHLO
				new Step("334 VXNlcm5hbWU6\r\n"), // AUTH LOGIN
				new Step("334 UGFzc3dvcmQ6\r\n"), // base64 username
				new Step("235 2.7.0 Authentication successful\r\n"), // base64 password
				new Step("250 OK\r\n"), // MAIL FROM
				new Step("250 OK\r\n"), // RCPT TO ops@example.com
				new Step("250 OK\r\n"), // RCPT TO oncall@example.com
				new Step("354 End data with <CR><LF>.<CR><LF>\r\n", "250 2.0.0 Ok: queued\r\n"), // DATA
																									// +
																									// body
				new Step("221 Bye\r\n") // QUIT
		);
		try (FakeSmtpServer server = new FakeSmtpServer(steps)) {
			SendResult result = this.sender.send(channel("127.0.0.1", server.port(), "NONE", "relay-user"), "s3cret!",
					payload());

			assertThat(result.outcome()).isEqualTo("SENT");
			List<String> lines = server.received;
			assertThat(lines).contains("EHLO social-cross-poster", "AUTH LOGIN",
					Base64.getEncoder().encodeToString("relay-user".getBytes(StandardCharsets.UTF_8)),
					Base64.getEncoder().encodeToString("s3cret!".getBytes(StandardCharsets.UTF_8)),
					"MAIL FROM:<alerts@example.com>", "RCPT TO:<ops@example.com>", "RCPT TO:<oncall@example.com>",
					"DATA", "QUIT");
			// CRLF line endings and the dot terminator on the wire
			String raw = server.rawAsString();
			assertThat(raw).contains("\r\n.\r\n");
			// redacted body carries the event id header and the JSON payload, never a
			// secret
			assertThat(server.dataBody).contains("X-SCP-Event-Id: 42")
				.contains("\"eventId\":42")
				.contains("\"category\":\"DELIVERY_FAILED\"")
				.contains("From: alerts@example.com")
				.contains("To: ops@example.com, oncall@example.com")
				.doesNotContain("s3cret!");
		}
	}

	@Test
	void rcptRejected550IsPermanent() throws Exception {
		List<Step> steps = List.of(new Step("250-fake\r\n250 AUTH LOGIN\r\n"), // EHLO
				new Step("250 OK\r\n"), // MAIL FROM
				new Step("550 5.1.1 No such user\r\n") // RCPT TO
		);
		try (FakeSmtpServer server = new FakeSmtpServer(steps)) {
			SendResult result = this.sender.send(channel("127.0.0.1", server.port(), "NONE", null), null, payload());
			assertThat(result.outcome()).isEqualTo("FAILED_PERMANENT");
			assertThat(result.detail()).contains("550").contains("RCPT TO").doesNotContain("No such user");
		}
	}

	@Test
	void mailFromDeferred450IsRetryable() throws Exception {
		List<Step> steps = List.of(new Step("250 fake relay\r\n"), new Step("450 4.2.0 Mailbox busy\r\n"));
		try (FakeSmtpServer server = new FakeSmtpServer(steps)) {
			SendResult result = this.sender.send(channel("127.0.0.1", server.port(), "NONE", null), null, payload());
			assertThat(result.outcome()).isEqualTo("FAILED_RETRYABLE");
			assertThat(result.detail()).contains("450").contains("MAIL FROM").doesNotContain("Mailbox busy");
		}
	}

	@Test
	void starttlsRequiredButNotOfferedIsPermanent() throws Exception {
		List<Step> steps = List.of(new Step("250-fake\r\n250 AUTH LOGIN\r\n")); // no
																				// STARTTLS
																				// extension
		try (FakeSmtpServer server = new FakeSmtpServer(steps)) {
			SendResult result = this.sender.send(channel("127.0.0.1", server.port(), "STARTTLS", null), null,
					payload());
			assertThat(result.outcome()).isEqualTo("FAILED_PERMANENT");
			assertThat(result.detail()).contains("STARTTLS");
			assertThat(server.received).doesNotContain("STARTTLS");
		}
	}

	@Test
	void starttlsOfferedThenServerClosesIsRetryable() throws Exception {
		List<Step> steps = List.of(new Step("250-fake\r\n250 STARTTLS\r\n"), // EHLO
																				// advertises
																				// STARTTLS
				new Step("220 Ready to start TLS\r\n", true) // reply then close
		);
		try (FakeSmtpServer server = new FakeSmtpServer(steps)) {
			SendResult result = this.sender.send(channel("127.0.0.1", server.port(), "STARTTLS", null), null,
					payload());
			assertThat(result.outcome()).isEqualTo("FAILED_RETRYABLE");
			assertThat(result.detail()).isEqualTo("transport error");
			assertThat(server.received).contains("STARTTLS");
		}
	}

	@Test
	void implicitTlsSendsClientHelloFirst() throws Exception {
		try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
			byte[] first = new byte[3];
			Thread t = new Thread(() -> {
				try (Socket s = ss.accept()) {
					InputStream in = s.getInputStream();
					for (int i = 0; i < first.length; i++) {
						first[i] = (byte) in.read();
					}
				}
				catch (IOException ignored) {
					// server side closes on its own; client gives up
				}
			});
			t.start();
			SendResult result = this.sender.send(channel("127.0.0.1", ss.getLocalPort(), "IMPLICIT_TLS", null), null,
					payload());
			t.join(5000);
			assertThat(result.outcome()).isEqualTo("FAILED_RETRYABLE"); // no real TLS
																		// peer
			assertThat(first[0] & 0xFF).isEqualTo(0x16); // TLS handshake record
			assertThat(first[1] & 0xFF).isEqualTo(0x03); // TLS version major
		}
	}

	@Test
	void guardBlockedTargetNeverHitsTheNetwork() {
		SendResult result = this.strictSender.send(channel("10.0.0.5", 587, "STARTTLS", null), null, payload());
		assertThat(result.outcome()).isEqualTo("BLOCKED");
		assertThat(result.detail()).contains("denied");
	}

	@Test
	void connectionRefusedIsRetryableTransportError() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
			closedPort = socket.getLocalPort();
		}
		SendResult result = this.sender.send(channel("127.0.0.1", closedPort, "NONE", null), null, payload());
		assertThat(result.outcome()).isEqualTo("FAILED_RETRYABLE");
		assertThat(result.detail()).isEqualTo("transport error");
	}

	@Test
	void channelWithoutRecipientsIsPermanentWithoutNetwork() {
		NotificationChannel noRcpt = new NotificationChannel(1L, 1L, "mail", null, "cipher", "nonce", "test-v1", "",
				true, OffsetDateTime.now(), NotificationChannel.TYPE_SMTP, "127.0.0.1", 2525, "NONE",
				"alerts@example.com", "  ", null);
		SendResult result = this.sender.send(noRcpt, null, payload());
		assertThat(result.outcome()).isEqualTo("FAILED_PERMANENT");
		assertThat(result.detail()).contains("no recipients");
	}

	/**
	 * One scripted server reply; for the DATA step {@code afterBody} is sent after the
	 * dot.
	 */
	private record Step(String reply, String afterBody, boolean closeAfter) {

		Step(String reply) {
			this(reply, null, false);
		}

		Step(String reply, boolean closeAfter) {
			this(reply, null, closeAfter);
		}

		Step(String reply, String afterBody) {
			this(reply, afterBody, false);
		}

	}

	/**
	 * A minimal scripted SMTP server: greeting, then one reply per received command line.
	 */
	private static final class FakeSmtpServer implements AutoCloseable {

		private final ServerSocket serverSocket;

		private final Thread thread;

		final List<String> received = new CopyOnWriteArrayList<>();

		final ByteArrayOutputStream raw = new ByteArrayOutputStream();

		volatile String dataBody = "";

		FakeSmtpServer(List<Step> steps) throws IOException {
			this.serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
			this.thread = new Thread(() -> serve(steps));
			this.thread.setDaemon(true);
			this.thread.start();
		}

		int port() {
			return this.serverSocket.getLocalPort();
		}

		String rawAsString() {
			return this.raw.toString(StandardCharsets.UTF_8);
		}

		private void serve(List<Step> steps) {
			try (Socket socket = this.serverSocket.accept()) {
				InputStream in = socket.getInputStream();
				OutputStream out = socket.getOutputStream();
				write(out, "220 fake ESMTP ready\r\n");
				for (Step step : steps) {
					String line = readLine(in);
					if (line == null) {
						return;
					}
					this.received.add(line);
					if ("DATA".equals(line)) {
						write(out, step.reply());
						StringBuilder body = new StringBuilder();
						String bodyLine;
						while ((bodyLine = readLine(in)) != null) {
							if (".".equals(bodyLine)) {
								break;
							}
							body.append(bodyLine).append('\n');
						}
						this.dataBody = body.toString();
						write(out, step.afterBody());
						continue;
					}
					write(out, step.reply());
					if (step.closeAfter()) {
						return; // socket closed by try-with-resources
					}
				}
				// drain until QUIT or EOF
				String line;
				while ((line = readLine(in)) != null) {
					this.received.add(line);
					if (line.startsWith("QUIT")) {
						write(out, "221 Bye\r\n");
						break;
					}
					write(out, "250 OK\r\n");
				}
			}
			catch (IOException ignored) {
				// script ended / client went away — assertions run on what was received
			}
		}

		private void write(OutputStream out, String s) throws IOException {
			if (s == null) {
				return;
			}
			byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
			this.raw.write(b, 0, b.length);
			out.write(b);
			out.flush();
		}

		private String readLine(InputStream in) throws IOException {
			List<Integer> bytes = new ArrayList<>();
			int c;
			while ((c = in.read()) != -1) {
				bytes.add(c);
				if (c == '\n') {
					break;
				}
			}
			if (c == -1 && bytes.isEmpty()) {
				return null;
			}
			for (int b : bytes) {
				this.raw.write(b);
			}
			int len = bytes.size();
			if (len > 0 && bytes.get(len - 1) == '\n') {
				len--;
			}
			if (len > 0 && bytes.get(len - 1) == '\r') {
				len--;
			}
			StringBuilder sb = new StringBuilder(len);
			for (int i = 0; i < len; i++) {
				sb.append((char) bytes.get(i).intValue());
			}
			return sb.toString();
		}

		@Override
		public void close() throws IOException {
			this.serverSocket.close();
		}

	}

}
