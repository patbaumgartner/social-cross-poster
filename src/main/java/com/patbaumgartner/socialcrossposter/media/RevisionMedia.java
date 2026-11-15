package com.patbaumgartner.socialcrossposter.media;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;

/**
 * One media asset attached to one revision at a position with a mandatory local alt text
 * (UC-13, ADR-016). Links belong to the revision; amending them is allowed only on the
 * post's current revision so older revisions stay immutable (ADR-015).
 */
public record RevisionMedia(@Id Long id, Long workspaceId, Long revisionId, Long mediaId, Integer position,
		String altText, OffsetDateTime createdAt) {
}
