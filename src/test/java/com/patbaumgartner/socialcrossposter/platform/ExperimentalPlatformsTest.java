package com.patbaumgartner.socialcrossposter.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the UC-14 experimental gate (ADR-010): WhatsApp Business is permanently
 * excluded in every spelling, the experimental candidates are exactly personal WhatsApp
 * Status and Signal, and no target becomes available without a passed feasibility record
 * — even when the opt-in flag is on. No Spring context.
 */
class ExperimentalPlatformsTest {

	private final ExperimentalPlatforms disabled = new ExperimentalPlatforms(new ExperimentalProperties(false));

	private final ExperimentalPlatforms enabled = new ExperimentalPlatforms(new ExperimentalProperties(true));

	@Test
	void optInFlagDefaultsToFalse() {
		assertThat(new ExperimentalProperties(null).enabled()).isFalse();
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

}
