package com.patbaumgartner.socialcrossposter.scheduling;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.patbaumgartner.socialcrossposter.content.PostService;
import com.patbaumgartner.socialcrossposter.notify.NotificationService;
import com.patbaumgartner.socialcrossposter.publish.DeliveryJobService;
import com.patbaumgartner.socialcrossposter.publish.PublicationService;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceException;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for the UC-15 step 6 archive guards in {@link ScheduleService} (ADR-013):
 * scheduling on an archived workspace fails closed before any post/schedule interaction,
 * and a due PENDING schedule of an archived workspace is canceled instead of dispatched.
 * Mocked collaborators, no Spring context.
 */
class ScheduleServiceTest {

	private static final Long WS = 1L;

	private static final Long SCHEDULE_ID = 11L;

	private static final OffsetDateTime NOW = OffsetDateTime.now();

	private PublicationScheduleRepository schedules;

	private PostService posts;

	private WorkspaceRepository workspaces;

	private PublicationService publications;

	private DeliveryJobService jobs;

	private NotificationService notifications;

	private ScheduleService service;

	@BeforeEach
	void setUp() {
		this.schedules = mock(PublicationScheduleRepository.class);
		this.posts = mock(PostService.class);
		this.workspaces = mock(WorkspaceRepository.class);
		this.publications = mock(PublicationService.class);
		this.jobs = mock(DeliveryJobService.class);
		this.notifications = mock(NotificationService.class);
		this.service = new ScheduleService(this.schedules, this.posts, this.workspaces, this.publications, this.jobs,
				this.notifications);
	}

	@Test
	void scheduleOnArchivedWorkspaceFailsClosedBeforeTouchingPostsOrSchedules() {
		given(this.workspaces.findById(WS)).willReturn(Optional.of(archived()));

		assertThatThrownBy(() -> this.service.schedule(WS, 2L, "2030-01-01T10:00", "Europe/Zurich", null))
			.isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("archived");

		verifyNoInteractions(this.posts, this.publications);
		verify(this.schedules, never()).save(any(PublicationSchedule.class));
	}

	@Test
	void processDueOnArchivedWorkspaceCancelsPendingScheduleInsteadOfDispatching() {
		PublicationSchedule pending = schedule(SCHEDULE_ID, ScheduleService.S_PENDING);
		given(this.schedules.findById(SCHEDULE_ID)).willReturn(Optional.of(pending));
		given(this.workspaces.findById(WS)).willReturn(Optional.of(archived()));

		this.service.processDue(pending, NOW);

		verify(this.schedules).transition(eq(SCHEDULE_ID), eq(WS), eq(ScheduleService.S_PENDING),
				eq(ScheduleService.S_CANCELED), any(OffsetDateTime.class));
		verifyNoInteractions(this.publications, this.jobs, this.notifications);
	}

	private Workspace archived() {
		return new Workspace(WS, "private", "Private", "#3b82f6", "Europe/Zurich", NOW, null, null, null, null, false,
				true, NOW);
	}

	private PublicationSchedule schedule(Long id, String state) {
		return new PublicationSchedule(id, WS, 2L, 3L, "sched-" + id, "hash", LocalDateTime.now(), "Europe/Zurich", NOW,
				state, null, NOW, NOW);
	}

}
