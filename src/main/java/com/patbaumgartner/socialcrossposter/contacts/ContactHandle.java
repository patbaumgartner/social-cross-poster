package com.patbaumgartner.socialcrossposter.contacts;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One platform-specific identity of a contact (UC-03 step 2, ADR-003 point 5). The raw
 * {@code handle} stays distinct from the resolved {@code identityValue}; {@code host}
 * carries the instance for federated platforms ('' = none). {@code verified} is only true
 * after a provider-verified resolution — manual entry never sets it.
 */
@Table("contact_handle")
public record ContactHandle(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("contact_id") Long contactId, @Column("platform") String platform, @Column("handle") String handle,
		@Column("host") String host, @Column("identity_kind") String identityKind,
		@Column("identity_value") String identityValue, @Column("verified") boolean verified,
		@Column("created_at") OffsetDateTime createdAt, @Column("updated_at") OffsetDateTime updatedAt) {

}
