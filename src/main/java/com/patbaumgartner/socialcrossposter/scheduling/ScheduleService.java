package com.patbaumgartner.socialcrossposter.scheduling;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.patbaumgartner.socialcrossposter.content.Post;
import com.patbaumgartner.socialcrossposter.content.PostRevision;
import com.patbaumgartner.socialcrossposter.content.PostService;
import com.patbaumgartner.socialcrossposter.publish.Delivery;
import com.patbaumgartner.socialcrossposter.publish.DeliveryJobService;
import com.patbaumgartner.socialcrossposter.publish.Publication;
import com.patbaumgartner.socialcrossposter.publish.PublicationService;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceRepository;

/**
 * One-time publication schedules (UC-10, ADR-004, ADR-009, ADR-013, ADR-016).
 *
 * <p>
 * Approval (steps 1-3) freezes the current revision, its semantic payload hash and the
 * owner's explicit time choice: a local date/time plus an IANA timezone, resolved to a
 * UTC instant. DST is resolved before approval with an explicit owner choice — a gap is
 * rejected (never shifted automatically) and an overlap requires picking one of the two
 * valid offsets (never the earlier one automatically, ADR-016). The chosen local time,
 * timezone, offset-implied instant and a workspace-scoped submission key are persisted as
 * one PENDING {@link PublicationSchedule}.
 *
 * <p>
 * Dispatch (step 4) happens in {@link #runDueSchedules}: when the UTC instant is due, at
 * most one publication is created for the schedule — the conditional claim (PENDING →
 * CREATED) lets exactly one worker win — and every delivery is executed through the
 * UC-05/UC-07 path without invoking AI. ADR-016: remote post-create is allowed only until
 * the scheduled instant + {@link #DISPATCH_CUTOFF} (15 minutes, inclusive); beyond it the
 * schedule becomes EXPIRED and renewed time approval is required — never an automatic
 * resend. If the semantic payload hash changed since approval (step 5), the schedule
 * becomes NEEDS_REVIEW instead of dispatching.
 *
 * <p>
 * All lookups are workspace-scoped and fail closed for foreign ids (ADR-013). Like
 * {@link DeliveryJobService#runDueJobs}, the worker loop commits each step separately —
 * no wrapping transaction across schedules — so one bad schedule cannot block the rest.
 */
@Service
public class ScheduleService {

	/** Schedule states: PENDING, CREATED, CANCELED, EXPIRED, NEEDS_REVIEW. */
	public static final String S_PENDING = "PENDING";

	public static final String S_CREATED = "CREATED";

	public static final String S_CANCELED = "CANCELED";

	public static final String S_EXPIRED = "EXPIRED";

	public static final String S_NEEDS_REVIEW = "NEEDS_REVIEW";

	/**
	 * ADR-016: an approved one-time schedule permits remote post creation until the
	 * scheduled UTC instant plus 15 minutes, inclusive.
	 */
	public static final Duration DISPATCH_CUTOFF = Duration.ofMinutes(15);

	private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

	private final PublicationScheduleRepository schedules;

	private final PostService posts;

	private final WorkspaceRepository workspaces;

	private final PublicationService publications;

	private final DeliveryJobService jobs;

	public ScheduleService(PublicationScheduleRepository schedules, PostService posts, WorkspaceRepository workspaces,
			PublicationService publications, DeliveryJobService jobs) {
		this.schedules = schedules;
		this.posts = posts;
		this.workspaces = workspaces;
		this.publications = publications;
		this.jobs = jobs;
	}

