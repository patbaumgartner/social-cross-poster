package com.patbaumgartner.socialcrossposter.notify;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.patbaumgartner.socialcrossposter.accounts.SecretCipher;
import com.patbaumgartner.socialcrossposter.accounts.SecretCipher.SealedSecret;

/**
 * External notification channel management (UC-12 second + third slices, step 1, ADR-004,
 * ADR-007, ADR-013). Webhook channels: the URL is validated by the SSRF guard and a fresh
 * random HMAC secret is generated server-side and stored only sealed (AAD
 * {@code "<slug>:notify-webhook:<label>"}). SMTP channels: host/port/TLS are validated by
 * {@link OutboundSmtpGuard} and the owner-supplied password is stored only sealed (AAD
 * {@code "<slug>:notify-smtp:<label>"}), so no plaintext credential is ever persisted or
 * displayed. All lookups are workspace-scoped and fail closed for foreign ids (ADR-013).
 */
@Service
public class NotificationChannelService {

	private static final String PURPOSE_WEBHOOK = "notify-webhook";

	private static final String PURPOSE_SMTP = "notify-smtp";

	private final NotificationChannelRepository channels;

	private final NotificationDeliveryRepository deliveries;

	private final OutboundWebhookGuard webhookGuard;

	private final OutboundSmtpGuard smtpGuard;

	private final SecretCipher cipher;

	private final SecureRandom random = new SecureRandom();

	public NotificationChannelService(NotificationChannelRepository channels, NotificationDeliveryRepository deliveries,
			OutboundWebhookGuard webhookGuard, OutboundSmtpGuard smtpGuard, SecretCipher cipher) {
		this.channels = channels;
		this.deliveries = deliveries;
		this.webhookGuard = webhookGuard;
		this.smtpGuard = smtpGuard;
		this.cipher = cipher;
	}

	/**
	 * Creates a webhook channel. The URL must pass the SSRF guard; the label must be
	 * unique within the workspace. Throws {@link NotificationException} with a sanitized
	 * message otherwise.
	 */
	@Transactional
	public NotificationChannel create(Long workspaceId, String workspaceSlug, String label, String url,
			String categories) {
		String cleanLabel = requireLabel(label);
		String cleanUrl = url == null ? "" : url.trim();
		this.webhookGuard.validate(cleanUrl); // throws NotificationException with a safe
												// reason
		String cleanCategories = normalizeCategories(categories);
		requireLabelAvailable(workspaceId, cleanLabel);
		String secret = newSecret();
		SealedSecret sealed = this.cipher.seal(aad(workspaceSlug, PURPOSE_WEBHOOK, cleanLabel), secret);
		return this.channels.save(new NotificationChannel(null, workspaceId, cleanLabel, cleanUrl, sealed.ciphertext(),
				sealed.nonce(), sealed.keyId(), cleanCategories, true, OffsetDateTime.now(),
				NotificationChannel.TYPE_WEBHOOK, null, null, null, null, null, null));
	}

	/**
	 * Creates an SMTP channel. The host/port/TLS combination must pass the SSRF guard
	 * (canonical mapping 25=NONE allow-listed only, 465=IMPLICIT_TLS, 587=STARTTLS); from
	 * and to must be valid ASCII addresses (header-injection safe); the password is
	 * required when a username is given and is stored only sealed (ADR-007).
	 */
	@Transactional
	public NotificationChannel createSmtp(Long workspaceId, String workspaceSlug, String label, String host,
			String portRaw, String tlsMode, String from, String to, String username, String password,
			String categories) {
		String cleanLabel = requireLabel(label);
		int port;
		try {
			port = Integer.parseInt(portRaw == null ? "" : portRaw.trim());
		}
		catch (NumberFormatException ex) {
			throw new NotificationException("SMTP port must be a number between 1 and 65535.");
		}
		String cleanTlsMode = this.smtpGuard.validate(host, port, tlsMode); // safe
																			// reason,
																			// normalized
		String cleanFrom = cleanAddress(from, "SMTP from address");
		List<String> recipientList = Arrays.stream(to == null ? new String[0] : to.split(","))
			.map(String::trim)
			.filter(StringUtils::hasText)
			.map(a -> cleanAddress(a, "SMTP to address"))
			.toList();
		if (recipientList.isEmpty()) {
			throw new NotificationException("SMTP to address is required (csv of recipients).");
		}
		String cleanTo = String.join(",", recipientList);
		if (cleanTo.length() > 1024) {
			throw new NotificationException("SMTP recipient list is too long (max 1024 chars).");
		}
		String cleanUsername = username == null ? "" : username.trim();
		if (cleanUsername.length() > 255 || containsControl(cleanUsername)) {
			throw new NotificationException("SMTP username is invalid (max 255 chars, no control characters).");
		}
		if (!cleanUsername.isEmpty() && !StringUtils.hasText(password)) {
			throw new NotificationException("SMTP password is required when a username is given.");
		}
		String cleanPassword = password == null ? "" : password;
		String cleanCategories = normalizeCategories(categories);
		requireLabelAvailable(workspaceId, cleanLabel);
		SealedSecret sealed = this.cipher.seal(aad(workspaceSlug, PURPOSE_SMTP, cleanLabel), cleanPassword);
		return this.channels.save(new NotificationChannel(null, workspaceId, cleanLabel, null, sealed.ciphertext(),
				sealed.nonce(), sealed.keyId(), cleanCategories, true, OffsetDateTime.now(),
				NotificationChannel.TYPE_SMTP, host.trim(), port, cleanTlsMode, cleanFrom, cleanTo,
				cleanUsername.isEmpty() ? null : cleanUsername));
	}

