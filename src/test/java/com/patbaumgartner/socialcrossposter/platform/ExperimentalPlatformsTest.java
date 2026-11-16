package com.patbaumgartner.socialcrossposter.platform;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the UC-14 experimental gate (ADR-010): WhatsApp Business is permanently
 * excluded in every spelling, the experimental candidates are exactly personal WhatsApp
 * Status and Signal, and a target only becomes available when the opt-in flag is on, a
 * dated feasibility record is configured <em>and</em> a bridge adapter is wired — no
 * record or no adapter means unavailable, even with the flag on. No Spring context.
 */
class ExperimentalPlatformsTest {

	private static final Map<String, String> NO_RECORDS = Map.of();

	private static final Map<String, String> SIGNAL_RECORD = Map.of("signal", "2026-11-17 local probe record (test)");

	private final ExperimentalPlatforms disabled = gate(false, NO_RECORDS, List.of());

	private final ExperimentalPlatforms enabled = gate(true, NO_RECORDS, List.of());

	private static ExperimentalPlatforms gate(boolean enabled, Map<String, String> records,
			List<PlatformProbe> probes) {
		return new ExperimentalPlatforms(new ExperimentalProperties(enabled, records), new PlatformRegistry(probes));
	}

	private static PlatformProbe probeFor(String platform) {
		PlatformProbe probe = mock(PlatformProbe.class);
		given(probe.platform()).willReturn(platform);
		return probe;
	}

	@Test
	void optInFlagAndRecordsDefaultToEmpty() {
		assertThat(new ExperimentalProperties(null, null).enabled()).isFalse();
		assertThat(new ExperimentalProperties(null, null).feasibility()).isEmpty();
		assertThat(this.disabled.enabled()).isFalse();
		assertThat(this.enabled.enabled()).isTrue();
	}

	@Test
	void whatsappBusinessVariantsArePermanentlyExcluded() {
		assertThat(ExperimentalPlatforms.isExcluded("whatsapp-business")).isTrue();
		assertThat(ExperimentalPlatforms.isExcluded("WhatsApp Business")).isTrue();
		assertThat(ExperimentalPlatforms.isExcluded("whatsapp-business-cloud")).isTrue();
		assertThat(ExperimentalPlatforms.isExcluded("whatsapp-status")).isFalse();
		assertThat(ExperimentalPlatforms.isExcluded("signal")).isFalse();
		assertThat(ExperimentalPlatforms.isExcluded("telegram")).isFalse();
		assertThat(ExperimentalPlatforms.isExcluded(null)).isFalse();
	}

	@Test
	void experimentalCandidatesAreExactlySignalAndWhatsappStatus() {
		assertThat(ExperimentalPlatforms.isExperimental("signal")).isTrue();
		assertThat(ExperimentalPlatforms.isExperimental("whatsapp-status")).isTrue();
		assertThat(ExperimentalPlatforms.isExperimental("telegram")).isFalse();
		assertThat(ExperimentalPlatforms.isExperimental(null)).isFalse();
		assertThat(ExperimentalPlatforms.experimentalKeys()).containsExactly("signal", "whatsapp-status");
	}

	@Test
	void disabledFlagMakesExperimentalTargetsUnavailable() {
		ExperimentalPlatforms.Availability a = this.disabled.availability("signal");
		assertThat(a.available()).isFalse();
		assertThat(a.reason()).contains("disabled");
	}

	@Test
	void noFeasibilityRecordMeansUnavailableEvenWhenEnabled() {
		assertThat(this.enabled.feasibilityPassed("signal")).isFalse();
		assertThat(this.enabled.feasibilityPassed("whatsapp-status")).isFalse();
		for (String key : ExperimentalPlatforms.experimentalKeys()) {
			ExperimentalPlatforms.Availability a = this.enabled.availability(key);
			assertThat(a.available()).isFalse();
			assertThat(a.reason()).contains("feasibility");
		}
	}

	@Test
	void recordWithoutWiredBridgeAdapterStaysUnavailable() {
		ExperimentalPlatforms gate = gate(true, SIGNAL_RECORD, List.of());
		assertThat(gate.feasibilityPassed("signal")).isTrue();
		assertThat(gate.feasibilityRecord("signal")).contains("2026-11-17");
		assertThat(gate.bridgeWired("signal")).isFalse();
		ExperimentalPlatforms.Availability a = gate.availability("signal");
		assertThat(a.available()).isFalse();
		assertThat(a.reason()).contains("no bridge adapter");
		assertThatThrownBy(() -> gate.requireConnectable("signal")).isInstanceOf(ProbeException.class)
			.hasMessageContaining("no bridge adapter");
	}

