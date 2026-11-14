package com.patbaumgartner.socialcrossposter.platform;

import org.springframework.stereotype.Component;

/**
 * Telegram text rules (UC-04, ADR-003 point 2, docs/platforms/README.md): a Bot API
 * message text is limited to 4096 characters after entities are parsed; the limit is
 * counted in UTF-16 code units, which {@link String#length()} provides. The same rules
 * drive preview counting and blocking validation so they can never disagree.
 */
@Component
public class TelegramTextRules implements TextRules {

	public static final String PLATFORM = "telegram";

	/** Telegram Bot API message text limit (docs/platforms/README.md). */
	public static final int MAX_TEXT_LENGTH = 4096;

	@Override
	public String platform() {
		return PLATFORM;
	}

	@Override
	public int maxTextLength() {
		return MAX_TEXT_LENGTH;
	}

}
