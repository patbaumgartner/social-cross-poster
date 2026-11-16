package com.patbaumgartner.socialcrossposter.notify;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One durable, deduplicated, redacted in-app notification event (UC-12, ADR-013,
 * ADR-015). {@code eventKey} is unique per workspace, so recording the same event twice
 * is a no-op. {@code summary} never contains draft text, images, prompts, tokens, raw
 * provider bodies or signed URLs (UC-12 alternative flow 4). The optional
 * publication/delivery/schedule/connection links let the dashboard link to the
 * authenticated detail view (UC-12 step 3).
 */
@Table("notification_event")
public record NotificationEvent(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("event_key") String eventKey, @Column("category") String category,
		@Column("publication_id") Long publicationId, @Column("delivery_id") Long deliveryId,
		@Column("schedule_id") Long scheduleId, @Column("connection_id") Long connectionId,
		@Column("summary") String summary, @Column("created_at") OffsetDateTime createdAt) {

}
