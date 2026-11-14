package com.patbaumgartner.socialcrossposter.content;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One layered text override of a revision (ADR-003 point 1). {@code target} is
 * {@code platform:<platformKey>} or {@code destination:<accountId>}. A {@code null}
 * text means the override is absent (inherit from the next layer); an empty string is
 * an explicit clear, which is validated (blocking) instead of silently inherited.
 */
@Table("revision_override")
public record RevisionOverride(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("revision_id") Long revisionId, @Column("target") String target, @Column("text") String text,
		@Column("updated_at") OffsetDateTime updatedAt) {

	/** Override target key for a whole platform, e.g. {@code platform:telegram}. */
	public static String platformTarget(String platform) {
		return "platform:" + platform;
	}

	/** Override target key for one destination, e.g. {@code destination:42}. */
	public static String destinationTarget(Long accountId) {
		return "destination:" + accountId;
	}

}
