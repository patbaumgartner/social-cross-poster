package com.patbaumgartner.socialcrossposter.notify;

/**
 * Fail-closed notification error surfaced to the owner as a sanitized flash message
 * (ADR-013): messages are safe to show and never contain another workspace's data,
 * credentials or response bodies.
 */
public class NotificationException extends RuntimeException {

	public NotificationException(String message) {
		super(message);
	}

	public NotificationException(String message, Throwable cause) {
		super(message, cause);
	}

}
