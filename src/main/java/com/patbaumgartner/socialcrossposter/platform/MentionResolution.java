package com.patbaumgartner.socialcrossposter.platform;

/**
 * How one contact resolves for one platform: {@link MentionStatus#NATIVE} with the
 * platform-native mention text, or a fallback with the display name plus a note that
 * explains the limitation directly (UC-03: never claim a notification that will not
 * happen).
 */
public record MentionResolution(MentionStatus status, String text, String note) {

	public static MentionResolution fallback(String displayName, String note) {
		return new MentionResolution(MentionStatus.FALLBACK, displayName, note);
	}

}
