package com.patbaumgartner.socialcrossposter.publish;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link DeliveryJobService} (UC-07, ADR-015): bounded exponential backoff
 * with jitter and a 1s floor, {@code retryNow} guards (non-retryable state, foreign
 * publication, exhausted budget, live lease) plus immediate PENDING scheduling, the
 * {@code settle} paths (SUCCESS closes the job, FAILED schedules backoff or EXHAUSTS at
 * the budget, UNKNOWN is reconciled first, NEEDS_* cancels the job) and
 * {@code runDueJobs} claim/execute/release semantics (a lost claim race skips the job, an
 * exception releases it with the base backoff). Mocked dependencies, no Spring context.
 */
class DeliveryJobServiceTest {

	private static final Long WS = 1L;

	private static final Long PUB = 100L;

	private static final Long DELIVERY = 500L;

	private static final OffsetDateTime NOW = OffsetDateTime.now();

	private PublicationService publications;

	private DeliveryJobRepository jobs;

	private WorkspaceRepository workspaces;

	private DeliveryJobService service;

	@BeforeEach
	void setUp() {
		this.publications = mock(PublicationService.class);
		this.jobs = mock(DeliveryJobRepository.class);
		this.workspaces = mock(WorkspaceRepository.class);
		WorkerProperties props = new WorkerProperties(true, 5000L, 2000L, 60L, 3, 60L, 3600L, "test-worker");
		this.service = new DeliveryJobService(this.publications, this.jobs, this.workspaces, props);
		given(this.workspaces.findById(WS))
			.willReturn(Optional.of(new Workspace(WS, "private", "Private", "#ffffff", "UTC", NOW)));
		given(this.jobs.save(any(DeliveryJob.class))).willAnswer((inv) -> inv.getArgument(0));
	}

	@Test
	void instanceIdUsesConfiguredNameOrRandomWorkerPrefix() {
		assertThat(this.service.instanceId()).isEqualTo("test-worker");
		DeliveryJobService random = new DeliveryJobService(this.publications, this.jobs, this.workspaces,
				new WorkerProperties(null, null, null, null, null, null, null, null));
		assertThat(random.instanceId()).startsWith("worker-");
	}

	@Test
	void backoffIsExponentialCappedJitteredAndAtLeastOneSecond() {
		assertThat(this.service.backoffSeconds(1)).isBetween(48L, 72L);
		assertThat(this.service.backoffSeconds(2)).isBetween(96L, 144L);
		assertThat(this.service.backoffSeconds(3)).isBetween(192L, 288L);
		assertThat(this.service.backoffSeconds(100)).isBetween(2880L, 3600L); // capped at
																				// 3600
		DeliveryJobService tiny = new DeliveryJobService(this.publications, this.jobs, this.workspaces,
				new WorkerProperties(null, null, null, null, null, 1L, 1L, null));
		assertThat(tiny.backoffSeconds(5)).isEqualTo(1L); // floor
	}

	@Test
	void retryNowRejectsNonRetryableState() {
		given(this.publications.requireDelivery(WS, DELIVERY)).willReturn(delivery(PublicationService.D_SUCCESS));

		assertThatThrownBy(() -> this.service.retryNow(WS, PUB, DELIVERY)).isInstanceOf(PublicationException.class)
			.hasMessageContaining("never re-run");
		verify(this.jobs, never()).save(any());
	}

	@Test
	void retryNowRejectsForeignPublication() {
		given(this.publications.requireDelivery(WS, DELIVERY)).willReturn(delivery(PublicationService.D_FAILED));

		assertThatThrownBy(() -> this.service.retryNow(WS, 999L, DELIVERY)).isInstanceOf(PublicationException.class)
			.hasMessageContaining("does not belong");
	}

	@Test
	void retryNowRejectsExhaustedBudget() {
		given(this.publications.requireDelivery(WS, DELIVERY)).willReturn(delivery(PublicationService.D_FAILED));
		given(this.publications.attemptCount(DELIVERY)).willReturn(3L); // maxAttempts = 3

		assertThatThrownBy(() -> this.service.retryNow(WS, PUB, DELIVERY)).isInstanceOf(PublicationException.class)
			.hasMessageContaining("budget exhausted");
		verify(this.jobs, never()).save(any());
	}

