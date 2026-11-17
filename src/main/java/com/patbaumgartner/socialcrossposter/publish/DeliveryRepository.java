package com.patbaumgartner.socialcrossposter.publish;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DeliveryRepository extends CrudRepository<Delivery, Long> {

	Optional<Delivery> findByIdAndWorkspaceId(Long id, Long workspaceId);

	List<Delivery> findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(Long workspaceId, Long publicationId);

	List<Delivery> findAllByWorkspaceId(Long workspaceId);

	/**
	 * UC-15 step 6: ids of the workspace's unresolved deliveries (active/UNKNOWN states,
	 * not yet acknowledged at archive time), oldest first.
	 */
	@Query("SELECT id FROM delivery WHERE workspace_id = :workspaceId AND state IN (:states) "
			+ "AND acknowledged_at IS NULL ORDER BY id ASC")
	List<Long> findUnacknowledgedIds(@Param("workspaceId") Long workspaceId, @Param("states") List<String> states);

	/**
	 * UC-15 step 6: marks all unresolved (active/UNKNOWN) deliveries of one workspace as
	 * acknowledged at archive time — the owner explicitly accepts their unresolved
	 * effects; the rows and their history are retained. Returns the number of
	 * acknowledged rows.
	 */
	@Transactional
	@Modifying
	@Query("UPDATE delivery SET acknowledged_at = :now, updated_at = :now "
			+ "WHERE workspace_id = :workspaceId AND state IN (:states) AND acknowledged_at IS NULL")
	int acknowledgeUnresolved(@Param("workspaceId") Long workspaceId, @Param("states") List<String> states,
			@Param("now") OffsetDateTime now);

}
