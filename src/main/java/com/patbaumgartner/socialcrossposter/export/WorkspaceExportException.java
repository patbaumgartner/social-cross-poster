package com.patbaumgartner.socialcrossposter.export;

/**
 * UC-15 step 5 (secret-free export): sanitized, user-presentable failure when the export
 * archive cannot be built (ADR-007). Never carries secrets or internal details.
 */
public class WorkspaceExportException extends RuntimeException {

	public WorkspaceExportException(String message) {
		super(message);
	}

	public WorkspaceExportException(String message, Throwable cause) {
		super(message, cause);
	}

}
