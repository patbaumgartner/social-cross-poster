package com.patbaumgartner.socialcrossposter.accounts;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One selectable destination (profile/page/chat/channel) exposed by a workspace-local
 * connection (ADR-006, ADR-013). Several destinations of the same platform may coexist;
 * platform alone is never a uniqueness key.
 */
@Table("account")
public record AccountDestination(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("connection_id") Long connectionId, @Column("platform") String platform, @Column("kind") String kind,
		@Column("provider_account_id") String providerAccountId, @Column("host") String host,
		@Column("label") String label, @Column("enabled") boolean enabled, @Column("archived") boolean archived,
		@Column("created_at") OffsetDateTime createdAt, @Column("updated_at") OffsetDateTime updatedAt) {

}
