package com.patbaumgartner.socialcrossposter.platform;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * UC-14 opt-in flag and feasibility records (ADR-010), bound from the environment under
 * {@code app.experimental}. Experimental targets (personal WhatsApp Status, Signal) are
 * disabled by default: the core release has no experimental bridge dependency.
 *
 * <p>
 * {@code feasibility} maps an experimental platform key to the dated ADR-010 feasibility
 * record that proves the exact operation/audience with an authorized test identity (set
 * via e.g. {@code APP_EXPERIMENTAL_FEASIBILITY_SIGNAL="2026-11-17 …"}). It is empty by
 * default — no bridge has passed the probe — and {@link ExperimentalPlatforms} validates
 * every entry at startup and fails closed on excluded, non-experimental or blank records.
 * A record alone does not open the gate: the target also needs a wired bridge adapter.
 */
@ConfigurationProperties(prefix = "app.experimental")
public record ExperimentalProperties(Boolean enabled, Map<String, String> feasibility) {

	public ExperimentalProperties {
		if (enabled == null) {
			enabled = false;
		}
		if (feasibility == null) {
			feasibility = Map.of();
		}
	}

}
