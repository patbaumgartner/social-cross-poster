package com.patbaumgartner.socialcrossposter.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.patbaumgartner.socialcrossposter.accounts.AccountDestination;
import com.patbaumgartner.socialcrossposter.accounts.AccountRepository;
import com.patbaumgartner.socialcrossposter.contacts.MentionMatch;
import com.patbaumgartner.socialcrossposter.contacts.MentionResolver;
import com.patbaumgartner.socialcrossposter.platform.TextRules;
import com.patbaumgartner.socialcrossposter.platform.TextRulesRegistry;

/**
 * Builds per-destination previews for one revision (UC-04, ADR-003 points 1-3). Uses the
 * same deterministic adapters as execution: override precedence destination → platform →
 * default, mention resolution via {@link MentionResolver}, and the platform's
 * {@link TextRules} for the counter. Over-limit or explicitly cleared text becomes a
 * blocker — the preview never silently truncates, splits, or drops content (ADR-003 point
 * 3). All lookups are workspace-scoped and fail closed for foreign ids (ADR-013).
 */
@Service
public class PreviewService {

	private final AccountRepository accounts;

	private final TextRulesRegistry textRules;

	private final MentionResolver mentions;

	public PreviewService(AccountRepository accounts, TextRulesRegistry textRules, MentionResolver mentions) {
		this.accounts = accounts;
		this.textRules = textRules;
		this.mentions = mentions;
	}

	/**
	 * Resolves the effective text for one destination of a revision: destination override
	 * (NULL = inherit, "" = explicit clear) wins over the platform override, which wins
	 * over the revision default text.
	 */
	public String resolveText(Long workspaceId, PostRevision revision, Long accountId,
			Map<String, RevisionOverride> overridesByTarget) {
		String destinationTarget = RevisionOverride.destinationTarget(accountId);
		if (overridesByTarget.containsKey(destinationTarget)) {
			String text = overridesByTarget.get(destinationTarget).text();
			return text == null ? inherit(workspaceId, revision, accountId, overridesByTarget) : text;
		}
		return inherit(workspaceId, revision, accountId, overridesByTarget);
	}

	private String inherit(Long workspaceId, PostRevision revision, Long accountId,
			Map<String, RevisionOverride> overridesByTarget) {
		AccountDestination destination = this.accounts.findByIdAndWorkspaceId(accountId, workspaceId)
			.orElseThrow(() -> new PostException("Unknown destination for this workspace."));
		String platformTarget = RevisionOverride.platformTarget(destination.platform());
		if (overridesByTarget.containsKey(platformTarget)) {
			String text = overridesByTarget.get(platformTarget).text();
			return text == null ? revision.defaultText() : text;
		}
		return revision.defaultText();
	}

	/** Builds the full preview for one selected destination of one revision. */
	public DestinationPreview preview(Long workspaceId, PostRevision revision, Long accountId,
			List<RevisionOverride> overrides) {
		AccountDestination destination = this.accounts.findByIdAndWorkspaceId(accountId, workspaceId)
			.orElseThrow(() -> new PostException("Unknown destination for this workspace."));
		Map<String, RevisionOverride> byTarget = overrides.stream()
			.collect(Collectors.toMap(RevisionOverride::target, Function.identity(), (a, b) -> a));
		String effective = resolveText(workspaceId, revision, accountId, byTarget);
		TextRules rules = this.textRules.find(destination.platform());
		int length = rules.count(effective);
		int max = rules.maxTextLength();
		List<MentionMatch> mentionMatches = this.mentions.resolve(workspaceId, effective, destination.platform());
		List<String> blockers = new ArrayList<>();
		if (length > max) {
			blockers.add("Text is " + length + " characters but " + destination.platform() + " allows at most " + max
					+ " — shorten or edit the override; the preview never truncates (ADR-003).");
		}
		String destinationTarget = RevisionOverride.destinationTarget(accountId);
		if (byTarget.containsKey(destinationTarget) && byTarget.get(destinationTarget).text() != null
				&& byTarget.get(destinationTarget).text().isEmpty()) {
			blockers.add("Destination override explicitly clears the text; a destination with empty text is "
					+ "blocked until it gets content (ADR-003 point 1).");
		}
		String platformTarget = RevisionOverride.platformTarget(destination.platform());
		if (byTarget.containsKey(platformTarget) && byTarget.get(platformTarget).text() != null
				&& byTarget.get(platformTarget).text().isEmpty() && !byTarget.containsKey(destinationTarget)) {
			blockers.add("Platform override explicitly clears the text for " + destination.platform()
					+ "; this destination inherits the empty value and is blocked (ADR-003 point 1).");
		}
		return new DestinationPreview(destination, effective, length, max, length > max, mentionMatches, blockers);
	}

	/** Builds previews for every selected destination of a revision. */
	public List<DestinationPreview> previews(Long workspaceId, PostRevision revision,
			List<RevisionDestination> destinations, List<RevisionOverride> overrides) {
		return destinations.stream().map((d) -> preview(workspaceId, revision, d.accountId(), overrides)).toList();
	}

}
