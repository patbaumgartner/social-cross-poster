package com.patbaumgartner.socialcrossposter.platform;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Telegram mention contract (ADR-003 point 6): Telegram natively resolves
 * {@code @username} in message text (Bot API creates a mention link), so a contact handle
 * that is a valid Telegram username is a native mention. Any other identity kind (DID,
 * URN, URL) is not a Telegram-native identity and falls back to the display name. The
 * contract uses exactly the entered handle — it never invents an identifier.
 */
@Component
public class TelegramMentionContract implements MentionContract {

	public static final String PLATFORM = "telegram";

	/** Telegram usernames: 5-32 chars, letters/digits/underscore, no leading @ here. */
	private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{5,32}");

	@Override
	public String platform() {
		return PLATFORM;
	}

	@Override
	public MentionResolution resolve(MentionCandidate candidate, String displayName) {
		String kind = candidate.identityKind() == null ? "none" : candidate.identityKind();
		String handle = candidate.handle() == null ? "" : candidate.handle().trim();
		String username = handle.startsWith("@") ? handle.substring(1) : handle;
		boolean usernameIdentity = "none".equals(kind) || "handle".equals(kind);
		if (usernameIdentity && USERNAME.matcher(username).matches()) {
			return new MentionResolution(MentionStatus.NATIVE, "@" + username,
					"Telegram resolves @username natively (Bot API mention link).");
		}
		return MentionResolution.fallback(displayName,
				"This handle is not a Telegram username, so no native mention is possible; "
						+ "the preview shows the display name only and no notification is claimed (ADR-003).");
	}

}
