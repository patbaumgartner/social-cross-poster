package com.patbaumgartner.socialcrossposter.web;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

	private MockHttpSession login() throws Exception {
		MvcResult result = this.mockMvc.perform(formLogin().user("owner").password("test-password"))
			.andExpect(authenticated())
			.andReturn();
		return (MockHttpSession) result.getRequest().getSession();
	}

}
