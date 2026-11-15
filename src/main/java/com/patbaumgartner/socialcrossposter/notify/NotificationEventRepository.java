package com.patbaumgartner.socialcrossposter.notify;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

/**
 * Workspace-scoped access to {@link NotificationEvent} (ADR-013). The
 * {@code (workspace_id, event_key)} unique constraint is the deduplication backstop
 * (UC-12 step 2).
 */
public interface NotificationEventRepository extends CrudRepository<NotificationEvent, Long> {

	Optional<NotificationEvent> findByWorkspaceIdAndEventKey(Long workspaceId, String eventKey);

	List<NotificationEvent> findTop50ByWorkspaceIdOrderByIdDesc(Long workspaceId);

}
