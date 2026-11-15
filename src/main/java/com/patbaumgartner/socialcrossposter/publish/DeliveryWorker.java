package com.patbaumgartner.socialcrossposter.publish;

import java.time.OffsetDateTime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled durable retry worker (UC-07 step 3, ADR-015). Polls for due delivery jobs and
 * lets {@link DeliveryJobService} claim, execute and settle them with lease-guarded
 * backoff. Disabled with {@code APP_WORKER_ENABLED=false} — tests drive
 * {@link DeliveryJobService#runDueJobs} manually instead.
 */
@Component
@ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryWorker {

	private final DeliveryJobService jobService;

	public DeliveryWorker(DeliveryJobService jobService) {
		this.jobService = jobService;
	}

	@Scheduled(fixedDelayString = "${app.worker.poll-interval-ms:5000}",
			initialDelayString = "${app.worker.initial-delay-ms:2000}")
	public void poll() {
		this.jobService.runDueJobs(OffsetDateTime.now());
	}

}
