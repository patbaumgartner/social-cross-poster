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
 * Webhook channel management (UC-12 second slice step 1, ADR-004, ADR-007, ADR-013). On
 * create, the URL is validated by the SSRF guard and a fresh random HMAC secret is
 * generated server-side and stored only sealed (AAD
 * {@code "<slug>:notify-webhook:<label>"}), so the plaintext secret is never persisted or
 * displayed. All lookups are workspace-scoped and fail closed for foreign ids (ADR-013).
 */
@Service
public class NotificationChannelService {

	private static final String PURPOSE = "notify-webhook";

	private final NotificationChannelRepository channels;

	private final NotificationDeliveryRepository deliveries;

	private final OutboundWebhookGuard guard;

	private final SecretCipher cipher;

	private final SecureRandom random = new SecureRandom();

	public NotificationChannelService(NotificationChannelRepository channels, NotificationDeliveryRepository deliveries,
			OutboundWebhookGuard guard, SecretCipher cipher) {
		this.channels = channels;
		this.deliveries = deliveries;
		this.guard = guard;
		this.cipher = cipher;
	}

	/**
	 * Creates a channel. The URL must pass the SSRF guard; the label must be unique
	 * within the workspace. Throws {@link NotificationException} with a sanitized message
	 * otherwise.
	 */
	@Transactional
	public NotificationChannel create(Long workspaceId, String workspaceSlug, String label, String url,
			String categories) {
		if (!StringUtils.hasText(label) || label.trim().length() > 80) {
			throw new NotificationException("Channel label is required (max 80 chars).");
		}
		String cleanLabel = label.trim();
		String cleanUrl = url == null ? "" : url.trim();
		this.guard.validate(cleanUrl); // throws NotificationException with a safe reason
		String cleanCategories = normalizeCategories(categories);
		if (this.channels.findByWorkspaceIdAndLabel(workspaceId, cleanLabel).isPresent()) {
			throw new NotificationException(
					"A channel with label '" + cleanLabel + "' already exists in this workspace.");
		}
		String secret = newSecret();
		SealedSecret sealed = this.cipher.seal(aad(workspaceSlug, cleanLabel), secret);
		return this.channels.save(new NotificationChannel(null, workspaceId, cleanLabel, cleanUrl, sealed.ciphertext(),
				sealed.nonce(), sealed.keyId(), cleanCategories, true, OffsetDateTime.now()));
	}

	/**
	 * Opens the sealed HMAC secret of a channel owned by the workspace (ADR-007 AAD
	 * check).
	 */
	public String openSecret(String workspaceSlug, NotificationChannel channel) {
		return this.cipher.open(aad(workspaceSlug, channel.label()),
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
				channel.categories(), !channel.enabled(), channel.createdAt()));
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

	static String aad(String workspaceSlug, String label) {
		return SecretCipher.associatedData(workspaceSlug, PURPOSE, label);
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
