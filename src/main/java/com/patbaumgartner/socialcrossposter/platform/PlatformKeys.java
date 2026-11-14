package com.patbaumgartner.socialcrossposter.platform;

import java.util.Set;

/**
 * Platform keys of the integration catalog (docs/platforms/README.md). Contacts may
 * carry handles for platforms that are not connected yet, so this list is broader
 * than the wired {@link PlatformProbe}s.
 */
public final class PlatformKeys {

	private PlatformKeys() {
	}

	/** Catalog platforms, lowercase keys, matching docs/platforms/README.md. */
	public static final Set<String> ALL = Set.of("bluesky", "mastodon", "telegram", "linkedin", "x", "instagram",
			"threads", "facebook", "whatsapp-status", "signal");

}

