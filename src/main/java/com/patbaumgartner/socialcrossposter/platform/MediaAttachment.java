package com.patbaumgartner.socialcrossposter.platform;

/**
 * One approved image attached to a publish payload (UC-13 second slice, ADR-008): the
 * private source bytes with filename/content type for a direct multipart upload, plus the
 * local alt text (ADR-016). Platforms that can transmit alt text (e.g. a media
 * description) may use it; platforms that cannot must surface that limitation (ADR-016) —
 * the alt text always stays stored locally on the revision regardless.
 */
public record MediaAttachment(String filename, String contentType, byte[] bytes, String altText) {

	public MediaAttachment {
		bytes = bytes == null ? new byte[0] : bytes.clone();
	}

	@Override
	public byte[] bytes() {
		return bytes.clone();
	}

}
