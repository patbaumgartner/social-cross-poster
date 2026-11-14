package com.patbaumgartner.socialcrossposter.content;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One workspace-local draft container ("post") (UC-04, ADR-004, ADR-013). Holds the
 * editorial state, the pointer to the current immutable revision and an optimistic
 * version bumped on every save; content itself lives only in {@link PostRevision}.
 */
@Table("post")
public record Post(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("title") String title, @Column("state") String state,
		@Column("current_revision_id") Long currentRevisionId, @Column("version") long version,
		@Column("created_at") OffsetDateTime createdAt, @Column("updated_at") OffsetDateTime updatedAt) {

}
