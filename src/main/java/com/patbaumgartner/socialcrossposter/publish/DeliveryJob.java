package com.patbaumgartner.socialcrossposter.publish;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One durable retry/reconcile job for a delivery (UC-07, ADR-015). Exactly one job per
 * delivery ({@code delivery_id} is UNIQUE). The worker claims due jobs by setting
 * {@code RUNNING} with a {@code leaseOwner}/{@code leaseUntil}; a crashed worker's jobs
 * are reclaimed once the lease expires. {@code nextRunAt} implements the bounded backoff
 * schedule. {@code state} is one of PENDING, RUNNING, SUCCEEDED, EXHAUSTED, CANCELED.
 */
@Table("delivery_job")
public record DeliveryJob(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("delivery_id") Long deliveryId, @Column("state") String state,
		@Column("next_run_at") OffsetDateTime nextRunAt, @Column("lease_owner") String leaseOwner,
		@Column("lease_until") OffsetDateTime leaseUntil, @Column("created_at") OffsetDateTime createdAt,
		@Column("updated_at") OffsetDateTime updatedAt) {

}
