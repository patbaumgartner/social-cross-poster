package com.patbaumgartner.socialcrossposter.contacts;

import com.patbaumgartner.socialcrossposter.platform.MentionStatus;

/**
 * One resolved mention token for one platform (UC-03 step 4). {@code renderedText} is
 * what a preview shows: the native mention when the platform contract verifies one,
 * otherwise the display name. Unknown tokens are flagged
 * ({@link MentionStatus#UNKNOWN_TOKEN}) and keep their raw text — a person is never
 * silently omitted (ADR-003 point 7).
 */
public record MentionMatch(String rawToken, Long contactId, String displayName, boolean archived, MentionStatus status,
		String renderedText, String note) {

}
