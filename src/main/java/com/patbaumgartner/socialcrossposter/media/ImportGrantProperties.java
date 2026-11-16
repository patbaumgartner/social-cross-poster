package com.patbaumgartner.socialcrossposter.media;

import java.util.Base64;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Short-lived revocable import-grant configuration (UC-13 fourth slice, ADR-008), bound
 * from the environment under {@code app.import-grants}. The grant origin is the
 * installation's configured {@code app.base-url} (ADR-008) and the HMAC signing key is
 * the versioned secrets key ({@code app.secrets.key}, ADR-007) — both are wired in
 * {@code application.yml}, never hard-coded. A missing signing key fails startup closed.
 * The TTL is bounded ({@code ttlMinutes}, default 30) and fetches per grant are
 * rate-limited ({@code maxFetchesPerWindow} per {@code windowSeconds}) so a leaked grant
 * URL is a bounded bearer capability, not an open endpoint.
 */
@ConfigurationProperties(prefix = "app.import-grants")
public record ImportGrantProperties(String baseUrl, String signingKey, String signingKeyId, Integer ttlMinutes,
		Integer maxFetchesPerWindow, Integer windowSeconds) {

	public ImportGrantProperties {
		if (!StringUtils.hasText(baseUrl)) {
			baseUrl = "http://localhost:8080";
		}
		if (!StringUtils.hasText(signingKey)) {
			throw new IllegalStateException(
					"app.import-grants.signing-key is required — bind it to app.secrets.key (ADR-007).");
		}
		if (!StringUtils.hasText(signingKeyId)) {
			signingKeyId = "v1";
		}
		if (ttlMinutes == null || ttlMinutes <= 0) {
			ttlMinutes = 30;
		}
		if (maxFetchesPerWindow == null || maxFetchesPerWindow <= 0) {
			maxFetchesPerWindow = 10;
		}
		if (windowSeconds == null || windowSeconds <= 0) {
			windowSeconds = 600;
		}
	}

	byte[] signingKeyBytes() {
		return Base64.getDecoder().decode(this.signingKey);
	}

}
