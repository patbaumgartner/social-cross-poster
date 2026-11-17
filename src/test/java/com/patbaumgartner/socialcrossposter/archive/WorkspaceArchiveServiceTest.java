package com.patbaumgartner.socialcrossposter.archive;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.patbaumgartner.socialcrossposter.publish.DeliveryJob;
import com.patbaumgartner.socialcrossposter.publish.DeliveryJobRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryRepository;
import com.patbaumgartner.socialcrossposter.scheduling.PublicationSchedule;
import com.patbaumgartner.socialcrossposter.scheduling.PublicationScheduleRepository;
import com.patbaumgartner.socialcrossposter.scheduling.ScheduleService;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceRepository;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link WorkspaceArchiveService} (UC-15 step 6, ADR-013): archiving
 * cancels the PENDING schedules and unclaimed jobs, acknowledges the unresolved
 * deliveries and sets the archived flag in one sweep; the preview only lists without
 * writing; reopen clears the flag and never touches the schedule/job/delivery
 * repositories. Unknown workspaces and wrongly-state workspaces fail closed. Mocked
 * repositories, no Spring context.
 */
class WorkspaceArchiveServiceTest {

	private static final Long WS = 1L;

	private static final OffsetDateTime NOW = OffsetDateTime.now();

	private WorkspaceRepository workspaces;

	private WorkspaceService workspaceService;

	private PublicationScheduleRepository schedules;

	private DeliveryJobRepository jobs;

	private DeliveryRepository deliveries;

	private WorkspaceArchiveService service;

	private Workspace active;

	@BeforeEach
	void setUp() {
		this.workspaces = mock(WorkspaceRepository.class);
		this.workspaceService = mock(WorkspaceService.class);
		this.schedules = mock(PublicationScheduleRepository.class);
		this.jobs = mock(DeliveryJobRepository.class);
		this.deliveries = mock(DeliveryRepository.class);
		this.service = new WorkspaceArchiveService(this.workspaces, this.workspaceService, this.schedules, this.jobs,
				this.deliveries);
		this.active = new Workspace(WS, "private", "Private", "#3b82f6", "Europe/Zurich", NOW);
		given(this.workspaces.findById(WS)).willReturn(Optional.of(this.active));
	}

	@Test
	void archiveCancelsPendingSchedulesAndJobsAcknowledgesDeliveriesAndSetsFlag() {
		given(this.schedules.findAllByWorkspaceIdAndStateOrderByScheduledAtAsc(WS, ScheduleService.S_PENDING))
			.willReturn(List.of(schedule(11L), schedule(12L)));
		given(this.jobs.findAllByWorkspaceIdAndState(WS, "PENDING"))
			.willReturn(List.of(new DeliveryJob(21L, WS, 501L, "PENDING", NOW, null, null, NOW, NOW)));
		given(this.deliveries.findUnacknowledgedIds(eq(WS), eq(WorkspaceArchiveService.UNRESOLVED_DELIVERY_STATES)))
			.willReturn(List.of(501L, 502L));
		given(this.workspaceService.setArchived("private", true)).willReturn(archived());

		ArchiveReport report = this.service.archive(WS);

		assertThat(report.pendingSchedules()).isEqualTo(2);
		assertThat(report.unclaimedJobs()).isEqualTo(1);
		assertThat(report.unresolvedDeliveries()).isEqualTo(2);
		assertThat(report.archivedAt()).isEqualTo(NOW);
		verify(this.schedules).transition(eq(11L), eq(WS), eq(ScheduleService.S_PENDING),
				eq(ScheduleService.S_CANCELED), any(OffsetDateTime.class));
		verify(this.schedules).transition(eq(12L), eq(WS), eq(ScheduleService.S_PENDING),
				eq(ScheduleService.S_CANCELED), any(OffsetDateTime.class));
		verify(this.jobs).cancelAllPending(eq(WS), any(OffsetDateTime.class));
		verify(this.deliveries).acknowledgeUnresolved(eq(WS), eq(WorkspaceArchiveService.UNRESOLVED_DELIVERY_STATES),
				any(OffsetDateTime.class));
		verify(this.workspaceService).setArchived("private", true);
	}

