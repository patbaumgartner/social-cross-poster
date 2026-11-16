package com.patbaumgartner.socialcrossposter.notify;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Workspace-scoped access to the notification outbox (UC-12 second slice, ADR-013,
 * ADR-015). The {@code (event_id, channel_id)} unique constraint is the idempotency
 * backstop: the same event is never queued twice for the same channel.
 */
public interface NotificationDeliveryRepository extends CrudRepository<NotificationDelivery, Long> {

	Optional<NotificationDelivery> findByEventIdAndChannelId(Long eventId, Long channelId);

	List<NotificationDelivery> findAllByEventIdOrderByIdAsc(Long eventId);

	List<NotificationDelivery> findAllByChannelIdOrderByIdDesc(Long channelId);

	/**
	 * Outbox rows due now for one workspace: PENDING with the backoff instant reached.
	 */
	@Query("SELECT * FROM notification_delivery WHERE workspace_id = :workspaceId AND state = 'PENDING' "
			+ "AND next_attempt_at <= :now ORDER BY next_attempt_at ASC")
	List<NotificationDelivery> findDue(@Param("workspaceId") Long workspaceId, @Param("now") OffsetDateTime now);

}
