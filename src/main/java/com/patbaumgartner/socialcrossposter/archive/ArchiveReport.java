package com.patbaumgartner.socialcrossposter.archive;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * UC-15 step 6: what an archive operation listed and did — the pending schedules,
 * unclaimed jobs and unresolved (active/UNKNOWN) deliveries of the workspace that were
 * canceled or acknowledged, plus the archive timestamp. Used for the confirmation page
 * and the post-archive notice; history and private media are retained untouched.
 */
public record ArchiveReport(List<Long> pendingScheduleIds, List<Long> unclaimedJobIds, List<Long> unresolvedDeliveryIds,
		OffsetDateTime archivedAt) {

	public int pendingSchedules() {
		return this.pendingScheduleIds.size();
	}

	public int unclaimedJobs() {
		return this.unclaimedJobIds.size();
	}

	public int unresolvedDeliveries() {
		return this.unresolvedDeliveryIds.size();
	}

}
