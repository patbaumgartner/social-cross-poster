package com.patbaumgartner.socialcrossposter.platform;

/**
 * Per-platform text constraints used by previews and validation (UC-04, ADR-003 point 2).
 * The same rules drive the preview counter and the blocking validation — preview and
 * execution never disagree. Limits are conservative catalog values; they are verified per
 * destination before publication (docs/platforms/README.md).
 */
public interface TextRules {

	/** Platform key this rule set applies to (see {@link PlatformKeys}). */
	String platform();

	/** Maximum text length in characters for one message/caption on this platform. */
	int maxTextLength();

	/**
	 * Counts the text length the way the platform does. Defaults to
	 * {@link String#length()}; platforms with different weighting (e.g. UTF-16 code units
	 * vs. code points) override this.
	 */
	default int count(String text) {
		return text == null ? 0 : text.length();
	}

}
