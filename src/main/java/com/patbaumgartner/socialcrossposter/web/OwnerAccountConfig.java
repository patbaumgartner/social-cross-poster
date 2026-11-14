package com.patbaumgartner.socialcrossposter.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.util.StringUtils;

/**
 * Single owner login (ADR-005). Credentials come from the environment only; when they are
 * missing the application fails closed at startup and there are no default credentials.
 * The stored value is an adaptive password hash (for example a {@code {bcrypt}...} hash)
 * matched through a delegating password encoder.
 */
@Configuration
@EnableConfigurationProperties(OwnerAccountProperties.class)
public class OwnerAccountConfig {

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	UserDetailsManager userDetailsManager(OwnerAccountProperties properties) {
		String username = properties.user();
		String passwordHash = properties.passwordHash();
		if (!StringUtils.hasText(username) || !StringUtils.hasText(passwordHash)) {
			throw new IllegalStateException("Owner credentials are not configured: set APP_USER and "
					+ "APP_PASSWORD_HASH (adaptive hash, e.g. {bcrypt}...). Startup fails closed without "
					+ "them; there are no default credentials (ADR-005).");
		}
		UserDetails owner = User.withUsername(username).password(passwordHash).roles("OWNER").build();
		return new InMemoryUserDetailsManager(owner);
	}

}
