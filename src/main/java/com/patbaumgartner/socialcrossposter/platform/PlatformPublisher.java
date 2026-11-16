package com.patbaumgartner.socialcrossposter.platform;

/**
 * Stateless per-platform publisher strategy (ADR-001, ADR-015): sends one approved
 * payload to one destination and reports a redacted {@link PublishResult}. The credential
 * is passed per call via {@link PublishContext} and is never logged, stored in the bean,
 * or included in any error detail (ADR-007).
 */
public interface PlatformPublisher {

	/** Platform key, e.g. {@code telegram}. */
	String platform();

	/**
	 * Publishes {@code context.text()} to {@code context.targetId()} with the given
	 * credential. Classifies the outcome as SUCCESS / FAILED / UNKNOWN per ADR-015.
	 */
	PublishResult publish(PublishContext context);

	/**
	 * Whether this platform can upload attached images directly (UC-13, ADR-008).
	 * Defaults to {@code false} so publishing a media-attached revision to a platform
	 * without upload support stays blocked fail-closed.
	 */
	default boolean supportsMediaUpload() {
		return false;
	}

	/**
	 * Whether this platform has a verified source-fetch API that can import an image from
	 * a short-lived revocable signed URL (UC-13 fourth slice, ADR-008 step 3). Defaults
	 * to {@code false}; dispatch then mints import grants for the approved derivatives. A
	 * platform with neither upload nor import support keeps media-attached publishing
	 * blocked fail-closed — permanent public source URLs are never substituted.
	 */
	default boolean supportsMediaImport() {
		return false;
	}

}
