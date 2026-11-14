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

}
