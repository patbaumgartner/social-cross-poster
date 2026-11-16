package com.patbaumgartner.socialcrossposter.notify;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.patbaumgartner.socialcrossposter.accounts.Connection;
import com.patbaumgartner.socialcrossposter.publish.Delivery;
import com.patbaumgartner.socialcrossposter.scheduling.PublicationSchedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link NotificationService} (UC-12, ADR-013, ADR-015): {@code record}
 * deduplicates on an existing {@code (workspace, event_key)} and returns the existing row
 * without saving or enqueueing, saves new events and enqueues deduplicated webhook outbox
 * rows for them (second slice), {@code eventsOf} delegates to the workspace-scoped
 * finder, and the emit helpers build the expected redacted keys, categories and summaries
 * — summaries must never contain the payload text. Mocked repositories/services, no
 * Spring context.
 */
class NotificationServiceTest {

	private static final Long WS = 1L;

	private static final OffsetDateTime NOW = OffsetDateTime.now();

	private NotificationEventRepository events;

	private NotificationDeliveryService deliveries;

	private NotificationService service;

	@BeforeEach
	void setUp() {
		this.events = mock(NotificationEventRepository.class);
		this.deliveries = mock(NotificationDeliveryService.class);
		this.service = new NotificationService(this.events, this.deliveries);
		given(this.events.save(any(NotificationEvent.class))).willAnswer((inv) -> {
			NotificationEvent e = inv.getArgument(0);
			return new NotificationEvent(42L, e.workspaceId(), e.eventKey(), e.category(), e.publicationId(),
					e.deliveryId(), e.scheduleId(), e.connectionId(), e.summary(), e.createdAt());
		});
		given(this.events.findByWorkspaceIdAndEventKey(any(), anyString())).willReturn(Optional.empty());
	}

	@Test
	void recordSavesNewEventAndEnqueuesWebhookOutboxRows() {
		NotificationEvent saved = this.service.record(WS, "delivery:5:failed", NotificationService.C_DELIVERY_FAILED,
				100L, 5L, null, "summary");

		assertThat(saved.id()).isEqualTo(42L);
		assertThat(saved.workspaceId()).isEqualTo(WS);
		assertThat(saved.eventKey()).isEqualTo("delivery:5:failed");
		assertThat(saved.category()).isEqualTo(NotificationService.C_DELIVERY_FAILED);
		assertThat(saved.publicationId()).isEqualTo(100L);
		assertThat(saved.deliveryId()).isEqualTo(5L);
		verify(this.events).save(any(NotificationEvent.class));
		// UC-12 second slice: a newly recorded event enqueues outbox rows per channel
		verify(this.deliveries).enqueueForEvent(saved);
	}

	@Test
	void recordDeduplicatesOnExistingKeyAndSavesAndEnqueuesNothing() {
		NotificationEvent existing = new NotificationEvent(7L, WS, "delivery:5:failed",
				NotificationService.C_DELIVERY_FAILED, 100L, 5L, null, null, "original summary", NOW);
		given(this.events.findByWorkspaceIdAndEventKey(WS, "delivery:5:failed")).willReturn(Optional.of(existing));

		NotificationEvent result = this.service.record(WS, "delivery:5:failed", NotificationService.C_DELIVERY_FAILED,
				100L, 5L, null, "summary");

		assertThat(result).isSameAs(existing);
		verify(this.events, never()).save(any());
		verify(this.deliveries, never()).enqueueForEvent(any());
	}

	@Test
	void eventsOfDelegatesToWorkspaceScopedFinder() {
		NotificationEvent e = new NotificationEvent(9L, WS, "schedule:3:expired",
				NotificationService.C_SCHEDULE_EXPIRED, null, null, 3L, null, "expired", NOW);
		given(this.events.findTop50ByWorkspaceIdOrderByIdDesc(WS)).willReturn(List.of(e));

		assertThat(this.service.eventsOf(WS)).containsExactly(e);
		verify(this.events).findTop50ByWorkspaceIdOrderByIdDesc(WS);
	}

