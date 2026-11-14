package com.patbaumgartner.socialcrossposter.contacts;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.patbaumgartner.socialcrossposter.platform.MentionCandidate;
import com.patbaumgartner.socialcrossposter.platform.MentionRegistry;
import com.patbaumgartner.socialcrossposter.platform.MentionResolution;
import com.patbaumgartner.socialcrossposter.platform.MentionStatus;

/**
 * Resolves neutral {@code {{contact:<uuid>}}} tokens in a text for one platform through
 * that platform's verified mention contract (UC-03 step 4, ADR-003 points 5-7). The same
 * resolver is used for previews and execution. Archived contacts still resolve — existing
 * approved snapshots must not change (UC-03 alternative flow). All lookups are
 * workspace-scoped; another workspace's token fails closed to UNKNOWN_TOKEN.
 */
@Component
public class MentionResolver {

	private final ContactRepository contacts;

	private final ContactHandleRepository handles;

	private final MentionRegistry mentionContracts;

	public MentionResolver(ContactRepository contacts, ContactHandleRepository handles,
			MentionRegistry mentionContracts) {
		this.contacts = contacts;
		this.handles = handles;
		this.mentionContracts = mentionContracts;
	}

	public List<MentionMatch> resolve(Long workspaceId, String text, String platform) {
		return MentionToken.find(text).stream().map((token) -> resolveOne(workspaceId, platform, token)).toList();
	}

	private MentionMatch resolveOne(Long workspaceId, String platform, MentionToken.TokenOccurrence token) {
		Optional<Contact> contact = this.contacts.findByTokenAndWorkspaceId(token.raw(), workspaceId);
		if (contact.isEmpty()) {
			return new MentionMatch(token.raw(), null, token.raw(), false, MentionStatus.UNKNOWN_TOKEN, token.raw(),
					"Unknown contact token in this workspace — flagged, never silently dropped (ADR-003).");
		}
		Contact c = contact.get();
		List<ContactHandle> platformHandles = this.handles.findAllByContactId(c.id())
			.stream()
			.filter((h) -> h.platform().equalsIgnoreCase(platform))
			.toList();
		String archivedNote = c.archived() ? " Contact is archived; existing snapshots keep this resolution." : "";
		if (platformHandles.size() > 1) {
			return new MentionMatch(token.raw(), c.id(), c.displayName(), c.archived(), MentionStatus.AMBIGUOUS,
					c.displayName(),
					"Multiple handles for platform '" + platform + "' on this contact; an explicit choice is "
							+ "required (UC-03) — the preview falls back to the display name." + archivedNote);
		}
		if (platformHandles.isEmpty()) {
			return new MentionMatch(token.raw(), c.id(), c.displayName(), c.archived(), MentionStatus.FALLBACK,
					c.displayName(),
					"No handle for platform '" + platform + "' on this contact; display-name fallback, no "
							+ "notification claimed (ADR-003)." + archivedNote);
		}
		ContactHandle handle = platformHandles.get(0);
		MentionResolution resolution = this.mentionContracts.resolve(platform, new MentionCandidate(handle.handle(),
				handle.host(), handle.identityKind(), handle.identityValue(), handle.verified()), c.displayName());
		return new MentionMatch(token.raw(), c.id(), c.displayName(), c.archived(), resolution.status(),
				resolution.text(), resolution.note() + archivedNote);
	}

}
