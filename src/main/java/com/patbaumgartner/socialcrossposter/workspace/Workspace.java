package com.patbaumgartner.socialcrossposter.workspace;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A workspace is the logical tenant/account-set boundary of one installation (ADR-013).
 * The owner can open every workspace; services still verify resource ownership per
 * request.
 */
@Table("workspace")
public record Workspace(@Id @Column("id") Long id, @Column("slug") String slug, @Column("label") String label,
		@Column("color") String color, @Column("timezone") String timezone,
		@Column("created_at") OffsetDateTime createdAt) {

}
