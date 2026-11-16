package com.patbaumgartner.socialcrossposter.notify;

import java.security.GeneralSecurityException;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Sends one signed webhook POST (UC-12 second slice, step 4). The JSON body is the shared
 * redacted {@link NotificationPayload} (UC-12 step 3); never draft text, tokens, media or
 * raw provider bodies. Headers: {@code X-SCP-Event-Id} (stable id for receiver dedup),
 * {@code X-SCP-Timestamp} and
 * {@code X-SCP-Signature: t=<ts>,v1=<hex HMAC-SHA256(secret, ts + "." + body)>}.
 *
 * The target is re-validated by the SSRF guard immediately before the request (DNS
 * rebinding protection). POST never follows redirects: a 3xx is a permanent failure. 2xx
 * = SENT, 4xx = permanent FAILED, 5xx and transport errors = retryable FAILED. Response
 * bodies are never read into logs or details.
 */
@Component
public class WebhookSender {

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
	public SendResult send(String url, String secret, NotificationPayload payload) {
		try {
			this.guard.validate(url); // re-resolve DNS at send time (arc42 section 8)
		}
		catch (NotificationException ex) {
			return SendResult.blocked(ex.getMessage());
		}
		long timestamp = Instant.now().getEpochSecond();
		String body = payload.toJson();
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