	@Test
	void notifyDeliveryFailedBuildsRedactedKeyCategoryAndSummary() {
		Delivery delivery = new Delivery(5L, WS, 100L, 7L, "telegram", "News channel", null, "-100123",
				"secret draft text", "hash", "FAILED", null, NOW, NOW);

		NotificationEvent e = this.service.notifyDeliveryFailed(WS, delivery);

		assertThat(e.eventKey()).isEqualTo("delivery:5:failed");
		assertThat(e.category()).isEqualTo(NotificationService.C_DELIVERY_FAILED);
		assertThat(e.publicationId()).isEqualTo(100L);
		assertThat(e.deliveryId()).isEqualTo(5L);
		assertThat(e.scheduleId()).isNull();
		// redaction (UC-12 alternative flow 4): destination identity is in, payload is
		// out
		assertThat(e.summary()).contains("telegram").contains("News channel").doesNotContain("secret draft text");
	}

	@Test
	void notifyRetriesExhaustedCarriesAttemptCountButNoPayload() {
		Delivery delivery = new Delivery(5L, WS, 100L, 7L, "telegram", "News channel", null, "-100123",
				"secret draft text", "hash", "FAILED", null, NOW, NOW);

		NotificationEvent e = this.service.notifyRetriesExhausted(WS, delivery, 3L);

		assertThat(e.eventKey()).isEqualTo("delivery:5:exhausted");
		assertThat(e.category()).isEqualTo(NotificationService.C_RETRIES_EXHAUSTED);
		assertThat(e.summary()).contains("3 attempts").doesNotContain("secret draft text");
	}

	@Test
	void notifyScheduleExpiredLinksScheduleAndPost() {
		PublicationSchedule schedule = new PublicationSchedule(3L, WS, 77L, 88L, "sched-x", "hash",
				java.time.LocalDateTime.now(), "UTC", NOW, "PENDING", null, NOW, NOW);

		NotificationEvent e = this.service.notifyScheduleExpired(WS, schedule);

		assertThat(e.eventKey()).isEqualTo("schedule:3:expired");
		assertThat(e.category()).isEqualTo(NotificationService.C_SCHEDULE_EXPIRED);
		assertThat(e.scheduleId()).isEqualTo(3L);
		assertThat(e.summary()).contains("#77");
	}

	@Test
	void notifyScheduleNeedsReviewHasItsOwnKey() {
		PublicationSchedule schedule = new PublicationSchedule(3L, WS, 77L, 88L, "sched-x", "hash",
				java.time.LocalDateTime.now(), "UTC", NOW, "PENDING", null, NOW, NOW);

		NotificationEvent e = this.service.notifyScheduleNeedsReview(WS, schedule);

		assertThat(e.eventKey()).isEqualTo("schedule:3:needs-review");
		assertThat(e.category()).isEqualTo(NotificationService.C_SCHEDULE_NEEDS_REVIEW);
		verify(this.events).save(any(NotificationEvent.class));
		verify(this.events, never()).findByWorkspaceIdAndEventKey(eq(999L), anyString());
	}

	@Test
	void notifyConnectionFailedSequencesEpisodeKeysPerConnection() {
		Connection c = connection(7L, "OK");
		given(this.events.countByWorkspaceIdAndEventKeyStartingWith(WS, "connection:7:failed:")).willReturn(0L, 2L);

		NotificationEvent first = this.service.notifyConnectionFailed(WS, c);
		NotificationEvent second = this.service.notifyConnectionFailed(WS, c);

		assertThat(first.eventKey()).isEqualTo("connection:7:failed:1");
		assertThat(second.eventKey()).isEqualTo("connection:7:failed:3");
		assertThat(first.category()).isEqualTo(NotificationService.C_CONNECTION_FAILED);
		assertThat(second.category()).isEqualTo(NotificationService.C_CONNECTION_FAILED);
		verify(this.deliveries, times(2)).enqueueForEvent(any(NotificationEvent.class));
	}

	@Test
	void notifyConnectionRecoveredLinksConnectionAndStaysRedacted() {
		Connection c = connection(7L, "ERROR");

		NotificationEvent e = this.service.notifyConnectionRecovered(WS, c);

		assertThat(e.eventKey()).isEqualTo("connection:7:recovered:1");
		assertThat(e.category()).isEqualTo(NotificationService.C_CONNECTION_RECOVERED);
		assertThat(e.connectionId()).isEqualTo(7L);
		assertThat(e.summary()).contains("telegram")
			.contains("News bot")
			.contains("healthy again")
			.doesNotContain("cipher-value")
			.doesNotContain("nonce-value");
		verify(this.deliveries).enqueueForEvent(e);
	}

