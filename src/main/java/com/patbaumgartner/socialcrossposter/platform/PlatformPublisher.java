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

}
