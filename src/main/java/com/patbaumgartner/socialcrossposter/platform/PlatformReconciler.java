package com.patbaumgartner.socialcrossposter.platform;

/**
 * Stateless per-platform reconciliation strategy (UC-07 step 2, ADR-001, ADR-015): uses a
 * verified provider read-back to decide whether an UNKNOWN write actually happened. Never
 * a blind retry — only NOT_CONFIRMED allows a repeat of the exact frozen payload. The
 * credential is passed per call via {@link ReconcileContext} and is never logged, stored
 * in the bean, or included in any detail (ADR-007).
 */
public interface PlatformReconciler {

	/** Platform key, e.g. {@code telegram}. */
	String platform();

	/**
	 * Reconciles one uncertain delivery and reports a redacted {@link ReconcileResult}
	 * (CONFIRMED / NOT_CONFIRMED / INCONCLUSIVE).
	 */
	ReconcileResult reconcile(ReconcileContext context);

}
