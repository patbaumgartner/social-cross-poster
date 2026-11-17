package com.patbaumgartner.socialcrossposter.platform;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UC-14 / ADR-010: the default {@link PlatformProbe#startPairing()} fails closed — no
 * adapter ships a locally protected pairing path until a bridge has passed the ADR-010
 * feasibility probe, so no pairing endpoint or wizard is ever fabricated.
 */
class PlatformProbeTest {

	private static PlatformProbe probeWithoutPairing() {
		return new PlatformProbe() {
			@Override
			public String platform() {
				return "telegram";
			}

			@Override
			public Set<String> supportedModes() {
				return Set.of("pasted");
			}

			@Override
			public ProbeResult probe(String mode, String credential) {
				return new ProbeResult("@bot", "@bot");
			}
		};
	}

	@Test
	void defaultStartPairingFailsClosed() {
		PlatformProbe probe = probeWithoutPairing();
		assertThatThrownBy(probe::startPairing).isInstanceOf(ProbeException.class)
			.hasMessageContaining("no locally protected pairing path")
			.hasMessageContaining("ADR-010");
	}

	@Test
	void bridgeAdapterCanOverrideStartPairingWithSanitizedInstructions() {
		PlatformProbe probe = new PlatformProbe() {
			@Override
			public String platform() {
				return "signal";
			}

			@Override
			public Set<String> supportedModes() {
				return Set.of("bridge");
			}

			@Override
			public ProbeResult probe(String mode, String credential) {
				return new ProbeResult("+41791234567", "+41791234567");
			}

			@Override
			public PairingStart startPairing() {
				return new PairingStart("signal", "Scan the link code shown by the bridge.");
			}
		};
		PairingStart start = probe.startPairing();
		assertThat(start.platform()).isEqualTo("signal");
		assertThat(start.instructions()).contains("link code");
	}

}
