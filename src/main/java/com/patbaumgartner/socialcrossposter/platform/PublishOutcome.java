package com.patbaumgartner.socialcrossposter.platform;

/**
 * Outcome of one provider write attempt (ADR-015): SUCCESS is provider-confirmed, FAILED
 * is a confirmed rejection (e.g. HTTP 4xx) and UNKNOWN means the write may have happened
 * but the provider could not confirm it (HTTP 5xx or transport failure) — UNKNOWN always
 * triggers reconciliation, never a blind retry.
 */
public enum PublishOutcome {

	SUCCESS, FAILED, UNKNOWN

}