	/**
	 * Opens the sealed credential of a channel owned by the workspace (ADR-007 AAD
	 * check). The AAD purpose is type-aware: {@code notify-webhook} for webhook channels,
	 * {@code notify-smtp} for SMTP channels — a ciphertext can never be opened under the
	 * other type or another workspace slug.
	 */
	public String openSecret(String workspaceSlug, NotificationChannel channel) {
		String purpose = channel.isSmtp() ? PURPOSE_SMTP : PURPOSE_WEBHOOK;
		return this.cipher.open(aad(workspaceSlug, purpose, channel.label()),
				new SealedSecret(channel.secretCipher(), channel.secretNonce(), channel.secretKeyId()));
	}

	public List<NotificationChannel> channelsOf(Long workspaceId) {
		return this.channels.findAllByWorkspaceIdOrderByIdAsc(workspaceId);
	}

	/** Workspace-scoped lookup that fails closed for foreign or unknown ids (ADR-013). */
	public NotificationChannel requireById(Long workspaceId, Long channelId) {
		return this.channels.findByIdAndWorkspaceId(channelId, workspaceId)
			.orElseThrow(() -> new NotificationException("Unknown notification channel."));
	}

	@Transactional
	public NotificationChannel toggle(Long workspaceId, Long channelId) {
		NotificationChannel channel = requireById(workspaceId, channelId);
		return this.channels.save(new NotificationChannel(channel.id(), channel.workspaceId(), channel.label(),
				channel.url(), channel.secretCipher(), channel.secretNonce(), channel.secretKeyId(),
				channel.categories(), !channel.enabled(), channel.createdAt(), channel.channelType(),
				channel.smtpHost(), channel.smtpPort(), channel.smtpTlsMode(), channel.smtpFrom(), channel.smtpTo(),
				channel.smtpUsername()));
	}

	/** Deletes the channel and its outbox rows (FK-safe order). */
	@Transactional
	public void delete(Long workspaceId, Long channelId) {
		NotificationChannel channel = requireById(workspaceId, channelId);
		for (NotificationDelivery d : this.deliveries.findAllByChannelIdOrderByIdDesc(channelId)) {
			this.deliveries.delete(d);
		}
		this.channels.delete(channel);
	}

	static String aad(String workspaceSlug, String purpose, String label) {
		return SecretCipher.associatedData(workspaceSlug, purpose, label);
	}

	private String requireLabel(String label) {
		if (!StringUtils.hasText(label) || label.trim().length() > 80) {
			throw new NotificationException("Channel label is required (max 80 chars).");
		}
		return label.trim();
	}

	private void requireLabelAvailable(Long workspaceId, String cleanLabel) {
		if (this.channels.findByWorkspaceIdAndLabel(workspaceId, cleanLabel).isPresent()) {
			throw new NotificationException(
					"A channel with label '" + cleanLabel + "' already exists in this workspace.");
		}
	}

	/**
	 * Validates an email address: printable ASCII only, no control characters (header
	 * injection), non-empty local part and domain, max 320 chars.
	 */
	private static String cleanAddress(String raw, String fieldName) {
		String a = raw == null ? "" : raw.trim();
		int at = a.indexOf('@');
		if (a.isEmpty() || a.length() > 320 || at <= 0 || at == a.length() - 1 || containsControl(a)
				|| a.chars().anyMatch(c -> c < 0x21 || c > 0x7E)) {
			throw new NotificationException(fieldName + " must be a valid email address (printable ASCII only).");
		}
		return a;
	}

	private static boolean containsControl(String s) {
		return s.chars().anyMatch(c -> c < 0x20 || c == 0x7F);
	}

	private static String normalizeCategories(String categories) {
		if (!StringUtils.hasText(categories)) {
			return "";
		}
		return Arrays.stream(categories.split(","))
			.map(String::trim)
			.filter(StringUtils::hasText)
			.map(c -> c.toUpperCase(java.util.Locale.ROOT))
			.distinct()
			.collect(Collectors.joining(","));
	}

	private String newSecret() {
		byte[] bytes = new byte[32];
		this.random.nextBytes(bytes);
		StringBuilder hex = new StringBuilder(64);
		for (byte b : bytes) {
			hex.append(Character.forDigit((b >> 4) & 0xF, 16));
			hex.append(Character.forDigit(b & 0xF, 16));
		}
		return hex.toString();
	}

}
