package com.patbaumgartner.socialcrossposter.workspace;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A workspace is the logical tenant/account-set boundary of one installation (ADR-013).
 * The owner can open every workspace; services still verify resource ownership per
 * request. {@code languages} (csv of language tags) and {@code brandVoice} are the
 * workspace profile (Flyway V16, UC-15 third slice); {@code eventFacts} and
 * {@code eventLinks} (csv of http(s) URLs) are the protected event anchors (Flyway V17,
 * UC-15 fourth slice) — all nullable, NULL means unset.
 */
@Table("workspace")
public record Workspace(@Id @Column("id") Long id, @Column("slug") String slug, @Column("label") String label,
		@Column("color") String color, @Column("timezone") String timezone,
		@Column("created_at") OffsetDateTime createdAt, @Column("languages") String languages,
		@Column("brand_voice") String brandVoice, @Column("event_facts") String eventFacts,
		@Column("event_links") String eventLinks) {

	/**
	 * Convenience constructor for rows without a configured profile (V1-era rows and
	 * freshly created workspaces): languages/brand voice/event anchors are unset.
	 */
	public Workspace(Long id, String slug, String label, String color, String timezone, OffsetDateTime createdAt) {
		this(id, slug, label, color, timezone, createdAt, null, null, null, null);
	}

	/**
	 * Convenience constructor with the profile set but no event anchors (V16-era rows).
	 */
	public Workspace(Long id, String slug, String label, String color, String timezone, OffsetDateTime createdAt,
			String languages, String brandVoice) {
		this(id, slug, label, color, timezone, createdAt, languages, brandVoice, null, null);
	}

}
