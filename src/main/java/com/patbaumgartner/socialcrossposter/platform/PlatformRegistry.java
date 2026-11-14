package com.patbaumgartner.socialcrossposter.platform;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

/**
 * Registry of the available {@link PlatformProbe}s, keyed by platform (ADR-001). Only
 * platforms with a verified adapter are offered in the UI; the web layer lists exactly
 * these, so no unsupported credential mode is ever promised (ADR-006). The index is
 * rebuilt on access: probes are stateless beans, so this stays cheap and always reflects
 * the current beans (including test overrides).
 */
@Component
public class PlatformRegistry {

	private final List<PlatformProbe> probes;

	public PlatformRegistry(List<PlatformProbe> probes) {
		this.probes = List.copyOf(probes);
	}

	public Map<String, PlatformProbe> all() {
		Map<String, PlatformProbe> byPlatform = new TreeMap<>();
		for (PlatformProbe probe : this.probes) {
			String platform = probe.platform();
			if (platform == null || platform.isBlank()) {
				continue;
			}
			if (byPlatform.put(platform, probe) != null) {
				throw new IllegalStateException("Duplicate PlatformProbe for platform " + platform);
			}
		}
		return byPlatform;
	}

	public Optional<PlatformProbe> find(String platform) {
		return Optional.ofNullable(all().get(platform));
	}

}
