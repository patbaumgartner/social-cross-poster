package com.patbaumgartner.socialcrossposter.notify;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.patbaumgartner.socialcrossposter.content.PostRevision;
import com.patbaumgartner.socialcrossposter.content.PostRevisionRepository;
import com.patbaumgartner.socialcrossposter.publish.Publication;
import com.patbaumgartner.socialcrossposter.publish.PublicationRepository;
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
 * Tests for {@link NotificationDeliveryService} (UC-12 second + third slices, steps 2 and
 * 4, ADR-013, ADR-015): enqueue deduplicates per (event, channel) and honors the category
 * filter; runDue settles rows exactly once — SENT, retryable with backoff, permanent,
 * blocked, and the three-attempt budget — dispatching webhook channels to the webhook
 * sender and SMTP channels to the SMTP sender, and the payload sent is the redacted
 * event. Mocked repositories/senders, no Spring context.
 */
class NotificationDeliveryServiceTest {

	private static final Long WS = 1L;

	private static final OffsetDateTime NOW = OffsetDateTime.now();

	private NotificationDeliveryRepository deliveries;

	private NotificationChannelRepository channels;

	private NotificationEventRepository events;

	private WorkspaceRepository workspaces;

	private PublicationRepository publications;

	private PostRevisionRepository revisions;

	private NotificationChannelService channelService;

	private WebhookSender sender;

	private SmtpSender smtpSender;

	private NotificationDeliveryService service;

	@BeforeEach
	void setUp() {
		this.deliveries = mock(NotificationDeliveryRepository.class);
		this.channels = mock(NotificationChannelRepository.class);
		this.events = mock(NotificationEventRepository.class);
		this.workspaces = mock(WorkspaceRepository.class);
		this.publications = mock(PublicationRepository.class);
		this.revisions = mock(PostRevisionRepository.class);
		this.channelService = mock(NotificationChannelService.class);
		this.sender = mock(WebhookSender.class);
		this.smtpSender = mock(SmtpSender.class);
		NotifyProperties props = new NotifyProperties(null, null, null, 3, 60L, 3600L, null, null, List.of());
		this.service = new NotificationDeliveryService(this.deliveries, this.channels, this.events, this.workspaces,
				this.publications, this.revisions, this.channelService, this.sender, this.smtpSender, props);
	}

	private NotificationEvent event(String category) {
		return new NotificationEvent(42L, WS, "delivery:5:failed", category, 100L, 5L, null, null, "redacted summary",
				NOW);
	}

	private NotificationChannel channel(Long id, String categories) {
		return new NotificationChannel(id, WS, "ch" + id, "https://1.1.1.1/hook", "cipher", "nonce", "test-v1",
				categories, true, NOW, NotificationChannel.TYPE_WEBHOOK, null, null, null, null, null, null);
	}

