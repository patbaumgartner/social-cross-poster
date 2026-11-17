package com.patbaumgartner.socialcrossposter.export;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.jdbc.core.JdbcTemplate;

import com.patbaumgartner.socialcrossposter.media.MediaService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link WorkspaceExportService} (UC-15 sixth slice, main flow step 5,
 * ADR-007/ADR-013): the export is one in-memory ZIP with a JSON file per whitelisted
 * workspace-scoped table, the private media files and a manifest.json with per-entry
 * SHA-256 checksums, an aggregate checksum and the Flyway schema version. Secret-looking
 * columns are redacted in memory, their values never appear anywhere in the archive, and
 * other workspaces' rows are never read. Real JdbcTemplate over in-memory H2 in
 * PostgreSQL mode; mocked MediaService; no Spring context.
 */
class WorkspaceExportServiceTest {

	private static final Long WS = 1L;

	private JdbcTemplate jdbc;

	private MediaService media;

	private WorkspaceExportService service;

	@TempDir
	Path tempDir;

	@BeforeEach
	void setUp() {
		JdbcDataSource ds = new JdbcDataSource();
		ds.setURL("jdbc:h2:mem:export" + System.nanoTime()
				+ ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
		this.jdbc = new JdbcTemplate(ds);
		this.jdbc.execute("CREATE TABLE workspace (id BIGINT PRIMARY KEY, slug VARCHAR(64), label VARCHAR(120))");
		for (String table : WorkspaceExportService.WORKSPACE_TABLES) {
			this.jdbc.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, workspace_id BIGINT)");
		}
		this.jdbc.execute("ALTER TABLE connection ADD COLUMN label VARCHAR(120)");
		this.jdbc.execute("ALTER TABLE connection ADD COLUMN secret_cipher TEXT");
		this.jdbc.execute("ALTER TABLE connection ADD COLUMN secret_nonce VARCHAR(64)");
		this.jdbc.execute("ALTER TABLE connection ADD COLUMN secret_key_id VARCHAR(32)");
		this.jdbc.execute("ALTER TABLE contact_handle ADD COLUMN token VARCHAR(72)");
		this.jdbc.execute("ALTER TABLE media_import_grant ADD COLUMN key_id VARCHAR(32)");
		this.jdbc.execute("ALTER TABLE notification_channel ADD COLUMN secret_cipher VARCHAR(4096)");
		this.jdbc.execute("ALTER TABLE notification_channel ADD COLUMN secret_nonce VARCHAR(64)");
		this.jdbc.execute("ALTER TABLE notification_channel ADD COLUMN secret_key_id VARCHAR(40)");
		this.jdbc.execute("ALTER TABLE post ADD COLUMN title VARCHAR(200)");
		this.jdbc.execute("CREATE TABLE flyway_schema_history (installed_rank INT PRIMARY KEY, version VARCHAR(50))");
		this.jdbc.update("INSERT INTO flyway_schema_history (installed_rank, version) VALUES (18, '18')");
		this.jdbc.update("INSERT INTO workspace (id, slug, label) VALUES (1, 'private', 'Private')");
		this.jdbc.update("INSERT INTO connection (id, workspace_id, label, secret_cipher, secret_nonce, secret_key_id)"
				+ " VALUES (10, 1, 'mastodon', 'TOPSECRET-CIPHER', 'TOPSECRET-NONCE', 'v1')");
		this.jdbc.update("INSERT INTO contact_handle (id, workspace_id, token) VALUES (20, 1, 'TOPSECRET-TOKEN')");
		this.jdbc.update("INSERT INTO media_import_grant (id, workspace_id, key_id) VALUES (30, 1, 'TOPSECRET-KEYID')");
		this.jdbc
			.update("INSERT INTO notification_channel (id, workspace_id, secret_cipher, secret_nonce, secret_key_id)"
					+ " VALUES (40, 1, 'TOPSECRET-WEBHOOK', 'n', 'k')");
		this.jdbc.update("INSERT INTO post (id, workspace_id, title) VALUES (50, 1, 'Hello Voxxed')");
		this.jdbc.update("INSERT INTO post (id, workspace_id, title) VALUES (51, 2, 'FOREIGN ROW')");
		this.media = mock(MediaService.class);
		this.service = new WorkspaceExportService(this.jdbc, this.media);
	}