	/**
	 * Approves and persists a one-time schedule (UC-10 steps 1-3) for the post's current
	 * revision. The time must be a future local date/time in the given IANA timezone; DST
	 * gaps are rejected and overlaps need an explicit {@code offsetChoice} (e.g.
	 * {@code +02:00}) — never chosen automatically (ADR-016). Blocking previews and
	 * missing destinations fail before anything is persisted.
	 */
	@Transactional
	public PublicationSchedule schedule(Long workspaceId, Long postId, String localDateTime, String timezone,
			String offsetChoice) {
		Post post = this.posts.requirePost(workspaceId, postId);
		if (PostService.STATE_ARCHIVED.equals(post.state())) {
			throw new ScheduleException("Archived posts cannot be scheduled.");
		}
		PostRevision revision = this.posts.currentRevision(workspaceId, postId);
		if (this.posts.destinationsOf(revision.id()).isEmpty()) {
			throw new ScheduleException("Select at least one destination before scheduling.");
		}
		List<String> blockers = this.publications.blockersOf(workspaceId, revision.id());
		if (!blockers.isEmpty()) {
			throw new ScheduleException("Scheduling is blocked: " + String.join(" ", blockers));
		}
		LocalDateTime local = parseLocalDateTime(localDateTime);
		ZoneId zone = parseZone(timezone);
		List<ZoneOffset> validOffsets = zone.getRules().getValidOffsets(local);
		OffsetDateTime instant;
		if (validOffsets.isEmpty()) {
			throw new ScheduleException(local + " is not a valid local time in " + zone.getId()
					+ " (DST gap) — choose a valid time; the time is never shifted automatically (ADR-016).");
		}
		if (validOffsets.size() > 1) {
			if (offsetChoice == null || offsetChoice.isBlank()) {
				throw new ScheduleException(
						local + " occurs twice in " + zone.getId() + " (DST overlap) — choose one offset explicitly: "
								+ formatOffsets(validOffsets) + " (resubmit with the offset parameter).");
			}
			ZoneOffset chosen = parseOffset(offsetChoice);
			if (!validOffsets.contains(chosen)) {
				throw new ScheduleException("Offset " + chosen.getId() + " is not valid for " + local + " in "
						+ zone.getId() + " — choose " + formatOffsets(validOffsets) + ".");
			}
			instant = local.atOffset(chosen);
		}
		else {
			instant = local.atOffset(validOffsets.get(0));
		}
		// persist and display the resolved instant normalized to UTC
		instant = instant.withOffsetSameInstant(ZoneOffset.UTC);
		OffsetDateTime now = OffsetDateTime.now();
		if (!instant.isAfter(now)) {
			throw new ScheduleException("The scheduled time must be in the future (UTC " + instant + ").");
		}
		String payloadHash = this.publications.payloadHashFor(workspaceId, revision.id());
		return this.schedules.save(new PublicationSchedule(null, workspaceId, postId, revision.id(),
				"sched-" + UUID.randomUUID(), payloadHash, local, zone.getId(), instant, S_PENDING, null, now, now));
	}

	/** Loads a schedule fail-closed for another workspace (ADR-013). */
	public PublicationSchedule requireSchedule(Long workspaceId, Long scheduleId) {
		return this.schedules.findByIdAndWorkspaceId(scheduleId, workspaceId)
			.orElseThrow(() -> new ScheduleException("Unknown schedule for this workspace."));
	}

	/** Schedules of one post, fail-closed for foreign posts (ADR-013). */
	public List<PublicationSchedule> schedulesOfPost(Long workspaceId, Long postId) {
		this.posts.requirePost(workspaceId, postId);
		return this.schedules.findAllByWorkspaceIdAndPostIdOrderByScheduledAtAsc(workspaceId, postId);
	}

	/**
	 * Cancels a PENDING schedule (UC-10 alternative flow 1): the conditional transition
	 * guarantees an unclaimed schedule is canceled atomically; a schedule that already
	 * created its publication stays in history and is rejected.
	 */
	public PublicationSchedule cancel(Long workspaceId, Long scheduleId) {
		PublicationSchedule schedule = requireSchedule(workspaceId, scheduleId);
		int rows = this.schedules.transition(scheduleId, workspaceId, S_PENDING, S_CANCELED, OffsetDateTime.now());
		if (rows == 0) {
			throw new ScheduleException("Schedule #" + scheduleId + " is " + schedule.state()
					+ " — only PENDING schedules can be canceled; a created publication stays in history.");
		}
		return requireSchedule(workspaceId, scheduleId);
	}

