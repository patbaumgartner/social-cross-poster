package com.patbaumgartner.socialcrossposter.notify;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One external notification channel of a workspace (UC-12 second + third slices, ADR-004,
 * ADR-007, ADR-013). {@code channelType} is {@code WEBHOOK} or {@code SMTP}.
 *
 * <p>
 * WEBHOOK: the {@code url} is validated by the SSRF guard at save time and re-validated
 * at send time; the HMAC signing secret is stored only as a sealed ADR-007 ciphertext
 * bound to the AAD {@code "<slug>:notify-webhook:<label>"} — never in plaintext.
 *
 * <p>
 * SMTP (Flyway V13): {@code url} stays NULL and the {@code smtp_*} columns carry the
 * target; port/TLS mapping is enforced by {@link OutboundSmtpGuard} (25=NONE allow-listed
 * only, 465=IMPLICIT_TLS, 587=STARTTLS). The sealed {@code secret_*} columns hold the
 * SMTP password bound to the AAD {@code "<slug>:notify-smtp:<label>"}.
 *
 * <p>
 * {@code categories} is a csv of event categories this channel receives; empty means all
 * categories. All access is workspace-scoped (ADR-013).
 */
@Table("notification_channel")
public record NotificationChannel(@Id @Column("id") Long id, @Column("workspace_id") Long workspaceId,
		@Column("label") String label, @Column("url") String url, @Column("secret_cipher") String secretCipher,
		@Column("secret_nonce") String secretNonce, @Column("secret_key_id") String secretKeyId,
		@Column("categories") String categories, @Column("enabled") boolean enabled,
		@Column("created_at") OffsetDateTime createdAt, @Column("channel_type") String channelType,
		@Column("smtp_host") String smtpHost, @Column("smtp_port") Integer smtpPort,
		@Column("smtp_tls_mode") String smtpTlsMode, @Column("smtp_from") String smtpFrom,
		@Column("smtp_to") String smtpTo, @Column("smtp_username") String smtpUsername) {

	public static final String TYPE_WEBHOOK = "WEBHOOK";

	public static final String TYPE_SMTP = "SMTP";

	public boolean isSmtp() {
		return TYPE_SMTP.equals(this.channelType);
	}

	/** Human-readable target for the dashboard (webhook URL or host:port (tls)). */
	public String target() {
		if (isSmtp()) {
			return this.smtpHost + ":" + this.smtpPort + " (" + this.smtpTlsMode + ")";
		}
		return this.url == null ? "" : this.url;
	}

	/** The parsed recipient list of an SMTP channel (csv). */
	public List<String> recipients() {
		if (this.smtpTo == null || this.smtpTo.isBlank()) {
			return List.of();
		}
		return Arrays.stream(this.smtpTo.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
	}

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
