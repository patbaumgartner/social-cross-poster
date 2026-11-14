package com.patbaumgartner.socialcrossposter.web;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.patbaumgartner.socialcrossposter.accounts.AccountDestination;
import com.patbaumgartner.socialcrossposter.accounts.AccountRepository;
import com.patbaumgartner.socialcrossposter.accounts.Connection;
import com.patbaumgartner.socialcrossposter.accounts.ConnectionRepository;
import com.patbaumgartner.socialcrossposter.platform.ProbeException;
import com.patbaumgartner.socialcrossposter.platform.ProbeResult;
import com.patbaumgartner.socialcrossposter.platform.TelegramBotProbe;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
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
 * UC-02 end-to-end with the Telegram probe mocked: connect (probe + encrypted secret),
 * failed probe, duplicate handling, destinations, rotation, health, archive and workspace
 * isolation (ADR-006, ADR-007, ADR-013).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountFlowTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	WorkspaceService workspaces;

	@Autowired
	ConnectionRepository connections;

	@Autowired
	AccountRepository accounts;

	@MockitoBean
	TelegramBotProbe telegramProbe;

	@BeforeEach
	void resetState() {
		// The H2 in-memory database is shared across tests in this context;
		// each test starts from a clean accounts state.
		this.accounts.deleteAll();
		this.connections.deleteAll();
	}

	@BeforeEach
	void stubProbe() {
		given(this.telegramProbe.platform()).willReturn("telegram");
		given(this.telegramProbe.supportedModes()).willReturn(Set.of("pasted"));
		given(this.telegramProbe.probe("pasted", "good-token"))
			.willReturn(new ProbeResult("@testbot", "@testbot (id 42)"));
		given(this.telegramProbe.probe("pasted", "other-token"))
			.willReturn(new ProbeResult("@otherbot", "@otherbot (id 43)"));
		given(this.telegramProbe.probe("pasted", "bad-token"))
			.willThrow(new ProbeException("Telegram rejected the bot token (HTTP 401)."));
	}

	@Test
	void accountsPageRequiresLogin() throws Exception {
		this.mockMvc.perform(get("/w/private/accounts"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void emptyAccountsPageRenders() throws Exception {
		MockHttpSession session = login();
		this.mockMvc.perform(get("/w/private/accounts").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("No connections yet")))
			.andExpect(content().string(containsString("No destinations yet")))
			.andExpect(content().string(containsString("telegram")));
	}

	@Test
	void connectStoresSecretEncryptedAndPageNeverShowsIt() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/w/private/accounts/connections").with(csrf())
				.session(session)
				.param("platform", "telegram")
				.param("mode", "pasted")
				.param("label", "Main bot")
				.param("credential", "good-token"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/accounts"))
			.andExpect(flash().attribute("notice", containsString("@testbot")));

		Long workspaceId = workspaceId("private");
		Connection saved = this.connections
			.findByWorkspaceIdAndPlatformAndProviderPrincipal(workspaceId, "telegram", "@testbot")
			.orElseThrow();
		assertThat(saved.enabled()).isTrue();
		assertThat(saved.healthState()).isEqualTo("OK");
		assertThat(saved.secretCipher()).isNotEqualTo("good-token");
		assertThat(saved.secretCipher()).doesNotContain("good-token");
		assertThat(saved.secretKeyId()).isEqualTo("test-v1");

		this.mockMvc.perform(get("/w/private/accounts").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("@testbot")))
			.andExpect(content().string(not(containsString("good-token"))))
			.andExpect(content().string(not(containsString(saved.secretCipher()))));
	}

	@Test
	void failedProbePersistsDisabledUnverifiedRowWithSanitizedError() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/w/private/accounts/connections").with(csrf())
				.session(session)
				.param("platform", "telegram")
				.param("mode", "pasted")
				.param("credential", "bad-token"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/accounts"))
			.andExpect(flash().attribute("error", containsString("HTTP 401")));

		Long workspaceId = workspaceId("private");
		List<Connection> all = this.connections.findAllByWorkspaceIdOrderByPlatformAscProviderPrincipalAsc(workspaceId);
		assertThat(all).hasSize(1);
		Connection failed = all.get(0);
		assertThat(failed.providerPrincipal()).startsWith("unverified-");
		assertThat(failed.enabled()).isFalse();
		assertThat(failed.healthState()).isEqualTo("ERROR");

		this.mockMvc.perform(get("/w/private/accounts").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("HTTP 401")))
			.andExpect(content().string(containsString("unverified-")))
			.andExpect(content().string(not(containsString("bad-token"))));
	}

	@Test
	void duplicateConnectionOpensExistingRow() throws Exception {
		MockHttpSession session = login();
		connect(session, "private", "good-token");
		this.mockMvc
			.perform(post("/w/private/accounts/connections").with(csrf())
				.session(session)
				.param("platform", "telegram")
				.param("mode", "pasted")
				.param("credential", "good-token"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("Already connected")));
		assertThat(this.connections.findAllByWorkspaceIdOrderByPlatformAscProviderPrincipalAsc(workspaceId("private")))
			.hasSize(1);
	}

	@Test
	void addDestinationAndDuplicateReusesSameRow() throws Exception {
		MockHttpSession session = login();
		connect(session, "private", "good-token");
		Long connectionId = this.connections
			.findByWorkspaceIdAndPlatformAndProviderPrincipal(workspaceId("private"), "telegram", "@testbot")
			.orElseThrow()
			.id();

		addDestination(session, "private", connectionId, "-1001234567890", "News channel");
		this.mockMvc.perform(get("/w/private/accounts").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("News channel")))
			.andExpect(content().string(containsString("-1001234567890")));

		// Exact duplicate destination re-enables the existing entry, no second row.
		addDestination(session, "private", connectionId, "-1001234567890", "News channel");
		assertThat(this.accounts.findAllByWorkspaceIdAndArchivedFalseOrderByLabelAsc(workspaceId("private")))
			.hasSize(1);
	}

	@Test
	void workspaceIsolationHidesOtherWorkspacesAccounts() throws Exception {
		MockHttpSession session = login();
		connect(session, "private", "good-token");
		this.mockMvc.perform(get("/w/socrates-ch/accounts").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("@testbot"))))
			.andExpect(content().string(containsString("No connections yet")));
	}

	@Test
	void rotateWithChangedPrincipalIsRejected() throws Exception {
		MockHttpSession session = login();
		connect(session, "private", "good-token");
		Long connectionId = this.connections
			.findByWorkspaceIdAndPlatformAndProviderPrincipal(workspaceId("private"), "telegram", "@testbot")
			.orElseThrow()
			.id();

		this.mockMvc
			.perform(post("/w/private/accounts/connections/" + connectionId + "/rotate").with(csrf())
				.session(session)
				.param("credential", "other-token"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("new connection")));

		Connection unchanged = this.connections.findById(connectionId).orElseThrow();
		assertThat(unchanged.providerPrincipal()).isEqualTo("@testbot");
	}

	@Test
	void healthCheckReProbesWithStoredSecret() throws Exception {
		MockHttpSession session = login();
		connect(session, "private", "good-token");
		Long connectionId = this.connections
			.findByWorkspaceIdAndPlatformAndProviderPrincipal(workspaceId("private"), "telegram", "@testbot")
			.orElseThrow()
			.id();
		this.mockMvc
			.perform(post("/w/private/accounts/connections/" + connectionId + "/health").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attributeExists("notice"));
		assertThat(this.connections.findById(connectionId).orElseThrow().healthState()).isEqualTo("OK");
	}

	@Test
	void archiveDestinationRemovesItFromTheList() throws Exception {
		MockHttpSession session = login();
		connect(session, "private", "good-token");
		Long connectionId = this.connections
			.findByWorkspaceIdAndPlatformAndProviderPrincipal(workspaceId("private"), "telegram", "@testbot")
			.orElseThrow()
			.id();
		addDestination(session, "private", connectionId, "-1001234567890", "News channel");
		AccountDestination destination = this.accounts
			.findByWorkspaceIdAndPlatformAndProviderAccountId(workspaceId("private"), "telegram", "-1001234567890")
			.orElseThrow();

		this.mockMvc
			.perform(post("/w/private/accounts/destinations/" + destination.id() + "/delete").with(csrf())
				.session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("archived")));

		assertThat(this.accounts.findById(destination.id()).orElseThrow().archived()).isTrue();
		this.mockMvc.perform(get("/w/private/accounts").session(session))
			.andExpect(content().string(not(containsString("News channel"))));
	}

	@Test
	void foreignConnectionIdFailsClosedToRedirect() throws Exception {
		MockHttpSession session = login();
		connect(session, "private", "good-token");
		Long connectionId = this.connections
			.findByWorkspaceIdAndPlatformAndProviderPrincipal(workspaceId("private"), "telegram", "@testbot")
			.orElseThrow()
			.id();

		// socrates-ch must not touch private's connection: sanitized error, no 500,
		// unchanged row.
		this.mockMvc
			.perform(post("/w/socrates-ch/accounts/connections/" + connectionId + "/health").with(csrf())
				.session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/socrates-ch/accounts"))
			.andExpect(flash().attribute("error", containsString("Unknown connection")));
		assertThat(this.connections.findById(connectionId).orElseThrow().providerPrincipal()).isEqualTo("@testbot");
	}

	private void connect(MockHttpSession session, String slug, String credential) throws Exception {
		this.mockMvc
			.perform(post("/w/" + slug + "/accounts/connections").with(csrf())
				.session(session)
				.param("platform", "telegram")
				.param("mode", "pasted")
				.param("credential", credential))
			.andExpect(status().is3xxRedirection());
	}

	private void addDestination(MockHttpSession session, String slug, Long connectionId, String providerAccountId,
			String label) throws Exception {
		this.mockMvc
			.perform(post("/w/" + slug + "/accounts/destinations").with(csrf())
				.session(session)
				.param("connectionId", connectionId.toString())
				.param("kind", "channel")
				.param("providerAccountId", providerAccountId)
				.param("label", label))
			.andExpect(status().is3xxRedirection());
	}

	private Long workspaceId(String slug) {
		return this.workspaces.findBySlug(slug).orElseThrow().id();
	}

	private MockHttpSession login() throws Exception {
		MvcResult result = this.mockMvc.perform(formLogin().user("owner").password("test-password"))
			.andExpect(authenticated())
			.andReturn();
		return (MockHttpSession) result.getRequest().getSession();
	}

}
