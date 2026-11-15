package com.patbaumgartner.socialcrossposter.platform;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

/**
 * Registry of the available {@link PlatformRemoteActor}s, keyed by platform (ADR-001),
 * mirroring {@link PublisherRegistry}. Platforms without a wired actor expose no
 * supported actions, so edit/delete stay disabled with an explanation (UC-08 step 1). The
 * index is rebuilt on access because actors are stateless beans (including test
 * overrides).
 */
@Component
public class RemoteActorRegistry {

	private final List<PlatformRemoteActor> actors;

	public RemoteActorRegistry(List<PlatformRemoteActor> actors) {
		this.actors = List.copyOf(actors);
	}

	public Map<String, PlatformRemoteActor> all() {
		Map<String, PlatformRemoteActor> byPlatform = new TreeMap<>();
		for (PlatformRemoteActor actor : this.actors) {
			byPlatform.put(actor.platform(), actor);
		}
		return byPlatform;
	}

	public Optional<PlatformRemoteActor> find(String platform) {
		return Optional.ofNullable(all().get(platform));
	}

}
