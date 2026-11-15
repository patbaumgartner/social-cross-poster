package com.patbaumgartner.socialcrossposter.publish;

import java.util.List;

/**
 * One remote action with its append-only attempts for the history detail view (UC-08
 * steps 4-5). The original delivery row is never modified by a remote action (ADR-015);
 * the action outcome and redacted attempts stay auditable here (ADR-007).
 */
public record RemoteActionDetail(RemoteAction action, List<RemoteActionAttempt> attempts) {

}
