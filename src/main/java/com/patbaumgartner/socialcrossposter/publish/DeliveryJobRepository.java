package com.patbaumgartner.socialcrossposter.publish;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DeliveryJobRepository extends CrudRepository<DeliveryJob, Long> {

	Optional<DeliveryJob> findByIdAndWorkspaceId(Long id, Long workspaceId);

	Optional<DeliveryJob> findByDeliveryIdAndWorkspaceId(Long deliveryId, Long workspaceId);

	List<DeliveryJob> findAllByWorkspaceId(Long workspaceId);

	/**
	 * Jobs of one workspace in one state (UC-15 step 6 archive: unclaimed PENDING jobs
	 * are listed before they are canceled; RUNNING jobs hold a lease and settle on their
	 * own).
	 */
	List<DeliveryJob> findAllByWorkspaceIdAndState(Long workspaceId, String state);

	/**
	 * UC-15 step 6: cancels all unclaimed (PENDING) jobs of one workspace in one
	 * statement, clearing any stale lease columns. Returns the number of canceled jobs.
	 * RUNNING jobs are left alone — a live lease settles through the normal worker path.
	 */
	@Transactional
	@Modifying
	@Query("UPDATE delivery_job SET state = 'CANCELED', lease_owner = NULL, lease_until = NULL, updated_at = :now "
			+ "WHERE workspace_id = :workspaceId AND state = 'PENDING'")
	int cancelAllPending(@Param("workspaceId") Long workspaceId, @Param("now") OffsetDateTime now);

	/**
	 * Ids of jobs due now (UC-07, ADR-015): PENDING with {@code next_run_at} reached, or
	 * RUNNING with an expired lease (crashed worker).
	 */
	@Query("SELECT id FROM delivery_job WHERE (state = 'PENDING' AND next_run_at <= :now) "
			+ "OR (state = 'RUNNING' AND lease_until < :now) ORDER BY next_run_at ASC")
	List<Long> findDueIds(@Param("now") OffsetDateTime now);

	/**
	 * Atomically claims one due job for {@code owner} until {@code until}. Returns 1 when
	 * the conditional update matched, 0 when another worker won the race — only the
	 * winner executes the job.
	 */
	@Transactional
	@Modifying
	@Query("UPDATE delivery_job SET state = 'RUNNING', lease_owner = :owner, lease_until = :until, updated_at = :now "
			+ "WHERE id = :id AND ((state = 'PENDING' AND next_run_at <= :now) "
			+ "OR (state = 'RUNNING' AND lease_until < :now))")
	int claim(@Param("id") Long id, @Param("owner") String owner, @Param("until") OffsetDateTime until,
			@Param("now") OffsetDateTime now);

}
