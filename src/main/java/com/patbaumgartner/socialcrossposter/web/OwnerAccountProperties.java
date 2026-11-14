package com.patbaumgartner.socialcrossposter.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Owner bootstrap configuration. Bound from the environment as APP_USER,
 * APP_PASSWORD_HASH and APP_BASE_URL (ADR-005, ADR-008). User and password hash are
 * deliberately not defaulted: missing values fail startup.
 */
@ConfigurationProperties(prefix = "app")
public record OwnerAccountProperties(String user, String passwordHash, String baseUrl) {

}
