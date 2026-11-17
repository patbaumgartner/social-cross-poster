package com.patbaumgartner.socialcrossposter.archive;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.patbaumgartner.socialcrossposter.publish.DeliveryJobRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryRepository;
import com.patbaumgartner.socialcrossposter.publish.PublicationService;
import com.patbaumgartner.socialcrossposter.scheduling.PublicationScheduleRepository;
import com.patbaumgartner.socialcrossposter.scheduling.ScheduleService;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceRepository;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

/**
 * UC-15 step 6 (archive lifecycle, ADR-013): archiving a workspace first lists its
 * pending schedules, unclaimed jobs and active/UNKNOWN deliveries, then cancels the
 * unclaimed work (PENDING schedules and PENDING jobs — RUNNING jobs hold a lease and
 * settle through the normal worker path), acknowledges the unresolved delivery effects
 * ({@code delivery.acknowledged_at}), and sets {@code workspace.archived} so the service
 * guards block new publication, scheduling and AI assistance. History, publications,
 * deliveries, media and settings are all retained — archiving never deletes anything.
 *
 * <p>
 * Reopening clears the flag and never auto-resumes canceled work: canceled schedules and
 * jobs stay CANCELED and require a fresh owner approval. All lookups are workspace-scoped
 * and fail closed (ADR-013).
 */
@Service
public class WorkspaceArchiveService {

	/**
	 * Delivery states whose effects are unresolved at archive time and get acknowledged:
	 * everything except the final SUCCESS/FAILED/SKIPPED/CANCELED states (ADR-015).
	 */
	public static final List<String> UNRESOLVED_DELIVERY_STATES = List.of(PublicationService.D_PENDING,
			PublicationService.D_RUNNING, PublicationService.D_RETRY_WAIT, PublicationService.D_UNKNOWN,
			PublicationService.D_NEEDS_ATTENTION, PublicationService.D_NEEDS_REVIEW);

	private final WorkspaceRepository workspaces;

	private final WorkspaceService workspaceService;

	private final PublicationScheduleRepository schedules;

	private final DeliveryJobRepository jobs;

	private final DeliveryRepository deliveries;

	public WorkspaceArchiveService(WorkspaceRepository workspaces, WorkspaceService workspaceService,
			PublicationScheduleRepository schedules, DeliveryJobRepository jobs, DeliveryRepository deliveries) {
		this.workspaces = workspaces;
		this.workspaceService = workspaceService;
		this.schedules = schedules;
		this.jobs = jobs;
		this.deliveries = deliveries;
	}

	/**
	 * The confirmation-page listing (UC-15 step 6): what archiving this workspace would
	 * cancel and acknowledge right now. Fails closed for an unknown id and for an already
	 * archived workspace.
	 */
	public ArchiveReport preview(Long workspaceId) {
		Workspace workspace = requireWorkspace(workspaceId);
		if (workspace.archived()) {
			throw new WorkspaceArchiveException("Workspace '" + workspace.slug() + "' is already archived.");
		}
		return new ArchiveReport(
				this.schedules.findAllByWorkspaceIdAndStateOrderByScheduledAtAsc(workspaceId, ScheduleService.S_PENDING)
					.stream()
					.map((s) -> s.id())
					.toList(),
				this.jobs.findAllByWorkspaceIdAndState(workspaceId, "PENDING").stream().map((j) -> j.id()).toList(),
				this.deliveries.findUnacknowledgedIds(workspaceId, UNRESOLVED_DELIVERY_STATES), null);
	}

	/**
	 * Archives one workspace (UC-15 step 6): lists pending schedules/jobs and unresolved
	 * deliveries, cancels every PENDING schedule and job (unclaimed work), acknowledges
	 * every unresolved delivery, then sets {@code archived} with a timestamp. All in one
	 * transaction — the workspace flips only when the whole sweep succeeded. Fails closed
	 * for an unknown id and for an already archived workspace.
	 */
	@Transactional
	public ArchiveReport archive(Long workspaceId) {
		Workspace workspace = requireWorkspace(workspaceId);
		if (workspace.archived()) {
			throw new WorkspaceArchiveException("Workspace '" + workspace.slug() + "' is already archived.");
		}
		OffsetDateTime now = OffsetDateTime.now();
		List<Long> pendingScheduleIds = this.schedules
			.findAllByWorkspaceIdAndStateOrderByScheduledAtAsc(workspaceId, ScheduleService.S_PENDING)
			.stream()
			.map((s) -> s.id())
			.toList();
		for (Long scheduleId : pendingScheduleIds) {
			this.schedules.transition(scheduleId, workspaceId, ScheduleService.S_PENDING, ScheduleService.S_CANCELED,
					now);
		}
		List<Long> unclaimedJobIds = this.jobs.findAllByWorkspaceIdAndState(workspaceId, "PENDING")
			.stream()
			.map((j) -> j.id())
			.toList();
		this.jobs.cancelAllPending(workspaceId, now);
		List<Long> unresolvedDeliveryIds = this.deliveries.findUnacknowledgedIds(workspaceId,
				UNRESOLVED_DELIVERY_STATES);
		this.deliveries.acknowledgeUnresolved(workspaceId, UNRESOLVED_DELIVERY_STATES, now);
		Workspace archived = this.workspaceService.setArchived(workspace.slug(), true);
		return new ArchiveReport(pendingScheduleIds, unclaimedJobIds, unresolvedDeliveryIds, archived.archivedAt());
	}

	/**
	 * Reopens an archived workspace: clears {@code archived}/{@code archived_at} so new
	 * publication, scheduling and AI work again. Canceled schedules and jobs are NOT
	 * resumed — they stay CANCELED and need a fresh owner approval. Fails closed for an
	 * unknown id and for a workspace that is not archived.
	 */
	@Transactional
	public Workspace reopen(Long workspaceId) {
		Workspace workspace = requireWorkspace(workspaceId);
		if (!workspace.archived()) {
			throw new WorkspaceArchiveException("Workspace '" + workspace.slug() + "' is not archived.");
		}
		return this.workspaceService.setArchived(workspace.slug(), false);
	}

	private Workspace requireWorkspace(Long workspaceId) {
		return this.workspaces.findById(workspaceId)
			.orElseThrow(() -> new WorkspaceArchiveException("Unknown workspace."));
	}

}
