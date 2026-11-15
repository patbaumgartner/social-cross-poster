package com.patbaumgartner.socialcrossposter.publish;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceRepository;

/**
 * Durable retry/reconcile orchestration for deliveries (UC-07, ADR-015). The UC-05
 * synchronous initial attempt stays in the web request via
 * {@link #executeInitialAndSettle}; this service owns everything after that: bounded
 * retries with exponential backoff, lease-guarded job execution, and UNKNOWN
 * reconciliation. It depends on {@link PublicationService} and the workspace repository
 * only — never the reverse, so no circular dependency. All lookups are workspace-scoped
 * and fail closed for foreign ids (ADR-013).
 */
@Service
public class DeliveryJobService {

	/** Job states (ADR-015): PENDING, RUNNING, SUCCEEDED, EXHAUSTED, CANCELED. */
	public static final String J_PENDING = "PENDING";

	public static final String J_RUNNING = "RUNNING";

	public static final String J_SUCCEEDED = "SUCCEEDED";

	public static final String J_EXHAUSTED = "EXHAUSTED";

	public static final String J_CANCELED = "CANCELED";

	private static final Logger log = LoggerFactory.getLogger(DeliveryJobService.class);

	private final PublicationService publications;

	private final DeliveryJobRepository jobs;

	private final WorkspaceRepository workspaces;

	private final WorkerProperties props;

	private final String instanceId;

	private final Random random = new Random();

	public DeliveryJobService(PublicationService publications, DeliveryJobRepository jobs,
			WorkspaceRepository workspaces, WorkerProperties props) {
		this.publications = publications;
		this.jobs = jobs;
		this.workspaces = workspaces;
		this.props = props;
		this.instanceId = props.instanceId() == null || props.instanceId().isBlank() ? "worker-" + UUID.randomUUID()
				: props.instanceId();
	}

	/**
	 * Lease owner name of this worker instance (APP_WORKER_INSTANCE_ID or a random id).
	 */
	public String instanceId() {
		return this.instanceId;
	}

	/**
	 * UC-05/UC-07 handoff: runs the synchronous initial attempt of one PENDING delivery
	 * and then settles the durable outcome — a retryable failure becomes RETRY_WAIT with
	 * a PENDING job on the backoff schedule, an UNKNOWN outcome is reconciled first.
	 */
	@Transactional
	public Delivery executeInitialAndSettle(Long workspaceId, String workspaceSlug, Long deliveryId) {
		Delivery delivery = this.publications.executeDelivery(workspaceId, workspaceSlug, deliveryId);
		return settle(workspaceId, workspaceSlug, delivery, OffsetDateTime.now());
	}

	/**
	 * Owner-triggered retry (UC-07 step 1): moves a retryable delivery to RETRY_WAIT and
	 * schedules its job to run now. Throws when the delivery is not retryable, when the
	 * attempt budget is exhausted, or when a live lease holds the job.
	 */
	@Transactional
	public DeliveryJob retryNow(Long workspaceId, Long publicationId, Long deliveryId) {
		Delivery delivery = this.publications.requireDelivery(workspaceId, deliveryId);
		if (!Long.valueOf(publicationId).equals(delivery.publicationId())) {
			throw new PublicationException("Delivery does not belong to this publication.");
		}
		if (!PublicationService.RETRYABLE_DELIVERY_STATES.contains(delivery.state())) {
			throw new PublicationException("Delivery #" + deliveryId + " in state " + delivery.state()
					+ " is not retryable — successful, skipped or canceled deliveries are never re-run.");
		}
		long attempts = this.publications.attemptCount(deliveryId);
		if (attempts >= this.props.maxAttempts()) {
			throw new PublicationException("Retry budget exhausted for delivery #" + deliveryId + " (" + attempts + "/"
					+ this.props.maxAttempts() + " attempts) — review it manually.");
		}
		OffsetDateTime now = OffsetDateTime.now();
		Optional<DeliveryJob> jobOpt = this.jobs.findByDeliveryIdAndWorkspaceId(deliveryId, workspaceId);
		if (jobOpt.isPresent() && J_RUNNING.equals(jobOpt.get().state()) && jobOpt.get().leaseUntil() != null
				&& jobOpt.get().leaseUntil().isAfter(now)) {
			throw new PublicationException(
					"A worker currently holds the lease for delivery #" + deliveryId + " — try again shortly.");
		}
		this.publications.updateDeliveryState(workspaceId, deliveryId, PublicationService.D_RETRY_WAIT,
				delivery.externalRef());
		DeliveryJob job = upsertJob(workspaceId, deliveryId, J_PENDING, now);
		this.publications.refreshAggregate(workspaceId, publicationId);
		return job;
	}

