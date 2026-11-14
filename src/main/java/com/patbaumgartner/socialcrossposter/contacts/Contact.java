package com.patbaumgartner.socialcrossposter.contacts;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One workspace-local contact (UC-03, ADR-003 point 5, ADR-013). {@code token} is the
 * neutral local mention token ({@code {{contact:<uuid>}}) that draft text stores;
 * platform adapters resolve it through the contact's handles. {@code notes} are
 * private: they are never sent to providers or to AI by default. {@code photo} is a
 * data: URI only — the app never fetches external image URLs (ADR-008).
 */
@Table("contact")
public record Contact(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("token") String token, @Column("display_name") String displayName, @Column("photo") String photo,
		@Column("notes") String notes, @Column("archived") boolean archived,
		@Column("created_at") OffsetDateTime createdAt, @Column("updated_at") OffsetDateTime updatedAt) {

}
