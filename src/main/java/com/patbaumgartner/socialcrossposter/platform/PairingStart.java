package com.patbaumgartner.socialcrossposter.platform;

/**
 * UC-14 / ADR-010: result of starting the locally protected pairing/setup path of an
 * experimental bridge (e.g. a link-code / QR flow). Carries only sanitized,
 * credential-free instructions — never a session secret, token or credential (ADR-007).
 */
public record PairingStart(String platform, String instructions) {
}