	@Test
	void archiveOnAlreadyArchivedWorkspaceFailsClosed() {
		given(this.workspaces.findById(WS)).willReturn(Optional.of(archived()));

		assertThatThrownBy(() -> this.service.archive(WS)).isInstanceOf(WorkspaceArchiveException.class)
			.hasMessageContaining("already archived");

		verifyNoInteractions(this.workspaceService, this.schedules, this.jobs, this.deliveries);
	}

	@Test
	void archiveOnUnknownWorkspaceFailsClosed() {
		given(this.workspaces.findById(999L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.archive(999L)).isInstanceOf(WorkspaceArchiveException.class)
			.hasMessageContaining("Unknown workspace");

		verifyNoInteractions(this.workspaceService, this.schedules, this.jobs, this.deliveries);
	}

	@Test
	void reopenClearsFlagWithoutTouchingSchedulesJobsOrDeliveries() {
		given(this.workspaces.findById(WS)).willReturn(Optional.of(archived()));
		given(this.workspaceService.setArchived("private", false)).willReturn(this.active);

		Workspace reopened = this.service.reopen(WS);

		assertThat(reopened.archived()).isFalse();
		assertThat(reopened.archivedAt()).isNull();
		verify(this.workspaceService).setArchived("private", false);
		verifyNoInteractions(this.schedules, this.jobs, this.deliveries);
	}

	@Test
	void reopenOnNotArchivedWorkspaceFailsClosed() {
		assertThatThrownBy(() -> this.service.reopen(WS)).isInstanceOf(WorkspaceArchiveException.class)
			.hasMessageContaining("not archived");

		verifyNoInteractions(this.workspaceService, this.schedules, this.jobs, this.deliveries);
	}

	@Test
	void previewListsPendingWorkWithoutWritingAnything() {
		given(this.schedules.findAllByWorkspaceIdAndStateOrderByScheduledAtAsc(WS, ScheduleService.S_PENDING))
			.willReturn(List.of(schedule(11L)));
		given(this.jobs.findAllByWorkspaceIdAndState(WS, "PENDING")).willReturn(List.of());
		given(this.deliveries.findUnacknowledgedIds(eq(WS), eq(WorkspaceArchiveService.UNRESOLVED_DELIVERY_STATES)))
			.willReturn(List.of(501L));

		ArchiveReport report = this.service.preview(WS);

		assertThat(report.pendingSchedules()).isEqualTo(1);
		assertThat(report.unclaimedJobs()).isEqualTo(0);
		assertThat(report.unresolvedDeliveries()).isEqualTo(1);
		assertThat(report.archivedAt()).isNull();
		verifyNoInteractions(this.workspaceService);
		verify(this.schedules, never()).transition(any(), any(), any(), any(), any());
		verify(this.jobs, never()).cancelAllPending(any(), any());
		verify(this.deliveries, never()).acknowledgeUnresolved(any(), any(), any());
	}

	@Test
	void previewOnArchivedWorkspaceFailsClosed() {
		given(this.workspaces.findById(WS)).willReturn(Optional.of(archived()));

		assertThatThrownBy(() -> this.service.preview(WS)).isInstanceOf(WorkspaceArchiveException.class)
			.hasMessageContaining("already archived");

		verifyNoInteractions(this.workspaceService, this.schedules, this.jobs, this.deliveries);
	}

	private Workspace archived() {
		return new Workspace(WS, "private", "Private", "#3b82f6", "Europe/Zurich", NOW, null, null, null, null, false,
				true, NOW);
	}

	private PublicationSchedule schedule(Long id) {
		return new PublicationSchedule(id, WS, 2L, 3L, "sched-" + id, "hash", LocalDateTime.now(), "Europe/Zurich", NOW,
				ScheduleService.S_PENDING, null, NOW, NOW);
	}

}
