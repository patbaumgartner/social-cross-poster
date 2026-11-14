package com.patbaumgartner.socialcrossposter.platform;

/**
 * Result of one provider write (ADR-015). {@code externalRef} is only set when the
 * provider confirmed the write; {@code detail} is redacted and must never contain a
 * credential, token or provider response body (ADR-007).
 */
public record PublishResult(PublishOutcome outcome, String externalRef, String detail) {

	public static PublishResult success(String externalRef) {
		return new PublishResult(PublishOutcome.SUCCESS, externalRef, null);
	}

	public static PublishResult failed(String detail) {
		return new PublishResult(PublishOutcome.FAILED, null, detail);
	}

	public static PublishResult unknown(String detail) {
		return new PublishResult(PublishOutcome.UNKNOWN, null, detail);
	}

}
