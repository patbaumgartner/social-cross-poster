package com.patbaumgartner.socialcrossposter.scheduling;

/**
 * Sanitized, user-presentable scheduling error (UC-10). Messages must never contain
 * another workspace's data or any credential — unknown/foreign ids fail closed with a
 * generic message (ADR-013).
 */
public class ScheduleException extends RuntimeException {

	public ScheduleException(String message) {
		super(message);
	}

}
