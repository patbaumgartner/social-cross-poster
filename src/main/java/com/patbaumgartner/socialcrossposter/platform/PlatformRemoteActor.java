package com.patbaumgartner.socialcrossposter.platform;

import java.util.Set;

/**
 * Stateless per-platform remote actor strategy (UC-08, ADR-001, ADR-015): performs one
 * verified edit/delete on an already published remote post and reports a redacted
 * {@link PublishResult}. Operations a platform does not support must be absent from
 * {@link #supportedActions()} so the UI disables them with an explanation instead of
 * attempting them (UC-08 step 1). The credential is passed per call and is never logged,
 * stored in the bean, or included in any detail (ADR-007). Outcomes are classified per
 * ADR-015: SUCCESS provider-confirmed, FAILED a confirmed rejection, UNKNOWN a possible
 * write — UNKNOWN triggers reconciliation, never a blind repeat or a replacement
 * create-post.
 */
public interface PlatformRemoteActor {

	String ACTION_EDIT = "EDIT";

	String ACTION_DELETE = "DELETE";

	/** Platform key, e.g. {@code telegram}. */
	String platform();

	/** Remote actions this platform supports, e.g. EDIT and DELETE. */
	Set<String> supportedActions();

	/**
	 * Executes one remote action on the post referenced by {@code context.externalRef()}
	 * at destination {@code context.targetId()}. Classifies the outcome per ADR-015.
	 */
	PublishResult execute(RemoteActionContext context);

}
