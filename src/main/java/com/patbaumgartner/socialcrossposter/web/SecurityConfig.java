package com.patbaumgartner.socialcrossposter.web;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security baseline (ADR-005): form login at {@code /login}, CSRF on all browser
 * mutations, session rotation at login, logout invalidation, and no long-lived
 * remember-me. Public routes are only the login page and static assets; management stays
 * on the separate loopback listener.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	/**
	 * Management endpoints live on the separate loopback-only listener (port 8081,
	 * ADR-005) and are not behind the owner login; the listener is never published
	 * publicly by the Compose baseline.
	 */
	@Bean
	@Order(1)
	SecurityFilterChain managementSecurityFilterChain(HttpSecurity http) throws Exception {
		http.securityMatcher(EndpointRequest.toAnyEndpoint())
			.authorizeHttpRequests((auth) -> auth.anyRequest().permitAll());
		return http.build();
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, LoginThrottleFilter loginThrottleFilter,
			LoginAttemptService loginAttempts) throws Exception {
		http.authorizeHttpRequests((auth) -> auth.requestMatchers("/login", "/assets/**", "/favicon.ico", "/error")
			.permitAll()
			.anyRequest()
			.authenticated())
			.formLogin((form) -> form.loginPage("/login")
				.loginProcessingUrl("/login")
				.successHandler((request, response, authentication) -> {
					loginAttempts.loginSucceeded(LoginAttemptService.key(request));
					response.sendRedirect("/");
				})
				.failureHandler((request, response, exception) -> {
					loginAttempts.loginFailed(LoginAttemptService.key(request));
					response.sendRedirect("/login?error");
				})
				.permitAll())
			.logout((logout) -> logout.logoutUrl("/logout")
				.logoutSuccessHandler((request, response, authentication) -> response.sendRedirect("/login?logout"))
				.invalidateHttpSession(true)
				.deleteCookies("JSESSIONID")
				.permitAll())
			.sessionManagement((session) -> session.sessionFixation().migrateSession())
			.csrf(Customizer.withDefaults())
			.headers((headers) -> headers.contentSecurityPolicy((csp) -> csp
				.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
						+ "connect-src 'self'; font-src 'self'; base-uri 'self'; form-action 'self'; "
						+ "frame-ancestors 'none'")))
			.addFilterBefore(loginThrottleFilter, UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

}
