package com.patbaumgartner.socialcrossposter.platform;

/**
 * Result of one provider reconciliation read (UC-07, ADR-015). {@code externalRef} is
 * only set on CONFIRMED; {@code detail} is redacted and must never contain a credential,
 * token or provider response body (ADR-007).
 */
public record ReconcileResult(ReconcileOutcome outcome, String externalRef, String detail) {

	public static ReconcileResult confirmed(String externalRef) {
		return new ReconcileResult(ReconcileOutcome.CONFIRMED, externalRef, null);
	}

	public static ReconcileResult notConfirmed(String detail) {
		return new ReconcileResult(ReconcileOutcome.NOT_CONFIRMED, null, detail);
	}

	public static ReconcileResult inconclusive(String detail) {
		return new ReconcileResult(ReconcileOutcome.INCONCLUSIVE, null, detail);
	}

}
