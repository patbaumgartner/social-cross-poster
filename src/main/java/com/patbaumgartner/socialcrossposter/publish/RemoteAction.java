package com.patbaumgartner.socialcrossposter.publish;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One verified remote edit/delete action on a published destination (UC-08, ADR-015).
 * A remote action is a SEPARATE record: it never replaces or rewrites the original
 * delivery or publication, and a provider rejection never turns a formerly SUCCESS
 * delivery into FAILED. {@code payloadText} holds the replacement text for EDIT
 * (NULL for DELETE) and is immutable once set; {@code externalRef} references the
 * remote post and is only stored when the original delivery confirmed it.
 * {@code state} is one of PENDING, RUNNING, SUCCESS, FAILED, UNKNOWN, CANCELED.
 */
@Table("remote_action")
public record RemoteAction(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("delivery_id") Long deliveryId, @Column("action") String action, @Column("state") String state,
		@Column("payload_text") String payloadText, @Column("payload_hash") String payloadHash,
		@Column("external_ref") String externalRef, @Column("created_at") OffsetDateTime createdAt,
		@Column("updated_at") OffsetDateTime updatedAt) {

}
