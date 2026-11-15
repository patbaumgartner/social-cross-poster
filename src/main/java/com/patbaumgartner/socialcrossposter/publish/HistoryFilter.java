package com.patbaumgartner.socialcrossposter.publish;

import java.time.OffsetDateTime;

/**
 * Optional filters for the workspace publication history (UC-06 step 2). Every field is
 * nullable; {@code null} means "no filter". {@code text} matches case-insensitively
 * against the post title and the delivered payload texts; {@code from}/{@code to} bound
 * the publication's {@code created_at} (both inclusive).
 */
public record HistoryFilter(Long accountId, String platform, String state, String text, OffsetDateTime from,
		OffsetDateTime to) {

	public static HistoryFilter empty() {
		return new HistoryFilter(null, null, null, null, null, null);
	}

}
