package com.patbaumgartner.socialcrossposter.notify;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.OffsetDateTime;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Sends one signed webhook POST (UC-12 second slice, step 4). The JSON payload carries
 * only redacted fields — event id/key, workspace id/label, category, redacted summary,
 * the optional publication/delivery/schedule ids and the occurrence time (UC-12 step 3);
 * never draft text, tokens, media or raw provider bodies. Headers: {@code X-SCP-Event-Id}
 * (stable id for receiver dedup), {@code X-SCP-Timestamp} and
 * {@code X-SCP-Signature: t=<ts>,v1=<hex HMAC-SHA256(secret, ts + "." + body)>}.
 *
 * The target is re-validated by the SSRF guard immediately before the request (DNS
 * rebinding protection). POST never follows redirects: a 3xx is a permanent failure. 2xx
 * = SENT, 4xx = permanent FAILED, 5xx and transport errors = retryable FAILED. Response
 * bodies are never read into logs or details.
 */
@Component
public class WebhookSender {

	/** The redacted webhook payload (UC-12 step 3). */
	public record WebhookPayload(Long eventId, String eventKey, Long workspaceId, String workspaceLabel,
			String category, String summary, Long publicationId, Long deliveryId, Long scheduleId,
			OffsetDateTime occurredAt) {
	}

	/** Outcome of one send attempt. */
	public record SendResult(String outcome, String detail) {

		public static SendResult sent() {
			return new SendResult("SENT", "delivered (HTTP 2xx)");
		}

		public static SendResult permanent(String detail) {
			return new SendResult("FAILED_PERMANENT", detail);
		}

		public static SendResult retryable(String detail) {
			return new SendResult("FAILED_RETRYABLE", detail);
		}

		public static SendResult blocked(String detail) {
			return new SendResult("BLOCKED", detail);
		}

	}

	private final OutboundWebhookGuard guard;

	private final RestClient restClient;

	public WebhookSender(NotifyProperties properties, OutboundWebhookGuard guard) {
		this.guard = guard;
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(properties.connectTimeoutMs().intValue());
		factory.setReadTimeout(properties.readTimeoutMs().intValue());
		this.restClient = RestClient.builder().requestFactory(factory).build();
	}

	/**
	 * POSTs the signed payload to {@code url}. Never throws for expected failure modes —
	 * the result carries a redacted detail (status class or guard reason, never a body).
	 */
	public SendResult send(String url, String secret, WebhookPayload payload) {
		try {
			this.guard.validate(url); // re-resolve DNS at send time (arc42 section 8)
		}
		catch (NotificationException ex) {
			return SendResult.blocked(ex.getMessage());
		}
		long timestamp = Instant.now().getEpochSecond();
		String body = toJson(payload);
		String signature = "t=" + timestamp + ",v1=" + hmacSha256Hex(secret, timestamp + "." + body);
		try {
			var response = this.restClient.post()
				.uri(url)
				.contentType(MediaType.APPLICATION_JSON)
				.header("X-SCP-Event-Id", String.valueOf(payload.eventId()))
				.header("X-SCP-Timestamp", String.valueOf(timestamp))
				.header("X-SCP-Signature", signature)
				.body(body)
				.retrieve()
				.toBodilessEntity();
			int status = response.getStatusCode().value();
			if (status >= 200 && status < 300) {
				return SendResult.sent();
			}
			if (status >= 300 && status < 400) {
				return SendResult.permanent("redirect not followed (HTTP " + status + ")");
			}
			if (status >= 400 && status < 500) {
				return SendResult.permanent("HTTP " + status);
			}
			return SendResult.retryable("HTTP " + status);
		}
		catch (RestClientResponseException ex) {
			int status = ex.getStatusCode().value();
			if (status >= 400 && status < 500) {
				return SendResult.permanent("HTTP " + status);
			}
			return SendResult.retryable("HTTP " + status); // 5xx — body never inspected
															// or logged
		}
		catch (ResourceAccessException ex) {
			return SendResult.retryable("transport error");
		}
	}

	static String toJson(WebhookPayload p) {
		StringBuilder json = new StringBuilder(256);
		json.append('{');
		field(json, "eventId", p.eventId()).append(',');
		field(json, "eventKey", p.eventKey()).append(',');
		field(json, "workspaceId", p.workspaceId()).append(',');
		field(json, "workspaceLabel", p.workspaceLabel()).append(',');
		field(json, "category", p.category()).append(',');
		field(json, "summary", p.summary()).append(',');
		field(json, "publicationId", p.publicationId()).append(',');
		field(json, "deliveryId", p.deliveryId()).append(',');
		field(json, "scheduleId", p.scheduleId()).append(',');
		field(json, "occurredAt", p.occurredAt() == null ? null : p.occurredAt().toString());
		json.append('}');
		return json.toString();
	}

	private static StringBuilder field(StringBuilder json, String name, Object value) {
		json.append('"').append(name).append("\":");
		if (value == null) {
			json.append("null");
		}
		else if (value instanceof Number) {
			json.append(value);
		}
		else {
			json.append('"').append(escape(String.valueOf(value))).append('"');
		}
		return json;
	}

	private static String escape(String s) {
		StringBuilder out = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					}
					else {
						out.append(c);
					}
				}
			}
		}
		return out.toString();
	}

	private static String hmacSha256Hex(String secret, String message) {
		try {
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
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
		}
	}

}
