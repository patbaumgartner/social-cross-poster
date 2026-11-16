package com.patbaumgartner.socialcrossposter.notify;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One external webhook channel of a workspace (UC-12 second slice, ADR-004, ADR-007,
 * ADR-013). The {@code url} is validated by the SSRF guard at save time and re-validated
 * at send time; the HMAC signing secret is stored only as a sealed ADR-007 ciphertext
 * bound to the AAD {@code "<slug>:notify-webhook:<label>"} — never in plaintext.
 * {@code categories} is a csv of event categories this channel receives; empty means all
 * categories. All access is workspace-scoped (ADR-013).
 */
@Table("notification_channel")
public record NotificationChannel(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("label") String label, @Column("url") String url, @Column("secret_cipher") String secretCipher,
		@Column("secret_nonce") String secretNonce, @Column("secret_key_id") String secretKeyId,
		@Column("categories") String categories, @Column("enabled") boolean enabled,
		@Column("created_at") OffsetDateTime createdAt) {

	/** The parsed category filter; an empty csv means "all categories". */
	public Set<String> categorySet() {
		if (this.categories == null || this.categories.isBlank()) {
			return Set.of();
		}
		return Arrays.stream(this.categories.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.collect(Collectors.toSet());
	}

	/** True when this channel is interested in {@code category} (empty filter = all). */
	public boolean interestedIn(String category) {
		Set<String> filter = categorySet();
		return filter.isEmpty() || filter.contains(category);
	}

}
