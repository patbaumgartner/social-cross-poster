package com.patbaumgartner.socialcrossposter.platform;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * UC-14 experimental-target gate (ADR-010). Personal WhatsApp Status and Signal are
 * optional, unofficial experiments behind the {@code app.experimental.enabled} opt-in
 * flag. WhatsApp Business is permanently excluded: no Cloud API, Business onboarding,
 * Business messaging, Business OAuth or Business credential type, and an experimental
 * failure never falls back to Business messaging.
 *
 * <p>
 * No bridge has passed the ADR-010 feasibility probe yet, so {@link #FEASIBILITY_PASSED}
 * is empty and every experimental target reports unavailable — no REST endpoint, pairing
 * wizard or successful Status/story posting is promised or fabricated. When a bridge is
 * accepted, its platform key is recorded here together with the dated feasibility record
 * required by docs/platforms/README.md, and only then does the gate open for that key.
 * Signal message and story stay separate capabilities (a later slice); a missing story
 * capability blocks a story target instead of falling back to a chat message.
 */
@Component
public class ExperimentalPlatforms {

	/**
	 * Permanent label shown on setup, destination selection, confirmation and history.
	 */
	public static final String LABEL = "experimental / unofficial";

	/** Experimental candidate platform keys (docs/platforms/README.md catalog). */
	private static final Set<String> EXPERIMENTAL = Set.of("whatsapp-status", "signal");

	/**
	 * Platform keys whose bridge passed the ADR-010 feasibility probe. Empty as of
	 * 2026-11-17 — no bridge is selected or support certified (ADR-010 status).
	 */
	private static final Set<String> FEASIBILITY_PASSED = Set.of();

	private final ExperimentalProperties properties;

	public ExperimentalPlatforms(ExperimentalProperties properties) {
		this.properties = properties;
	}

	/** Whether the experimental opt-in profile is enabled at all. */
	public boolean enabled() {
		return this.properties.enabled();
	}

	/** Experimental candidate keys, sorted, for listing on the accounts page. */
	public static List<String> experimentalKeys() {
		return EXPERIMENTAL.stream().sorted().toList();
	}

	public static boolean isExperimental(String platform) {
		return platform != null && EXPERIMENTAL.contains(normalize(platform));
	}

	/**
	 * WhatsApp Business variants are never supported (ADR-010): the exact key and any
	 * {@code whatsapp*business*} spelling are rejected, so no Business credential mode
	 * can ever be offered as a substitute.
	 */
	public static boolean isExcluded(String platform) {
		String p = normalize(platform);
		return p.equals("whatsapp-business") || (p.startsWith("whatsapp") && p.contains("business"));
	}

	/** Whether a dated ADR-010 feasibility record passed for this platform key. */
	public boolean feasibilityPassed(String platform) {
		return FEASIBILITY_PASSED.contains(normalize(platform));
	}

	/**
	 * Availability view for one experimental target: never available without a passed
	 * probe.
	 */
	public Availability availability(String platform) {
		String p = normalize(platform);
		if (isExcluded(p)) {
			return new Availability(p, false, "permanently excluded — WhatsApp Business is not supported (ADR-010)");
		}
		if (!isExperimental(p)) {
			return new Availability(p, false, "not an experimental target");
		}
		if (!enabled()) {
			return new Availability(p, false, "experimental targets are disabled (APP_EXPERIMENTAL_ENABLED=false)");
		}
		if (!feasibilityPassed(p)) {
			return new Availability(p, false,
					"unavailable — no bridge has passed the ADR-010 feasibility probe, so no pairing endpoint is offered");
		}
		return new Availability(p, true, "feasibility record passed — opt in through a locally protected setup path");
	}

	/**
	 * Fail-closed gate for connect/destination operations: excluded platforms are always
	 * rejected, experimental targets only pass when the opt-in flag is on <em>and</em> a
	 * feasibility record exists. Official platforms pass through unchanged. Messages are
	 * sanitized and never contain credential material (ADR-007).
	 */
	public void requireConnectable(String platform) {
		String p = normalize(platform);
		if (isExcluded(p)) {
			throw new ProbeException(
					"WhatsApp Business is not supported (ADR-010): no Cloud API, Business onboarding, Business messaging or Business credentials. Personal WhatsApp Status is the only WhatsApp candidate.");
		}
		if (isExperimental(p)) {
			if (!enabled()) {
				throw new ProbeException(
						"Experimental target '" + p + "' is disabled (APP_EXPERIMENTAL_ENABLED=false).");
			}
			if (!feasibilityPassed(p)) {
				throw new ProbeException("Experimental target '" + p
						+ "' is unavailable: no bridge has passed the ADR-010 feasibility probe, so no pairing endpoint is offered.");
			}
		}
	}

	private static String normalize(String platform) {
		return platform == null ? "" : platform.trim().toLowerCase();
	}

	/** Accounts-page view of one experimental target. */
	public record Availability(String platform, boolean available, String reason) {
	}

}
