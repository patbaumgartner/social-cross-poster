package com.patbaumgartner.socialcrossposter.content;

import java.util.List;

import com.patbaumgartner.socialcrossposter.accounts.AccountDestination;
import com.patbaumgartner.socialcrossposter.contacts.MentionMatch;

/**
 * One destination's resolved preview for a revision (UC-04, ADR-003 points 1-3). The
 * effective text is resolved by precedence destination override → platform override →
 * default text; {@code null} override means inherit, empty string means explicit clear
 * and surfaces as a blocking validation issue. Mentions are resolved through the same
 * {@code MentionResolver} used for execution, the counter uses the platform's
 * {@code TextRules}, and an over-limit text is a blocker — the preview never truncates
 * (ADR-003 point 3).
 */
public record DestinationPreview(AccountDestination destination, String effectiveText, int textLength,
		int maxTextLength, boolean overLimit, List<MentionMatch> mentions, List<String> blockers) {

}
