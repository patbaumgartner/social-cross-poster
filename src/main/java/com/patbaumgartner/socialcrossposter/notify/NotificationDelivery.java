package com.patbaumgartner.socialcrossposter.notify;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One deduplicated outbox row per (event, channel) (UC-12 second slice, ADR-013,
 * ADR-015). State is PENDING|SENT|FAILED; {@code attempts} counts total delivery attempts
 * against the bounded budget (UC-12 step 4: at most three). {@code lastDetail} is
 * redacted — a status class or guard reason, never a response body or URL with
 * credentials. Delivery failures never create new events, so notification delivery cannot
 * recurse (UC-12 alternative flow 3).
 */
@Table("notification_delivery")
public record NotificationDelivery(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("event_id") Long eventId, @Column("channel_id") Long channelId, @Column("state") String state,
		@Column("attempts") int attempts, @Column("last_detail") String lastDetail,
		@Column("next_attempt_at") OffsetDateTime nextAttemptAt, @Column("created_at") OffsetDateTime createdAt,
		@Column("updated_at") OffsetDateTime updatedAt) {

}
