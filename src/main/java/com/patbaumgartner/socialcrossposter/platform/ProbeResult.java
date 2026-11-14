package com.patbaumgartner.socialcrossposter.platform;

/**
 * Verified provider identity returned by a {@link PlatformProbe}: the stable principal
 * (e.g. bot username or DID) and a human-readable display name.
 */
public record ProbeResult(String principal, String displayName) {

}
