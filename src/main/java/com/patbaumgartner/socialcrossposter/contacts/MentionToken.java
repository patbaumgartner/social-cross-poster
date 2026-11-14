package com.patbaumgartner.socialcrossposter.contacts;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Neutral local mention token format {@code {{contact:<uuid>}}} (ADR-003 point 5). Draft
 * text stores only this token; adapters resolve it through the contact's platform handles
 * at preview/execution time, so a contact can be renamed or re-identified without
 * touching existing drafts.
 */
public final class MentionToken {

	public static final String PREFIX = "{{contact:";

	public static final String SUFFIX = "}}";

	private static final Pattern PATTERN = Pattern.compile("\\{\\{contact:([0-9a-fA-F-]{36})}}");

	private MentionToken() {
	}

	public static String format(UUID uuid) {
		return PREFIX + uuid + SUFFIX;
	}

	/** All token occurrences in {@code text}, in order of appearance. */
	public static List<TokenOccurrence> find(String text) {
		List<TokenOccurrence> occurrences = new ArrayList<>();
		if (text == null) {
			return occurrences;
		}
		Matcher matcher = PATTERN.matcher(text);
		while (matcher.find()) {
			occurrences.add(new TokenOccurrence(matcher.group(0), matcher.group(1), matcher.start(), matcher.end()));
		}
		return occurrences;
	}

	/** One occurrence of a token in a text. */
	public record TokenOccurrence(String raw, String uuid, int start, int end) {

	}

}