	@Test
	void exportBuildsZipWithManifestRedactedTablesAndMedia() throws Exception {
		Path original = this.tempDir.resolve("deadbeef");
		Path derivative = this.tempDir.resolve("out-deadbeef");
		Files.write(original, "private-image-bytes".getBytes(StandardCharsets.UTF_8));
		Files.write(derivative, "outgoing-bytes".getBytes(StandardCharsets.UTF_8));
		given(this.media.mediaFilesOf(WS)).willReturn(List.of(original, derivative));

		Map<String, byte[]> entries = unzip(this.service.export(WS, "private"));

		// one JSON file per whitelisted table + the workspace row + media files +
		// manifest
		assertThat(entries).containsKeys("manifest.json", "tables/workspace.json", "tables/connection.json",
				"tables/post.json", "media/deadbeef", "media/out-deadbeef");
		assertThat(entries).hasSize(WorkspaceExportService.WORKSPACE_TABLES.size()
				+ 1 /* workspace row */ + 2 /* media */ + 1);
		assertThat(text(entries, "tables/workspace.json")).contains("\"slug\":\"private\"")
			.contains("\"label\":\"Private\"");
		assertThat(text(entries, "tables/post.json")).contains("Hello Voxxed");
		assertThat(entries.get("media/deadbeef")).isEqualTo("private-image-bytes".getBytes(StandardCharsets.UTF_8));

		// manifest: schema version, workspace, per-entry checksums matching the actual
		// bytes, and an aggregate checksum
		String manifest = text(entries, "manifest.json");
		assertThat(manifest).contains("\"schemaVersion\":\"18\"").contains("\"slug\":\"private\"");
		for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
			if (!entry.getKey().equals("manifest.json")) {
				assertThat(manifest)
					.contains("\"path\":\"" + entry.getKey() + "\",\"sha256\":\"" + sha256Hex(entry.getValue()) + "\"");
			}
		}
		assertThat(manifest).containsPattern("\"sha256\":\"[0-9a-f]{64}\"");
	}

	@Test
	void exportRedactsSecretColumnsAndNeverReadsOtherWorkspaces() throws Exception {
		given(this.media.mediaFilesOf(WS)).willReturn(List.of());

		Map<String, byte[]> entries = unzip(this.service.export(WS, "private"));

		// every secret-looking column is redacted; non-secret columns survive
		String connection = text(entries, "tables/connection.json");
		assertThat(connection).contains("mastodon")
			.contains(WorkspaceExportService.REDACTED)
			.doesNotContain("TOPSECRET-CIPHER")
			.doesNotContain("TOPSECRET-NONCE");
		assertThat(text(entries, "tables/contact_handle.json")).contains(WorkspaceExportService.REDACTED)
			.doesNotContain("TOPSECRET-TOKEN");
		assertThat(text(entries, "tables/media_import_grant.json")).contains(WorkspaceExportService.REDACTED)
			.doesNotContain("TOPSECRET-KEYID");
		assertThat(text(entries, "tables/notification_channel.json")).doesNotContain("TOPSECRET-WEBHOOK");

		// no secret value appears in any archive entry (checked decompressed)
		String all = entries.values()
			.stream()
			.map((bytes) -> new String(bytes, StandardCharsets.ISO_8859_1))
			.reduce("", String::concat);
		assertThat(all).doesNotContain("TOPSECRET");

		// rows of other workspaces are never exported
		assertThat(text(entries, "tables/post.json")).doesNotContain("FOREIGN ROW");
	}

	private static Map<String, byte[]> unzip(byte[] zipBytes) throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				entries.put(entry.getName(), zip.readAllBytes());
			}
		}
		return entries;
	}

	private static String text(Map<String, byte[]> entries, String name) {
		return new String(entries.get(name), StandardCharsets.UTF_8);
	}

	private static String sha256Hex(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

}
