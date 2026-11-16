package com.patbaumgartner.socialcrossposter.notify;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WebhookSender} (UC-12 second slice step 4) against a real local
 * {@link HttpServer} on 127.0.0.1 (allow-listed in the test guard): 2xx = SENT with a
 * verifiable {@code X-SCP-Signature} HMAC header and the redacted JSON payload, 5xx =
 * retryable, 4xx = permanent, 3xx = permanent redirect (never followed), guard-blocked
 * targets never hit the network, transport errors are retryable, and response bodies are
 * never surfaced in details.
 */
class WebhookSenderTest {

	private static final String SECRET = "test-hmac-secret";

	private HttpServer server;

	private int port;

	private final List<Captured> requests = new CopyOnWriteArrayList<>();

	private final AtomicInteger hits = new AtomicInteger();

	private WebhookSender sender;

	private record Captured(String method, String path, String contentType, String eventId, String timestamp,
			String signature, String body) {
	}

	@BeforeEach
	void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", (exchange) -> {
			this.hits.incrementAndGet();
			byte[] body = exchange.getRequestBody().readAllBytes();
			this.requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders().getFirst("Content-Type"),
					exchange.getRequestHeaders().getFirst("X-SCP-Event-Id"),
					exchange.getRequestHeaders().getFirst("X-SCP-Timestamp"),
					exchange.getRequestHeaders().getFirst("X-SCP-Signature"),
					new String(body, StandardCharsets.UTF_8)));
			int status = switch (exchange.getRequestURI().getPath()) {
				case "/ok" -> 200;
				case "/boom" -> 500;
				case "/bad" -> 400;
				case "/redirect" -> 302;
				default -> 200;
			};
			if (status == 302) {
				exchange.getResponseHeaders().add("Location", "/ok");
			}
			exchange.sendResponseHeaders(status, status == 302 ? -1 : 0);
			if (status != 302) {
				try (OutputStream os = exchange.getResponseBody()) {
					os.write("secret-body-should-never-leak".getBytes(StandardCharsets.UTF_8));
				}
			}
			exchange.close();
		});
		this.server.start();
		this.port = this.server.getAddress().getPort();
		NotifyProperties props = new NotifyProperties(null, null, null, null, null, null, 2000L, 2000L,
				List.of("127.0.0.1"));
		this.sender = new WebhookSender(props, new OutboundWebhookGuard(props));
	}

	@AfterEach
	void tearDown() {
		this.server.stop(0);
	}

	private NotificationPayload payload() {
		return new NotificationPayload(42L, "delivery:5:failed", 1L, "Private", "DELIVERY_FAILED",
				"Delivery #5 to telegram failed; \"quoted\"\nline", 100L, 5L, null, null, OffsetDateTime.now());
	}

	@Test
	void successPostsSignedRedactedJsonPayload() throws Exception {
		var result = this.sender.send("http://127.0.0.1:" + this.port + "/ok", SECRET, payload());

		assertThat(result.outcome()).isEqualTo("SENT");
		assertThat(this.requests).hasSize(1);
		Captured req = this.requests.get(0);
		assertThat(req.method()).isEqualTo("POST");
		assertThat(req.path()).isEqualTo("/ok");
		assertThat(req.contentType()).contains("application/json");
		assertThat(req.eventId()).isEqualTo("42");
		assertThat(req.timestamp()).isNotBlank();
		// signature header: t=<ts>,v1=<hex HMAC-SHA256(secret, ts + "." + body)>
		assertThat(req.signature()).startsWith("t=" + req.timestamp() + ",v1=");
		String expected = "t=" + req.timestamp() + ",v1=" + hmacHex(SECRET, req.timestamp() + "." + req.body());
		assertThat(req.signature()).isEqualTo(expected);
		// redacted payload fields (UC-12 step 3), with JSON escaping
		assertThat(req.body()).contains("\"eventId\":42")
			.contains("\"eventKey\":\"delivery:5:failed\"")
			.contains("\"workspaceLabel\":\"Private\"")
			.contains("\"category\":\"DELIVERY_FAILED\"")
			.contains("\"publicationId\":100")
			.contains("\"deliveryId\":5")
			.contains("\"scheduleId\":null")
			.contains("\\\"quoted\\\"")
			.contains("\\n");
	}

	@Test
	void serverErrorIsRetryableAndNeverLeaksTheBody() {
		var result = this.sender.send("http://127.0.0.1:" + this.port + "/boom", SECRET, payload());
		assertThat(result.outcome()).isEqualTo("FAILED_RETRYABLE");
		assertThat(result.detail()).isEqualTo("HTTP 500").doesNotContain("secret-body-should-never-leak");
	}

	@Test
	void clientErrorIsPermanent() {
		var result = this.sender.send("http://127.0.0.1:" + this.port + "/bad", SECRET, payload());
		assertThat(result.outcome()).isEqualTo("FAILED_PERMANENT");
		assertThat(result.detail()).isEqualTo("HTTP 400");
	}

	@Test
	void redirectIsAPermanentFailureAndIsNotFollowed() {
		var result = this.sender.send("http://127.0.0.1:" + this.port + "/redirect", SECRET, payload());
		assertThat(result.outcome()).isEqualTo("FAILED_PERMANENT");
		assertThat(result.detail()).contains("redirect").contains("302");
		// exactly one request hit the server — the redirect target was not fetched
		assertThat(this.hits.get()).isEqualTo(1);
		assertThat(this.requests.get(0).path()).isEqualTo("/redirect");
	}

	@Test
	void guardBlockedTargetNeverHitsTheNetwork() {
		int before = this.hits.get();
		var result = this.sender.send("https://10.0.0.5/hook", SECRET, payload());
		assertThat(result.outcome()).isEqualTo("BLOCKED");
		assertThat(result.detail()).contains("denied");
		assertThat(this.hits.get()).isEqualTo(before);
		assertThat(this.requests).isEmpty();
	}

	@Test
	void connectionRefusedIsRetryableTransportError() throws IOException {
		int closedPort;
		try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
			closedPort = socket.getLocalPort();
		}
		var result = this.sender.send("http://127.0.0.1:" + closedPort + "/hook", SECRET, payload());
		assertThat(result.outcome()).isEqualTo("FAILED_RETRYABLE");
		assertThat(result.detail()).isEqualTo("transport error");
	}

	private static String hmacHex(String secret, String message) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
		StringBuilder hex = new StringBuilder(digest.length * 2);
		for (byte b : digest) {
			hex.append(Character.forDigit((b >> 4) & 0xF, 16));
			hex.append(Character.forDigit(b & 0xF, 16));
		}
		return hex.toString();
	}

}
