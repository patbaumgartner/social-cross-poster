package com.patbaumgartner.socialcrossposter.platform;

/**
 * Verified per-platform mention contract (ADR-003 point 6, UC-03 step 4). Only platforms
 * with a contract registered in the {@link MentionRegistry} can produce
 * {@link MentionStatus#NATIVE} mentions; every other platform falls back visibly to the
 * display name. Implementations are stateless and never invent an identifier.
 */
public interface MentionContract {

	/** Platform key this contract covers, e.g. {@code telegram}. */
	String platform();

	/**
	 * Resolves one contact identity for this platform.
	 * @param candidate the contact's handle/host/identity for this platform
	 * @param displayName the contact display name used for the fallback text
	 */
	MentionResolution resolve(MentionCandidate candidate, String displayName);

}
