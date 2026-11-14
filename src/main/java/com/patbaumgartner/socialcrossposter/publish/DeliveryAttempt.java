package com.patbaumgartner.socialcrossposter.publish;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One append-only attempt/stage record of a delivery (ADR-015). Attempts are never
 * overwritten; {@code outcome} is SUCCESS, FAILED or UNKNOWN and {@code detail} is
 * redacted — it must never contain a credential or bearer URL (ADR-007).
 */
@Table("delivery_attempt")
public record DeliveryAttempt(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("delivery_id") Long deliveryId, @Column("attempt_no") int attemptNo, @Column("stage") String stage,
		@Column("outcome") String outcome, @Column("detail") String detail, @Column("external_ref") String externalRef,
		@Column("created_at") OffsetDateTime createdAt) {

}
