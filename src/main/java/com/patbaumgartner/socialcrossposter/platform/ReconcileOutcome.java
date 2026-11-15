package com.patbaumgartner.socialcrossposter.platform;

/**
 * Outcome of a provider reconciliation read (UC-07 step 2, ADR-015): CONFIRMED means the
 * write is provider-verified, NOT_CONFIRMED means the provider verified that no write
 * happened (safe to repeat the exact frozen payload), INCONCLUSIVE means the provider
 * cannot tell — the delivery becomes NEEDS_ATTENTION and is never blindly retried.
 */
public enum ReconcileOutcome {

	CONFIRMED, NOT_CONFIRMED, INCONCLUSIVE

}
