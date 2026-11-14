package com.patbaumgartner.socialcrossposter.accounts;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Versioned encryption-key configuration for workspace secrets (ADR-007). Bound from the
 * environment as APP_SECRETS_KEY (base64, 32 bytes for AES-256) and APP_SECRETS_KEY_ID
 * (default "v1"). The key itself never lives in the database; a missing or malformed key
 * fails startup closed.
 */
@ConfigurationProperties(prefix = "app.secrets")
public record SecretsProperties(String key, String keyId) {

}
