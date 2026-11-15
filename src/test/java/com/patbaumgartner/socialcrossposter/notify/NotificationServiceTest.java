package com.patbaumgartner.socialcrossposter.notify;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.patbaumgartner.socialcrossposter.publish.Delivery;
import com.patbaumgartner.socialcrossposter.scheduling.PublicationSchedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link NotificationService} (UC-12 first slice, ADR-013, ADR-015):
 * {@code record} deduplicates on an existing {@code (workspace, event_key)} and returns
 * the existing row without saving, saves new events, {@code eventsOf} delegates to the
 * workspace-scoped finder, and the emit helpers build the expected redacted keys,
 * categories and summaries — summaries must never contain the payload text. Mocked
 * repository, no Spring context.
 */
class NotificationServiceTest {

	private static final Long WS = 1L;

	private static final OffsetDateTime NOW = OffsetDateTime.now();

	private NotificationEventRepository events;

	private NotificationService service;

	@BeforeEach
	void setUp() {
		this.events = mock(NotificationEventRepository.class);
		this.service = new NotificationService(this.events);
		given(this.events.save(any(NotificationEvent.class))).willAnswer((inv) -> {
			NotificationEvent e = inv.getArgument(0);
			return new NotificationEvent(42L, e.workspaceId(), e.eventKey(), e.category(), e.publicationId(),
					e.deliveryId(), e.scheduleId(), e.summary(), e.createdAt());
		});
		given(this.events.findByWorkspaceIdAndEventKey(any(), anyString())).willReturn(Optional.empty());
	}

	@Test
	void recordSavesNewEvent() {
		NotificationEvent saved = this.service.record(WS, "delivery:5:failed", NotificationService.C_DELIVERY_FAILED,
				100L, 5L, null, "summary");

		assertThat(saved.id()).isEqualTo(42L);
		assertThat(saved.workspaceId()).isEqualTo(WS);
		assertThat(saved.eventKey()).isEqualTo("delivery:5:failed");
		assertThat(saved.category()).isEqualTo(NotificationService.C_DELIVERY_FAILED);
		assertThat(saved.publicationId()).isEqualTo(100L);
		assertThat(saved.deliveryId()).isEqualTo(5L);
		verify(this.events).save(any(NotificationEvent.class));
	}

	@Test
	void recordDeduplicatesOnExistingKeyAndSavesNothing() {
		NotificationEvent existing = new NotificationEvent(7L, WS, "delivery:5:failed",
				NotificationService.C_DELIVERY_FAILED, 100L, 5L, null, "original summary", NOW);
		given(this.events.findByWorkspaceIdAndEventKey(WS, "delivery:5:failed")).willReturn(Optional.of(existing));

		NotificationEvent result = this.service.record(WS, "delivery:5:failed", NotificationService.C_DELIVERY_FAILED,
				100L, 5L, null, "summary");

		assertThat(result).isSameAs(existing);
		verify(this.events, never()).save(any());
	}

	@Test
	void eventsOfDelegatesToWorkspaceScopedFinder() {
		NotificationEvent e = new NotificationEvent(9L, WS, "schedule:3:expired",
				NotificationService.C_SCHEDULE_EXPIRED, null, null, 3L, "expired", NOW);
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

}
