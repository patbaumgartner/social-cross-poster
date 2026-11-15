package com.patbaumgartner.socialcrossposter.platform;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link TelegramPublisher} (UC-13 second slice, ADR-008, ADR-011): the
 * payload routing (text vs. single photo vs. album), the fail-closed pre-flight checks
 * (album size, caption length), and the multipart form builders (file parts with
 * filename/content type, attach:// media group JSON with caption on the first item only,
 * JSON escaping). The HTTP layer is mocked at the RestClient.Builder seam; no network is
 * involved.
 */
class TelegramPublisherTest {

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3 };

	private final TelegramPublisher publisher = publisher();

	private static TelegramPublisher publisher() {
		RestClient.Builder builder = mock(RestClient.Builder.class);
		RestClient client = mock(RestClient.class);
		given(builder.baseUrl(anyString())).willReturn(builder);
		given(builder.requestFactory(any())).willReturn(builder);
		given(builder.build()).willReturn(client);
		return new TelegramPublisher(builder);
	}

	private static MediaAttachment image(String name) {
		return new MediaAttachment(name, "image/png", PNG, "alt of " + name);
	}

	@Test
	void platformKeyAndMediaUploadCapability() {
		assertThat(this.publisher.platform()).isEqualTo("telegram");
		assertThat(this.publisher.supportsMediaUpload()).isTrue();
	}

	@Test
	void rejectReasonIsNullForTextOnlyAndSmallAlbumsWithShortCaption() {
		assertThat(TelegramPublisher.rejectReason(new PublishContext("t", "-100", "hello", "k"))).isNull();
		assertThat(TelegramPublisher
			.rejectReason(new PublishContext("t", "-100", "c".repeat(1024), "k", List.of(image("a.png"))))).isNull();
		assertThat(TelegramPublisher.rejectReason(new PublishContext("t", "-100", "hello", "k",
				IntStream.range(0, TelegramPublisher.MAX_ALBUM_ITEMS)
					.mapToObj((i) -> image("a" + i + ".png"))
					.toList())))
			.isNull();
	}

	@Test
	void rejectReasonFailsClosedOnOversizedAlbumAndLongCaption() {
		List<MediaAttachment> eleven = IntStream.range(0, TelegramPublisher.MAX_ALBUM_ITEMS + 1)
			.mapToObj((i) -> image("a" + i + ".png"))
			.toList();
		assertThat(TelegramPublisher.rejectReason(new PublishContext("t", "-100", "hello", "k", eleven)))
			.contains("at most " + TelegramPublisher.MAX_ALBUM_ITEMS + " images")
			.contains("11");
		assertThat(TelegramPublisher
			.rejectReason(new PublishContext("t", "-100", "c".repeat(1025), "k", List.of(image("a.png")))))
			.contains("caption")
			.contains(String.valueOf(TelegramPublisher.CAPTION_MAX));
	}

	@Test
	void textFormCarriesChatIdAndText() {
		MultiValueMap<String, String> form = TelegramPublisher.textForm("-100123", "hello");
		assertThat(form.getFirst("chat_id")).isEqualTo("-100123");
		assertThat(form.getFirst("text")).isEqualTo("hello");
	}

	@Test
	void photoFormUploadsTheFileAsMultipartPartWithCaption() {
		MultiValueMap<String, Object> form = TelegramPublisher.photoForm("-100123", "cap", image("chart.png"));

		assertThat(form.getFirst("chat_id")).isEqualTo("-100123");
		assertThat(form.getFirst("caption")).isEqualTo("cap");
		Object photo = form.getFirst("photo");
		assertThat(photo).isInstanceOf(HttpEntity.class);
		@SuppressWarnings("unchecked")
		HttpEntity<ByteArrayResource> part = (HttpEntity<ByteArrayResource>) photo;
		assertThat(part.getBody().getFilename()).isEqualTo("chart.png");
		assertThat(part.getBody().contentLength()).isEqualTo(PNG.length);
		assertThat(part.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
		assertThat(part.getHeaders().getContentDisposition().getName()).isEqualTo("photo");
		assertThat(part.getHeaders().getContentDisposition().getFilename()).isEqualTo("chart.png");

		// no caption part when the text is blank
		assertThat(TelegramPublisher.photoForm("-100123", "  ", image("chart.png")).containsKey("caption")).isFalse();
	}

	@Test
	void mediaGroupFormUsesAttachReferencesWithCaptionOnFirstItemOnly() {
		MultiValueMap<String, Object> form = TelegramPublisher.mediaGroupForm("-100123", "cap \"quoted\"",
				List.of(image("a.png"), image("b.png")));

		assertThat(form.getFirst("chat_id")).isEqualTo("-100123");
		String json = (String) form.getFirst("media");
		assertThat(json).startsWith("[{").endsWith("}]");
		assertThat(json).contains("\"media\":\"attach://m0\"", "\"media\":\"attach://m1\"");
		assertThat(json).contains("\"caption\":\"cap \\\"quoted\\\"\""); // escaped, first
																			// item only
		assertThat(json.indexOf("caption")).isLessThan(json.indexOf("attach://m1"));
		assertThat(form.get("m0")).hasSize(1);
		assertThat(form.get("m1")).hasSize(1);
		@SuppressWarnings("unchecked")
		HttpEntity<ByteArrayResource> part = (HttpEntity<ByteArrayResource>) form.getFirst("m1");
		assertThat(part.getBody().getFilename()).isEqualTo("b.png");
		assertThat(part.getHeaders().getContentDisposition().getName()).isEqualTo("m1");
	}

	@Test
	void jsonEscapeHandlesQuotesBackslashesAndControlChars() {
		assertThat(TelegramPublisher.jsonEscape("a\"b\\c\nd\te")).isEqualTo("a\\\"b\\\\c\\nd\\te");
		assertThat(TelegramPublisher.jsonEscape("plain")).isEqualTo("plain");
	}

	@Test
	void mediaAttachmentDefensivelyCopiesBytes() {
		byte[] original = { 9, 9 };
		MediaAttachment attachment = new MediaAttachment("x.png", "image/png", original, "alt");
		original[0] = 0;
		assertThat(attachment.bytes()).containsExactly(9, 9);
		attachment.bytes()[0] = 0;
		assertThat(attachment.bytes()).containsExactly(9, 9);
	}

	@Test
	void publishContextDefaultsToNoMedia() {
		PublishContext context = new PublishContext("t", "-100", "hello", "k");
		assertThat(context.media()).isEmpty();
	}

}
