package com.patbaumgartner.socialcrossposter.publish;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Durable retry worker configuration (UC-07, ADR-015), bound from APP_WORKER_* env vars.
 * Defaults implement UC-07 step 4: at most {@code maxAttempts} attempts per delivery (4 =
 * the initial attempt + three automatic retries), exponential backoff starting at
 * {@code backoffBaseSeconds} capped at {@code backoffCapSeconds}, and a
 * {@code leaseTtlSeconds} lease so a crashed worker's jobs are reclaimed.
 * {@code instanceId} names this worker in lease rows (default: a random id per boot).
 */
@ConfigurationProperties(prefix = "app.worker")
public record WorkerProperties(Boolean enabled, Long pollIntervalMs, Long initialDelayMs, Long leaseTtlSeconds,
		Integer maxAttempts, Long backoffBaseSeconds, Long backoffCapSeconds, String instanceId) {

	public WorkerProperties {
		if (enabled == null) {
			enabled = true;
		}
		if (pollIntervalMs == null) {
			pollIntervalMs = 5000L;
		}
		if (initialDelayMs == null) {
			initialDelayMs = 2000L;
		}
		if (leaseTtlSeconds == null) {
			leaseTtlSeconds = 60L;
		}
		if (maxAttempts == null) {
			maxAttempts = 4;
		}
		if (backoffBaseSeconds == null) {
			backoffBaseSeconds = 60L;
		}
		if (backoffCapSeconds == null) {
			backoffCapSeconds = 3600L;
		}
	}

}
