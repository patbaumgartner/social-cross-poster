package com.patbaumgartner.socialcrossposter.media;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Strips outgoing image metadata at the container level (UC-13 third slice, ADR-016):
 * GPS/camera EXIF, XMP, IPTC, text chunks and comments are removed from the derivative
 * that is uploaded to the platform, while the private original keeps everything
 * (ADR-008/ADR-016). Stripping is done on the byte container — pixels, animation and
 * color profile are preserved byte-for-byte (no re-encode, so animated GIF/APNG/WebP
 * survive), only metadata chunks/segments are dropped. The derivative is computed once at
 * upload time and stored next to the original, so a later policy change can never
 * silently replace the bytes under an existing approval (ADR-016). Malformed input is
 * rejected fail-closed ({@link IllegalArgumentException}) — an unparsable "image" is
 * never uploaded.
 */
public final class OutgoingImageSanitizer {

	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };

	/**
	 * Ancillary PNG chunks kept in the derivative (color/alpha/layout/APNG); all other
	 * ancillary chunks are metadata and dropped.
	 */
	private static final Set<String> PNG_KEEP_ANCILLARY = Set.of("PLTE", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT",
			"pHYs", "acTL", "fcTL", "fdAT");

	private OutgoingImageSanitizer() {
	}

	/**
	 * Returns the metadata-stripped derivative for one allow-listed image type. Throws
	 * {@link IllegalArgumentException} for unsupported types or malformed bytes.
	 */
	public static byte[] strip(String contentType, byte[] in) {
		if (in == null || in.length == 0) {
			throw new IllegalArgumentException("empty input");
		}
		if (contentType == null) {
			throw new IllegalArgumentException("no content type");
		}
		return switch (contentType.toLowerCase()) {
			case "image/png" -> stripPng(in);
			case "image/jpeg" -> stripJpeg(in);
			case "image/gif" -> stripGif(in);
			case "image/webp" -> stripWebp(in);
			default -> throw new IllegalArgumentException("unsupported content type '" + contentType + "'");
		};
	}

	// ---- PNG: keep the signature, all critical chunks and whitelisted ancillary chunks;
	// drop text/EXIF/time and unknown ancillary chunks. ----

	static byte[] stripPng(byte[] in) {
		if (in.length < 8 || !startsWith(in, PNG_SIGNATURE)) {
			throw new IllegalArgumentException("not a PNG (bad signature)");
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
		w(out, PNG_SIGNATURE);
		boolean changed = false;
		boolean sawIhdr = false;
		boolean sawIend = false;
		int i = 8;
		while (i < in.length && !sawIend) {
			if (i + 8 > in.length) {
				throw new IllegalArgumentException("truncated PNG chunk header");
			}
			int len = be32(in, i);
			String type = new String(in, i + 4, 4, StandardCharsets.ISO_8859_1);
			if (len < 0 || i + 12L + len > in.length) {
				throw new IllegalArgumentException("truncated PNG chunk " + type);
			}
			boolean critical = type.charAt(0) >= 'A' && type.charAt(0) <= 'Z';
			boolean keep = critical || PNG_KEEP_ANCILLARY.contains(type);
			if (type.equals("IHDR")) {
				sawIhdr = true;
			}
			if (keep) {
				w(out, in, i, 12 + len);
			}
			else {
				changed = true;
			}
			if (type.equals("IEND")) {
				sawIend = true;
			}
			i += 12 + len;
		}
		if (!sawIhdr || !sawIend) {
			throw new IllegalArgumentException("PNG without IHDR/IEND");
		}
		return changed ? out.toByteArray() : in;
	}

	// ---- JPEG: keep SOI/EOI/RST, APP0 (JFIF) + APP2 (ICC), tables/frames/scan data;
	// drop APP1 (EXIF/XMP), APP13 (IPTC), COM and other APPn. ----

	static byte[] stripJpeg(byte[] in) {
		if (in.length < 4 || (in[0] & 0xFF) != 0xFF || (in[1] & 0xFF) != 0xD8) {
			throw new IllegalArgumentException("not a JPEG (missing SOI)");
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
		w(out, in, 0, 2);
		boolean changed = false;
		int i = 2;
		while (i < in.length) {
			if ((in[i] & 0xFF) != 0xFF) {
				throw new IllegalArgumentException("JPEG marker expected at " + i);
			}
			int marker = in[i + 1] & 0xFF;
			if (marker == 0xD9) { // EOI
				w(out, in, i, 2);
				return changed ? out.toByteArray() : in;
			}
			if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7)) { // standalone
																		// markers
				w(out, in, i, 2);
				i += 2;
				continue;
			}
			if (i + 4 > in.length) {
				throw new IllegalArgumentException("truncated JPEG segment");
			}
			int len = ((in[i + 2] & 0xFF) << 8) | (in[i + 3] & 0xFF);
			if (len < 2 || i + 2L + len > in.length) {
				throw new IllegalArgumentException("truncated JPEG segment FF" + Integer.toHexString(marker));
			}
			if (marker == 0xDA) { // SOS: segment header, then entropy-coded scan data
				w(out, in, i, 2 + len);
				i += 2 + len;
				int scanStart = i;
				while (i < in.length - 1) {
					if ((in[i] & 0xFF) == 0xFF) {
						int next = in[i + 1] & 0xFF;
						if (next != 0x00 && !(next >= 0xD0 && next <= 0xD7)) {
							break; // real marker follows
						}
					}
					i++;
				}
				w(out, in, scanStart, i - scanStart);
				continue;
			}
			boolean appn = marker >= 0xE0 && marker <= 0xEF;
			boolean keep = !appn || marker == 0xE0 || marker == 0xE2; // drop
																		// APP1/APP13/other
																		// APPn, keep
																		// APP0/APP2
			if (marker == 0xFE) { // COM
				keep = false;
			}
			if (keep) {
				w(out, in, i, 2 + len);
			}
			else {
				changed = true;
			}
			i += 2 + len;
		}
		throw new IllegalArgumentException("JPEG without EOI");
	}

	// ---- GIF: copy everything except comment extensions (0x21 0xFE). ----

	static byte[] stripGif(byte[] in) {
		if (in.length < 13) {
			throw new IllegalArgumentException("not a GIF (too short)");
		}
		String header = new String(in, 0, 6, StandardCharsets.ISO_8859_1);
		if (!header.equals("GIF87a") && !header.equals("GIF89a")) {
			throw new IllegalArgumentException("not a GIF (bad header)");
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
		w(out, in, 0, 13); // header + logical screen descriptor
		boolean changed = false;
		int i = 13;
		int packed = in[10] & 0xFF;
		if ((packed & 0x80) != 0) { // global color table
			int gct = 3 << ((packed & 0x07) + 1);
			if (i + gct > in.length) {
				throw new IllegalArgumentException("truncated GIF global color table");
			}
			w(out, in, i, gct);
			i += gct;
		}
		while (i < in.length) {
			int b = in[i] & 0xFF;
			if (b == 0x3B) { // trailer
				out.write(0x3B);
				return changed ? out.toByteArray() : in;
			}
			if (b == 0x21) { // extension
				if (i + 2 > in.length) {
					throw new IllegalArgumentException("truncated GIF extension");
				}
				int label = in[i + 1] & 0xFF;
				if (label == 0xFE) { // comment — drop
					changed = true;
					i += 2;
					i = skipSubBlocks(in, i);
				}
				else {
					out.write(0x21);
					out.write(label);
					i += 2;
					int start = i;
					i = skipSubBlocks(in, i);
					w(out, in, start, i - start);
				}
				continue;
			}
			if (b == 0x2C) { // image descriptor
				if (i + 10 > in.length) {
					throw new IllegalArgumentException("truncated GIF image descriptor");
				}
				w(out, in, i, 10);
				i += 10;
				int ipacked = in[i - 1] & 0xFF;
				if ((ipacked & 0x80) != 0) { // local color table
					int lct = 3 << ((ipacked & 0x07) + 1);
					if (i + lct > in.length) {
						throw new IllegalArgumentException("truncated GIF local color table");
					}
					w(out, in, i, lct);
					i += lct;
				}
				if (i >= in.length) {
					throw new IllegalArgumentException("truncated GIF image data");
				}
				out.write(in[i++]); // LZW minimum code size
				int start = i;
				i = skipSubBlocks(in, i);
				w(out, in, start, i - start);
				continue;
			}
			throw new IllegalArgumentException("unknown GIF block 0x" + Integer.toHexString(b) + " at " + i);
		}
		throw new IllegalArgumentException("GIF without trailer");
	}

	// ---- WebP: drop EXIF/XMP chunks and clear their VP8X flag bits; keep
	// ICC/alpha/animation and all image chunks. ----

	static byte[] stripWebp(byte[] in) {
		if (in.length < 12 || !startsWith(in, "RIFF".getBytes(StandardCharsets.ISO_8859_1))
				|| !startsWith(in, 8, "WEBP".getBytes(StandardCharsets.ISO_8859_1))) {
			throw new IllegalArgumentException("not a WebP (bad RIFF/WEBP header)");
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream(in.length);
		boolean changed = false;
		int i = 12;
		while (i < in.length) {
			if (i + 8 > in.length) {
				throw new IllegalArgumentException("truncated WebP chunk header");
			}
			String fourcc = new String(in, i, 4, StandardCharsets.ISO_8859_1);
			int size = le32(in, i + 4);
			if (size < 0 || i + 8L + size > in.length) {
				throw new IllegalArgumentException("truncated WebP chunk " + fourcc);
			}
			byte[] payload = new byte[size];
			System.arraycopy(in, i + 8, payload, 0, size);
			if (fourcc.equals("VP8X") && size >= 4) {
				payload[0] &= ~(0x08 | 0x04); // clear EXIF and XMP flags
			}
			if (fourcc.equals("EXIF") || fourcc.equals("XMP ") || fourcc.equals("XMP")) {
				changed = true;
			}
			else {
				w(body, fourcc.getBytes(StandardCharsets.ISO_8859_1));
				writeLe32(body, size);
				body.writeBytes(payload);
				if ((size & 1) == 1) {
					body.write(0); // pad byte
				}
			}
			i += 8 + size + (size & 1);
		}
		byte[] bodyBytes = body.toByteArray();
		if (!changed) {
			return in;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(12 + bodyBytes.length);
		out.writeBytes("RIFF".getBytes(StandardCharsets.ISO_8859_1));
		writeLe32(out, 4 + bodyBytes.length);
		w(out, "WEBP".getBytes(StandardCharsets.ISO_8859_1));
		out.writeBytes(bodyBytes);
		return out.toByteArray();
	}

	/**
	 * {@code ByteArrayOutputStream.write(byte[])} declares IOException on this JDK — wrap
	 * it (never thrown in memory).
	 */
	private static void w(ByteArrayOutputStream out, byte[] b) {
		try {
			out.write(b);
		}
		catch (java.io.IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

	private static void w(ByteArrayOutputStream out, byte[] b, int off, int len) {
		out.write(b, off, len);
	}

	private static int skipSubBlocks(byte[] in, int i) {
		while (i < in.length) {
			int len = in[i++] & 0xFF;
			if (len == 0) {
				return i;
			}
			if (i + len > in.length) {
				throw new IllegalArgumentException("truncated GIF sub-block");
			}
			i += len;
		}
		throw new IllegalArgumentException("GIF sub-blocks without terminator");
	}

	private static boolean startsWith(byte[] in, byte[] prefix) {
		return startsWith(in, 0, prefix);
	}

	private static boolean startsWith(byte[] in, int offset, byte[] prefix) {
		if (in.length < offset + prefix.length) {
			return false;
		}
		for (int j = 0; j < prefix.length; j++) {
			if (in[offset + j] != prefix[j]) {
				return false;
			}
		}
		return true;
	}

	private static int be32(byte[] in, int off) {
		return ((in[off] & 0xFF) << 24) | ((in[off + 1] & 0xFF) << 16) | ((in[off + 2] & 0xFF) << 8)
				| (in[off + 3] & 0xFF);
	}

	private static int le32(byte[] in, int off) {
		return (in[off] & 0xFF) | ((in[off + 1] & 0xFF) << 8) | ((in[off + 2] & 0xFF) << 16)
				| ((in[off + 3] & 0xFF) << 24);
	}

	private static void writeLe32(ByteArrayOutputStream out, int value) {
		out.write(value & 0xFF);
		out.write((value >> 8) & 0xFF);
		out.write((value >> 16) & 0xFF);
		out.write((value >> 24) & 0xFF);
	}

}
