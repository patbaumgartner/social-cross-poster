package com.patbaumgartner.socialcrossposter.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSetMetaData;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.patbaumgartner.socialcrossposter.media.MediaService;

/**
 * Secret-free workspace content export (UC-15 step 5, ADR-007, ADR-013): builds one
 * in-memory ZIP archive containing a JSON file per whitelisted workspace-scoped table,
 * the workspace's private media files (originals and outgoing derivatives) and a
 * {@code manifest.json} with a per-entry SHA-256 checksum, an aggregate checksum and the
 * Flyway schema version.
 *
 * <p>
 * Only the workspace row (by id) and rows with this {@code workspace_id} are read — other
 * workspaces' rows are never queried (ADR-013). Every column whose name contains
 * {@link #REDACTED_SUBSTRINGS} (cipher/nonce/key_id/secret/password/token — the sealed
 * credential material of ADR-007 lives in exactly those columns) is replaced with
 * {@link #REDACTED} in memory before anything is written, so secret values never appear
 * anywhere in the archive. The export excludes credentials by construction and is not a
 * recovery backup (docs/operations/README.md backup contract).
 */
@Service
public class WorkspaceExportService {

	/**
	 * Whitelisted workspace-scoped tables (each carries {@code workspace_id}) — nothing
	 * else is read.
	 */
	static final List<String> WORKSPACE_TABLES = List.of("account", "connection", "contact", "contact_handle",
			"delivery", "delivery_attempt", "delivery_job", "delivery_media", "media_asset", "media_import_grant",
			"notification_channel", "notification_delivery", "notification_event", "post", "post_revision",
			"publication", "publication_schedule", "remote_action", "remote_action_attempt", "revision_destination",
			"revision_media", "revision_override");

	/** Lowercase column-name substrings whose values are never exported (ADR-007). */
	static final List<String> REDACTED_SUBSTRINGS = List.of("cipher", "nonce", "key_id", "secret", "password", "token");

	/** Replacement value written for every redacted column. */
	static final String REDACTED = "[REDACTED]";

	private final JdbcTemplate jdbc;

	private final MediaService media;

	public WorkspaceExportService(JdbcTemplate jdbc, MediaService media) {
		this.jdbc = jdbc;
		this.media = media;
	}

