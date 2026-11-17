package com.patbaumgartner.socialcrossposter.workspace;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Workspace queries and lifecycle (UC-15). Callers always pass an explicit workspace;
 * missing context fails closed (ADR-013). Creating a workspace validates the slug, label,
 * color and timezone fail-closed and rejects duplicate slugs — the slug becomes part of
 * every workspace URL, so it is restricted to lowercase URL-safe characters and immutable
 * afterwards. Updating settings validates label/color/timezone with the same rules and
 * fails closed on an unknown slug.
 */
@Service
public class WorkspaceService {

	private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

	private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

	private static final Pattern LANGUAGE_TAG = Pattern.compile("^[a-z]{2,3}(-[A-Za-z0-9]{1,8})*$");

	private static final String DEFAULT_COLOR = "#3b82f6";

	private static final String DEFAULT_TIMEZONE = "Europe/Zurich";

	private static final int MAX_LANGUAGES = 10;

	private static final int MAX_LANGUAGES_LENGTH = 255;

	private static final int MAX_BRAND_VOICE_LENGTH = 2000;

	private final WorkspaceRepository repository;

	public WorkspaceService(WorkspaceRepository repository) {
		this.repository = repository;
	}

	public List<Workspace> listAll() {
		return this.repository.findAllByOrderByLabelAsc();
	}

	public Optional<Workspace> findBySlug(String slug) {
		if (!StringUtils.hasText(slug)) {
			return Optional.empty();
		}
		return this.repository.findBySlug(slug);
	}

	/**
	 * UC-15 step 1: creates a new empty account set. Fails closed (persists nothing) on
	 * an invalid or duplicate slug, a blank/oversized label, a malformed color or an
	 * unknown IANA timezone.
	 * @throws WorkspaceException with a sanitized, user-presentable message
	 */
	public Workspace create(String slug, String label, String color, String timezone) {
		String normalizedSlug = slug == null ? "" : slug.trim();
		if (!SLUG.matcher(normalizedSlug).matches()) {
			throw new WorkspaceException(
					"Slug must be 1-63 lowercase letters, digits or dashes, starting with a letter or digit (e.g. 'my-brand').");
		}
		if (this.repository.findBySlug(normalizedSlug).isPresent()) {
			throw new WorkspaceException("A workspace with slug '" + normalizedSlug + "' already exists.");
		}
		return this.repository.save(new Workspace(null, normalizedSlug, validateLabel(label), validateColor(color),
				validateTimezone(timezone), OffsetDateTime.now(), null, null));
	}

	/**
	 * UC-15 step 2: updates the label, color, timezone and profile (languages csv, brand
	 * voice) of an existing workspace. The slug is immutable (it is part of every
	 * workspace URL) and never changes here; the id and created_at of the existing row
	 * are kept. Fails closed (persists nothing) on an unknown or blank slug and on
	 * invalid label/color/timezone/languages/brand voice.
	 * @throws WorkspaceException with a sanitized, user-presentable message
	 */
	public Workspace updateSettings(String slug, String label, String color, String timezone, String languages,
			String brandVoice) {
		String normalizedSlug = slug == null ? "" : slug.trim();
		Workspace existing = this.repository.findBySlug(normalizedSlug)
			.orElseThrow(() -> new WorkspaceException("Unknown workspace."));
		return this.repository.save(new Workspace(existing.id(), existing.slug(), validateLabel(label),
				validateColor(color), validateTimezone(timezone), existing.createdAt(), validateLanguages(languages),
				validateBrandVoice(brandVoice)));
	}

	private static String validateLabel(String label) {
		String normalizedLabel = label == null ? "" : label.trim();
		if (normalizedLabel.isEmpty() || normalizedLabel.length() > 120) {
			throw new WorkspaceException("Label must be 1-120 characters.");
		}
		return normalizedLabel;
	}

	private static String validateColor(String color) {
		String normalizedColor = StringUtils.hasText(color) ? color.trim() : DEFAULT_COLOR;
		if (!COLOR.matcher(normalizedColor).matches()) {
			throw new WorkspaceException("Color must be a hex color like #3b82f6.");
		}
		return normalizedColor;
	}

	private static String validateTimezone(String timezone) {
		String normalizedTimezone = StringUtils.hasText(timezone) ? timezone.trim() : DEFAULT_TIMEZONE;
		if (!ZoneId.getAvailableZoneIds().contains(normalizedTimezone)) {
			throw new WorkspaceException("Unknown IANA timezone — use an id like Europe/Zurich.");
		}
		return normalizedTimezone;
	}

	/**
	 * Languages are an optional csv of language tags (e.g. "de,en,fr-CH"); blank stores
	 * NULL. Each tag must match a language(-script/region) shape, at most 10 tags and 255
	 * characters in total.
	 */
	private static String validateLanguages(String languages) {
		if (!StringUtils.hasText(languages)) {
			return null;
		}
		String[] tags = languages.split(",");
		if (tags.length > MAX_LANGUAGES) {
			throw new WorkspaceException(
					"At most " + MAX_LANGUAGES + " languages, comma-separated (e.g. 'de,en,fr-CH').");
		}
		StringBuilder normalized = new StringBuilder();
		for (String tag : tags) {
			String trimmed = tag.trim();
			if (!LANGUAGE_TAG.matcher(trimmed).matches()) {
				throw new WorkspaceException("Each language must be a tag like 'en' or 'fr-CH'.");
			}
			if (normalized.length() > 0) {
				normalized.append(',');
			}
			normalized.append(trimmed);
		}
		if (normalized.length() == 0) {
			return null;
		}
		if (normalized.length() > MAX_LANGUAGES_LENGTH) {
			throw new WorkspaceException("Languages must fit into " + MAX_LANGUAGES_LENGTH + " characters.");
		}
		return normalized.toString();
	}

	/** Brand voice is optional free text (max 2000 chars); blank stores NULL. */
	private static String validateBrandVoice(String brandVoice) {
		if (!StringUtils.hasText(brandVoice)) {
			return null;
		}
		String trimmed = brandVoice.trim();
		if (trimmed.length() > MAX_BRAND_VOICE_LENGTH) {
			throw new WorkspaceException("Brand voice must be at most " + MAX_BRAND_VOICE_LENGTH + " characters.");
		}
		return trimmed;
	}

}
