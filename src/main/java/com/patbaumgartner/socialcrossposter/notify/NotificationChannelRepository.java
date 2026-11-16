package com.patbaumgartner.socialcrossposter.notify;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

/**
 * Workspace-scoped access to {@link NotificationChannel} (ADR-013). The
 * {@code (workspace_id, label)} unique constraint is the label-dedup backstop.
 */
public interface NotificationChannelRepository extends CrudRepository<NotificationChannel, Long> {

	Optional<NotificationChannel> findByIdAndWorkspaceId(Long id, Long workspaceId);

	Optional<NotificationChannel> findByWorkspaceIdAndLabel(Long workspaceId, String label);

	List<NotificationChannel> findAllByWorkspaceIdOrderByIdAsc(Long workspaceId);

	/**
	 * Only enabled channels are considered when enqueueing outbox rows (UC-12 step 2).
	 */
	List<NotificationChannel> findAllByWorkspaceIdAndEnabledTrueOrderByIdAsc(Long workspaceId);

}
