package com.patbaumgartner.socialcrossposter.media;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints, verifies, rate-limits and revokes short-lived import grants (UC-13 fourth slice,
 * ADR-008 steps 3 and 5). A grant is a signed URL
 * {@code <base-url>/media/grants/{id}?exp=<epoch>&sig=<hmac>} whose HMAC-SHA256 signature
 * binds key id, workspace, delivery, sha256, purpose and expiry, so a grant can never be
 * replayed against another workspace, delivery, image, purpose or time. Only the SHA-256
 * hash of the signature is persisted — the signed URL is a bearer capability that is
 * returned exactly once at mint time and never logged or stored (ADR-007). Grants are
 * minted near actual dispatch; a fresh mint for a delivery first revokes that delivery's
 * still-active grants (renewal of the same approved bytes only), and a confirmed provider
 * import retires all of the delivery's grants. Fetches per grant are rate-limited to a
 * bounded sliding window. All lookups fail closed.
 */
@Service
public class ImportGrantService {

	/** Grant purpose for platform source-fetch imports (ADR-008 step 3). */
	public static final String PURPOSE_PLATFORM_IMPORT = "platform-import";

	/** Outcome of one grant verification at the public fetch route. */
	public enum Access {

		VALID, UNKNOWN, EXPIRED, REVOKED, RATE_LIMITED

	}

	/** A freshly minted grant: the signed URL leaves exactly once, here. */
	public record MintedGrant(Long grantId, String url, OffsetDateTime expiresAt) {

	}

	/** Verification result: {@link Access#VALID} carries the grant row. */
	public record GrantAccess(Access access, MediaImportGrant grant) {

	}

	private final MediaImportGrantRepository grants;

	private final MediaService media;

	private final ImportGrantProperties props;

	private final Map<Long, Deque<Long>> fetchWindows = new ConcurrentHashMap<>();

	public ImportGrantService(MediaImportGrantRepository grants, MediaService media, ImportGrantProperties props) {
		this.grants = grants;
		this.media = media;
		this.props = props;
	}

