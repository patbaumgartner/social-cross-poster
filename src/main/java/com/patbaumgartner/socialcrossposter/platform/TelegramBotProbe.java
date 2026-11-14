package com.patbaumgartner.socialcrossposter.platform;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Telegram bot token probe (ADR-006, ADR-011): validates a pasted bot token through the
 * documented read-only {@code getMe} endpoint. The token is only used in the request URL,
 * never logged or returned; failures are sanitized.
 */
@Component
public class TelegramBotProbe implements PlatformProbe {

	public static final String PLATFORM = "telegram";

	private final RestClient restClient;

	public TelegramBotProbe(RestClient.Builder restClientBuilder) {
		this.restClient = restClientBuilder.baseUrl("https://api.telegram.org").build();
	}

	@Override
	public String platform() {
		return PLATFORM;
	}

	@Override
	public Set<String> supportedModes() {
		return Set.of("pasted");
	}

	@Override
	public ProbeResult probe(String mode, String credential) {
		if (!"pasted".equals(mode) || credential == null || credential.isBlank()) {
			throw new ProbeException("Telegram bot tokens are pasted credentials; empty token rejected.");
		}
		try {
			Map<?, ?> body = this.restClient.get().uri("/bot{token}/getMe", credential).retrieve().body(Map.class);
			Object result = body != null ? body.get("result") : null;
			if (body == null || !Boolean.TRUE.equals(body.get("ok")) || !(result instanceof Map<?, ?> resultMap)) {
				throw new ProbeException("Telegram rejected the bot token (getMe).");
			}
			Object username = resultMap.get("username");
			Object id = resultMap.get("id");
			if (username == null || id == null) {
				throw new ProbeException("Telegram getMe returned an incomplete bot identity.");
			}
			return new ProbeResult("@" + username, "@" + username + " (id " + id + ")");
		}
		catch (RestClientResponseException ex) {
			// Sanitized: never include the response body or the token (ADR-007).
			throw new ProbeException("Telegram rejected the bot token (HTTP " + ex.getStatusCode().value() + ").", ex);
		}
	}

}
