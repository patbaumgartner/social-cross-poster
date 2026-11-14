package com.patbaumgartner.socialcrossposter.platform;

/**
 * One contact identity handed to a {@link MentionContract}: the raw handle plus the
 * optional instance host and the separately stored resolved identity. The contract
 * decides whether this yields a native mention or a fallback — it must never invent an
 * identifier (ADR-003 point 6).
 */
public record MentionCandidate(String handle, String host, String identityKind, String identityValue,
		boolean verified) {

}