	@Test
	void notifyConnectionFailedWithoutLabelDescribesPlatformOnlyAndNeverLeaksSecrets() {
		Connection c = new Connection(8L, WS, "telegram", "pasted", "@newsbot", null, "cipher-value", "nonce-value",
				"key-1", "OK", NOW, true, NOW, NOW);

		NotificationEvent e = this.service.notifyConnectionFailed(WS, c);

		assertThat(e.connectionId()).isEqualTo(8L);
		assertThat(e.summary()).contains("telegram")
			.doesNotContain("·")
			.doesNotContain("cipher-value")
			.doesNotContain("nonce-value")
			.doesNotContain("key-1");
	}

	@Test
	void notifyProviderOutageIsHourBucketedLinkedAndRedacted() {
		OffsetDateTime at = OffsetDateTime.parse("2026-11-16T17:23:41Z");
		Delivery delivery = new Delivery(5L, WS, 100L, 7L, "telegram", "News channel", null, "-100123",
				"secret draft text", "hash", "UNKNOWN", null, NOW, NOW);

		NotificationEvent e = this.service.notifyProviderOutage(WS, delivery, at);

		assertThat(e.eventKey()).isEqualTo("outage:telegram:2026-11-16T17Z");
		assertThat(e.category()).isEqualTo(NotificationService.C_PROVIDER_OUTAGE);
		assertThat(e.publicationId()).isEqualTo(100L);
		assertThat(e.deliveryId()).isEqualTo(5L);
		assertThat(e.scheduleId()).isNull();
		assertThat(e.connectionId()).isNull();
		// redaction (UC-12 alternative flow 4): platform + hour window + first-seen
		// delivery only — never the payload text or the destination label
		assertThat(e.summary()).contains("telegram")
			.contains("2026-11-16T17Z")
			.contains("delivery #5")
			.doesNotContain("secret draft text")
			.doesNotContain("News channel");
		verify(this.deliveries).enqueueForEvent(e);
	}

	@Test
	void notifyProviderOutageSameHourDeduplicatesOnTheBucketKey() {
		OffsetDateTime at = OffsetDateTime.parse("2026-11-16T17:59:59Z");
		Delivery first = delivery(5L);
		Delivery second = delivery(6L);
		NotificationEvent existing = new NotificationEvent(7L, WS, "outage:telegram:2026-11-16T17Z",
				NotificationService.C_PROVIDER_OUTAGE, 100L, 5L, null, null, "original outage summary", NOW);
		given(this.events.findByWorkspaceIdAndEventKey(WS, "outage:telegram:2026-11-16T17Z"))
			.willReturn(Optional.empty(), Optional.of(existing));

		NotificationEvent e1 = this.service.notifyProviderOutage(WS, first, at);
		NotificationEvent e2 = this.service.notifyProviderOutage(WS, second, at);

		assertThat(e1.eventKey()).isEqualTo("outage:telegram:2026-11-16T17Z");
		assertThat(e2).isSameAs(existing);
		verify(this.events, times(1)).save(any(NotificationEvent.class));
		verify(this.deliveries, times(1)).enqueueForEvent(any(NotificationEvent.class));
	}

	@Test
	void notifyProviderOutageNewHourGetsAFreshSummaryKey() {
		Delivery delivery = delivery(5L);

		NotificationEvent e17 = this.service.notifyProviderOutage(WS, delivery,
				OffsetDateTime.parse("2026-11-16T17:05:00Z"));
		NotificationEvent e18 = this.service.notifyProviderOutage(WS, delivery,
				OffsetDateTime.parse("2026-11-16T18:05:00Z"));

		assertThat(e17.eventKey()).isEqualTo("outage:telegram:2026-11-16T17Z");
		assertThat(e18.eventKey()).isEqualTo("outage:telegram:2026-11-16T18Z");
		verify(this.events, times(2)).save(any(NotificationEvent.class));
		verify(this.deliveries, times(2)).enqueueForEvent(any(NotificationEvent.class));
	}

	private static Delivery delivery(Long id) {
		return new Delivery(id, WS, 100L, 7L, "telegram", "News channel", null, "-100123", "secret draft text", "hash",
				"UNKNOWN", null, NOW, NOW);
	}

	private static Connection connection(Long id, String health) {
		return new Connection(id, WS, "telegram", "pasted", "@newsbot", "News bot", "cipher-value", "nonce-value",
				"key-1", health, NOW, true, NOW, NOW);
	}

}
