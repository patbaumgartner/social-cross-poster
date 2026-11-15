package com.patbaumgartner.socialcrossposter.platform;

/**
 * Explicit context for one provider reconciliation read (UC-07, ADR-001): the decrypted
 * credential, the destination's provider resource id, the exact frozen payload text and
 * the workspace-scoped idempotency key. Implementations must be stateless — no workspace,
 * token or session state in the bean.
 */
public record ReconcileContext(String credential, String targetId, String text, String idempotencyKey) {

}
