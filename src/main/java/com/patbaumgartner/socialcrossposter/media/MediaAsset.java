package com.patbaumgartner.socialcrossposter.media;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;

/**
 * A private source image of one workspace (UC-13 first slice, ADR-008): stored on local
 * disk, downloadable only with an authenticated workspace session, never exposed as a
 * permanent public URL. Content-addressed per workspace — the same bytes uploaded twice
 * reuse one row ({@code UNIQUE(workspace_id, sha256)}).
 */
public record MediaAsset(@Id Long id, Long workspaceId, String filename, String contentType, Long sizeBytes,
		String sha256, OffsetDateTime createdAt) {
}
