package com.patbaumgartner.socialcrossposter.notify;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.patbaumgartner.socialcrossposter.content.PostRevision;
import com.patbaumgartner.socialcrossposter.content.PostRevisionRepository;
import com.patbaumgartner.socialcrossposter.publish.PublicationRepository;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceRepository;

/**
 * Notification outbox (UC-12 second + third slices, steps 2 and 4, ADR-013, ADR-015). New
 * events enqueue one deduplicated delivery row per enabled, category-interested channel
 * (webhook or SMTP — the {@code (event_id, channel_id)} unique constraint is the
 * backstop). {@link #runDue} sends due rows with bounded backoff — {@code base * 2^(n-1)}
 * x jitter [0.8, 1.2), capped after jitter — and at most {@code maxAttempts} (3) total
 * attempts. The send dispatches on the channel type: webhook channels get a signed POST,
 * SMTP channels a mail. SENT/terminal rows keep a redacted detail only; failures never
 * create new events, so delivery cannot recurse (UC-12 alternative flow 3). External
 * payloads stay redacted unless the workspace opted in to text snippets
 * ({@code workspace.notify_snippets}, UC-15 fifth slice / UC-12 alternative flow 4); then
 * a bounded snippet of the linked publication's approved revision is appended.
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

	private final PublicationRepository publications;

	private final PostRevisionRepository revisions;

	private final NotificationChannelService channelService;

	private final WebhookSender webhookSender;

	private final SmtpSender smtpSender;

	private final NotifyProperties props;

	private final SecureRandom random = new SecureRandom();

	/** Max characters of the opt-in text snippet appended to external payloads. */
	private static final int SNIPPET_MAX = 200;

	public NotificationDeliveryService(NotificationDeliveryRepository deliveries,
			NotificationChannelRepository channels, NotificationEventRepository events, WorkspaceRepository workspaces,
			PublicationRepository publications, PostRevisionRepository revisions,
			NotificationChannelService channelService, WebhookSender webhookSender, SmtpSender smtpSender,
			NotifyProperties props) {
		this.deliveries = deliveries;
		this.channels = channels;
		this.events = events;
		this.workspaces = workspaces;
		this.publications = publications;
		this.revisions = revisions;
		this.channelService = channelService;
		this.webhookSender = webhookSender;
		this.smtpSender = smtpSender;
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
		// type-aware sealed credential: HMAC secret for webhooks, SMTP password for SMTP
		String credential = this.channelService.openSecret(workspace.slug(), channel);
		NotificationPayload payload = maybeWithSnippet(workspace, event,
				new NotificationPayload(event.id(), event.eventKey(), workspace.id(), workspace.label(),
						event.category(), event.summary(), event.publicationId(), event.deliveryId(),
						event.scheduleId(), event.connectionId(), event.createdAt()));
		SendResult result = channel.isSmtp() ? this.smtpSender.send(channel, credential, payload)
				: this.webhookSender.send(channel.url(), credential, payload);
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
	 * UC-12 alternative flow 4 / UC-15 fifth slice: external payloads stay redacted
	 * unless the workspace explicitly opted in via {@code notify_snippets}; then a
	 * bounded, whitespace-collapsed snippet of the linked publication's approved revision
	 * text is appended to the summary. A missing publication/revision never fails the
	 * delivery — the redacted summary stays.
	 */
	private NotificationPayload maybeWithSnippet(Workspace workspace, NotificationEvent event,
			NotificationPayload payload) {
		if (!workspace.notifySnippets() || event.publicationId() == null) {
			return payload;
		}
		String snippet = this.publications.findByIdAndWorkspaceId(event.publicationId(), workspace.id())
			.flatMap(pub -> this.revisions.findByIdAndWorkspaceIdAndPostId(pub.revisionId(), workspace.id(),
					pub.postId()))
			.map(PostRevision::defaultText)
			.map(NotificationDeliveryService::snippet)
			.orElse(null);
		if (snippet == null) {
			return payload;
		}
		return payload.withSummary(payload.summary() + " | text snippet: \"" + snippet + "\"");
	}

	/**
	 * Collapses all whitespace runs to single spaces and caps at 200 chars + ellipsis.
	 */
	static String snippet(String text) {
		if (text == null) {
			return null;
		}
		String collapsed = text.replaceAll("\\s+", " ").trim();
		if (collapsed.isEmpty()) {
			return null;
		}
		if (collapsed.length() > SNIPPET_MAX) {
			return collapsed.substring(0, SNIPPET_MAX) + "…";
		}
		return collapsed;
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
