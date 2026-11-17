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
 * every workspace URL, so it is restricted to lowercase URL-safe characters.
 */
@Service
public class WorkspaceService {

	private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

	private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

	private static final String DEFAULT_COLOR = "#3b82f6";

	private static final String DEFAULT_TIMEZONE = "Europe/Zurich";

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
		String normalizedLabel = label == null ? "" : label.trim();
		if (normalizedLabel.isEmpty() || normalizedLabel.length() > 120) {
			throw new WorkspaceException("Label must be 1-120 characters.");
		}
		String normalizedColor = StringUtils.hasText(color) ? color.trim() : DEFAULT_COLOR;
		if (!COLOR.matcher(normalizedColor).matches()) {
			throw new WorkspaceException("Color must be a hex color like #3b82f6.");
		}
		String normalizedTimezone = StringUtils.hasText(timezone) ? timezone.trim() : DEFAULT_TIMEZONE;
		if (!ZoneId.getAvailableZoneIds().contains(normalizedTimezone)) {
			throw new WorkspaceException("Unknown IANA timezone — use an id like Europe/Zurich.");
		}
		return this.repository.save(new Workspace(null, normalizedSlug, normalizedLabel, normalizedColor,
				normalizedTimezone, OffsetDateTime.now()));
	}

}
