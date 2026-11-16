package com.patbaumgartner.socialcrossposter.notify;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.patbaumgartner.socialcrossposter.notify.WebhookSender.SendResult;
import com.patbaumgartner.socialcrossposter.notify.WebhookSender.WebhookPayload;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceRepository;

/**
 * Notification outbox (UC-12 second slice steps 2 and 4, ADR-013, ADR-015). New events
 * enqueue one deduplicated delivery row per enabled, category-interested channel (the
 * {@code (event_id, channel_id)} unique constraint is the backstop). {@link #runDue}
 * sends due rows with bounded backoff — {@code base * 2^(n-1)} x jitter [0.8, 1.2),
 * capped after jitter — and at most {@code maxAttempts} (3) total attempts. SENT/terminal
 * rows keep a redacted detail only; failures never create new events, so delivery cannot
 * recurse (UC-12 alternative flow 3).
 */
@Service
public class NotificationDeliveryService {

	public static final String S_PENDING = "PENDING";

	public static final String S_SENT = "SENT";

	public static final String S_FAILED = "FAILED";

	private final NotificationDeliveryRepository deliveries;

	private final NotificationChannelRepository channels;

	private final NotificationEventRepository events;

	private final WorkspaceRepository workspaces;

	private final NotificationChannelService channelService;

	private final WebhookSender sender;

	private final NotifyProperties props;

	private final SecureRandom random = new SecureRandom();

	public NotificationDeliveryService(NotificationDeliveryRepository deliveries,
			NotificationChannelRepository channels, NotificationEventRepository events, WorkspaceRepository workspaces,
			NotificationChannelService channelService, WebhookSender sender, NotifyProperties props) {
		this.deliveries = deliveries;
		this.channels = channels;
		this.events = events;
		this.workspaces = workspaces;
		this.channelService = channelService;
		this.sender = sender;
		this.props = props;
	}

	/**
	 * Enqueues one outbox row per enabled channel interested in the event's category.
	 * Returns how many rows were actually inserted (0 when all were already enqueued or
	 * no channel matches). Idempotent: existing rows are skipped, and a concurrent
	 * duplicate insert is swallowed via the {@code (event_id, channel_id)} unique
	 * constraint.
	 */
	@Transactional
	public int enqueueForEvent(NotificationEvent event) {
		int inserted = 0;
		OffsetDateTime now = OffsetDateTime.now();
		List<NotificationChannel> enabled = this.channels
			.findAllByWorkspaceIdAndEnabledTrueOrderByIdAsc(event.workspaceId());
		for (NotificationChannel channel : enabled) {
			if (!channel.interestedIn(event.category())) {
				continue;
			}
			if (this.deliveries.findByEventIdAndChannelId(event.id(), channel.id()).isPresent()) {
				continue;
			}
			try {
				this.deliveries.save(new NotificationDelivery(null, event.workspaceId(), event.id(), channel.id(),
						S_PENDING, 0, null, now, now, now));
				inserted++;
			}
			catch (DataIntegrityViolationException ex) {
				// unique (event_id, channel_id) conflict — already enqueued concurrently
			}
		}
		return inserted;
	}

	/** Sends all due outbox rows of one workspace; each row is settled exactly once. */
	@Transactional
	public void runDue(Long workspaceId, OffsetDateTime now) {
		for (NotificationDelivery row : this.deliveries.findDue(workspaceId, now)) {
			settle(workspaceId, row, now);
		}
	}

	/** Newest outbox rows of one channel for the dashboard (redacted details only). */
	public List<NotificationDelivery> recentForChannel(Long channelId) {
		return this.deliveries.findAllByChannelIdOrderByIdDesc(channelId);
	}

	private void settle(Long workspaceId, NotificationDelivery row, OffsetDateTime now) {
		NotificationEvent event = this.events.findById(row.eventId())
			.orElseThrow(() -> new NotificationException("Notification event vanished (FK violation)"));
		NotificationChannel channel = this.channels.findByIdAndWorkspaceId(row.channelId(), workspaceId)
			.orElseThrow(() -> new NotificationException("Unknown notification channel."));
		Workspace workspace = this.workspaces.findById(workspaceId)
			.orElseThrow(() -> new NotificationException("Unknown workspace."));
		String secret = this.channelService.openSecret(workspace.slug(), channel);
		WebhookPayload payload = new WebhookPayload(event.id(), event.eventKey(), workspace.id(), workspace.label(),
				event.category(), event.summary(), event.publicationId(), event.deliveryId(), event.scheduleId(),
				event.createdAt());
		SendResult result = this.sender.send(channel.url(), secret, payload);
		int attempts = row.attempts() + 1;
		String state;
		String detail = result.detail();
		OffsetDateTime nextAttemptAt;
		switch (result.outcome()) {
			case "SENT" -> {
				state = S_SENT;
				nextAttemptAt = null;
			}
			case "BLOCKED", "FAILED_PERMANENT" -> {
				state = S_FAILED;
				nextAttemptAt = null;
			}
			default -> {
				if (attempts >= this.props.maxAttempts()) {
					state = S_FAILED; // UC-12 step 4: at most three total attempts
					detail = detail + " (attempt budget exhausted)";
					nextAttemptAt = null;
				}
				else {
					state = S_PENDING;
					nextAttemptAt = now.plusSeconds(backoffSeconds(attempts));
				}
			}
		}
		this.deliveries.save(new NotificationDelivery(row.id(), workspaceId, row.eventId(), row.channelId(), state,
				attempts, detail, nextAttemptAt, row.createdAt(), now));
	}

	/**
	 * Bounded exponential backoff (UC-12 step 4): {@code base * 2^(n-1)} scaled by a
	 * 0.8-1.2 jitter, capped at {@code cap} after jitter, floored at 1 second, where
	 * {@code n} is the attempt count so far.
	 */
	public long backoffSeconds(long attemptCount) {
		long n = Math.max(1L, attemptCount);
		long exponential = this.props.backoffBaseSeconds() * (1L << Math.min(n - 1, 20));
		double jitter = 0.8 + this.random.nextDouble() * 0.4;
		long jittered = (long) (exponential * jitter);
		return Math.min(this.props.backoffCapSeconds(), Math.max(1L, jittered));
	}

}
