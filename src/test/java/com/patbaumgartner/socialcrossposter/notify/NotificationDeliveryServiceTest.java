package com.patbaumgartner.socialcrossposter.notify;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.patbaumgartner.socialcrossposter.notify.WebhookSender.SendResult;
import com.patbaumgartner.socialcrossposter.notify.WebhookSender.WebhookPayload;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link NotificationDeliveryService} (UC-12 second slice steps 2 and 4,
 * ADR-013, ADR-015): enqueue deduplicates per (event, channel) and honors the category
 * filter; runDue settles rows exactly once — SENT, retryable with backoff, permanent,
 * blocked, and the three-attempt budget — and the payload sent is the redacted event.
 * Mocked repositories/sender, no Spring context.
 */
class NotificationDeliveryServiceTest {

	private static final Long WS = 1L;

	private static final OffsetDateTime NOW = OffsetDateTime.now();

	private NotificationDeliveryRepository deliveries;

	private NotificationChannelRepository channels;

	private NotificationEventRepository events;

	private WorkspaceRepository workspaces;

	private NotificationChannelService channelService;

	private WebhookSender sender;

	private NotificationDeliveryService service;

	@BeforeEach
	void setUp() {
		this.deliveries = mock(NotificationDeliveryRepository.class);
		this.channels = mock(NotificationChannelRepository.class);
		this.events = mock(NotificationEventRepository.class);
		this.workspaces = mock(WorkspaceRepository.class);
		this.channelService = mock(NotificationChannelService.class);
		this.sender = mock(WebhookSender.class);
		NotifyProperties props = new NotifyProperties(null, null, null, 3, 60L, 3600L, null, null, List.of());
		this.service = new NotificationDeliveryService(this.deliveries, this.channels, this.events, this.workspaces,
				this.channelService, this.sender, props);
	}

	private NotificationEvent event(String category) {
		return new NotificationEvent(42L, WS, "delivery:5:failed", category, 100L, 5L, null, "redacted summary", NOW);
	}

	private NotificationChannel channel(Long id, String categories) {
		return new NotificationChannel(id, WS, "ch" + id, "https://1.1.1.1/hook", "cipher", "nonce", "test-v1",
				categories, true, NOW);
	}

	private NotificationDelivery row(Long id, Long channelId, int attempts) {
		return new NotificationDelivery(id, WS, 42L, channelId, NotificationDeliveryService.S_PENDING, attempts, null,
				NOW, NOW, NOW);
	}

	@Test
	void enqueueSkipsChannelsWhoseCategoryFilterDoesNotMatch() {
		NotificationChannel all = channel(1L, "");
		NotificationChannel failedOnly = channel(2L, "DELIVERY_FAILED");
		given(this.channels.findAllByWorkspaceIdAndEnabledTrueOrderByIdAsc(WS)).willReturn(List.of(all, failedOnly));
		given(this.deliveries.findByEventIdAndChannelId(anyLong(), anyLong())).willReturn(Optional.empty());

		int inserted = this.service.enqueueForEvent(event(NotificationService.C_SCHEDULE_EXPIRED));

		assertThat(inserted).isEqualTo(1); // only the empty-filter channel matched
		ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
		verify(this.deliveries).save(captor.capture());
		assertThat(captor.getValue().channelId()).isEqualTo(1L);
		assertThat(captor.getValue().state()).isEqualTo(NotificationDeliveryService.S_PENDING);
		assertThat(captor.getValue().attempts()).isZero();
		verify(this.deliveries, never()).findByEventIdAndChannelId(42L, 2L); // filtered
																				// out
																				// first
	}

	@Test
	void enqueueIsIdempotentPerEventAndChannel() {
		given(this.channels.findAllByWorkspaceIdAndEnabledTrueOrderByIdAsc(WS)).willReturn(List.of(channel(1L, "")));
		given(this.deliveries.findByEventIdAndChannelId(42L, 1L)).willReturn(Optional.of(row(9L, 1L, 0)));

		assertThat(this.service.enqueueForEvent(event(NotificationService.C_DELIVERY_FAILED))).isZero();
		verify(this.deliveries, never()).save(any());
	}

	@Test
	void runDueSentMarksRowSentWithAttemptCount() {
		NotificationDelivery row = row(7L, 1L, 0);
		given(this.deliveries.findDue(WS, NOW)).willReturn(List.of(row));
		given(this.events.findById(42L)).willReturn(Optional.of(event(NotificationService.C_DELIVERY_FAILED)));
		given(this.channels.findByIdAndWorkspaceId(1L, WS)).willReturn(Optional.of(channel(1L, "")));
		given(this.workspaces.findById(WS))
			.willReturn(Optional.of(new Workspace(WS, "private", "Private", "#fff", "Europe/Zurich", NOW)));
		given(this.channelService.openSecret(anyString(), any())).willReturn("secret");
		given(this.sender.send(anyString(), anyString(), any(WebhookPayload.class))).willReturn(SendResult.sent());

		this.service.runDue(WS, NOW);

		ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
		verify(this.deliveries).save(captor.capture());
		NotificationDelivery saved = captor.getValue();
		assertThat(saved.state()).isEqualTo(NotificationDeliveryService.S_SENT);
		assertThat(saved.attempts()).isEqualTo(1);
		assertThat(saved.nextAttemptAt()).isNull();
		assertThat(saved.lastDetail()).contains("2xx");
		// the payload carries only redacted fields (UC-12 step 3)
		ArgumentCaptor<WebhookPayload> payload = ArgumentCaptor.forClass(WebhookPayload.class);
		verify(this.sender).send(anyString(), eq("secret"), payload.capture());
		assertThat(payload.getValue().eventId()).isEqualTo(42L);
		assertThat(payload.getValue().workspaceLabel()).isEqualTo("Private");
		assertThat(payload.getValue().category()).isEqualTo("DELIVERY_FAILED");
		assertThat(payload.getValue().summary()).isEqualTo("redacted summary");
	}

