package com.patbaumgartner.socialcrossposter.publish;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One approved publication of a post revision to the selected destinations (UC-05,
 * ADR-004, ADR-013, ADR-015). {@code submissionKey} is the workspace-scoped idempotency
 * key: resubmitting the same key returns this publication; different content under the
 * same key is rejected. {@code payloadHash} binds the approval to the exact approved
 * payloads; {@code state} is the ADR-015 aggregate derived from the deliveries.
 */
@Table("publication")
public record Publication(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("post_id") Long postId, @Column("revision_id") Long revisionId,
		@Column("submission_key") String submissionKey, @Column("state") String state,
		@Column("payload_hash") String payloadHash, @Column("created_at") OffsetDateTime createdAt,
		@Column("updated_at") OffsetDateTime updatedAt) {

}
