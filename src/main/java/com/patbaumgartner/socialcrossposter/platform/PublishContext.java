package com.patbaumgartner.socialcrossposter.platform;

import java.util.List;

/**
 * Explicit execution context for one provider write (ADR-001): the decrypted credential,
 * the destination's provider resource id (e.g. telegram chat id), the approved payload
 * text, the workspace-scoped idempotency key, — UC-13 second slice — the approved
 * attached images resolved from the private library for direct upload, and — UC-13 fourth
 * slice — the approved images as short-lived revocable signed import URLs for
 * source-fetch platforms (exactly one of the two lists is non-empty, per platform
 * capability). Implementations must be stateless — no workspace, token or session state
 * in the bean; grant URLs are bearer capabilities and must never be logged (ADR-007).
 */
public record PublishContext(String credential, String targetId, String text, String idempotencyKey,
		List<MediaAttachment> media, List<ImportSource> importSources) {

	public PublishContext {
		media = media == null ? List.of() : List.copyOf(media);
		importSources = importSources == null ? List.of() : List.copyOf(importSources);
	}

	/**
	 * Text-only payload (UC-05); media-carrying payloads use the 5-arg constructor (UC-13
	 * direct upload) or the 6-arg constructor (UC-13 import grants).
	 */
	public PublishContext(String credential, String targetId, String text, String idempotencyKey) {
		this(credential, targetId, text, idempotencyKey, List.of(), List.of());
	}

	/** Direct-upload media payload (UC-13 second slice). */
	public PublishContext(String credential, String targetId, String text, String idempotencyKey,
			List<MediaAttachment> media) {
		this(credential, targetId, text, idempotencyKey, media, List.of());
	}

}
