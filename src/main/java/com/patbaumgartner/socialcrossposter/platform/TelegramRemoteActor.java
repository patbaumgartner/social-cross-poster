package com.patbaumgartner.socialcrossposter.platform;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Telegram remote actor (UC-08, ADR-001, ADR-011, ADR-015): verified edit/delete of an
 * already published message through the Bot API {@code editMessageText} /
 * {@code deleteMessage}. The remote post is addressed by the delivery's confirmed
 * external ref ({@code telegram:message:<id>}) plus the destination chat id; the token is
 * only used in the request URL, never logged or returned. HTTP 4xx is a confirmed FAILED,
 * HTTP 5xx or a transport/timeout failure is UNKNOWN (the write may have happened —
 * reconcile, never issue a replacement create-post) and response bodies are never
 * included in any detail (ADR-007).
 */
@Component
public class TelegramRemoteActor implements PlatformRemoteActor {

	public static final String PLATFORM = TelegramBotProbe.PLATFORM;

	/** External ref prefix written by {@link TelegramPublisher} on confirmed sends. */
	public static final String REF_PREFIX = "telegram:message:";

	private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

	private final RestClient restClient;

	public TelegramRemoteActor(RestClient.Builder restClientBuilder) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
		requestFactory.setReadTimeout(READ_TIMEOUT);
		this.restClient = restClientBuilder.baseUrl("https://api.telegram.org").requestFactory(requestFactory).build();
	}

	@Override
	public String platform() {
		return PLATFORM;
	}

	@Override
	public Set<String> supportedActions() {
		return Set.of(ACTION_EDIT, ACTION_DELETE);
	}

	@Override
	public PublishResult execute(RemoteActionContext context) {
		String action = context.action() == null ? "" : context.action().toLowerCase(Locale.ROOT);
		if (context.credential() == null || context.credential().isBlank()) {
			return PublishResult.failed("No credential available for this telegram destination.");
		}
		if (context.targetId() == null || context.targetId().isBlank()) {
			return PublishResult.failed("No chat id configured for this telegram destination.");
		}
		String messageId = messageIdOf(context.externalRef());
		if (messageId == null) {
			return PublishResult.failed("The confirmed external reference is not a usable telegram message id.");
		}
		boolean edit = ACTION_EDIT.equals(context.action());
		if (edit && (context.text() == null || context.text().isBlank())) {
			return PublishResult.failed("The replacement text must not be empty.");
		}
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("chat_id", context.targetId());
		form.add("message_id", messageId);
		if (edit) {
			form.add("text", context.text());
		}
		String endpoint = edit ? "editMessageText" : "deleteMessage";
		try {
			Map<?, ?> body = this.restClient.post()
				.uri("/bot{token}/" + endpoint, context.credential())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.body(Map.class);
			if (body == null || !Boolean.TRUE.equals(body.get("ok"))) {
				return PublishResult.unknown("Telegram did not confirm the " + action + " (ok=false).");
			}
			return PublishResult.success(context.externalRef());
		}
		catch (RestClientResponseException ex) {
			// Sanitized: never include the response body or the token (ADR-007).
			int status = ex.getStatusCode().value();
			if (status >= 400 && status < 500) {
				return PublishResult.failed("Telegram rejected the " + action + " (HTTP " + status + ").");
			}
			return PublishResult.unknown("Telegram " + action + " could not be confirmed (HTTP " + status + ").");
		}
		catch (ResourceAccessException ex) {
			return PublishResult.unknown("Telegram could not be reached or did not answer in time.");
		}
	}

	/**
	 * Extracts the numeric message id from a {@code telegram:message:<id>} external ref.
	 */
	static String messageIdOf(String externalRef) {
		if (externalRef == null || !externalRef.startsWith(REF_PREFIX)) {
			return null;
		}
		String id = externalRef.substring(REF_PREFIX.length());
		return id.matches("\\d+") ? id : null;
	}

}
