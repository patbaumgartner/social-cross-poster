package com.patbaumgartner.socialcrossposter.publish;

/**
 * Sanitized, user-presentable publication error (UC-05). Messages must never contain
 * another workspace's data or any credential — unknown/foreign ids fail closed with a
 * generic message (ADR-013) and provider errors stay redacted (ADR-007).
 */
public class PublicationException extends RuntimeException {

	public PublicationException(String message) {
		super(message);
	}

}
