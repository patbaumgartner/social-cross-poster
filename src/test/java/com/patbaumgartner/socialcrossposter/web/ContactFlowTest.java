package com.patbaumgartner.socialcrossposter.web;

import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.patbaumgartner.socialcrossposter.contacts.ContactHandleRepository;
import com.patbaumgartner.socialcrossposter.contacts.ContactRepository;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
 * UC-03 end-to-end (ADR-003, ADR-013): contacts CRUD, handle validation, workspace-scoped
 * autocomplete that never leaks private notes, mention previews (native vs. fallback),
 * archiving and foreign-id fail-closed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContactFlowTest {

	private static final String PHOTO_DATA_URI = "data:image/png;base64,"
			+ Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 });

	@Autowired
	MockMvc mockMvc;

	@Autowired
	WorkspaceService workspaces;

	@Autowired
	ContactRepository contacts;

	@Autowired
	ContactHandleRepository handles;

	@BeforeEach
	void resetState() {
		// The H2 in-memory database is shared across tests in this context.
		this.handles.deleteAll();
		this.contacts.deleteAll();
	}

	@Test
	void contactsPageRequiresLogin() throws Exception {
		this.mockMvc.perform(get("/w/private/contacts"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void emptyContactsPageRenders() throws Exception {
		MockHttpSession session = login();
		this.mockMvc.perform(get("/w/private/contacts").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("No contacts yet")))
			.andExpect(content().string(containsString("Mention autocomplete")));
	}

	@Test
	void createContactShowsTokenAndSearchNeverLeaksNotes() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/w/private/contacts").with(csrf())
				.session(session)
				.param("displayName", "Alice Example")
				.param("photo", PHOTO_DATA_URI)
				.param("notes", "private note xyzzy"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/contacts"))
			.andExpect(flash().attribute("notice", containsString("Alice Example")))
			.andExpect(flash().attribute("notice", containsString("{{contact:")));

		Long workspaceId = workspaceId("private");
		assertThat(this.contacts.findAllByWorkspaceIdOrderByDisplayNameAsc(workspaceId)).hasSize(1);
		var contact = this.contacts.findAllByWorkspaceIdOrderByDisplayNameAsc(workspaceId).get(0);
		assertThat(contact.token()).startsWith("{{contact:").endsWith("}}");
		assertThat(contact.photo()).isEqualTo(PHOTO_DATA_URI);

		// Page shows name + token, and the owner-only notes.
		this.mockMvc.perform(get("/w/private/contacts").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Alice Example")))
			.andExpect(content().string(containsString(contact.token())))
			.andExpect(content().string(containsString("private note xyzzy")));

		// Autocomplete finds her by '@ali' and returns the token but never the notes.
		this.mockMvc.perform(get("/w/private/contacts/search").session(session).param("q", "Hello @ali"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Alice Example")))
			.andExpect(content().string(containsString(contact.token())))
			.andExpect(content().string(not(containsString("private note xyzzy"))));
	}

	@Test
	void photoMustBeDataUriExternalUrlsRejected() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/w/private/contacts").with(csrf())
				.session(session)
				.param("displayName", "Bob")
				.param("photo", "https://evil.example/track.png"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("data:image")));
		assertThat(this.contacts.findAllByWorkspaceIdOrderByDisplayNameAsc(workspaceId("private"))).isEmpty();
	}

	@Test
	void handleValidationRejectsUnknownPlatformAndMissingMastodonHost() throws Exception {
		MockHttpSession session = login();
		Long contactId = createContact(session, "private", "Alice Example");

		this.mockMvc
			.perform(post("/w/private/contacts/" + contactId + "/handles").with(csrf())
				.session(session)
				.param("platform", "myspace")
				.param("handle", "alice"))
			.andExpect(flash().attribute("error", containsString("Unknown platform")));

		this.mockMvc
			.perform(post("/w/private/contacts/" + contactId + "/handles").with(csrf())
				.session(session)
				.param("platform", "mastodon")
				.param("handle", "alice"))
			.andExpect(flash().attribute("error", containsString("instance host")));

		this.mockMvc
			.perform(post("/w/private/contacts/" + contactId + "/handles").with(csrf())
				.session(session)
				.param("platform", "mastodon")
				.param("handle", "alice")
				.param("host", "mastodon.social"))
			.andExpect(flash().attribute("notice", containsString("mastodon alice@mastodon.social")));

		assertThat(this.handles.findAllByContactId(contactId)).hasSize(1);
	}

	@Test
	void duplicateHandleReusesExistingRowAndUpdatesIdentity() throws Exception {
		MockHttpSession session = login();
		Long contactId = createContact(session, "private", "Alice Example");
		addHandle(session, "private", contactId, "telegram", "@alice_example", null, "none", null);
		addHandle(session, "private", contactId, "telegram", "@alice_example", null, "handle", "@alice_example");

		var all = this.handles.findAllByContactId(contactId);
		assertThat(all).hasSize(1);
		assertThat(all.get(0).identityKind()).isEqualTo("handle");
		// Manual entry is never provider-verified (UC-03).
		assertThat(all.get(0).verified()).isFalse();
	}

	@Test
	void searchIsWorkspaceScopedAndExcludesArchived() throws Exception {
		MockHttpSession session = login();
		Long contactId = createContact(session, "private", "Alice Example");

		// Other workspace sees nothing.
		this.mockMvc.perform(get("/w/socrates-ch/contacts/search").session(session).param("q", "@ali"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("No matching contacts")));

		// Archive removes the contact from search but keeps the token resolvable.
		this.mockMvc
			.perform(post("/w/private/contacts/" + contactId + "/toggle").with(csrf())
				.session(session)
				.param("archived", "true"))
			.andExpect(status().is3xxRedirection());
		this.mockMvc.perform(get("/w/private/contacts/search").session(session).param("q", "@ali"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("No matching contacts")));

		this.mockMvc
			.perform(get("/w/private/contacts/" + contactId + "/mentions").session(session)
				.param("platform", "telegram"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("archived")))
			.andExpect(content().string(containsString("FALLBACK")));
	}

	@Test
	void foreignContactIdFailsClosed() throws Exception {
		MockHttpSession session = login();
		Long contactId = createContact(session, "private", "Alice Example");

		this.mockMvc
			.perform(post("/w/socrates-ch/contacts/" + contactId + "/handles").with(csrf())
				.session(session)
				.param("platform", "telegram")
				.param("handle", "@mallory"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/socrates-ch/contacts"))
			.andExpect(flash().attribute("error", containsString("Unknown contact")));
		assertThat(this.handles.findAllByContactId(contactId)).isEmpty();
	}

	@Test
	void telegramHandleIsNativeMentionOtherPlatformsFallBack() throws Exception {
		MockHttpSession session = login();
		Long contactId = createContact(session, "private", "Alice Example");
		addHandle(session, "private", contactId, "telegram", "@alice_example", null, "handle", "@alice_example");
		addHandle(session, "private", contactId, "bluesky", "alice.bsky.social", null, "did", "did:plc:abc123");

		// Telegram: verified contract -> native @username mention.
		this.mockMvc
			.perform(get("/w/private/contacts/" + contactId + "/mentions").session(session)
				.param("platform", "telegram"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("NATIVE")))
			.andExpect(content().string(containsString("@alice_example")))
			.andExpect(content().string(containsString("natively")));

		// Bluesky: no verified contract in code yet -> visible display-name
		// fallback, no invented DID mention, no notification claim.
		this.mockMvc
			.perform(
					get("/w/private/contacts/" + contactId + "/mentions").session(session).param("platform", "bluesky"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("FALLBACK")))
			.andExpect(content().string(containsString("Alice Example")))
			.andExpect(content().string(containsString("No verified mention contract")))
			.andExpect(content().string(not(containsString("did:plc:abc123"))));
	}

	@Test
	void unknownTokenInPreviewTextIsFlaggedNotDropped() throws Exception {
		MockHttpSession session = login();
		Long contactId = createContact(session, "private", "Alice Example");
		this.mockMvc
			.perform(get("/w/private/contacts/" + contactId + "/mentions").session(session)
				.param("platform", "telegram")
				.param("text", "Hi {{contact:00000000-0000-0000-0000-000000000000}}!"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("UNKNOWN_TOKEN")))
			.andExpect(content().string(containsString("{{contact:00000000-0000-0000-0000-000000000000}}")))
			.andExpect(content().string(containsString("never silently dropped")));
	}

	private Long createContact(MockHttpSession session, String slug, String displayName) throws Exception {
		this.mockMvc
			.perform(post("/w/" + slug + "/contacts").with(csrf()).session(session).param("displayName", displayName))
			.andExpect(status().is3xxRedirection());
		return this.contacts.findAllByWorkspaceIdOrderByDisplayNameAsc(workspaceId(slug)).get(0).id();
	}

	private void addHandle(MockHttpSession session, String slug, Long contactId, String platform, String handle,
			String host, String identityKind, String identityValue) throws Exception {
		var request = post("/w/" + slug + "/contacts/" + contactId + "/handles").with(csrf())
			.session(session)
			.param("platform", platform)
			.param("handle", handle);
		if (host != null) {
			request = request.param("host", host);
		}
		if (identityKind != null) {
			request = request.param("identityKind", identityKind);
		}
		if (identityValue != null) {
			request = request.param("identityValue", identityValue);
		}
		this.mockMvc.perform(request).andExpect(status().is3xxRedirection());
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
