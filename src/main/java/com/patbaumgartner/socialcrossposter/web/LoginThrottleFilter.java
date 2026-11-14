package com.patbaumgartner.socialcrossposter.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects login POSTs while the username/IP pair is throttled, before credentials are
 * checked (ADR-005).
 */
@Component
public class LoginThrottleFilter extends OncePerRequestFilter {

	private final LoginAttemptService attempts;

	public LoginThrottleFilter(LoginAttemptService attempts) {
		this.attempts = attempts;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		if ("POST".equalsIgnoreCase(request.getMethod()) && "/login".equals(path)
				&& this.attempts.isBlocked(LoginAttemptService.key(request))) {
			response.sendRedirect("/login?locked");
			return;
		}
		chain.doFilter(request, response);
	}

}
