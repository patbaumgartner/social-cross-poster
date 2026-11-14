package com.patbaumgartner.socialcrossposter.platform;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Telegram text publisher (ADR-001, ADR-011, ADR-015): posts the approved payload through
 * the Bot API {@code sendMessage} for the destination's chat id. The token is only used
 * in the request URL, never logged or returned; HTTP 4xx is a confirmed FAILED, HTTP 5xx
 * or a transport/timeout failure is UNKNOWN (the write may have happened) and response
 * bodies are never included in any detail (ADR-007).
 */
@Component
public class TelegramPublisher implements PlatformPublisher {

	public static final String PLATFORM = TelegramBotProbe.PLATFORM;

	private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

	private final RestClient restClient;

	public TelegramPublisher(RestClient.Builder restClientBuilder) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
		requestFactory.setReadTimeout(READ_TIMEOUT);
		this.restClient = restClientBuilder.baseUrl("https://api.telegram.org").requestFactory(requestFactory).build();
	}

	@Override
	public String platform() {
		return PLATFORM;
	}

	@Override
	public PublishResult publish(PublishContext context) {
		if (context.credential() == null || context.credential().isBlank()) {
			return PublishResult.failed("No credential available for this telegram destination.");
		}
		if (context.targetId() == null || context.targetId().isBlank()) {
			return PublishResult.failed("No chat id configured for this telegram destination.");
		}
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("chat_id", context.targetId());
		form.add("text", context.text());
		try {
			Map<?, ?> body = this.restClient.post()
				.uri("/bot{token}/sendMessage", context.credential())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.body(Map.class);
			Object result = body != null ? body.get("result") : null;
			if (body == null || !Boolean.TRUE.equals(body.get("ok")) || !(result instanceof Map<?, ?> resultMap)) {
				return PublishResult.unknown("Telegram did not confirm the message (ok=false).");
			}
			Object messageId = resultMap.get("message_id");
			if (messageId == null) {
				return PublishResult.unknown("Telegram returned no message id.");
			}
			return PublishResult.success("telegram:message:" + messageId);
		}
		catch (RestClientResponseException ex) {
			// Sanitized: never include the response body or the token (ADR-007).
			int status = ex.getStatusCode().value();
			if (status >= 400 && status < 500) {
				return PublishResult.failed("Telegram rejected the message (HTTP " + status + ").");
			}
			return PublishResult.unknown("Telegram write could not be confirmed (HTTP " + status + ").");
		}
		catch (ResourceAccessException ex) {
			return PublishResult.unknown("Telegram could not be reached or did not answer in time.");
		}
	}

}
