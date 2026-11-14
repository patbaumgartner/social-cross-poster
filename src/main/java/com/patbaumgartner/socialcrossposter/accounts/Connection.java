package com.patbaumgartner.socialcrossposter.accounts;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One provider authorization (principal) inside one workspace (ADR-006, ADR-013). The
 * secret is stored only as ciphertext/nonce/key-id (ADR-007) and is never exposed through
 * web models.
 */
@Table("connection")
public record Connection(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("platform") String platform, @Column("mode") String mode,
		@Column("provider_principal") String providerPrincipal, @Column("label") String label,
		@Column("secret_cipher") String secretCipher, @Column("secret_nonce") String secretNonce,
		@Column("secret_key_id") String secretKeyId, @Column("health_state") String healthState,
		@Column("last_health_at") OffsetDateTime lastHealthAt, @Column("enabled") boolean enabled,
		@Column("created_at") OffsetDateTime createdAt, @Column("updated_at") OffsetDateTime updatedAt) {

}
