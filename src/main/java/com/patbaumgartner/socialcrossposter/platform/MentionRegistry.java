package com.patbaumgartner.socialcrossposter.platform;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

/**
 * Registry of verified {@link MentionContract}s keyed by platform (ADR-003 point 6). A
 * platform without a contract always resolves to the visible display-name fallback — no
 * invented mention entities (ADR-003 point 6). The index is rebuilt on access so test
 * overrides are reflected.
 */
@Component
public class MentionRegistry {

	private final List<MentionContract> contracts;

	public MentionRegistry(List<MentionContract> contracts) {
		this.contracts = List.copyOf(contracts);
	}

	public Map<String, MentionContract> all() {
		Map<String, MentionContract> byPlatform = new TreeMap<>();
		for (MentionContract contract : this.contracts) {
			String platform = contract.platform();
			if (platform == null || platform.isBlank()) {
				continue;
			}
			if (byPlatform.put(platform, contract) != null) {
				throw new IllegalStateException("Duplicate MentionContract for platform " + platform);
			}
		}
		return byPlatform;
	}

	public Optional<MentionContract> find(String platform) {
		return Optional.ofNullable(all().get(platform));
	}

	/**
	 * Resolves a candidate for a platform; platforms without a verified contract get the
	 * display-name fallback with an explicit note.
	 */
	public MentionResolution resolve(String platform, MentionCandidate candidate, String displayName) {
		return find(platform).map((contract) -> contract.resolve(candidate, displayName))
			.orElseGet(() -> MentionResolution.fallback(displayName, "No verified mention contract for platform '"
					+ platform
					+ "' yet; the preview shows the display name only and does not claim a notification (ADR-003)."));
	}

}
