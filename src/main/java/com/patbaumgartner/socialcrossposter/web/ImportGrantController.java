package com.patbaumgartner.socialcrossposter.web;

import java.time.OffsetDateTime;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.patbaumgartner.socialcrossposter.media.ImportGrantService;
import com.patbaumgartner.socialcrossposter.media.MediaAsset;
import com.patbaumgartner.socialcrossposter.media.MediaException;
import com.patbaumgartner.socialcrossposter.media.MediaImportGrant;
import com.patbaumgartner.socialcrossposter.media.MediaService;

/**
 * Public, grant-gated fetch route for short-lived revocable import grants (UC-13 fourth
 * slice, ADR-008 step 3): {@code GET/HEAD /media/grants/{grantId}?exp=…&sig=…}. The route
 * is the only permit-all endpoint besides login/assets — the signed URL itself is the
 * bearer capability. It serves the metadata-stripped outgoing derivative (never the
 * private original) with {@code Cache-Control: no-store}; unknown or wrongly signed
 * grants are indistinguishable 404s, expired/revoked grants are 410, over-limit fetches
 * are 429. The URL, signature and token hash are never logged (ADR-007).
 */
@RestController
public class ImportGrantController {

	private final ImportGrantService grants;

	private final MediaService media;

	public ImportGrantController(ImportGrantService grants, MediaService media) {
		this.grants = grants;
		this.media = media;
	}

	@GetMapping("/media/grants/{grantId}")
	public ResponseEntity<ByteArrayResource> fetch(@PathVariable Long grantId, @RequestParam("exp") long exp,
			@RequestParam("sig") String sig) {
		ImportGrantService.GrantAccess access = this.grants.resolve(grantId, exp, sig, OffsetDateTime.now());
		return switch (access.access()) {
			case VALID -> serve(access.grant());
			case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
			case EXPIRED, REVOKED -> ResponseEntity.status(HttpStatus.GONE).build();
			case UNKNOWN -> ResponseEntity.notFound().build();
		};
	}

	private ResponseEntity<ByteArrayResource> serve(MediaImportGrant grant) {
		MediaAsset asset = this.media.findBySha(grant.workspaceId(), grant.sha256()).orElse(null);
		if (asset == null) {
			return ResponseEntity.notFound().build();
		}
		final byte[] bytes;
		try {
			// Only the metadata-stripped derivative ever leaves the app (ADR-016).
			bytes = this.media.outgoingBytes(grant.workspaceId(), grant.sha256());
		}
		catch (MediaException ex) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(asset.contentType()))
			.contentLength(bytes.length)
			.cacheControl(CacheControl.noStore())
			.header("X-Content-Type-Options", "nosniff") // literal: the constant was
															// removed in Spring 7
			.body(new ByteArrayResource(bytes));
	}

}
