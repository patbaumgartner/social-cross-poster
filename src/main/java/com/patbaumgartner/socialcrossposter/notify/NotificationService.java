package com.patbaumgartner.socialcrossposter.notify;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.patbaumgartner.socialcrossposter.publish.Delivery;
import com.patbaumgartner.socialcrossposter.scheduling.PublicationSchedule;

/**
 * In-app notification events (UC-12, ADR-004, ADR-013, ADR-015). Events are durable,
 * deduplicated per workspace by {@code event_key} (step 2) and redacted — summaries carry
 * workspace/destination identity and a safe category only, never draft text, tokens, raw
 * provider bodies or signed URLs (alternative flow 4). Recording is idempotent: the same
 * event key returns the existing event. Every newly recorded event also enqueues one
 * deduplicated outbox row per enabled, category-interested webhook channel (second
 * slice); in-app events remain authoritative and are shown on the workspace dashboard
 * (step 5). All access is workspace-scoped (ADR-013).
 */
@Service
public class NotificationService {

	public static final String C_DELIVERY_FAILED = "DELIVERY_FAILED";

	public static final String C_DELIVERY_UNKNOWN = "DELIVERY_UNKNOWN";

	public static final String C_RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED";

	public static final String C_DELIVERY_NEEDS_ATTENTION = "DELIVERY_NEEDS_ATTENTION";

	public static final String C_SCHEDULE_EXPIRED = "SCHEDULE_EXPIRED";

	public static final String C_SCHEDULE_NEEDS_REVIEW = "SCHEDULE_NEEDS_REVIEW";

	private final NotificationEventRepository events;

	private final NotificationDeliveryService deliveries;

	public NotificationService(NotificationEventRepository events, NotificationDeliveryService deliveries) {
		this.events = events;
		this.deliveries = deliveries;
	}

	/**
	 * Records an event, deduplicated by {@code (workspaceId, eventKey)}: if the event
	 * already exists the existing row is returned and nothing is written (UC-12 step 2).
	 * A newly recorded event also enqueues deduplicated webhook outbox rows (second
	 * slice); enqueueing is idempotent, and delivery failures never create events, so
	 * this cannot recurse (UC-12 alternative flow 3).
	 */
	@Transactional
	public NotificationEvent record(Long workspaceId, String eventKey, String category, Long publicationId,
			Long deliveryId, Long scheduleId, String summary) {
		java.util.Optional<NotificationEvent> existing = this.events.findByWorkspaceIdAndEventKey(workspaceId,
				eventKey);
		if (existing.isPresent()) {
			return existing.get();
		}
		NotificationEvent saved = this.events.save(new NotificationEvent(null, workspaceId, eventKey, category,
				publicationId, deliveryId, scheduleId, summary, OffsetDateTime.now()));
		this.deliveries.enqueueForEvent(saved);
		return saved;
	}

	/** The workspace's newest events first, capped for the dashboard (UC-12 step 5). */
	public List<NotificationEvent> eventsOf(Long workspaceId) {
		return this.events.findTop50ByWorkspaceIdOrderByIdDesc(workspaceId);
	}

	/**
	 * A delivery failed but retries remain (UC-12 trigger); deduplicated per delivery.
	 */
	public NotificationEvent notifyDeliveryFailed(Long workspaceId, Delivery delivery) {
		return record(workspaceId, "delivery:" + delivery.id() + ":failed", C_DELIVERY_FAILED, delivery.publicationId(),
				delivery.id(), null,
				"Delivery #" + delivery.id() + " to " + delivery.platform() + " · " + delivery.destinationLabel()
						+ " failed; a bounded retry is scheduled (see the redacted attempts in history).");
	}

	/** A delivery stayed UNKNOWN after reconciliation and needs owner attention. */
	public NotificationEvent notifyDeliveryUnknown(Long workspaceId, Delivery delivery) {
		return record(workspaceId, "delivery:" + delivery.id() + ":unknown", C_DELIVERY_UNKNOWN,
				delivery.publicationId(), delivery.id(), null,
				"Delivery #" + delivery.id() + " to " + delivery.platform() + " · " + delivery.destinationLabel()
						+ " is UNKNOWN after reconciliation; owner reconcile in history.");
	}

	/** The retry budget is exhausted (UC-12 trigger: exhausted retries). */
	public NotificationEvent notifyRetriesExhausted(Long workspaceId, Delivery delivery, long attempts) {
		return record(workspaceId, "delivery:" + delivery.id() + ":exhausted", C_RETRIES_EXHAUSTED,
				delivery.publicationId(), delivery.id(), null,
				"Delivery #" + delivery.id() + " to " + delivery.platform() + " · " + delivery.destinationLabel()
						+ " failed permanently after " + attempts
						+ " attempts; owner retry remains possible in history.");
	}

	/**
	 * A delivery needs attention/review (e.g. Telegram reconciliation is inconclusive).
	 */
	public NotificationEvent notifyDeliveryNeedsAttention(Long workspaceId, Delivery delivery) {
		return record(workspaceId, "delivery:" + delivery.id() + ":needs-attention", C_DELIVERY_NEEDS_ATTENTION,
				delivery.publicationId(), delivery.id(), null,
				"Delivery #" + delivery.id() + " to " + delivery.platform() + " · " + delivery.destinationLabel()
						+ " needs attention (" + delivery.state() + "); see history.");
	}

	/**
	 * A schedule expired beyond the scheduled instant + 15 min (UC-12 trigger: overdue
	 * schedule, ADR-016).
	 */
	public NotificationEvent notifyScheduleExpired(Long workspaceId, PublicationSchedule schedule) {
		return record(workspaceId, "schedule:" + schedule.id() + ":expired", C_SCHEDULE_EXPIRED, null, null,
				schedule.id(), "Schedule #" + schedule.id() + " for post #" + schedule.postId()
						+ " expired (beyond the scheduled instant + 15 min); renewed approval required (ADR-016).");
	}

	/**
	 * A schedule's approved payload changed since approval (UC-12 trigger: capability
	 * review, UC-10 step 5).
	 */
	public NotificationEvent notifyScheduleNeedsReview(Long workspaceId, PublicationSchedule schedule) {
		return record(workspaceId, "schedule:" + schedule.id() + ":needs-review", C_SCHEDULE_NEEDS_REVIEW, null, null,
				schedule.id(), "Schedule #" + schedule.id() + " for post #" + schedule.postId()
						+ " needs review — the approved payload changed since approval; no dispatch happened.");
	}

}