	/**
	 * Owner-triggered reconciliation (UC-07 step 2): resolves an UNKNOWN/NEEDS_ATTENTION
	 * delivery through the platform reconciler and settles the result. Throws for
	 * non-uncertain deliveries.
	 */
	@Transactional
	public Delivery reconcileNow(Long workspaceId, Long publicationId, Long deliveryId) {
		Delivery delivery = this.publications.requireDelivery(workspaceId, deliveryId);
		if (!Long.valueOf(publicationId).equals(delivery.publicationId())) {
			throw new PublicationException("Delivery does not belong to this publication.");
		}
		if (!PublicationService.D_UNKNOWN.equals(delivery.state())
				&& !PublicationService.D_NEEDS_ATTENTION.equals(delivery.state())) {
			throw new PublicationException("Only UNKNOWN or NEEDS_ATTENTION deliveries can be reconciled; this one is "
					+ delivery.state() + ".");
		}
		String slug = slugOf(workspaceId);
		Delivery reconciled = this.publications.reconcileDelivery(workspaceId, slug, deliveryId);
		return settle(workspaceId, slug, reconciled, OffsetDateTime.now());
	}

	/**
	 * One worker tick (UC-07 step 3): claims every due job exactly once via the
	 * conditional {@code claim} (only the winner executes), runs it, and settles. A job
	 * that throws is released back to PENDING with the base backoff so one bad delivery
	 * cannot block the queue. Each step commits separately — no wrapping transaction
	 * across jobs.
	 */
	public void runDueJobs(OffsetDateTime now) {
		for (Long jobId : this.jobs.findDueIds(now)) {
			OffsetDateTime until = now.plusSeconds(this.props.leaseTtlSeconds());
			if (this.jobs.claim(jobId, this.instanceId, until, now) != 1) {
				continue; // another worker won the claim race
			}
			try {
				DeliveryJob claimed = this.jobs.findById(jobId)
					.orElseThrow(() -> new PublicationException("Claimed job disappeared: " + jobId));
				executeClaimedJob(claimed, now);
			}
			catch (RuntimeException ex) {
				log.warn("Delivery job {} failed; releasing it with the base backoff.", jobId, ex);
				releaseJob(jobId, now);
			}
		}
	}

	/**
	 * Executes one claimed job: uncertain deliveries are reconciled (never blindly
	 * retried), PENDING deliveries get their initial attempt, retryable deliveries are
	 * re-sent while the attempt budget lasts, then the outcome is settled.
	 */
	@Transactional
	public void executeClaimedJob(DeliveryJob job, OffsetDateTime now) {
		Long workspaceId = job.workspaceId();
		String slug = slugOf(workspaceId);
		Delivery delivery = this.publications.requireDelivery(workspaceId, job.deliveryId());
		if (PublicationService.D_PENDING.equals(delivery.state())) {
			this.publications.executeDelivery(workspaceId, slug, delivery.id());
		}
		else if (PublicationService.RETRYABLE_DELIVERY_STATES.contains(delivery.state())
				&& !PublicationService.D_UNKNOWN.equals(delivery.state())
				&& !PublicationService.D_NEEDS_ATTENTION.equals(delivery.state())
				&& this.publications.attemptCount(delivery.id()) < this.props.maxAttempts()) {
			this.publications.executeRetry(workspaceId, slug, delivery.id());
		}
		settle(workspaceId, slug, this.publications.requireDelivery(workspaceId, job.deliveryId()), now);
	}

