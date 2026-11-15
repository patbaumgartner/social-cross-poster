package com.patbaumgartner.socialcrossposter.media;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Private media storage configuration (UC-13 first slice, ADR-008), bound from
 * APP_MEDIA_* env vars. Sources live on local disk under {@code dir} (a compose volume in
 * the baseline deployment) and are served only to authenticated workspace sessions —
 * never as permanent public URLs. Uploads are allow-listed to image types and capped at
 * {@code maxBytes}.
 */
@ConfigurationProperties(prefix = "app.media")
public record MediaProperties(String dir, Long maxBytes, List<String> allowedContentTypes) {

	public MediaProperties {
		if (dir == null || dir.isBlank()) {
			dir = "data/media";
		}
		if (maxBytes == null || maxBytes <= 0) {
			maxBytes = 10L * 1024 * 1024;
		}
		if (allowedContentTypes == null || allowedContentTypes.isEmpty()) {
			allowedContentTypes = List.of("image/png", "image/jpeg", "image/webp", "image/gif");
		}
	}

}
