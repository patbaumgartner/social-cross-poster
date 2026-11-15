package com.patbaumgartner.socialcrossposter.platform;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

/**
 * Registry of the available {@link PlatformReconciler}s, keyed by platform (ADR-001),
 * mirroring {@link PublisherRegistry}. The index is rebuilt on access because reconcilers
 * are stateless beans (including test overrides).
 */
@Component
public class ReconcilerRegistry {

	private final List<PlatformReconciler> reconcilers;

	public ReconcilerRegistry(List<PlatformReconciler> reconcilers) {
		this.reconcilers = List.copyOf(reconcilers);
	}

	public Map<String, PlatformReconciler> all() {
		Map<String, PlatformReconciler> byPlatform = new TreeMap<>();
		for (PlatformReconciler reconciler : this.reconcilers) {
			byPlatform.put(reconciler.platform(), reconciler);
		}
		return byPlatform;
	}

	public Optional<PlatformReconciler> find(String platform) {
		return Optional.ofNullable(all().get(platform));
	}

}
