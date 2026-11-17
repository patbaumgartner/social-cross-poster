package com.patbaumgartner.socialcrossposter.web;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-15 step 1 end-to-end through the security filter chain (ADR-005/ADR-013): the owner
 * creates a new workspace from the picker. The H2 database is shared across tests in this
 * context, so created workspaces are removed before and after each test; the V1 seed rows
 * are never touched.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkspaceFlowTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	WorkspaceRepository workspaces;

	@BeforeEach
	@AfterEach
	void resetState() {
		this.workspaces.deleteBySlug("acme");
		this.workspaces.deleteBySlug("defaults-ws");
	}

	@Test
	void pickerRequiresLogin() throws Exception {
		this.mockMvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
	}

	@Test
	void createRequiresLogin() throws Exception {
		this.mockMvc.perform(post("/workspaces").with(csrf()).param("slug", "acme").param("label", "Acme"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
		assertThat(this.workspaces.findBySlug("acme")).isEmpty();
	}

	@Test
	void createRequiresCsrfToken() throws Exception {
		MockHttpSession session = login();
		this.mockMvc.perform(post("/workspaces").session(session).param("slug", "acme").param("label", "Acme"))
			.andExpect(status().isForbidden());
		assertThat(this.workspaces.findBySlug("acme")).isEmpty();
	}

	@Test
	void createWorkspaceEntersItsDashboardAndListsItInPicker() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/workspaces").with(csrf())
				.session(session)
				.param("slug", "acme")
				.param("label", "Acme")
				.param("color", "#dc2626")
				.param("timezone", "Europe/Zurich"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/acme/dashboard"))
			.andExpect(flash().attributeExists("notice"));

		Optional<Workspace> created = this.workspaces.findBySlug("acme");
		assertThat(created).isPresent();
		assertThat(created.get().label()).isEqualTo("Acme");
		assertThat(created.get().color()).isEqualTo("#dc2626");
		assertThat(created.get().timezone()).isEqualTo("Europe/Zurich");
		assertThat(created.get().createdAt()).isNotNull();

		this.mockMvc.perform(get("/").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Acme")));
		this.mockMvc.perform(get("/w/acme/dashboard").session(session)).andExpect(status().isOk());
	}

	@Test
	void createAppliesDefaultColorAndTimezoneWhenOmitted() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/workspaces").with(csrf())
				.session(session)
				.param("slug", "defaults-ws")
				.param("label", "Defaults WS"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/defaults-ws/dashboard"));

		Optional<Workspace> created = this.workspaces.findBySlug("defaults-ws");
		assertThat(created).isPresent();
		assertThat(created.get().color()).isEqualTo("#3b82f6");
		assertThat(created.get().timezone()).isEqualTo("Europe/Zurich");
	}

	@Test
	void duplicateSlugShowsErrorAndPersistsNothing() throws Exception {
		MockHttpSession session = login();
		long before = this.workspaces.count();
		this.mockMvc
			.perform(post("/workspaces").with(csrf()).session(session).param("slug", "private").param("label", "Dup"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"))
			.andExpect(flash().attribute("error", containsString("already exists")));
		assertThat(this.workspaces.count()).isEqualTo(before);
	}

	@Test
	void invalidSlugShowsErrorAndPersistsNothing() throws Exception {
		MockHttpSession session = login();
		long before = this.workspaces.count();
		this.mockMvc
			.perform(
					post("/workspaces").with(csrf()).session(session).param("slug", "Bad Slug!").param("label", "Acme"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"))
			.andExpect(flash().attribute("error", containsString("Slug")));
		assertThat(this.workspaces.count()).isEqualTo(before);
	}

	@Test
	void blankLabelShowsErrorAndPersistsNothing() throws Exception {
		MockHttpSession session = login();
		long before = this.workspaces.count();
		this.mockMvc
			.perform(post("/workspaces").with(csrf()).session(session).param("slug", "acme").param("label", "   "))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"))
			.andExpect(flash().attribute("error", containsString("Label")));
		assertThat(this.workspaces.count()).isEqualTo(before);
		assertThat(this.workspaces.findBySlug("acme")).isEmpty();
	}

	@Test
	void invalidColorShowsErrorAndPersistsNothing() throws Exception {
		MockHttpSession session = login();
		long before = this.workspaces.count();
		this.mockMvc
			.perform(post("/workspaces").with(csrf())
				.session(session)
				.param("slug", "acme")
				.param("label", "Acme")
				.param("color", "red"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"))
			.andExpect(flash().attribute("error", containsString("Color")));
		assertThat(this.workspaces.count()).isEqualTo(before);
	}

	@Test
	void unknownTimezoneShowsErrorAndPersistsNothing() throws Exception {
		MockHttpSession session = login();
		long before = this.workspaces.count();
		this.mockMvc
			.perform(post("/workspaces").with(csrf())
				.session(session)
				.param("slug", "acme")
				.param("label", "Acme")
				.param("timezone", "Mars/Olympus"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"))
			.andExpect(flash().attribute("error", containsString("timezone")));
		assertThat(this.workspaces.count()).isEqualTo(before);
	}

	@Test
	void settingsFormRequiresLogin() throws Exception {
		this.mockMvc.perform(get("/w/private/settings"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void settingsFormUnknownSlugRedirectsToPicker() throws Exception {
		MockHttpSession session = login();
		this.mockMvc.perform(get("/w/ghost/settings").session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));
	}

	@Test
	void settingsFormShowsCurrentValues() throws Exception {
		MockHttpSession session = login();
		this.mockMvc.perform(get("/w/private/settings").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Private")))
			.andExpect(content().string(containsString("private")))
			.andExpect(content().string(containsString("Europe/Zurich")));
	}

	@Test
	void updateSettingsRequiresCsrfTokenAndLeavesRowUnchanged() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/w/private/settings").session(session).param("label", "Hacked").param("color", "#dc2626"))
			.andExpect(status().isForbidden());
		Optional<Workspace> row = this.workspaces.findBySlug("private");
		assertThat(row).isPresent();
		assertThat(row.get().label()).isEqualTo("Private");
		assertThat(row.get().color()).isEqualTo("#6b7280");
	}

	@Test
	void updateSettingsSuccessPersistsFieldsKeepsSlugIdAndCreatedAt() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/workspaces").with(csrf())
				.session(session)
				.param("slug", "acme")
				.param("label", "Acme")
				.param("color", "#dc2626")
				.param("timezone", "Europe/Zurich"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/acme/dashboard"));
		Workspace before = this.workspaces.findBySlug("acme").orElseThrow();

		this.mockMvc
			.perform(post("/w/acme/settings").with(csrf())
				.session(session)
				.param("label", " Acme AG ")
				.param("color", "#059669")
				.param("timezone", "Europe/Berlin")
				.param("languages", "de, en, fr-CH")
				.param("brandVoice", "Friendly, concise.")
				.param("eventFacts", "Voxxed Days Zurich 2026, June 11-12.")
				.param("eventLinks", "https://voxxeddays.com/zurich, https://example.com/tickets"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/acme/dashboard"))
			.andExpect(flash().attribute("notice", containsString("Acme AG")));

		Workspace after = this.workspaces.findBySlug("acme").orElseThrow();
		assertThat(after.id()).isEqualTo(before.id());
		assertThat(after.slug()).isEqualTo("acme");
		assertThat(after.createdAt()).isEqualTo(before.createdAt());
		assertThat(after.label()).isEqualTo("Acme AG");
		assertThat(after.color()).isEqualTo("#059669");
		assertThat(after.timezone()).isEqualTo("Europe/Berlin");
		assertThat(after.languages()).isEqualTo("de,en,fr-CH");
		assertThat(after.brandVoice()).isEqualTo("Friendly, concise.");
		assertThat(after.eventFacts()).isEqualTo("Voxxed Days Zurich 2026, June 11-12.");
		assertThat(after.eventLinks()).isEqualTo("https://voxxeddays.com/zurich,https://example.com/tickets");

		// The dashboard shows the new label, and the settings form pre-fills the new
		// values.
		this.mockMvc.perform(get("/w/acme/dashboard").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Acme AG")));
		this.mockMvc.perform(get("/w/acme/settings").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Acme AG")))
			.andExpect(content().string(containsString("Europe/Berlin")))
			.andExpect(content().string(containsString("de,en,fr-CH")))
			.andExpect(content().string(containsString("https://voxxeddays.com/zurich")));
	}

	@Test
	void updateSettingsInvalidEventLinksShowsErrorAndLeavesRowUnchanged() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/workspaces").with(csrf()).session(session).param("slug", "acme").param("label", "Acme"))
			.andExpect(status().is3xxRedirection());

		this.mockMvc
			.perform(post("/w/acme/settings").with(csrf())
				.session(session)
				.param("label", "Acme")
				.param("eventLinks", "not-a-url"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/acme/settings"))
			.andExpect(flash().attribute("error", containsString("link")));

		Workspace row = this.workspaces.findBySlug("acme").orElseThrow();
		assertThat(row.label()).isEqualTo("Acme");
		assertThat(row.eventFacts()).isNull();
		assertThat(row.eventLinks()).isNull();
	}

	@Test
	void updateSettingsInvalidLanguagesShowsErrorAndLeavesRowUnchanged() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/workspaces").with(csrf()).session(session).param("slug", "acme").param("label", "Acme"))
			.andExpect(status().is3xxRedirection());

		this.mockMvc
			.perform(post("/w/acme/settings").with(csrf())
				.session(session)
				.param("label", "Acme")
				.param("languages", "english!"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/acme/settings"))
			.andExpect(flash().attribute("error", containsString("language")));

		Workspace row = this.workspaces.findBySlug("acme").orElseThrow();
		assertThat(row.label()).isEqualTo("Acme");
		assertThat(row.languages()).isNull();
		assertThat(row.brandVoice()).isNull();
	}

	@Test
	void updateSettingsInvalidShowsErrorAndLeavesRowUnchanged() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/workspaces").with(csrf()).session(session).param("slug", "acme").param("label", "Acme"))
			.andExpect(status().is3xxRedirection());

		this.mockMvc.perform(post("/w/acme/settings").with(csrf()).session(session).param("label", "   "))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/acme/settings"))
			.andExpect(flash().attribute("error", containsString("Label")));

		Workspace row = this.workspaces.findBySlug("acme").orElseThrow();
		assertThat(row.label()).isEqualTo("Acme");
	}

	@Test
	void updateSettingsUnknownSlugRedirectsToPicker() throws Exception {
		MockHttpSession session = login();
		long before = this.workspaces.count();
		this.mockMvc.perform(post("/w/ghost/settings").with(csrf()).session(session).param("label", "Ghost"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));
		assertThat(this.workspaces.count()).isEqualTo(before);
	}

	@Test
	void updateNotificationDefaultsFlowTogglesSnippetOptIn() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/workspaces").with(csrf()).session(session).param("slug", "acme").param("label", "Acme"))
			.andExpect(status().is3xxRedirection());
		assertThat(this.workspaces.findBySlug("acme").orElseThrow().notifySnippets()).isFalse(); // default

		// opt in via the checkbox
		this.mockMvc
			.perform(post("/w/acme/settings/notifications").with(csrf()).session(session).param("notifySnippets", "on"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/acme/dashboard"))
			.andExpect(flash().attribute("notice", containsString("Notification defaults")));
		Workspace optedIn = this.workspaces.findBySlug("acme").orElseThrow();
		assertThat(optedIn.notifySnippets()).isTrue();
		assertThat(optedIn.label()).isEqualTo("Acme"); // everything else preserved

		// the settings form shows the checkbox checked
		this.mockMvc.perform(get("/w/acme/settings").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("notifySnippets")));

		// opt out again (checkbox absent)
		this.mockMvc.perform(post("/w/acme/settings/notifications").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/acme/dashboard"));
		assertThat(this.workspaces.findBySlug("acme").orElseThrow().notifySnippets()).isFalse();

		// unknown slug returns to the picker without touching anything
		this.mockMvc
			.perform(
					post("/w/ghost/settings/notifications").with(csrf()).session(session).param("notifySnippets", "on"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));
	}

	@Test
	void exportReturnsZipWithManifestTablesAndWorkspaceRow() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/workspaces").with(csrf()).session(session).param("slug", "acme").param("label", "Acme"))
			.andExpect(status().is3xxRedirection());

		MvcResult result = this.mockMvc.perform(get("/w/acme/export").session(session))
			.andExpect(status().isOk())
			.andExpect(content().contentType("application/zip"))
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
					containsString("attachment; filename=\"scp-export-acme-")))
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(".zip\"")))
			.andReturn();

		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(
				new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				entries.put(entry.getName(), zip.readAllBytes());
			}
		}
		assertThat(entries).containsKeys("manifest.json", "tables/workspace.json", "tables/connection.json",
				"tables/post.json");
		String manifest = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);
		assertThat(manifest).contains("\"slug\":\"acme\"").contains("\"schemaVersion\":\"19\"");
		assertThat(new String(entries.get("tables/workspace.json"), StandardCharsets.UTF_8)).contains("acme");
	}

	@Test
	void exportRequiresLoginAndUnknownSlugRedirectsToPicker() throws Exception {
		this.mockMvc.perform(get("/w/acme/export"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
		MockHttpSession session = login();
		this.mockMvc.perform(get("/w/ghost/export").session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));
	}

	@Test
	void archiveRequiresLoginAndCsrfAndKnownSlug() throws Exception {
		this.mockMvc.perform(get("/w/acme/archive"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
		MockHttpSession session = login();
		this.mockMvc.perform(post("/w/acme/archive").session(session)).andExpect(status().isForbidden());
		this.mockMvc.perform(get("/w/ghost/archive").session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));
		this.mockMvc.perform(post("/w/ghost/archive").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));
	}

	@Test
	void archiveFlowShowsPreviewArchivesThenReopens() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/workspaces").with(csrf()).session(session).param("slug", "acme").param("label", "Acme"))
			.andExpect(status().is3xxRedirection());

		// the confirmation page lists what archiving would cancel/acknowledge (nothing
		// yet)
		this.mockMvc.perform(get("/w/acme/archive").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Archive Acme?")))
			.andExpect(content().string(containsString("0 pending schedule(s)")));

		// archiving flips the flag, flashes the counts and the settings page shows the
		// banner
		this.mockMvc.perform(post("/w/acme/archive").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/acme/settings"))
			.andExpect(flash().attribute("notice", containsString("archived")));
		Workspace archived = this.workspaces.findBySlug("acme").orElseThrow();
		assertThat(archived.archived()).isTrue();
		assertThat(archived.archivedAt()).isNotNull();
		this.mockMvc.perform(get("/w/acme/settings").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("This workspace is archived")));

		// archiving an already archived workspace fails closed with a flash error
		this.mockMvc.perform(post("/w/acme/archive").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/acme/settings"))
			.andExpect(flash().attribute("error", containsString("already archived")));

		// reopening clears the flag and the archived banner is replaced by the archive
		// link
		this.mockMvc.perform(post("/w/acme/reopen").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/acme/settings"))
			.andExpect(flash().attribute("notice", containsString("reopened")));
		Workspace reopened = this.workspaces.findBySlug("acme").orElseThrow();
		assertThat(reopened.archived()).isFalse();
		assertThat(reopened.archivedAt()).isNull();
		this.mockMvc.perform(get("/w/acme/settings").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Archive this workspace")));
	}

	private MockHttpSession login() throws Exception {
		MvcResult result = this.mockMvc.perform(formLogin().user("owner").password("test-password"))
			.andExpect(authenticated())
			.andReturn();
		return (MockHttpSession) result.getRequest().getSession();
	}

}
