package com.patbaumgartner.socialcrossposter.platform;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

/**
 * Registry of {@link TextRules} keyed by platform (UC-04, ADR-003 point 2). Platforms
 * without a wired rule set fall back to a conservative default limit instead of being
 * unlimited — an unknown limit is never treated as "no limit".
 */
@Component
public class TextRulesRegistry {

	/**
	 * Conservative fallback when a platform has no wired rules yet (UC-04 first slice).
	 */
	public static final int DEFAULT_MAX_TEXT_LENGTH = 4096;

	private final List<TextRules> rules;

	public TextRulesRegistry(List<TextRules> rules) {
		this.rules = List.copyOf(rules);
	}

	public Map<String, TextRules> all() {
		Map<String, TextRules> byPlatform = new TreeMap<>();
		for (TextRules rule : this.rules) {
			String platform = rule.platform();
			if (platform == null || platform.isBlank()) {
				continue;
			}
			if (byPlatform.put(platform, rule) != null) {
				throw new IllegalStateException("Duplicate TextRules for platform " + platform);
			}
		}
		return byPlatform;
	}

	public TextRules find(String platform) {
		return findOrDefault(platform);
	}

	public TextRules findOrDefault(String platform) {
		return Optional.ofNullable(all().get(platform)).orElseGet(() -> new TextRules() {
			@Override
			public String platform() {
				return platform;
			}

			@Override
			public int maxTextLength() {
				return DEFAULT_MAX_TEXT_LENGTH;
			}
		});
	}

}
