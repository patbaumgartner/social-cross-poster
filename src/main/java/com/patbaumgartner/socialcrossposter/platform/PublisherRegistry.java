package com.patbaumgartner.socialcrossposter.platform;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

/**
 * Registry of the available {@link PlatformPublisher}s, keyed by platform (ADR-001). Only
 * platforms with a wired publisher can execute deliveries; the index is rebuilt on access
 * because publishers are stateless beans (including test overrides).
 */
@Component
public class PublisherRegistry {

	private final List<PlatformPublisher> publishers;

	public PublisherRegistry(List<PlatformPublisher> publishers) {
		this.publishers = List.copyOf(publishers);
	}

	public Map<String, PlatformPublisher> all() {
		Map<String, PlatformPublisher> byPlatform = new TreeMap<>();
		for (PlatformPublisher publisher : this.publishers) {
			byPlatform.put(publisher.platform(), publisher);
		}
		return byPlatform;
	}

	public Optional<PlatformPublisher> find(String platform) {
		return Optional.ofNullable(all().get(platform));
	}

}
