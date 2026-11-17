package com.patbaumgartner.socialcrossposter.archive;

/**
 * UC-15 step 6 (archive): sanitized, user-presentable failure when the archive/reopen
 * lifecycle cannot proceed (unknown workspace, already archived, not archived). Never
 * carries internal details.
 */
public class WorkspaceArchiveException extends RuntimeException {

	public WorkspaceArchiveException(String message) {
		super(message);
	}

	public WorkspaceArchiveException(String message, Throwable cause) {
		super(message, cause);
	}

}
