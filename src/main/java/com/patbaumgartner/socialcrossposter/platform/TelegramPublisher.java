package com.patbaumgartner.socialcrossposter.platform;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Telegram publisher (ADR-001, ADR-011, ADR-015; UC-13 second slice, ADR-008): posts the
 * approved payload through the Bot API. Without attached images it uses
 * {@code sendMessage} (form-urlencoded); with one image {@code sendPhoto}, with 2–10
 * images {@code sendMediaGroup} (multipart direct upload of the private source bytes — no
 * public URL is ever sent to the provider, ADR-008), with the text as caption. The token
 * is only used in the request URL, never logged or returned; HTTP 4xx is a confirmed
 * FAILED, HTTP 5xx or a transport/timeout failure is UNKNOWN (the write may have
 * happened) and response bodies are never included in any detail (ADR-007). Telegram
 * cannot transmit alt text on photos, so the local alt text stays on the revision and the
 * limitation is shown in the UI (ADR-016).
 */
@Component
public class TelegramPublisher implements PlatformPublisher {

	public static final String PLATFORM = TelegramBotProbe.PLATFORM;

	/** Telegram albums ({@code sendMediaGroup}) hold at most 10 items. */
	static final int MAX_ALBUM_ITEMS = 10;

	/** With attached images the text is sent as the media caption (max 1024 chars). */
	static final int CAPTION_MAX = 1024;

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
	public boolean supportsMediaUpload() {
		return true;
	}

	@Override
	public PublishResult publish(PublishContext context) {
		if (context.credential() == null || context.credential().isBlank()) {
			return PublishResult.failed("No credential available for this telegram destination.");
		}
		if (context.targetId() == null || context.targetId().isBlank()) {
			return PublishResult.failed("No chat id configured for this telegram destination.");
		}
		String reject = rejectReason(context);
		if (reject != null) {
			return PublishResult.failed(reject);
		}
		List<MediaAttachment> media = context.media();
		if (media.isEmpty()) {
			return post(context, "/bot{token}/sendMessage", textForm(context.targetId(), context.text()),
					MediaType.APPLICATION_FORM_URLENCODED);
		}
		if (media.size() == 1) {
			return post(context, "/bot{token}/sendPhoto", photoForm(context.targetId(), context.text(), media.get(0)),
					MediaType.MULTIPART_FORM_DATA);
		}
		return post(context, "/bot{token}/sendMediaGroup", mediaGroupForm(context.targetId(), context.text(), media),
				MediaType.MULTIPART_FORM_DATA);
	}

	/**
	 * Fail-closed pre-flight checks for media payloads; {@code null} means the payload is
	 * sendable. Kept separate from the HTTP call so it is unit-testable without a server.
	 */
	static String rejectReason(PublishContext context) {
		List<MediaAttachment> media = context.media();
		if (!media.isEmpty() && media.size() > MAX_ALBUM_ITEMS) {
			return "Telegram albums hold at most " + MAX_ALBUM_ITEMS + " images; this revision attaches " + media.size()
					+ ".";
		}
		if (!media.isEmpty() && context.text() != null && context.text().length() > CAPTION_MAX) {
			return "With attached images the text is sent as the caption (max " + CAPTION_MAX + " characters).";
		}
		return null;
	}

	static MultiValueMap<String, String> textForm(String chatId, String text) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("chat_id", chatId);
		form.add("text", text == null ? "" : text);
		return form;
	}

	static MultiValueMap<String, Object> photoForm(String chatId, String caption, MediaAttachment photo) {
		MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
		form.add("chat_id", chatId);
		if (caption != null && !caption.isBlank()) {
			form.add("caption", caption);
		}
		form.add("photo", filePart("photo", photo));
		return form;
	}

	static MultiValueMap<String, Object> mediaGroupForm(String chatId, String caption, List<MediaAttachment> media) {
		MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
		form.add("chat_id", chatId);
		StringBuilder json = new StringBuilder("[");
		for (int i = 0; i < media.size(); i++) {
			if (i > 0) {
				json.append(',');
			}
			json.append("{\"type\":\"photo\",\"media\":\"attach://m").append(i).append('"');
			if (i == 0 && caption != null && !caption.isBlank()) {
				json.append(",\"caption\":\"").append(jsonEscape(caption)).append('"');
			}
			json.append('}');
			form.add("m" + i, filePart("m" + i, media.get(i)));
		}
		json.append(']');
		form.add("media", json.toString());
		return form;
	}

	private static HttpEntity<ByteArrayResource> filePart(String partName, MediaAttachment media) {
		ByteArrayResource resource = new ByteArrayResource(media.bytes()) {
			@Override
			public String getFilename() {
				return media.filename();
			}
		};
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType(media.contentType()));
		headers.setContentDispositionFormData(partName, media.filename());
		return new HttpEntity<>(resource, headers);
	}

	static String jsonEscape(String value) {
		StringBuilder out = new StringBuilder(value.length() + 8);
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
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

	private PublishResult post(PublishContext context, String uri, MultiValueMap<String, ?> form,
			MediaType contentType) {
		try {
			Object body = this.restClient.post()
				.uri(uri, context.credential())
				.contentType(contentType)
				.body(form)
				.retrieve()
				.body(Object.class);
			if (!(body instanceof Map<?, ?> response) || !Boolean.TRUE.equals(response.get("ok"))) {
				return PublishResult.unknown("Telegram did not confirm the message (ok=false).");
			}
			Object result = response.get("result");
			if (result instanceof Map<?, ?> message && message.get("message_id") != null) {
				return PublishResult.success("telegram:message:" + message.get("message_id"));
			}
			if (result instanceof List<?> messages && !messages.isEmpty()) {
				List<Object> ids = new ArrayList<>();
				for (Object m : messages) {
					if (m instanceof Map<?, ?> mm && mm.get("message_id") != null) {
						ids.add(mm.get("message_id"));
					}
				}
				if (!ids.isEmpty()) {
					return PublishResult.success("telegram:messages:" + ids.get(0) + "-" + ids.get(ids.size() - 1));
				}
			}
			return PublishResult.unknown("Telegram returned no message id.");
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
