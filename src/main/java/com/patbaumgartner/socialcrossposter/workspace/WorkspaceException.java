package com.patbaumgartner.socialcrossposter.workspace;

/**
 * UC-15 workspace lifecycle: sanitized, user-presentable validation failure for workspace
 * create/update operations (ADR-013). Never carries secrets (ADR-007).
 */
public class WorkspaceException extends RuntimeException {

	public WorkspaceException(String message) {
		super(message);
	}

}
