package com.patbaumgartner.socialcrossposter.media;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One short-lived revocable import grant (UC-13 fourth slice, ADR-008 steps 3/5): a
 * signed, expiring {@code /media/grants/{id}?exp=…&sig=…} URL for exactly one approved
 * derivative, bound to workspace, delivery, sha256, purpose, expiry and signing key id.
 * Only the SHA-256 hash of the signature is stored ({@code tokenHash}) — the signed URL
 * itself is a bearer capability, is shown to no one after minting and is never logged or
 * persisted (ADR-007). {@code revokedAt} retires the grant before expiry when the
 * provider confirmed the import or a retry superseded it.
 */
@Table("media_import_grant")
public record MediaImportGrant(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("delivery_id") Long deliveryId, @Column("sha256") String sha256, @Column("purpose") String purpose,
		@Column("key_id") String keyId, @Column("token_hash") String tokenHash,
		@Column("expires_at") OffsetDateTime expiresAt, @Column("revoked_at") OffsetDateTime revokedAt,
		@Column("created_at") OffsetDateTime createdAt) {

}
