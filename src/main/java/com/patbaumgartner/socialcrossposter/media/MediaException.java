package com.patbaumgartner.socialcrossposter.media;

/** Fail-closed media error surfaced to the owner as a flash message. */
public class MediaException extends RuntimeException {

	public MediaException(String message) {
		super(message);
	}

}
