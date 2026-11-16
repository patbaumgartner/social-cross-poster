package com.patbaumgartner.socialcrossposter.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * UC-14 opt-in flag (ADR-010), bound from the environment under {@code app.experimental}.
 * Experimental targets (personal WhatsApp Status, Signal) are disabled by default: the
 * core release has no experimental bridge dependency. Enabling the flag only lists the
 * targets with their permanent label — a target still stays unavailable until its bridge
 * passes the ADR-010 feasibility probe (see {@link ExperimentalPlatforms}).
 */
@ConfigurationProperties(prefix = "app.experimental")
public record ExperimentalProperties(Boolean enabled) {

	public ExperimentalProperties {
		if (enabled == null) {
			enabled = false;
		}
	}

}