	/**
	 * Builds the export ZIP for one workspace. Table names come only from the
	 * {@link #WORKSPACE_TABLES} whitelist, never from user input.
	 * @throws WorkspaceExportException with a sanitized message if the archive or a media
	 * file cannot be read
	 */
	@Transactional(readOnly = true)
	public byte[] export(Long workspaceId, String slug) {
		try {
			Map<String, byte[]> entries = new LinkedHashMap<>();
			entries.put("tables/workspace.json", rowsJson(query("SELECT * FROM workspace WHERE id = ?", workspaceId))
				.getBytes(StandardCharsets.UTF_8));
			for (String table : WORKSPACE_TABLES) {
				entries.put("tables/" + table + ".json",
						rowsJson(query("SELECT * FROM " + table + " WHERE workspace_id = ?", workspaceId))
							.getBytes(StandardCharsets.UTF_8));
			}
			for (Path file : this.media.mediaFilesOf(workspaceId)) {
				entries.put("media/" + file.getFileName(), Files.readAllBytes(file));
			}
			byte[] manifest = manifestJson(workspaceId, slug, entries);
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
				for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
					zip.putNextEntry(new ZipEntry(entry.getKey()));
					zip.write(entry.getValue());
					zip.closeEntry();
				}
				zip.putNextEntry(new ZipEntry("manifest.json"));
				zip.write(manifest);
				zip.closeEntry();
			}
			return buffer.toByteArray();
		}
		catch (IOException ex) {
			throw new WorkspaceExportException("The workspace export could not be created.", ex);
		}
	}

	/**
	 * Reads one table's rows for the workspace, redacting secret-looking columns in
	 * memory.
	 */
	private List<Map<String, Object>> query(String sql, Long workspaceId) {
		return this.jdbc.query(sql, (rs, rowNum) -> {
			ResultSetMetaData meta = rs.getMetaData();
			Map<String, Object> row = new LinkedHashMap<>();
			for (int i = 1; i <= meta.getColumnCount(); i++) {
				String column = meta.getColumnLabel(i).toLowerCase(Locale.ROOT);
				row.put(column, isRedacted(column) ? REDACTED : rs.getObject(i));
			}
			return row;
		}, workspaceId);
	}

	private static boolean isRedacted(String column) {
		return REDACTED_SUBSTRINGS.stream().anyMatch(column::contains);
	}

	/** Latest Flyway schema version, or {@code null} when the history table is absent. */
	private String schemaVersion() {
		try {
			return this.jdbc.queryForObject(
					"SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class);
		}
		catch (DataAccessException ex) {
			return null;
		}
	}

	private byte[] manifestJson(Long workspaceId, String slug, Map<String, byte[]> entries) {
		StringBuilder json = new StringBuilder("{");
		field(json, "format", "social-cross-poster-export").append(',');
		field(json, "schemaVersion", schemaVersion()).append(',');
		field(json, "generatedAt", OffsetDateTime.now(ZoneOffset.UTC).toString()).append(',');
		json.append("\"workspace\":{");
		field(json, "id", workspaceId).append(',');
		field(json, "slug", slug);
		json.append("},");
		json.append("\"redactedColumnSubstrings\":[");
		for (int i = 0; i < REDACTED_SUBSTRINGS.size(); i++) {
			if (i > 0) {
				json.append(',');
			}
			json.append('"').append(REDACTED_SUBSTRINGS.get(i)).append('"');
		}
		json.append("],");
		json.append("\"entries\":[");
		StringBuilder aggregate = new StringBuilder();
		boolean first = true;
		for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
			if (!first) {
				json.append(',');
			}
			first = false;
			String sha = sha256Hex(entry.getValue());
			aggregate.append(entry.getKey()).append(':').append(sha).append('\n');
			json.append("{\"path\":\"")
				.append(escape(entry.getKey()))
				.append("\",\"sha256\":\"")
				.append(sha)
				.append("\",\"bytes\":")
				.append(entry.getValue().length)
				.append('}');
		}
		json.append("],");
		field(json, "sha256", sha256Hex(aggregate.toString().getBytes(StandardCharsets.UTF_8)));
		json.append('}');
		return json.toString().getBytes(StandardCharsets.UTF_8);
	}

	/** Serializes rows as a compact JSON array of objects. */
	private static String rowsJson(List<Map<String, Object>> rows) {
		StringBuilder json = new StringBuilder("[");
		for (int r = 0; r < rows.size(); r++) {
			if (r > 0) {
				json.append(',');
			}
			json.append('{');
			boolean first = true;
			for (Map.Entry<String, Object> cell : rows.get(r).entrySet()) {
				if (!first) {
					json.append(',');
				}
				first = false;
				field(json, cell.getKey(), cell.getValue());
			}
			json.append('}');
		}
		return json.append(']').toString();
	}

	private static StringBuilder field(StringBuilder json, String name, Object value) {
		json.append('"').append(escape(name)).append("\":");
		if (value == null) {
			json.append("null");
		}
		else if (value instanceof Number || value instanceof Boolean) {
			json.append(value);
		}
		else if (value instanceof byte[] bytes) {
			json.append('"').append(HexFormat.of().formatHex(bytes)).append('"');
		}
		else {
			json.append('"').append(escape(String.valueOf(value))).append('"');
		}
		return json;
	}

	private static String sha256Hex(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String escape(String s) {
		StringBuilder out = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					}
					else {
						out.append(c);
					}
				}
			}
		}
		return out.toString();
	}

}
