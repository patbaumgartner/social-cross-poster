package com.patbaumgartner.socialcrossposter.platform;

/**
 * Outcome of resolving one neutral contact token for one platform (ADR-003 points 5-6).
 * {@link #NATIVE} means the platform's verified contract renders a real mention;
 * {@link #FALLBACK} is the visible display-name fallback that never claims a
 * notification; {@link #AMBIGUOUS} and {@link #UNKNOWN_TOKEN} are flagged, never
 * silently dropped.
 */
public enum MentionStatus {

	NATIVE, FALLBACK, AMBIGUOUS, UNKNOWN_TOKEN

}
