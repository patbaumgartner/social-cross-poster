package com.patbaumgartner.socialcrossposter.notify;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External notification configuration (UC-12 second slice, ADR-013, ADR-015), bound from
 * APP_NOTIFY_* env vars. {@code workerEnabled} switches the scheduled outbox worker (off
 * in tests, which drive {@link NotificationDeliveryService#runDue} manually).
 * {@code maxAttempts} implements UC-12 step 4 (at most three total delivery attempts)
 * with exponential backoff {@code base * 2^(n-1)} x jitter capped at {@code cap}.
 * {@code allowedInternalHosts} is the deployment operator allow-list of arc42 section 8:
 * empty by default, meaning only https URLs to public hosts pass the SSRF guard.
 */
@ConfigurationProperties(prefix = "app.notify")
public record NotifyProperties(Boolean workerEnabled, Long pollIntervalMs, Long initialDelayMs, Integer maxAttempts,
		Long backoffBaseSeconds, Long backoffCapSeconds, Long connectTimeoutMs, Long readTimeoutMs,
		List<String> allowedInternalHosts) {

	public NotifyProperties {
		if (workerEnabled == null) {
			workerEnabled = true;
		}
		if (pollIntervalMs == null || pollIntervalMs <= 0) {
			pollIntervalMs = 5000L;
		}
		if (initialDelayMs == null || initialDelayMs <= 0) {
			initialDelayMs = 2000L;
		}
		if (maxAttempts == null || maxAttempts < 1) {
			maxAttempts = 3;
		}
		if (backoffBaseSeconds == null || backoffBaseSeconds < 1) {
			backoffBaseSeconds = 60L;
		}
		if (backoffCapSeconds == null || backoffCapSeconds < 1) {
			backoffCapSeconds = 3600L;
		}
		if (connectTimeoutMs == null || connectTimeoutMs <= 0) {
			connectTimeoutMs = 5000L;
		}
		if (readTimeoutMs == null || readTimeoutMs <= 0) {
			readTimeoutMs = 10000L;
		}
		if (allowedInternalHosts == null) {
			allowedInternalHosts = List.of();
		}
	}

}
