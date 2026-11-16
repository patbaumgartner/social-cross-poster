package com.patbaumgartner.socialcrossposter.notify;

import java.time.OffsetDateTime;

/**
 * The redacted notification payload (UC-12 step 3), shared by the webhook JSON body and
 * the SMTP mail body: event id/key, workspace id/label, category, redacted summary, the
 * optional publication/delivery/schedule ids and the occurrence time — never draft text,
 * tokens, media or raw provider bodies.
 */
public record NotificationPayload(Long eventId, String eventKey, Long workspaceId, String workspaceLabel,
		String category, String summary, Long publicationId, Long deliveryId, Long scheduleId,
		OffsetDateTime occurredAt) {

	/** Serializes the payload to compact JSON with proper escaping. */
	public String toJson() {
		StringBuilder json = new StringBuilder(256);
		json.append('{');
		field(json, "eventId", this.eventId).append(',');
		field(json, "eventKey", this.eventKey).append(',');
		field(json, "workspaceId", this.workspaceId).append(',');
		field(json, "workspaceLabel", this.workspaceLabel).append(',');
		field(json, "category", this.category).append(',');
		field(json, "summary", this.summary).append(',');
		field(json, "publicationId", this.publicationId).append(',');
		field(json, "deliveryId", this.deliveryId).append(',');
		field(json, "scheduleId", this.scheduleId).append(',');
		field(json, "occurredAt", this.occurredAt == null ? null : this.occurredAt.toString());
		json.append('}');
		return json.toString();
	}

	private static StringBuilder field(StringBuilder json, String name, Object value) {
		json.append('"').append(name).append("\":");
		if (value == null) {
			json.append("null");
		}
		else if (value instanceof Number) {
			json.append(value);
		}
		else {
			json.append('"').append(escape(String.valueOf(value))).append('"');
		}
		return json;
	}

	private static String escape(String s) {
		StringBuilder out = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					}
					else {
						out.append(c);
					}
				}
			}
		}
		return out.toString();
	}

}