	/**
	 * Settles one delivery outcome (UC-07 steps 1-2, 4): UNKNOWN is reconciled first;
	 * SUCCESS/SKIPPED close the job as SUCCEEDED; FAILED with budget left becomes
	 * RETRY_WAIT with a PENDING job on the backoff schedule, without budget the job is
	 * EXHAUSTED; NEEDS_ATTENTION/NEEDS_REVIEW cancel the job for manual review. Always
	 * refreshes the ADR-015 aggregate afterwards.
	 */
	@Transactional
	public Delivery settle(Long workspaceId, String workspaceSlug, Delivery delivery, OffsetDateTime now) {
		if (PublicationService.D_UNKNOWN.equals(delivery.state())) {
			delivery = this.publications.reconcileDelivery(workspaceId, workspaceSlug, delivery.id());
		}
		switch (delivery.state()) {
			case PublicationService.D_SUCCESS, PublicationService.D_SKIPPED ->
				markJob(workspaceId, delivery.id(), J_SUCCEEDED, null, now);
			case PublicationService.D_FAILED -> {
				long attempts = this.publications.attemptCount(delivery.id());
				if (attempts < this.props.maxAttempts()) {
					this.publications.updateDeliveryState(workspaceId, delivery.id(), PublicationService.D_RETRY_WAIT,
							delivery.externalRef());
					upsertJob(workspaceId, delivery.id(), J_PENDING, now.plusSeconds(backoffSeconds(attempts)));
				}
				else {
					markJob(workspaceId, delivery.id(), J_EXHAUSTED, null, now);
				}
			}
			case PublicationService.D_NEEDS_ATTENTION, PublicationService.D_NEEDS_REVIEW ->
				markJob(workspaceId, delivery.id(), J_CANCELED, null, now);
			default ->
				upsertJob(workspaceId, delivery.id(), J_PENDING, now.plusSeconds(this.props.backoffBaseSeconds()));
		}
		this.publications.refreshAggregate(workspaceId, delivery.publicationId());
		return this.publications.requireDelivery(workspaceId, delivery.id());
	}

	/**
	 * Bounded exponential backoff (UC-07 step 4): {@code base * 2^(n-1)} scaled by a
	 * 0.8-1.2 jitter, then capped at {@code cap} and floored at 1 second, where {@code n}
	 * is the attempt count so far — the result never exceeds the cap.
	 */
	public long backoffSeconds(long attemptCount) {
		long n = Math.max(1L, attemptCount);
		long exponential = this.props.backoffBaseSeconds() * (1L << Math.min(n - 1, 20));
		double jitter = 0.8 + this.random.nextDouble() * 0.4;
		long jittered = (long) (exponential * jitter);
		return Math.min(this.props.backoffCapSeconds(), Math.max(1L, jittered));
	}

	private DeliveryJob upsertJob(Long workspaceId, Long deliveryId, String state, OffsetDateTime nextRunAt) {
		OffsetDateTime now = OffsetDateTime.now();
		Optional<DeliveryJob> existing = this.jobs.findByDeliveryIdAndWorkspaceId(deliveryId, workspaceId);
		if (existing.isPresent()) {
			DeliveryJob j = existing.get();
			return this.jobs.save(
					new DeliveryJob(j.id(), workspaceId, deliveryId, state, nextRunAt, null, null, j.createdAt(), now));
		}
		return this.jobs.save(new DeliveryJob(null, workspaceId, deliveryId, state, nextRunAt, null, null, now, now));
	}

	private void markJob(Long workspaceId, Long deliveryId, String state, OffsetDateTime nextRunAt,
			OffsetDateTime now) {
		Optional<DeliveryJob> existing = this.jobs.findByDeliveryIdAndWorkspaceId(deliveryId, workspaceId);
		if (existing.isPresent()) {
			DeliveryJob j = existing.get();
			this.jobs.save(new DeliveryJob(j.id(), workspaceId, deliveryId, state,
					nextRunAt != null ? nextRunAt : j.nextRunAt(), null, null, j.createdAt(), now));
		}
		else {
			this.jobs.save(new DeliveryJob(null, workspaceId, deliveryId, state, nextRunAt != null ? nextRunAt : now,
					null, null, now, now));
		}
	}

	private void releaseJob(Long jobId, OffsetDateTime now) {
		this.jobs.findById(jobId)
			.ifPresent((j) -> this.jobs.save(new DeliveryJob(j.id(), j.workspaceId(), j.deliveryId(), J_PENDING,
					now.plusSeconds(this.props.backoffBaseSeconds()), null, null, j.createdAt(), now)));
	}

	private String slugOf(Long workspaceId) {
		return this.workspaces.findById(workspaceId)
			.map(Workspace::slug)
			.orElseThrow(() -> new PublicationException("Unknown workspace."));
	}

}
