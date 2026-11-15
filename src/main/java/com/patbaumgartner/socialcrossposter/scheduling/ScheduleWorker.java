package com.patbaumgartner.socialcrossposter.scheduling;

import java.time.OffsetDateTime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled dispatch worker (UC-10 step 4, ADR-016). Polls for due one-time schedules and
 * lets {@link ScheduleService} create at most one publication per schedule when due,
 * within the scheduled-instant + 15 minutes cutoff. Disabled with
 * {@code APP_WORKER_ENABLED=false} — tests drive {@link ScheduleService#runDueSchedules}
 * manually instead.
 */
@Component
@ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ScheduleWorker {

	private final ScheduleService schedules;

	public ScheduleWorker(ScheduleService schedules) {
		this.schedules = schedules;
	}

	@Scheduled(fixedDelayString = "${app.worker.poll-interval-ms:5000}",
			initialDelayString = "${app.worker.initial-delay-ms:2000}")
	public void runDueSchedules() {
		this.schedules.runDueSchedules(OffsetDateTime.now());
	}

}
