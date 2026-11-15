package com.patbaumgartner.socialcrossposter;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

/**
 * Real encodable image bytes for tests. The UC-13 outgoing sanitizer rejects unparsable
 * "images" fail-closed, so tests must upload real containers, not magic-byte stubs.
 * Helpers also allow injecting metadata chunks/segments to prove stripping.
 */
public final class TestImages {

	private TestImages() {
	}

	/** A real 1x1 RGB PNG produced by ImageIO (IHDR/IDAT/IEND, no metadata chunks). */
	public static byte[] png1x1() {
		return png(1, 1);
	}

	public static byte[] png(int w, int h) {
		try {
			BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			img.setRGB(0, 0, 0x336699);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(img, "png", out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** A real 1x1 JPEG produced by ImageIO (SOI/APP0-JFIF/DQT/SOF0/SOS/scan/EOI). */
	public static byte[] jpeg1x1() {
		try {
			BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(img, "jpeg", out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Inserts a PNG chunk right before IEND (used to add tEXt/eXIf metadata to a real
	 * PNG). Chunk = length(4 BE) + type(4) + data + CRC32(type+data)(4 BE).
	 */
	public static byte[] pngWithChunk(byte[] png, String type, byte[] data) {
		byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
		int iend = indexOf(png, "IEND".getBytes(StandardCharsets.ISO_8859_1));
		if (iend < 0) {
			throw new IllegalArgumentException("no IEND in png");
		}
		int chunkStart = iend - 4; // skip the IEND length field
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + data.length + 12);
		out.write(png, 0, chunkStart);
		writeBe32(out, data.length);
		out.write(typeBytes, 0, 4);
		out.write(data, 0, data.length);
		writeBe32(out, (int) crc.getValue());
		out.write(png, chunkStart, png.length - chunkStart);
		return out.toByteArray();
	}

	/**
	 * A real PNG carrying a tEXt chunk with fake GPS/camera text (what EXIF stripping
	 * must remove).
	 */
	public static byte[] pngWithGpsText() {
		return pngWithChunk(png1x1(), "tEXt",
				"Description\0GPS: 48.1867N, 16.3769E camera=TestCam 9000".getBytes(StandardCharsets.ISO_8859_1));
	}

	/** Inserts a JPEG segment (FF xx + length + payload) right after the SOI marker. */
	public static byte[] jpegWithSegment(byte[] jpeg, int marker, byte[] payload) {
		if ((jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
			throw new IllegalArgumentException("not a jpeg");
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + payload.length + 4);
		out.write(jpeg, 0, 2);
		out.write(0xFF);
		out.write(marker);
		int len = payload.length + 2;
		out.write((len >> 8) & 0xFF);
		out.write(len & 0xFF);
		out.write(payload, 0, payload.length);
		out.write(jpeg, 2, jpeg.length - 2);
		return out.toByteArray();
	}

	/** A minimal structurally valid GIF89a with a comment extension and one 1x1 image. */
	public static byte[] gifWithComment() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes("GIF89a".getBytes(StandardCharsets.ISO_8859_1));
		out.write(1);
		out.write(0); // width 1
		out.write(1);
		out.write(0); // height 1
		out.write(0x80); // GCT present, 2 entries
		out.write(0);
		out.write(0);
		out.writeBytes(new byte[] { 0, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF }); // GCT:
																						// black,
																						// white
		// comment extension (0x21 0xFE) — metadata to strip
		out.write(0x21);
		out.write(0xFE);
		byte[] comment = "secret camera note".getBytes(StandardCharsets.ISO_8859_1);
		out.write(comment.length);
		out.writeBytes(comment);
		out.write(0); // block terminator
		// image descriptor
		out.write(0x2C);
		out.writeBytes(new byte[] { 0, 0, 0, 0, 1, 0, 1, 0, 0 });
		out.write(2); // LZW min code size
		out.write(2);
		out.writeBytes(new byte[] { 0x44, 0x01 }); // one data sub-block
		out.write(0); // block terminator
		out.write(0x3B); // trailer
		return out.toByteArray();
	}

	/**
	 * A minimal RIFF/WEBP: VP8X (EXIF|XMP|ICC|ALPHA|ANIM flags), EXIF chunk, XMP chunk,
	 * VP8L chunk.
	 */
	public static byte[] webpWithExifXmp() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		// VP8X, 10 bytes: flags 0x3E, 3 reserved, canvas w-1, canvas h-1 (3 bytes LE
		// each)
		chunk(body, "VP8X", new byte[] { 0x3E, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
		chunk(body, "EXIF", new byte[] { 'g', 'p', 's', '!' });
		chunk(body, "XMP ", new byte[] { 'x', 'm', 'p', '!' });
		chunk(body, "VP8L", new byte[] { 0x2F, 0, 0, 0, 0 }); // odd size -> pad byte
																// added by chunk()
		byte[] bodyBytes = body.toByteArray();
		ByteArrayOutputStream out = new ByteArrayOutputStream(12 + bodyBytes.length);
		out.writeBytes("RIFF".getBytes(StandardCharsets.ISO_8859_1));
		writeLe32(out, 4 + bodyBytes.length);
		out.writeBytes("WEBP".getBytes(StandardCharsets.ISO_8859_1));
		out.writeBytes(bodyBytes);
		return out.toByteArray();
	}

	private static void chunk(ByteArrayOutputStream out, String fourcc, byte[] payload) {
		out.writeBytes(fourcc.getBytes(StandardCharsets.ISO_8859_1));
		writeLe32(out, payload.length);
		out.writeBytes(payload);
		if ((payload.length & 1) == 1) {
			out.write(0); // RIFF pad byte
		}
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer: for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private static void writeBe32(ByteArrayOutputStream out, int value) {
		out.write((value >> 24) & 0xFF);
		out.write((value >> 16) & 0xFF);
		out.write((value >> 8) & 0xFF);
		out.write(value & 0xFF);
	}

	private static void writeLe32(ByteArrayOutputStream out, int value) {
		out.write(value & 0xFF);
		out.write((value >> 8) & 0xFF);
		out.write((value >> 16) & 0xFF);
		out.write((value >> 24) & 0xFF);
	}

}