	@Test
	void retryNowRejectsLiveLease() {
		given(this.publications.requireDelivery(WS, DELIVERY)).willReturn(delivery(PublicationService.D_FAILED));
		given(this.publications.attemptCount(DELIVERY)).willReturn(1L);
		given(this.jobs.findByDeliveryIdAndWorkspaceId(DELIVERY, WS))
			.willReturn(Optional.of(job(DeliveryJobService.J_RUNNING, NOW, NOW.plusSeconds(30))));

		assertThatThrownBy(() -> this.service.retryNow(WS, PUB, DELIVERY)).isInstanceOf(PublicationException.class)
			.hasMessageContaining("lease");
		verify(this.jobs, never()).save(any());
	}

	@Test
	void retryNowSchedulesImmediatePendingJobAndMarksRetryWait() {
		given(this.publications.requireDelivery(WS, DELIVERY)).willReturn(delivery(PublicationService.D_FAILED));
		given(this.publications.attemptCount(DELIVERY)).willReturn(1L);
		given(this.jobs.findByDeliveryIdAndWorkspaceId(DELIVERY, WS)).willReturn(Optional.empty());

		DeliveryJob result = this.service.retryNow(WS, PUB, DELIVERY);

		verify(this.publications).updateDeliveryState(WS, DELIVERY, PublicationService.D_RETRY_WAIT, null);
		ArgumentCaptor<DeliveryJob> captor = ArgumentCaptor.forClass(DeliveryJob.class);
		verify(this.jobs).save(captor.capture());
		assertThat(captor.getValue().state()).isEqualTo(DeliveryJobService.J_PENDING);
		assertThat(captor.getValue().nextRunAt()).isNotNull()
			.isBefore(OffsetDateTime.now().plusSeconds(5))
			.isAfter(NOW.minusSeconds(5));
		verify(this.publications).refreshAggregate(WS, PUB);
		assertThat(result.state()).isEqualTo(DeliveryJobService.J_PENDING);
	}

	@Test
	void settleSuccessClosesJobAsSucceeded() {
		given(this.publications.requireDelivery(WS, DELIVERY)).willReturn(delivery(PublicationService.D_SUCCESS));
		given(this.jobs.findByDeliveryIdAndWorkspaceId(DELIVERY, WS))
			.willReturn(Optional.of(job(DeliveryJobService.J_PENDING, NOW, null)));

		Delivery result = this.service.settle(WS, "private", delivery(PublicationService.D_SUCCESS), NOW);

		verify(this.jobs).save(argThat((DeliveryJob j) -> DeliveryJobService.J_SUCCEEDED.equals(j.state())
				&& j.leaseOwner() == null && j.leaseUntil() == null));
		verify(this.publications).refreshAggregate(WS, PUB);
		assertThat(result.state()).isEqualTo(PublicationService.D_SUCCESS);
	}

	@Test
	void settleFailedSchedulesBackoffWhileBudgetLasts() {
		given(this.publications.requireDelivery(WS, DELIVERY)).willReturn(delivery(PublicationService.D_FAILED));
		given(this.publications.attemptCount(DELIVERY)).willReturn(1L);
		given(this.jobs.findByDeliveryIdAndWorkspaceId(DELIVERY, WS))
			.willReturn(Optional.of(job(DeliveryJobService.J_RUNNING, NOW, NOW.plusSeconds(60))));

		this.service.settle(WS, "private", delivery(PublicationService.D_FAILED), NOW);

		verify(this.publications).updateDeliveryState(WS, DELIVERY, PublicationService.D_RETRY_WAIT, null);
		ArgumentCaptor<DeliveryJob> captor = ArgumentCaptor.forClass(DeliveryJob.class);
		verify(this.jobs).save(captor.capture());
		assertThat(captor.getValue().state()).isEqualTo(DeliveryJobService.J_PENDING);
		assertThat(captor.getValue().nextRunAt()).isBetween(NOW.plusSeconds(48), NOW.plusSeconds(72));
	}

	@Test
	void settleFailedExhaustsJobWhenBudgetSpent() {
		given(this.publications.requireDelivery(WS, DELIVERY)).willReturn(delivery(PublicationService.D_FAILED));
		given(this.publications.attemptCount(DELIVERY)).willReturn(3L); // maxAttempts = 3
		given(this.jobs.findByDeliveryIdAndWorkspaceId(DELIVERY, WS))
			.willReturn(Optional.of(job(DeliveryJobService.J_RUNNING, NOW, NOW.plusSeconds(60))));

		this.service.settle(WS, "private", delivery(PublicationService.D_FAILED), NOW);

		verify(this.publications, never()).updateDeliveryState(any(), any(), any(), any());
		verify(this.jobs).save(argThat((DeliveryJob j) -> DeliveryJobService.J_EXHAUSTED.equals(j.state())));
		verify(this.publications).refreshAggregate(WS, PUB);
	}

