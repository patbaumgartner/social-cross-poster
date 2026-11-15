package com.patbaumgartner.socialcrossposter.media;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.patbaumgartner.socialcrossposter.TestImages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link OutgoingImageSanitizer} (UC-13 third slice, ADR-016): the outgoing
 * derivative drops GPS/EXIF/XMP/IPTC/text/comments at the container level while keeping
 * pixels, animation and color profile; malformed input is rejected fail-closed.
 */
class OutgoingImageSanitizerTest {

	@Test
	void pngDropsTextAndExifChunksButKeepsCriticalChunksAndIsIdempotent() {
		byte[] dirty = TestImages.pngWithGpsText();
		assertThat(text(dirty)).contains("tEXt").contains("GPS: 48.1867N");

		byte[] clean = OutgoingImageSanitizer.strip("image/png", dirty);

		assertThat(text(clean)).doesNotContain("tEXt").doesNotContain("GPS: 48.1867N");
		assertThat(text(clean)).contains("IHDR").contains("IDAT").contains("IEND"); // critical
																					// chunks
																					// kept
		// idempotent, and a metadata-free PNG is returned unchanged (same bytes)
		assertThat(OutgoingImageSanitizer.strip("image/png", clean)).isEqualTo(clean);
		byte[] alreadyClean = TestImages.png1x1();
		assertThat(OutgoingImageSanitizer.strip("image/png", alreadyClean)).isSameAs(alreadyClean);
	}

	@Test
	void pngDropsExifChunk() {
		byte[] dirty = TestImages.pngWithChunk(TestImages.png1x1(), "eXIf", new byte[] { 0, 1, 2, 3, 'G', 'P', 'S' });
		byte[] clean = OutgoingImageSanitizer.strip("image/png", dirty);
		assertThat(text(clean)).doesNotContain("eXIf").contains("IDAT");
	}

	@Test
	void jpegDropsApp1App13AndComButKeepsJfifIccTablesAndScan() {
		byte[] jpeg = TestImages.jpegWithSegment(TestImages.jpeg1x1(), 0xE1,
				"Exif\0\0gps".getBytes(StandardCharsets.ISO_8859_1));
		jpeg = TestImages.jpegWithSegment(jpeg, 0xED, "Photoshop 3.0 IPTC".getBytes(StandardCharsets.ISO_8859_1));
		jpeg = TestImages.jpegWithSegment(jpeg, 0xFE, "camera comment".getBytes(StandardCharsets.ISO_8859_1));

		byte[] clean = OutgoingImageSanitizer.strip("image/jpeg", jpeg);

		assertThat(clean[0]).isEqualTo((byte) 0xFF);
		assertThat(clean[1]).isEqualTo((byte) 0xD8); // SOI kept
		assertThat(clean[clean.length - 2]).isEqualTo((byte) 0xFF);
		assertThat(clean[clean.length - 1]).isEqualTo((byte) 0xD9); // EOI kept
		assertThat(containsSegment(clean, 0xE0)).isTrue(); // APP0 JFIF kept
		assertThat(containsSegment(clean, 0xDB)).isTrue(); // DQT kept
		assertThat(containsSegment(clean, 0xC0)).isTrue(); // SOF0 kept
		assertThat(containsSegment(clean, 0xDA)).isTrue(); // SOS + scan kept
		assertThat(containsSegment(clean, 0xE1)).isFalse(); // EXIF dropped
		assertThat(containsSegment(clean, 0xED)).isFalse(); // IPTC dropped
		assertThat(containsSegment(clean, 0xFE)).isFalse(); // COM dropped
		assertThat(text(clean)).doesNotContain("camera comment").doesNotContain("IPTC");
		assertThat(OutgoingImageSanitizer.strip("image/jpeg", clean)).isEqualTo(clean); // idempotent
	}

