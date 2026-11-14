package com.patbaumgartner.socialcrossposter.accounts;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the versioned secret-encryption key from the environment (ADR-007). Missing or
 * malformed key material fails startup closed in {@link SecretCipher}.
 */
@Configuration
@EnableConfigurationProperties(SecretsProperties.class)
public class SecretsConfig {

}
