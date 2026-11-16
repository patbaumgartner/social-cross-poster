package com.patbaumgartner.socialcrossposter.platform;

/**
 * One approved image handed to a source-fetch platform as a short-lived revocable signed
 * URL (UC-13 fourth slice, ADR-008 step 3): the content hash, the minted grant URL and
 * the local alt text. The URL is a bearer capability — implementations must never log it
 * or include it in error details (ADR-007); the grant is revoked after the provider
 * confirmed the import. The URL is never the lasting image reference in a published post
 * — the platform-owned media reference is.
 */
public record ImportSource(String sha256, String url, String altText) {

}
