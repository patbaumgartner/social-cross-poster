package com.patbaumgartner.socialcrossposter.content;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One immutable versioned revision of a post (ADR-004, ADR-015): default text plus the
 * selected destinations and layered overrides stored in {@link RevisionDestination} /
 * {@link RevisionOverride}. Revisions are never updated in place — every save creates
 * the next revision number, so approved snapshots stay stable.
 */
@Table("post_revision")
public record PostRevision(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("post_id") Long postId, @Column("revision_no") int revisionNo,
		@Column("default_text") String defaultText, @Column("created_at") OffsetDateTime createdAt) {

}
