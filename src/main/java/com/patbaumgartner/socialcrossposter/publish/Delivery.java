package com.patbaumgartner.socialcrossposter.publish;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One destination's delivery inside a publication (UC-05, ADR-015). The row snapshots the
 * destination identity and the approved, mention-resolved {@code payloadText} — both
 * immutable after approval. {@code state} follows the ADR-015 delivery states;
 * {@code externalRef} is only set when the provider confirmed the write.
 */
@Table("delivery")
public record Delivery(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("publication_id") Long publicationId, @Column("account_id") Long accountId,
		@Column("platform") String platform, @Column("destination_label") String destinationLabel,
		@Column("host") String host, @Column("provider_account_id") String providerAccountId,
		@Column("payload_text") String payloadText, @Column("payload_hash") String payloadHash,
		@Column("state") String state, @Column("external_ref") String externalRef,
		@Column("created_at") OffsetDateTime createdAt, @Column("updated_at") OffsetDateTime updatedAt) {

}
