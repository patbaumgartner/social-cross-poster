package com.patbaumgartner.socialcrossposter.platform;

/**
 * Sanitized probe failure: the message must never contain the credential, provider
 * response bodies, or tokens (ADR-007 redaction rule).
 */
public class ProbeException extends RuntimeException {

	public ProbeException(String message) {
		super(message);
	}

	public ProbeException(String message, Throwable cause) {
		super(message, cause);
	}

}
