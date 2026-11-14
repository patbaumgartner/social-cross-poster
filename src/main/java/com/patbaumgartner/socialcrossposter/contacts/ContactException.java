package com.patbaumgartner.socialcrossposter.contacts;

/**
 * Sanitized, user-presentable contacts error (UC-03). Messages must never contain
 * secrets, raw provider responses or another workspace's data — unknown/foreign ids fail
 * closed with a generic message (ADR-013).
 */
public class ContactException extends RuntimeException {

	public ContactException(String message) {
		super(message);
	}

}
