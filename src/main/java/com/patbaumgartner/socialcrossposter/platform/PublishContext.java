package com.patbaumgartner.socialcrossposter.platform;

/**
 * Explicit execution context for one provider write (ADR-001): the decrypted credential,
 * the destination's provider resource id (e.g. telegram chat id), the approved payload
 * text and the workspace-scoped idempotency key. Implementations must be stateless — no
 * workspace, token or session state in the bean.
 */
public record PublishContext(String credential, String targetId, String text, String idempotencyKey) {

}
