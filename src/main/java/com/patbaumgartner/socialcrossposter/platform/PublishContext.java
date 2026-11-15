package com.patbaumgartner.socialcrossposter.platform;

import java.util.List;

/**
 * Explicit execution context for one provider write (ADR-001): the decrypted credential,
 * the destination's provider resource id (e.g. telegram chat id), the approved payload
 * text, the workspace-scoped idempotency key and — UC-13 second slice — the approved
 * attached images resolved from the private library. Implementations must be stateless —
 * no workspace, token or session state in the bean.
 */
public record PublishContext(String credential, String targetId, String text, String idempotencyKey,
		List<MediaAttachment> media) {

	public PublishContext {
		media = media == null ? List.of() : List.copyOf(media);
	}

	/**
	 * Text-only payload (UC-05); media-carrying payloads use the 5-arg constructor
	 * (UC-13).
	 */
	public PublishContext(String credential, String targetId, String text, String idempotencyKey) {
		this(credential, targetId, text, idempotencyKey, List.of());
	}

}