	@Test
	void gifDropsCommentExtensionButKeepsImageAndTrailer() {
		byte[] dirty = TestImages.gifWithComment();
		assertThat(text(dirty)).contains("secret camera note");

		byte[] clean = OutgoingImageSanitizer.strip("image/gif", dirty);

		assertThat(text(clean)).doesNotContain("secret camera note");
		assertThat(clean[0]).isEqualTo((byte) 'G');
		assertThat(clean[clean.length - 1]).isEqualTo((byte) 0x3B); // trailer kept
		assertThat(containsBytes(clean, 0x21, 0xFE)).isFalse(); // no comment extension
																// anymore
		assertThat(OutgoingImageSanitizer.strip("image/gif", clean)).isEqualTo(clean); // idempotent
	}

	@Test
	void webpDropsExifAndXmpChunksClearsVp8xFlagsAndFixesRiffSize() {
		byte[] dirty = TestImages.webpWithExifXmp();

		byte[] clean = OutgoingImageSanitizer.strip("image/webp", dirty);

		assertThat(text(clean)).doesNotContain("EXIF").doesNotContain("XMP ").doesNotContain("gps!");
		assertThat(text(clean)).contains("VP8X").contains("VP8L"); // structure + image
																	// data kept
		// VP8X flag byte: EXIF (0x08) and XMP (0x04) cleared, ICC/ALPHA/ANIM kept
		int vp8x = text(clean).indexOf("VP8X") + 4 + 4; // fourcc + size field
		assertThat(clean[vp8x] & 0xFF).isEqualTo(0x3E & ~(0x08 | 0x04));
		// RIFF size field matches the new file size
		int riffSize = (clean[4] & 0xFF) | ((clean[5] & 0xFF) << 8) | ((clean[6] & 0xFF) << 16)
				| ((clean[7] & 0xFF) << 24);
		assertThat(riffSize).isEqualTo(clean.length - 8);
	}

	@Test
	void malformedContainersAreRejectedFailClosed() {
		assertThatThrownBy(() -> OutgoingImageSanitizer.strip("image/png", new byte[0]))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> OutgoingImageSanitizer.strip("image/png", "not a png".getBytes(StandardCharsets.UTF_8)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("PNG");
		assertThatThrownBy(
				() -> OutgoingImageSanitizer.strip("image/png", new byte[] { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3 }))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> OutgoingImageSanitizer.strip("image/jpeg", new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF }))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OutgoingImageSanitizer.strip("image/gif",
				"NOTGIF89a.......".getBytes(StandardCharsets.ISO_8859_1)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("GIF");
		assertThatThrownBy(() -> OutgoingImageSanitizer.strip("image/webp",
				"RIFF\0\0\0\0XXXX".getBytes(StandardCharsets.ISO_8859_1)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("WebP");
	}

	@Test
	void unsupportedOrMissingContentTypeIsRejected() {
		byte[] png = TestImages.png1x1();
		assertThatThrownBy(() -> OutgoingImageSanitizer.strip("image/bmp", png))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("unsupported");
		assertThatThrownBy(() -> OutgoingImageSanitizer.strip(null, png)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void contentTypeMatchingIsCaseInsensitive() {
		byte[] dirty = TestImages.pngWithGpsText();
		byte[] clean = OutgoingImageSanitizer.strip("IMAGE/PNG", dirty);
		assertThat(text(clean)).doesNotContain("tEXt");
	}

	private static String text(byte[] bytes) {
		return new String(bytes, StandardCharsets.ISO_8859_1);
	}

	/**
	 * True when a JPEG marker FF xx appears as a real marker (outside scan data is not
	 * parsed here — tests craft inputs accordingly).
	 */
	private static boolean containsSegment(byte[] jpeg, int marker) {
		return containsBytes(jpeg, 0xFF, marker);
	}

	private static boolean containsBytes(byte[] haystack, int first, int second) {
		for (int i = 0; i < haystack.length - 1; i++) {
			if ((haystack[i] & 0xFF) == first && (haystack[i + 1] & 0xFF) == second) {
				return true;
			}
		}
		return false;
	}

}