	/**
	 * Mints one grant for the exact approved derivative of a delivery. Fail-closed: an
	 * unknown hash for this workspace throws {@link MediaException}. Still-active grants
	 * of the same delivery are revoked first — a retry renews only the same approved
	 * bytes (ADR-008 alternative flow "expired grant").
	 */
	@Transactional
	public MintedGrant mint(Long workspaceId, Long deliveryId, String sha256, String purpose, OffsetDateTime now) {
		if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
			throw new MediaException("Invalid content hash.");
		}
		if (this.media.findBySha(workspaceId, sha256).isEmpty()) {
			throw new MediaException("Unknown media asset for this workspace.");
		}
		revokeActiveForDelivery(workspaceId, deliveryId, now);
		OffsetDateTime expiresAt = now.plusMinutes(this.props.ttlMinutes());
		long expEpoch = expiresAt.toEpochSecond();
		String sig = sign(workspaceId, deliveryId, sha256, purpose, expEpoch, this.props.signingKeyId());
		MediaImportGrant saved = this.grants.save(new MediaImportGrant(null, workspaceId, deliveryId, sha256, purpose,
				this.props.signingKeyId(), sha256Hex(sig), expiresAt, null, now));
		String url = this.props.baseUrl() + "/media/grants/" + saved.id() + "?exp=" + expEpoch + "&sig=" + sig;
		return new MintedGrant(saved.id(), url, expiresAt);
	}

	/**
	 * Verifies one fetch of a grant URL. The signature is checked in constant time
	 * against the stored grant's bound fields; expiry, revocation, continued asset
	 * existence and the per-grant rate limit are enforced afterwards. Unknown/invalid
	 * signatures are indistinguishable ({@link Access#UNKNOWN}) so the route leaks
	 * nothing (ADR-013).
	 */
	public GrantAccess resolve(Long grantId, long expEpoch, String sig, OffsetDateTime now) {
		if (grantId == null || sig == null || sig.isBlank()) {
			return new GrantAccess(Access.UNKNOWN, null);
		}
		Optional<MediaImportGrant> opt = this.grants.findById(grantId);
		if (opt.isEmpty()) {
			return new GrantAccess(Access.UNKNOWN, null);
		}
		MediaImportGrant grant = opt.get();
		String expected = sign(grant.workspaceId(), grant.deliveryId(), grant.sha256(), grant.purpose(), expEpoch,
				grant.keyId());
		if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8))) {
			return new GrantAccess(Access.UNKNOWN, null);
		}
		if (grant.revokedAt() != null) {
			return new GrantAccess(Access.REVOKED, grant);
		}
		if (expEpoch != grant.expiresAt().toEpochSecond() || !now.isBefore(grant.expiresAt())) {
			return new GrantAccess(Access.EXPIRED, grant);
		}
		if (this.media.findBySha(grant.workspaceId(), grant.sha256()).isEmpty()) {
			return new GrantAccess(Access.UNKNOWN, null);
		}
		if (!allowFetch(grant.id(), now)) {
			return new GrantAccess(Access.RATE_LIMITED, grant);
		}
		return new GrantAccess(Access.VALID, grant);
	}

	/** Revokes one grant (workspace-scoped, fail-closed on foreign ids). */
	@Transactional
	public void revoke(Long workspaceId, Long grantId, OffsetDateTime now) {
		this.grants.findByIdAndWorkspaceId(grantId, workspaceId).ifPresent((grant) -> {
			if (grant.revokedAt() == null) {
				this.grants.save(withRevoked(grant, now));
			}
		});
	}

	/**
	 * Revokes all still-active grants of one delivery in the workspace — called when the
	 * provider confirmed the import (ADR-008 step 5) and when a retry supersedes them.
	 * Returns the number revoked.
	 */
	@Transactional
	public int revokeActiveForDelivery(Long workspaceId, Long deliveryId, OffsetDateTime now) {
		int revoked = 0;
		for (MediaImportGrant grant : this.grants.findAllByDeliveryIdOrderByIdAsc(deliveryId)) {
			if (grant.workspaceId().equals(workspaceId) && grant.revokedAt() == null) {
				this.grants.save(withRevoked(grant, now));
				revoked++;
			}
		}
		return revoked;
	}

	/** Grants of one delivery in the workspace (inspection/tests). */
	public List<MediaImportGrant> grantsOfDelivery(Long workspaceId, Long deliveryId) {
		return this.grants.findAllByDeliveryIdOrderByIdAsc(deliveryId)
			.stream()
			.filter((g) -> g.workspaceId().equals(workspaceId))
			.toList();
	}

	private static MediaImportGrant withRevoked(MediaImportGrant grant, OffsetDateTime now) {
		return new MediaImportGrant(grant.id(), grant.workspaceId(), grant.deliveryId(), grant.sha256(),
				grant.purpose(), grant.keyId(), grant.tokenHash(), grant.expiresAt(), now, grant.createdAt());
	}

	/**
	 * HMAC-SHA256 over {@code keyId|workspaceId|deliveryId|sha256|purpose|expEpoch} with
	 * the versioned env key (ADR-007). The key id is part of the signed payload, so a
	 * signature cannot be replayed under a rotated key id.
	 */
	private String sign(Long workspaceId, Long deliveryId, String sha256, String purpose, long expEpoch, String keyId) {
		String payload = keyId + "|" + workspaceId + "|" + deliveryId + "|" + sha256 + "|" + purpose + "|" + expEpoch;
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(this.props.signingKeyBytes(), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA256 not available", ex);
		}
	}

	/** Bounded sliding-window rate limit per grant id. */
	private boolean allowFetch(Long grantId, OffsetDateTime now) {
		Deque<Long> window = this.fetchWindows.computeIfAbsent(grantId, (key) -> new ArrayDeque<>());
		synchronized (window) {
			long cutoff = now.minusSeconds(this.props.windowSeconds()).toEpochSecond();
			while (!window.isEmpty() && window.peekFirst() < cutoff) {
				window.pollFirst();
			}
			if (window.size() >= this.props.maxFetchesPerWindow()) {
				return false;
			}
			window.addLast(now.toEpochSecond());
			return true;
		}
	}

	private static String sha256Hex(String text) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}

}
