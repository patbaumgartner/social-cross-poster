package com.patbaumgartner.socialcrossposter.publish;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One append-only attempt/stage record of a remote action (UC-08, ADR-015). Attempts
 * are never overwritten; {@code outcome} is SUCCESS, FAILED or UNKNOWN and
 * {@code detail} is redacted — it must never contain a credential or bearer URL
 * (ADR-007).
 */
@Table("remote_action_attempt")
public record RemoteActionAttempt(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("remote_action_id") Long remoteActionId, @Column("attempt_no") int attemptNo,
		@Column("stage") String stage, @Column("outcome") String outcome, @Column("detail") String detail,
		@Column("external_ref") String externalRef, @Column("created_at") OffsetDateTime createdAt) {

}