	private NotificationChannel smtpChannel(Long id) {
		return new NotificationChannel(id, WS, "smtp" + id, null, "cipher", "nonce", "test-v1", "", true, NOW,
				NotificationChannel.TYPE_SMTP, "127.0.0.1", 2525, "NONE", "alerts@example.com", "ops@example.com",
				"relay-user");
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
		given(this.sender.send(anyString(), anyString(), any(NotificationPayload.class))).willReturn(SendResult.sent());

		this.service.runDue(WS, NOW);

		ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
		verify(this.deliveries).save(captor.capture());
		NotificationDelivery saved = captor.getValue();
		assertThat(saved.state()).isEqualTo(NotificationDeliveryService.S_SENT);
		assertThat(saved.attempts()).isEqualTo(1);
		assertThat(saved.nextAttemptAt()).isNull();
		assertThat(saved.lastDetail()).contains("2xx");
		// the payload carries only redacted fields (UC-12 step 3)
		ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
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
		given(this.sender.send(anyString(), anyString(), any(NotificationPayload.class)))
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
		given(this.sender.send(anyString(), anyString(), any(NotificationPayload.class)))
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
		given(this.sender.send(anyString(), anyString(), any(NotificationPayload.class)))
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
	void runDueDispatchesSmtpChannelsToTheSmtpSenderOnly() {
		NotificationDelivery row = row(7L, 2L, 0);
		given(this.deliveries.findDue(WS, NOW)).willReturn(List.of(row));
		given(this.events.findById(42L)).willReturn(Optional.of(event(NotificationService.C_DELIVERY_FAILED)));
		given(this.channels.findByIdAndWorkspaceId(2L, WS)).willReturn(Optional.of(smtpChannel(2L)));
		given(this.workspaces.findById(WS))
			.willReturn(Optional.of(new Workspace(WS, "private", "Private", "#fff", "UTC", NOW)));
		given(this.channelService.openSecret(anyString(), any())).willReturn("smtp-password");
		given(this.smtpSender.send(any(NotificationChannel.class), anyString(), any(NotificationPayload.class)))
			.willReturn(SendResult.sent());

		this.service.runDue(WS, NOW);

		ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
		verify(this.smtpSender).send(any(NotificationChannel.class), eq("smtp-password"), payload.capture());
		assertThat(payload.getValue().eventId()).isEqualTo(42L);
		assertThat(payload.getValue().category()).isEqualTo("DELIVERY_FAILED");
		// the webhook sender is never touched for an SMTP channel
		verify(this.sender, never()).send(anyString(), anyString(), any(NotificationPayload.class));
		ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
		verify(this.deliveries).save(captor.capture());
		assertThat(captor.getValue().state()).isEqualTo(NotificationDeliveryService.S_SENT);
		assertThat(captor.getValue().attempts()).isEqualTo(1);
	}

	@Test
	void backoffGrowsExponentiallyAndIsCappedAfterJitter() {
		long first = this.service.backoffSeconds(1); // 60 * 2^0 * [0.8,1.2)
		assertThat(first).isBetween(48L, 72L);
		long second = this.service.backoffSeconds(2); // 60 * 2^1 * [0.8,1.2)
		assertThat(second).isBetween(96L, 144L);
		assertThat(this.service.backoffSeconds(100)).isEqualTo(3600L); // capped
	}

	@Test
	void runDueWithSnippetOptInAppendsBoundedPostTextSnippet() {
		NotificationDelivery row = row(7L, 1L, 0);
		given(this.deliveries.findDue(WS, NOW)).willReturn(List.of(row));
		given(this.events.findById(42L)).willReturn(Optional.of(event(NotificationService.C_DELIVERY_FAILED)));
		given(this.channels.findByIdAndWorkspaceId(1L, WS)).willReturn(Optional.of(channel(1L, "")));
		given(this.workspaces.findById(WS)).willReturn(
				Optional.of(new Workspace(WS, "private", "Private", "#fff", "UTC", NOW, null, null, null, null, true)));
		given(this.channelService.openSecret(anyString(), any())).willReturn("secret");
		given(this.publications.findByIdAndWorkspaceId(100L, WS))
			.willReturn(Optional.of(new Publication(100L, WS, 7L, 55L, "sub-key", "APPROVED", "hash", NOW, NOW)));
		given(this.revisions.findByIdAndWorkspaceIdAndPostId(55L, WS, 7L))
			.willReturn(Optional.of(new PostRevision(55L, WS, 7L, 2, "  Hello   Voxxed\nDays Zurich 2026!  ", NOW)));
		given(this.sender.send(anyString(), anyString(), any(NotificationPayload.class))).willReturn(SendResult.sent());

		this.service.runDue(WS, NOW);

		ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
		verify(this.sender).send(anyString(), eq("secret"), payload.capture());
		// UC-12 alternative flow 4 opt-in (UC-15 fifth slice): bounded, whitespace-
		// collapsed snippet appended; the redacted summary stays as the prefix
		assertThat(payload.getValue().summary())
			.isEqualTo("redacted summary | text snippet: \"Hello Voxxed Days Zurich 2026!\"");
	}

	@Test
	void runDueWithSnippetOptInButMissingPublicationKeepsRedactedSummary() {
		NotificationDelivery row = row(7L, 1L, 0);
		given(this.deliveries.findDue(WS, NOW)).willReturn(List.of(row));
		given(this.events.findById(42L)).willReturn(Optional.of(event(NotificationService.C_DELIVERY_FAILED)));
		given(this.channels.findByIdAndWorkspaceId(1L, WS)).willReturn(Optional.of(channel(1L, "")));
		given(this.workspaces.findById(WS)).willReturn(
				Optional.of(new Workspace(WS, "private", "Private", "#fff", "UTC", NOW, null, null, null, null, true)));
		given(this.channelService.openSecret(anyString(), any())).willReturn("secret");
		given(this.publications.findByIdAndWorkspaceId(100L, WS)).willReturn(Optional.empty());
		given(this.sender.send(anyString(), anyString(), any(NotificationPayload.class))).willReturn(SendResult.sent());

		this.service.runDue(WS, NOW);

		ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
		verify(this.sender).send(anyString(), eq("secret"), payload.capture());
		// a missing publication never fails the delivery — the redacted summary stays
		assertThat(payload.getValue().summary()).isEqualTo("redacted summary");
		verify(this.revisions, never()).findByIdAndWorkspaceIdAndPostId(anyLong(), anyLong(), anyLong());
	}

	@Test
	void snippetCollapsesWhitespaceAndCapsAt200Chars() {
		assertThat(NotificationDeliveryService.snippet("  a \n\t b  c ")).isEqualTo("a b c");
		assertThat(NotificationDeliveryService.snippet(null)).isNull();
		assertThat(NotificationDeliveryService.snippet("   ")).isNull();
		String longText = "x".repeat(500);
		String snippet = NotificationDeliveryService.snippet(longText);
		assertThat(snippet).hasSize(201); // 200 chars + ellipsis
		assertThat(snippet).endsWith("…");
	}

}