	@Test
	void recordWithWiredBridgeAdapterOpensTheGate() {
		ExperimentalPlatforms gate = gate(true, SIGNAL_RECORD, List.of(probeFor("signal")));
		assertThat(gate.bridgeWired("signal")).isTrue();
		ExperimentalPlatforms.Availability a = gate.availability("signal");
		assertThat(a.available()).isTrue();
		assertThat(a.reason()).contains("feasibility record passed");
		assertThatCode(() -> gate.requireConnectable("signal")).doesNotThrowAnyException();
		// whatsapp-status has no record even in this gate
		assertThat(gate.availability("whatsapp-status").available()).isFalse();
	}

	@Test
	void recordKeysAreNormalizedCaseInsensitively() {
		ExperimentalPlatforms gate = gate(true, Map.of("Signal", "record text"), List.of());
		assertThat(gate.feasibilityPassed("SIGNAL")).isTrue();
	}

	@Test
	void invalidFeasibilityRecordsFailStartupClosed() {
		assertThatThrownBy(() -> gate(true, Map.of("whatsapp-business", "record"), List.of()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("excluded")
			.hasMessageContaining("ADR-010");
		assertThatThrownBy(() -> gate(true, Map.of("telegram", "record"), List.of()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("not an experimental target");
		assertThatThrownBy(() -> gate(true, Map.of("signal", "  "), List.of()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("dated ADR-010 record");
	}

	@Test
	void excludedPlatformsReportPermanentExclusion() {
		ExperimentalPlatforms.Availability a = this.enabled.availability("whatsapp-business");
		assertThat(a.available()).isFalse();
		assertThat(a.reason()).contains("excluded").contains("ADR-010");
	}

	@Test
	void requireConnectablePassesOfficialPlatformsThrough() {
		assertThatCode(() -> this.disabled.requireConnectable("telegram")).doesNotThrowAnyException();
		assertThatCode(() -> this.enabled.requireConnectable("linkedin")).doesNotThrowAnyException();
	}

	@Test
	void requireConnectableRejectsExcludedTargetsAlways() {
		assertThatThrownBy(() -> this.enabled.requireConnectable("whatsapp-business"))
			.isInstanceOf(ProbeException.class)
			.hasMessageContaining("WhatsApp Business is not supported")
			.hasMessageContaining("ADR-010");
		assertThatThrownBy(() -> this.disabled.requireConnectable("whatsapp-business"))
			.isInstanceOf(ProbeException.class)
			.hasMessageContaining("not supported");
	}

	@Test
	void requireConnectableRejectsExperimentalTargetsWithoutPassedProbe() {
		assertThatThrownBy(() -> this.disabled.requireConnectable("signal")).isInstanceOf(ProbeException.class)
			.hasMessageContaining("disabled");
		assertThatThrownBy(() -> this.enabled.requireConnectable("whatsapp-status")).isInstanceOf(ProbeException.class)
			.hasMessageContaining("feasibility");
	}

	@Test
	void verifiedDestinationKindsAreStatusForWhatsappStatusAndMessageOnlyForSignal() {
		assertThat(ExperimentalPlatforms.destinationKinds("signal")).containsExactly("message");
		assertThat(ExperimentalPlatforms.destinationKinds("whatsapp-status")).containsExactly("status");
		assertThat(ExperimentalPlatforms.destinationKinds("telegram")).isEmpty();
		// story is a separate Signal capability and deliberately not listed
		assertThat(ExperimentalPlatforms.destinationKinds("signal")).doesNotContain("story");
	}

	@Test
	void signalStoryKindIsBlockedWithoutMessageFallback() {
		assertThatThrownBy(() -> this.enabled.requireVerifiedKind("signal", "story")).isInstanceOf(ProbeException.class)
			.hasMessageContaining("story")
			.hasMessageContaining("never sent as a fallback");
		assertThatCode(() -> this.enabled.requireVerifiedKind("signal", "message")).doesNotThrowAnyException();
	}

	@Test
	void unverifiedKindsAreRejectedAndOfficialPlatformsPassThrough() {
		assertThatThrownBy(() -> this.enabled.requireVerifiedKind("whatsapp-status", "message"))
			.isInstanceOf(ProbeException.class)
			.hasMessageContaining("not a verified kind");
		assertThatCode(() -> this.enabled.requireVerifiedKind("whatsapp-status", "status")).doesNotThrowAnyException();
		assertThatCode(() -> this.enabled.requireVerifiedKind("telegram", "channel")).doesNotThrowAnyException();
		assertThatThrownBy(() -> this.enabled.requireVerifiedKind("whatsapp-business", "status"))
			.isInstanceOf(ProbeException.class)
			.hasMessageContaining("not supported");
	}

	@Test
	void availabilityCarriesCandidateKinds() {
		assertThat(this.enabled.availability("signal").kinds()).containsExactly("message");
		assertThat(this.enabled.availability("whatsapp-status").kinds()).containsExactly("status");
		assertThat(this.enabled.availability("whatsapp-business").kinds()).isEmpty();
	}

}
