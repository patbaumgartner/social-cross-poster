package com.patbaumgartner.socialcrossposter.platform;

import java.util.Set;

/**
 * Stateless per-platform credential probe (ADR-001, ADR-006): validates a pasted
 * credential or completed OAuth grant through a documented read-only endpoint and returns
 * the provider principal identity. Implementations must be stateless — no workspace,
 * token or session state in the bean — and must never log the credential. Health probes
 * never create test posts.
 */
public interface PlatformProbe {

	/** Platform key, e.g. {@code telegram}. */
	String platform();

	/** Credential modes this provider actually supports (ADR-006). */
	Set<String> supportedModes();

	/**
	 * Validates {@code credential} (a raw token or an OAuth access token) with a
	 * read-only provider call and returns the verified principal.
	 * @throws ProbeException with a sanitized, credential-free message when the provider
	 * rejects or cannot verify the credential
	 */
	ProbeResult probe(String mode, String credential);

	/**
	 * UC-14 / ADR-010: starts the locally protected pairing/setup path of an experimental
	 * bridge (e.g. a link-code / QR flow) and returns sanitized, credential-free
	 * instructions. The default fails closed — no adapter ships a pairing wizard until a
	 * bridge has passed the ADR-010 feasibility probe, so no pairing endpoint is ever
	 * fabricated (UC-14 alternative flow 1). Only bridge adapters for experimental
	 * targets override this.
	 * @throws ProbeException with a sanitized, credential-free message when the adapter
	 * has no pairing path
	 */
	default PairingStart startPairing() {
		throw new ProbeException("This platform adapter has no locally protected pairing path (ADR-010).");
	}

}
