package com.patbaumgartner.socialcrossposter.publish;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One approved attached image snapshotted on a delivery (UC-13 second slice, ADR-008,
 * ADR-015): the content hash of the private {@code media_asset} plus the local alt text
 * (ADR-016), in the approved order. The dispatch path resolves the private source bytes
 * by hash and fails closed when a source is no longer available. The snapshot never
 * changes after approval, so retries send exactly the approved images.
 */
@Table("delivery_media")
public record DeliveryMedia(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("delivery_id") Long deliveryId, @Column("position") Integer position, @Column("sha256") String sha256,
		@Column("alt_text") String altText, @Column("created_at") OffsetDateTime createdAt) {

}
