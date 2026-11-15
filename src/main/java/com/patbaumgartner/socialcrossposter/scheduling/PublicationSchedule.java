package com.patbaumgartner.socialcrossposter.scheduling;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One approved one-time publication schedule (UC-10, ADR-004, ADR-009, ADR-013, ADR-016).
 * The approved {@code revisionId} and the semantic {@code payloadHash} are frozen at
 * approval; {@code localDateTime} + {@code timezone} + {@code scheduledAt} persist the
 * owner's explicit time choice including the resolved UTC instant (DST gaps are rejected,
 * overlaps require an explicit offset — never shifted automatically, ADR-016).
 * {@code state} moves PENDING → CREATED (exactly one publication via the conditional
 * claim), or PENDING → CANCELED / EXPIRED / NEEDS_REVIEW. {@code publicationId} links the
 * created publication once dispatched.
 */
@Table("publication_schedule")
public record PublicationSchedule(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("post_id") Long postId, @Column("revision_id") Long revisionId,
		@Column("submission_key") String submissionKey, @Column("payload_hash") String payloadHash,
		@Column("local_date_time") LocalDateTime localDateTime, @Column("timezone") String timezone,
		@Column("scheduled_at") OffsetDateTime scheduledAt, @Column("state") String state,
		@Column("publication_id") Long publicationId, @Column("created_at") OffsetDateTime createdAt,
		@Column("updated_at") OffsetDateTime updatedAt) {

}
