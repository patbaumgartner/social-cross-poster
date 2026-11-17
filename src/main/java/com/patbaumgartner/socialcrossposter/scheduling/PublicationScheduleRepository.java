package com.patbaumgartner.socialcrossposter.scheduling;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PublicationScheduleRepository extends CrudRepository<PublicationSchedule, Long> {

	Optional<PublicationSchedule> findByIdAndWorkspaceId(Long id, Long workspaceId);

	List<PublicationSchedule> findAllByWorkspaceIdAndPostIdOrderByScheduledAtAsc(Long workspaceId, Long postId);

	/**
	 * Schedules of one workspace in one state (UC-15 step 6 archive: all PENDING
	 * schedules are listed before they are canceled).
	 */
	List<PublicationSchedule> findAllByWorkspaceIdAndStateOrderByScheduledAtAsc(Long workspaceId, String state);

	/**
	 * Schedules due now (UC-10 step 4): PENDING with the resolved UTC instant reached.
	 */
	@Query("SELECT * FROM publication_schedule WHERE state = 'PENDING' AND scheduled_at <= :now "
			+ "ORDER BY scheduled_at ASC")
	List<PublicationSchedule> findDuePending(@Param("now") OffsetDateTime now);

	/**
	 * Atomic state transition guarded by the current state. Returns 1 when the update
	 * matched, 0 when another actor won the race — only the winner proceeds.
	 */
	@Transactional
	@Modifying
	@Query("UPDATE publication_schedule SET state = :to, updated_at = :now "
			+ "WHERE id = :id AND workspace_id = :workspaceId AND state = :from")
	int transition(@Param("id") Long id, @Param("workspaceId") Long workspaceId, @Param("from") String from,
			@Param("to") String to, @Param("now") OffsetDateTime now);

	/**
	 * Claims a PENDING schedule for dispatch (UC-10 step 4): PENDING → CREATED with the
	 * created publication linked. Returns 1 for exactly one winner — at most one
	 * publication per schedule, even across concurrent workers (ADR-015).
	 */
	@Transactional
	@Modifying
	@Query("UPDATE publication_schedule SET state = 'CREATED', publication_id = :publicationId, updated_at = :now "
			+ "WHERE id = :id AND workspace_id = :workspaceId AND state = 'PENDING'")
	int claim(@Param("id") Long id, @Param("workspaceId") Long workspaceId, @Param("publicationId") Long publicationId,
			@Param("now") OffsetDateTime now);

}