	/**
	 * One worker tick (UC-10 step 4): processes every PENDING schedule whose UTC instant
	 * has been reached. A schedule that throws is logged and skipped so one bad schedule
	 * cannot block the queue.
	 */
	public void runDueSchedules(OffsetDateTime now) {
		for (PublicationSchedule schedule : this.schedules.findDuePending(now)) {
			try {
				processDue(schedule, now);
			}
			catch (RuntimeException ex) {
				log.warn("Schedule #{} could not be processed: {}", schedule.id(), ex.getMessage());
			}
		}
	}

	/**
	 * Dispatches one due schedule. ADR-016 cutoff first: beyond scheduled instant +
	 * {@link #DISPATCH_CUTOFF} the schedule becomes EXPIRED (renewed approval required,
	 * never an automatic resend). Then UC-10 step 5: if the recomputed semantic payload
	 * hash differs from the approved one, the schedule becomes NEEDS_REVIEW. Otherwise
	 * the frozen revision is published via the UC-05 path under the schedule's submission
	 * key (idempotent), the conditional claim links the publication, and every delivery
	 * is executed and settled through UC-07.
	 */
	public void processDue(PublicationSchedule schedule, OffsetDateTime now) {
		PublicationSchedule current = this.schedules.findById(schedule.id()).orElseThrow();
		if (!S_PENDING.equals(current.state())) {
			return;
		}
		if (now.isAfter(current.scheduledAt().plus(DISPATCH_CUTOFF))) {
			this.schedules.transition(current.id(), current.workspaceId(), S_PENDING, S_EXPIRED, now);
			return;
		}
		Workspace workspace = this.workspaces.findById(current.workspaceId()).orElseThrow();
		String payloadHash = this.publications.payloadHashFor(current.workspaceId(), current.revisionId());
		if (!payloadHash.equals(current.payloadHash())) {
			this.schedules.transition(current.id(), current.workspaceId(), S_PENDING, S_NEEDS_REVIEW, now);
			return;
		}
		Publication publication = this.publications.create(current.workspaceId(), workspace.slug(), current.postId(),
				current.revisionId(), current.submissionKey());
		int claimed = this.schedules.claim(current.id(), current.workspaceId(), publication.id(), now);
		if (claimed == 0) {
			throw new ScheduleException("Schedule #" + current.id() + " was already claimed by another worker.");
		}
		for (Delivery delivery : this.publications.deliveriesOf(current.workspaceId(), publication.id())) {
			this.jobs.executeInitialAndSettle(current.workspaceId(), workspace.slug(), delivery.id());
		}
		this.publications.refreshAggregate(current.workspaceId(), publication.id());
	}

	private LocalDateTime parseLocalDateTime(String value) {
		try {
			return LocalDateTime.parse(value);
		}
		catch (DateTimeParseException | NullPointerException ex) {
			throw new ScheduleException("Invalid local date/time — use the date-time picker format.");
		}
	}

	private ZoneId parseZone(String timezone) {
		try {
			return ZoneId.of(timezone);
		}
		catch (RuntimeException ex) {
			throw new ScheduleException("Unknown IANA timezone '" + timezone + "'.");
		}
	}

	private ZoneOffset parseOffset(String value) {
		try {
			return ZoneOffset.of(value);
		}
		catch (DateTimeParseException ex) {
			throw new ScheduleException("Invalid offset '" + value + "' — use e.g. +02:00.");
		}
	}

	private String formatOffsets(List<ZoneOffset> offsets) {
		return offsets.stream().map(ZoneOffset::getId).collect(Collectors.joining(" or "));
	}

}
