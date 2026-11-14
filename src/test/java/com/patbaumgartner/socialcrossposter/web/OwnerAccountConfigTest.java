package com.patbaumgartner.socialcrossposter.web;

import org.junit.jupiter.api.Test;

import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnerAccountConfigTest {

	private final OwnerAccountConfig config = new OwnerAccountConfig();

	@Test
	void missingOwnerCredentialsFailStartupClosed() {
		OwnerAccountProperties properties = new OwnerAccountProperties(null, null, "http://localhost:8080");
		assertThatThrownBy(() -> this.config.userDetailsManager(properties)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("APP_USER")
			.hasMessageContaining("APP_PASSWORD_HASH");
	}

	@Test
	void passwordHashIsAdaptiveAndMatchedByDelegatingEncoder() {
		PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		String hash = encoder.encode("test-password");
		assertThat(hash).startsWith("{bcrypt}");
		assertThat(encoder.matches("test-password", hash)).isTrue();
		assertThat(encoder.matches("other-password", hash)).isFalse();
	}

}
