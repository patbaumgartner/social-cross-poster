package com.patbaumgartner.socialcrossposter.platform;

/**
 * Explicit execution context for one remote edit/delete (UC-08, ADR-001): the decrypted
 * credential, the destination's provider resource id (e.g. telegram chat id), the action
 * (EDIT/DELETE), the replacement text (EDIT only) and the confirmed external reference of
 * the remote post. Implementations must be stateless — no workspace, token or session
 * state in the bean.
 */
public record RemoteActionContext(String credential, String targetId, String action, String text, String externalRef) {

}
