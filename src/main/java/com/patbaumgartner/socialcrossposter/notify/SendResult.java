package com.patbaumgartner.socialcrossposter.notify;

/**
 * Outcome of one external notification send attempt (UC-12 step 4), shared by the webhook
 * and SMTP senders. {@code detail} is always redacted — a status class or guard reason,
 * never a response body, banner or URL with credentials.
 */
public record SendResult(String outcome, String detail) {

	public static SendResult sent() {
		return new SendResult("SENT", "delivered (2xx/250)");
	}

	public static SendResult permanent(String detail) {
		return new SendResult("FAILED_PERMANENT", detail);
	}

	public static SendResult retryable(String detail) {
		return new SendResult("FAILED_RETRYABLE", detail);
	}

	public static SendResult blocked(String detail) {
		return new SendResult("BLOCKED", detail);
	}

}
