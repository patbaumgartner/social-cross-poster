package com.patbaumgartner.socialcrossposter.content;

/**
 * Sanitized, user-presentable post/draft error (UC-04). Messages must never contain
 * another workspace's data — unknown/foreign ids fail closed with a generic message
 * (ADR-013).
 */
public class PostException extends RuntimeException {

	public PostException(String message) {
		super(message);
	}

}
