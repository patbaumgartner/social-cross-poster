package com.patbaumgartner.socialcrossposter.platform;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * UC-14 experimental-target gate (ADR-010). Personal WhatsApp Status and Signal are
 * optional, unofficial experiments behind the {@code app.experimental.enabled} opt-in
 * flag. WhatsApp Business is permanently excluded: no Cloud API, Business onboarding,
 * Business messaging, Business OAuth or Business credential type, and an experimental
 * failure never falls back to Business messaging.
 *
 * <p>
 * Feasibility records come from configuration ({@code app.experimental.feasibility},
 * empty by default — no bridge has passed the ADR-010 probe as of 2026-11-17) and are
 * validated at startup: excluded, non-experimental or blank entries fail the boot closed.
 * Even with a record on file, a target only becomes available when a bridge adapter
 * ({@link PlatformProbe}) for that key is wired in this build — until then no REST
 * endpoint, pairing wizard or successful Status/story posting is promised or fabricated.
 * Signal message and story stay separate capabilities (UC-14 alternative flow 3): story
 * is deliberately absent from {@link #DESTINATION_KINDS} for {@code signal} until it
 * passes its own probe, so a story target is blocked instead of falling back to a chat
 * message.
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
	 * Verified destination kinds per experimental platform (UC-14 step 4: only the
	 * verified kind may be added, with the permanent experimental label). Signal story is
	 * a separate capability that has not passed its probe, so it is not listed — a
	 * missing story capability blocks the story target, never substitutes a message.
	 */
	private static final Map<String, Set<String>> DESTINATION_KINDS = Map.of("whatsapp-status", Set.of("status"),
			"signal", Set.of("message"));

	private final ExperimentalProperties properties;

	private final PlatformRegistry platforms;

	/**
	 * Normalized, validated feasibility records (platform key → dated ADR-010 record).
	 * Invalid entries fail startup closed.
	 */
	private final Map<String, String> records;

	public ExperimentalPlatforms(ExperimentalProperties properties, PlatformRegistry platforms) {
		this.properties = properties;
		this.platforms = platforms;
		Map<String, String> normalized = new TreeMap<>();
		for (Map.Entry<String, String> entry : properties.feasibility().entrySet()) {
			String key = normalize(entry.getKey());
			String value = entry.getValue();
			if (isExcluded(key)) {
				throw new IllegalStateException("app.experimental.feasibility contains the excluded platform '" + key
						+ "' — WhatsApp Business is not supported (ADR-010).");
			}
			if (!isExperimental(key)) {
				throw new IllegalStateException("app.experimental.feasibility key '" + key
						+ "' is not an experimental target (whatsapp-status, signal).");
			}
			if (!StringUtils.hasText(value)) {
				throw new IllegalStateException(
						"app.experimental.feasibility entry '" + key + "' needs a dated ADR-010 record text.");
			}
			normalized.put(key, value.trim());
		}
		this.records = Map.copyOf(normalized);
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

	/** Whether a dated ADR-010 feasibility record is on file for this platform key. */
	public boolean feasibilityPassed(String platform) {
		return this.records.containsKey(normalize(platform));
	}

	/** The dated feasibility record on file for this platform, or empty. */
	public String feasibilityRecord(String platform) {
		return this.records.getOrDefault(normalize(platform), "");
	}

	/**
	 * Whether a bridge adapter ({@link PlatformProbe}) for this key is wired in this
	 * build.
	 */
	public boolean bridgeWired(String platform) {
		return this.platforms.all().containsKey(normalize(platform));
	}

	/**
	 * Verified destination kinds for an experimental platform, sorted; empty otherwise.
	 */
	public static List<String> destinationKinds(String platform) {
		String p = normalize(platform);
		return DESTINATION_KINDS.getOrDefault(p, Set.of()).stream().sorted().toList();
	}

	/**
	 * Availability view for one experimental target: available only when the opt-in flag
	 * is on, a dated feasibility record is on file <em>and</em> a bridge adapter is wired
	 * — otherwise unavailable with the precise reason. {@code kinds} lists the candidate
	 * destination kinds (story is not among Signal's until its own capability probe
	 * passes).
	 */
	public Availability availability(String platform) {
		String p = normalize(platform);
		if (isExcluded(p)) {
			return new Availability(p, false, "permanently excluded — WhatsApp Business is not supported (ADR-010)",
					List.of());
		}
		if (!isExperimental(p)) {
			return new Availability(p, false, "not an experimental target", List.of());
		}
		if (!enabled()) {
			return new Availability(p, false, "experimental targets are disabled (APP_EXPERIMENTAL_ENABLED=false)",
					destinationKinds(p));
		}
		if (!feasibilityPassed(p)) {
			return new Availability(p, false,
					"unavailable — no bridge has passed the ADR-010 feasibility probe, so no pairing endpoint is offered",
					destinationKinds(p));
		}
		if (!bridgeWired(p)) {
			return new Availability(p, false,
					"unavailable — feasibility record on file but no bridge adapter is wired in this build, so the pairing path stays closed",
					destinationKinds(p));
		}
		return new Availability(p, true,
				"feasibility record passed and bridge wired — opt in through a locally protected setup path",
				destinationKinds(p));
	}

	/**
	 * Fail-closed gate for connect/destination operations: excluded platforms are always
	 * rejected; experimental targets only pass when the opt-in flag is on, a dated
	 * feasibility record exists <em>and</em> a bridge adapter is wired in this build.
	 * Official platforms pass through unchanged. Messages are sanitized and never contain
	 * credential material (ADR-007).
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
			if (!bridgeWired(p)) {
				throw new ProbeException("Experimental target '" + p
						+ "' has a feasibility record but no bridge adapter is wired in this build — the locally protected pairing path is not available.");
			}
		}
	}

	/**
	 * Destination-kind gate for experimental targets (UC-14 step 4 + alternative flow 3):
	 * after {@link #requireConnectable(String)}, only a verified kind may be added.
	 * Official platforms pass through unchanged. Signal story is rejected with an
	 * explicit no-fallback message — a chat message is never sent instead.
	 */
	public void requireDestinationKind(String platform, String kind) {
		requireConnectable(platform);
		requireVerifiedKind(platform, kind);
	}

	/**
	 * Kind check alone (composition of {@link #requireDestinationKind}); package-visible
	 * for tests because the connect gate currently blocks every experimental target.
	 */
	void requireVerifiedKind(String platform, String kind) {
		String p = normalize(platform);
		if (isExcluded(p)) {
			throw new ProbeException("WhatsApp Business is not supported (ADR-010).");
		}
		if (!isExperimental(p)) {
			return;
		}
		String k = normalize(kind);
		Set<String> kinds = DESTINATION_KINDS.getOrDefault(p, Set.of());
		if (p.equals("signal") && k.equals("story")) {
			throw new ProbeException(
					"Signal story targets are unavailable: story publishing is a separate capability that has not passed the ADR-010 feasibility probe, and a chat message is never sent as a fallback.");
		}
		if (!kinds.contains(k)) {
			throw new ProbeException("Destination kind '" + k + "' is not a verified kind for experimental target '" + p
					+ "' (verified: " + String.join(", ", kinds.stream().sorted().toList()) + ").");
		}
	}

	private static String normalize(String platform) {
		return platform == null ? "" : platform.trim().toLowerCase();
	}

	/** Accounts-page view of one experimental target. */
	public record Availability(String platform, boolean available, String reason, List<String> kinds) {
	}

}
