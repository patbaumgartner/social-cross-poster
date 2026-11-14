package com.patbaumgartner.socialcrossposter.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

/**
 * In-memory login throttling (ADR-005): after {@value #MAX_ATTEMPTS} failed attempts for
 * one username/IP pair, further login attempts are rejected for {@value #LOCK_DURATION}.
 * State is per process and resets on restart.
 */
@Component
public class LoginAttemptService {

	static final int MAX_ATTEMPTS = 5;

	static final Duration LOCK_DURATION = Duration.ofMinutes(15);

	private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

	private record Attempt(int failures, Instant lockedUntil) {
	}

	public static String key(HttpServletRequest request) {
		String username = request.getParameter("username");
		return (username != null ? username : "") + "@" + request.getRemoteAddr();
	}

	public boolean isBlocked(String key) {
		Attempt attempt = this.attempts.get(key);
		if (attempt == null) {
			return false;
		}
		Instant lockedUntil = attempt.lockedUntil();
		if (lockedUntil == null) {
			return false;
		}
		if (Instant.now().isBefore(lockedUntil)) {
			return true;
		}
		this.attempts.remove(key, attempt);
		return false;
	}

	public void loginFailed(String key) {
		this.attempts.compute(key, (k, current) -> {
			int failures = (current == null ? 0 : current.failures()) + 1;
			Instant lockedUntil = failures >= MAX_ATTEMPTS ? Instant.now().plus(LOCK_DURATION)
					: (current == null ? null : current.lockedUntil());
			return new Attempt(failures, lockedUntil);
		});
	}

	public void loginSucceeded(String key) {
		this.attempts.remove(key);
	}

	/** Clears all throttle state (used by tests). */
	public void reset() {
		this.attempts.clear();
	}

}