	@Test
	void settleUnknownReconcilesFirstAndCancelsOnInconclusive() {
		given(this.publications.requireDelivery(WS, DELIVERY)).willReturn(delivery(PublicationService.D_UNKNOWN),
				delivery(PublicationService.D_NEEDS_ATTENTION));
		given(this.publications.reconcileDelivery(WS, "private", DELIVERY))
			.willReturn(delivery(PublicationService.D_NEEDS_ATTENTION));
		given(this.jobs.findByDeliveryIdAndWorkspaceId(DELIVERY, WS))
			.willReturn(Optional.of(job(DeliveryJobService.J_RUNNING, NOW, NOW.plusSeconds(60))));

		this.service.settle(WS, "private", delivery(PublicationService.D_UNKNOWN), NOW);

		verify(this.publications).reconcileDelivery(WS, "private", DELIVERY);
		verify(this.publications, never()).executeRetry(any(), any(), any());
		verify(this.jobs).save(argThat((DeliveryJob j) -> DeliveryJobService.J_CANCELED.equals(j.state())));
	}

	@Test
	void settleNeedsAttentionCancelsWithoutReconcile() {
		given(this.publications.requireDelivery(WS, DELIVERY))
			.willReturn(delivery(PublicationService.D_NEEDS_ATTENTION));
		given(this.jobs.findByDeliveryIdAndWorkspaceId(DELIVERY, WS))
			.willReturn(Optional.of(job(DeliveryJobService.J_PENDING, NOW, null)));

		this.service.settle(WS, "private", delivery(PublicationService.D_NEEDS_ATTENTION), NOW);

		verify(this.publications, never()).reconcileDelivery(any(), any(), any());
		verify(this.jobs).save(argThat((DeliveryJob j) -> DeliveryJobService.J_CANCELED.equals(j.state())));
	}

	@Test
	void runDueJobsClaimsExecutesAndSettles() {
		given(this.jobs.findDueIds(NOW)).willReturn(List.of(1L));
		given(this.jobs.claim(eq(1L), eq("test-worker"), any(), eq(NOW))).willReturn(1);
		given(this.jobs.findById(1L)).willReturn(Optional.of(job(DeliveryJobService.J_PENDING, NOW, null)));
		given(this.publications.requireDelivery(WS, DELIVERY)).willReturn(delivery(PublicationService.D_FAILED),
				delivery(PublicationService.D_SUCCESS), delivery(PublicationService.D_SUCCESS));
		given(this.publications.attemptCount(DELIVERY)).willReturn(1L);
		given(this.publications.executeRetry(WS, "private", DELIVERY))
			.willReturn(delivery(PublicationService.D_SUCCESS));
		given(this.jobs.findByDeliveryIdAndWorkspaceId(DELIVERY, WS))
			.willReturn(Optional.of(job(DeliveryJobService.J_RUNNING, NOW, NOW.plusSeconds(60))));

		this.service.runDueJobs(NOW);

		verify(this.jobs).claim(1L, "test-worker", NOW.plusSeconds(60L), NOW);
		verify(this.publications).executeRetry(WS, "private", DELIVERY);
		verify(this.jobs).save(argThat((DeliveryJob j) -> DeliveryJobService.J_SUCCEEDED.equals(j.state())));
		verify(this.publications).refreshAggregate(WS, PUB);
	}

	@Test
	void runDueJobsSkipsWhenClaimLosesRace() {
		given(this.jobs.findDueIds(NOW)).willReturn(List.of(1L));
		given(this.jobs.claim(eq(1L), any(), any(), eq(NOW))).willReturn(0);

		this.service.runDueJobs(NOW);

		verify(this.jobs, never()).findById(any());
		verifyNoInteractions(this.publications);
	}

	@Test
	void runDueJobsReleasesJobWithBaseBackoffOnError() {
		given(this.jobs.findDueIds(NOW)).willReturn(List.of(1L));
		given(this.jobs.claim(eq(1L), any(), any(), eq(NOW))).willReturn(1);
		given(this.jobs.findById(1L)).willReturn(Optional.of(job(DeliveryJobService.J_RUNNING, NOW, null)));
		given(this.publications.requireDelivery(WS, DELIVERY)).willThrow(new PublicationException("boom"));

		this.service.runDueJobs(NOW);

		ArgumentCaptor<DeliveryJob> captor = ArgumentCaptor.forClass(DeliveryJob.class);
		verify(this.jobs).save(captor.capture());
		assertThat(captor.getValue().state()).isEqualTo(DeliveryJobService.J_PENDING);
		assertThat(captor.getValue().nextRunAt()).isEqualTo(NOW.plusSeconds(60L));
		assertThat(captor.getValue().leaseOwner()).isNull();
	}