	@Test
	void runDueRetryableSchedulesBackoffWithinJitterBounds() {
		NotificationDelivery row = row(7L, 1L, 0);
		given(this.deliveries.findDue(WS, NOW)).willReturn(List.of(row));
		given(this.events.findById(42L)).willReturn(Optional.of(event(NotificationService.C_DELIVERY_FAILED)));
		given(this.channels.findByIdAndWorkspaceId(1L, WS)).willReturn(Optional.of(channel(1L, "")));
		given(this.workspaces.findById(WS))
			.willReturn(Optional.of(new Workspace(WS, "private", "Private", "#fff", "UTC", NOW)));
		given(this.channelService.openSecret(anyString(), any())).willReturn("secret");
		given(this.sender.send(anyString(), anyString(), any(WebhookPayload.class)))
			.willReturn(SendResult.retryable("HTTP 500"));

		this.service.runDue(WS, NOW);

		ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
		verify(this.deliveries).save(captor.capture());
		NotificationDelivery saved = captor.getValue();
		assertThat(saved.state()).isEqualTo(NotificationDeliveryService.S_PENDING);
		assertThat(saved.attempts()).isEqualTo(1);
		assertThat(saved.lastDetail()).isEqualTo("HTTP 500");
		// base 60s * 2^0 * jitter [0.8, 1.2) -> next attempt in [48s, 72s)
		assertThat(saved.nextAttemptAt()).isAfter(NOW.plusSeconds(47)).isBefore(NOW.plusSeconds(73));
	}

	@Test
	void runDueExhaustsTheThreeAttemptBudget() {
		NotificationDelivery row = row(7L, 1L, 2); // third and final attempt
		given(this.deliveries.findDue(WS, NOW)).willReturn(List.of(row));
		given(this.events.findById(42L)).willReturn(Optional.of(event(NotificationService.C_DELIVERY_FAILED)));
		given(this.channels.findByIdAndWorkspaceId(1L, WS)).willReturn(Optional.of(channel(1L, "")));
		given(this.workspaces.findById(WS))
			.willReturn(Optional.of(new Workspace(WS, "private", "Private", "#fff", "UTC", NOW)));
		given(this.channelService.openSecret(anyString(), any())).willReturn("secret");
		given(this.sender.send(anyString(), anyString(), any(WebhookPayload.class)))
			.willReturn(SendResult.retryable("HTTP 500"));

		this.service.runDue(WS, NOW);

		ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
		verify(this.deliveries).save(captor.capture());
		NotificationDelivery saved = captor.getValue();
		assertThat(saved.state()).isEqualTo(NotificationDeliveryService.S_FAILED); // UC-12
																					// step
																					// 4:
																					// max
																					// 3
																					// attempts
		assertThat(saved.attempts()).isEqualTo(3);
		assertThat(saved.nextAttemptAt()).isNull();
		assertThat(saved.lastDetail()).contains("budget exhausted");
	}

	@Test
	void runDuePermanentAndBlockedFailuresAreTerminalImmediately() {
		NotificationDelivery perm = row(7L, 1L, 0);
		NotificationDelivery blocked = row(8L, 1L, 0);
		given(this.deliveries.findDue(WS, NOW)).willReturn(List.of(perm, blocked));
		given(this.events.findById(42L)).willReturn(Optional.of(event(NotificationService.C_DELIVERY_FAILED)));
		given(this.channels.findByIdAndWorkspaceId(1L, WS)).willReturn(Optional.of(channel(1L, "")));
		given(this.workspaces.findById(WS))
			.willReturn(Optional.of(new Workspace(WS, "private", "Private", "#fff", "UTC", NOW)));
		given(this.channelService.openSecret(anyString(), any())).willReturn("secret");
		given(this.sender.send(anyString(), anyString(), any(WebhookPayload.class)))
			.willReturn(SendResult.permanent("HTTP 400"), SendResult.blocked("host denied (SSRF)"));

		this.service.runDue(WS, NOW);

		ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
		verify(this.deliveries, org.mockito.Mockito.times(2)).save(captor.capture());
		List<NotificationDelivery> saved = captor.getAllValues();
		assertThat(saved).allSatisfy((d) -> {
			assertThat(d.state()).isEqualTo(NotificationDeliveryService.S_FAILED);
			assertThat(d.attempts()).isEqualTo(1);
			assertThat(d.nextAttemptAt()).isNull();
		});
		assertThat(saved.get(0).lastDetail()).isEqualTo("HTTP 400");
		assertThat(saved.get(1).lastDetail()).contains("SSRF");
	}

	@Test
	void backoffGrowsExponentiallyAndIsCappedAfterJitter() {
		long first = this.service.backoffSeconds(1); // 60 * 2^0 * [0.8,1.2)
		assertThat(first).isBetween(48L, 72L);
		long second = this.service.backoffSeconds(2); // 60 * 2^1 * [0.8,1.2)
		assertThat(second).isBetween(96L, 144L);
		assertThat(this.service.backoffSeconds(100)).isEqualTo(3600L); // capped
	}

}
