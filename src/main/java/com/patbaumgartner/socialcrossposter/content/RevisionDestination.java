package com.patbaumgartner.socialcrossposter.content;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One destination (account) selected for one revision (UC-04 step 3, ADR-004). The
 * account id is validated against the workspace in the service layer before insert
 * (ADR-013); several accounts of the same platform stay separate rows.
 */
@Table("revision_destination")
public record RevisionDestination(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("revision_id") Long revisionId, @Column("account_id") Long accountId) {

}