	@Test
	void executeClaimedJobReconcilesUnknownInsteadOfRetrying() {
		DeliveryJob claimed = job(DeliveryJobService.J_RUNNING, NOW, NOW.plusSeconds(60));
		given(this.publications.requireDelivery(WS, DELIVERY)).willReturn(delivery(PublicationService.D_UNKNOWN),
				delivery(PublicationService.D_UNKNOWN), delivery(PublicationService.D_NEEDS_ATTENTION));
		given(this.publications.reconcileDelivery(WS, "private", DELIVERY))
			.willReturn(delivery(PublicationService.D_NEEDS_ATTENTION));
		given(this.jobs.findByDeliveryIdAndWorkspaceId(DELIVERY, WS)).willReturn(Optional.of(claimed));

		this.service.executeClaimedJob(claimed, NOW);

		verify(this.publications, never()).executeRetry(any(), any(), any());
		verify(this.publications).reconcileDelivery(WS, "private", DELIVERY);
		verify(this.jobs).save(argThat((DeliveryJob j) -> DeliveryJobService.J_CANCELED.equals(j.state())));
	}

	@Test
	void executeClaimedJobSkipsRetryWhenBudgetSpent() {
		DeliveryJob claimed = job(DeliveryJobService.J_RUNNING, NOW, NOW.plusSeconds(60));
		given(this.publications.requireDelivery(WS, DELIVERY)).willReturn(delivery(PublicationService.D_FAILED),
				delivery(PublicationService.D_FAILED), delivery(PublicationService.D_FAILED));
		given(this.publications.attemptCount(DELIVERY)).willReturn(3L); // maxAttempts = 3
		given(this.jobs.findByDeliveryIdAndWorkspaceId(DELIVERY, WS)).willReturn(Optional.of(claimed));

		this.service.executeClaimedJob(claimed, NOW);

		verify(this.publications, never()).executeRetry(any(), any(), any());
		verify(this.jobs).save(argThat((DeliveryJob j) -> DeliveryJobService.J_EXHAUSTED.equals(j.state())));
	}

	@Test
	void executeInitialAndSettleRunsInitialAttemptThenSettlesRetry() {
		given(this.publications.requireDelivery(WS, DELIVERY)).willReturn(delivery(PublicationService.D_FAILED),
				delivery(PublicationService.D_FAILED));
		given(this.publications.executeDelivery(WS, "private", DELIVERY))
			.willReturn(delivery(PublicationService.D_FAILED));
		given(this.publications.attemptCount(DELIVERY)).willReturn(1L);
		given(this.jobs.findByDeliveryIdAndWorkspaceId(DELIVERY, WS)).willReturn(Optional.empty());

		Delivery result = this.service.executeInitialAndSettle(WS, "private", DELIVERY);

		verify(this.publications).executeDelivery(WS, "private", DELIVERY);
		verify(this.publications).updateDeliveryState(WS, DELIVERY, PublicationService.D_RETRY_WAIT, null);
		ArgumentCaptor<DeliveryJob> captor = ArgumentCaptor.forClass(DeliveryJob.class);
		verify(this.jobs).save(captor.capture());
		assertThat(captor.getValue().state()).isEqualTo(DeliveryJobService.J_PENDING);
		assertThat(captor.getValue().nextRunAt()).isAfter(NOW.plusSeconds(47L))
			.isBefore(OffsetDateTime.now().plusSeconds(73L));
		verify(this.publications).refreshAggregate(WS, PUB);
		assertThat(result.state()).isEqualTo(PublicationService.D_FAILED);
	}

	private Delivery delivery(String state) {
		return new Delivery(DELIVERY, WS, PUB, 7L, "telegram", "News channel", null, "-100123", "hello", "hash", state,
				null, NOW, NOW);
	}

	private DeliveryJob job(String state, OffsetDateTime nextRunAt, OffsetDateTime leaseUntil) {
		return new DeliveryJob(1L, WS, DELIVERY, state, nextRunAt, "other-worker", leaseUntil, NOW, NOW);
	}

}
