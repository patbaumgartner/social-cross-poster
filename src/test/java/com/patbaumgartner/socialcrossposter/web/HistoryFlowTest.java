package com.patbaumgartner.socialcrossposter.web;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.patbaumgartner.socialcrossposter.accounts.AccountDestination;
import com.patbaumgartner.socialcrossposter.accounts.AccountRepository;
import com.patbaumgartner.socialcrossposter.accounts.Connection;
import com.patbaumgartner.socialcrossposter.accounts.ConnectionRepository;
import com.patbaumgartner.socialcrossposter.accounts.SecretCipher;
import com.patbaumgartner.socialcrossposter.contacts.ContactHandleRepository;
import com.patbaumgartner.socialcrossposter.contacts.ContactRepository;
import com.patbaumgartner.socialcrossposter.content.Post;
import com.patbaumgartner.socialcrossposter.content.PostRepository;
import com.patbaumgartner.socialcrossposter.content.PostRevision;
import com.patbaumgartner.socialcrossposter.content.PostRevisionRepository;
import com.patbaumgartner.socialcrossposter.content.PostService;
import com.patbaumgartner.socialcrossposter.content.RevisionDestinationRepository;
import com.patbaumgartner.socialcrossposter.content.RevisionOverrideRepository;
import com.patbaumgartner.socialcrossposter.publish.Delivery;
import com.patbaumgartner.socialcrossposter.publish.DeliveryAttempt;
import com.patbaumgartner.socialcrossposter.publish.DeliveryAttemptRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryJobRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryRepository;
import com.patbaumgartner.socialcrossposter.publish.Publication;
import com.patbaumgartner.socialcrossposter.publish.PublicationRepository;
import com.patbaumgartner.socialcrossposter.publish.PublicationService;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-06 first slice end-to-end (ADR-004, ADR-013, ADR-015): the history routes require
 * login, the list shows each publication with content summary, timestamps, aggregate
 * state and per-destination states, filters by destination/platform/state/text/date and
 * paginates, the detail shows the approved revision, actual payloads, provider identity,
 * external refs and redacted attempts, and foreign-workspace or unknown publication ids
 * fail closed (ADR-013). Publications are seeded directly through the repositories; each
 * test creates its connection/account once because (workspace, platform, provider
 * principal) is unique. The H2 database is shared across tests in this context, so
 * cleanup runs in FK-safe order before and after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HistoryFlowTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	WorkspaceService workspaces;

	@Autowired
	SecretCipher secrets;

	@Autowired
	ConnectionRepository connections;

	@Autowired
	AccountRepository accounts;

	@Autowired
	ContactRepository contacts;

	@Autowired
	ContactHandleRepository handles;

	@Autowired
	PostRepository posts;

	@Autowired
	PostRevisionRepository revisions;

	@Autowired
	RevisionDestinationRepository destinations;

	@Autowired
	RevisionOverrideRepository overrides;

	@Autowired
	PublicationRepository publications;

	@Autowired
	DeliveryRepository deliveries;

	@Autowired
	DeliveryAttemptRepository attempts;

	@Autowired
	DeliveryJobRepository jobs;

	@BeforeEach
	@AfterEach
	void resetState() {
		this.jobs.deleteAll();
		this.attempts.deleteAll();
		this.deliveries.deleteAll();
		this.publications.deleteAll();
		this.overrides.deleteAll();
		this.destinations.deleteAll();
		this.posts.clearCurrentRevisionPointers();
		this.revisions.deleteAll();
		this.posts.deleteAll();
		this.handles.deleteAll();
		this.contacts.deleteAll();
		this.accounts.deleteAll();
		this.connections.deleteAll();
	}

	@Test
	void historyRoutesRequireLogin() throws Exception {
		this.mockMvc.perform(get("/w/private/history"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
		this.mockMvc.perform(get("/w/private/history/1"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void listShowsPublicationWithSummaryStateAndDestinations() throws Exception {
		MockHttpSession session = login();
		AccountDestination account = seedAccount("private", "@testbot", "-1001234567890", "News channel");
		seedPublication(account, "News channel", "Launch post", "key-1", PublicationService.P_PUBLISHED,
				"hello world payload", PublicationService.D_SUCCESS, "telegram:message:99");

		this.mockMvc.perform(get("/w/private/history").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Launch post")))
			.andExpect(content().string(containsString("hello world payload")))
			.andExpect(content().string(containsString("PUBLISHED")))
			.andExpect(content().string(containsString("News channel")))
			.andExpect(content().string(containsString("SUCCESS")))
			.andExpect(content().string(containsString("Details")));
	}

	@Test
	void listFiltersByTextStatePlatformAndAccount() throws Exception {
		MockHttpSession session = login();
		AccountDestination news = seedAccount("private", "@testbot", "-1001234567890", "News channel");
		AccountDestination group = seedAccount("private", "@testbot-2", "-1001234567891", "Group chat");
		Seed alpha = seedPublication(news, "News channel", "Alpha post", "key-a", PublicationService.P_PUBLISHED,
				"alpha payload", PublicationService.D_SUCCESS, "telegram:message:1");
		seedPublication(group, "Group chat", "Beta post", "key-b", PublicationService.P_FAILED, "beta payload",
				PublicationService.D_FAILED, null);

		this.mockMvc.perform(get("/w/private/history").param("q", "alpha").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Alpha post")))
			.andExpect(content().string(not(containsString("Beta post"))));
		this.mockMvc.perform(get("/w/private/history").param("state", "FAILED").session(session))
			.andExpect(content().string(containsString("Beta post")))
			.andExpect(content().string(not(containsString("Alpha post"))));
		this.mockMvc.perform(get("/w/private/history").param("platform", "mastodon").session(session))
			.andExpect(content().string(containsString("No publications match")));
		this.mockMvc.perform(get("/w/private/history").param("account", alpha.accountId.toString()).session(session))
			.andExpect(content().string(containsString("Alpha post")))
			.andExpect(content().string(not(containsString("Beta post"))));
	}

	@Test
	void listPaginates() throws Exception {
		MockHttpSession session = login();
		AccountDestination account = seedAccount("private", "@testbot", "-1001234567890", "News channel");
		seedPublication(account, "News channel", "Pub one", "key-1", PublicationService.P_PUBLISHED, "p1",
				PublicationService.D_SUCCESS, "telegram:message:1");
		seedPublication(account, "News channel", "Pub two", "key-2", PublicationService.P_PUBLISHED, "p2",
				PublicationService.D_SUCCESS, "telegram:message:2");
		seedPublication(account, "News channel", "Pub three", "key-3", PublicationService.P_PUBLISHED, "p3",
				PublicationService.D_SUCCESS, "telegram:message:3");

		this.mockMvc.perform(get("/w/private/history").param("size", "2").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Pub three")))
			.andExpect(content().string(containsString("Pub two")))
			.andExpect(content().string(not(containsString("Pub one"))));
		this.mockMvc.perform(get("/w/private/history").param("size", "2").param("page", "1").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Pub one")))
			.andExpect(content().string(not(containsString("Pub three"))));
	}

	@Test
	void listDateFilterFromFutureShowsNothing() throws Exception {
		MockHttpSession session = login();
		AccountDestination account = seedAccount("private", "@testbot", "-1001234567890", "News channel");
		seedPublication(account, "News channel", "Launch post", "key-1", PublicationService.P_PUBLISHED, "hello",
				PublicationService.D_SUCCESS, "telegram:message:99");

		this.mockMvc.perform(get("/w/private/history").param("from", "2099-01-01").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("No publications match")));
	}

	@Test
	void detailShowsApprovedRevisionPayloadAttemptsAndExternalRef() throws Exception {
		MockHttpSession session = login();
		AccountDestination account = seedAccount("private", "@testbot", "-1001234567890", "News channel");
		Seed seed = seedPublication(account, "News channel", "Launch post", "key-1", PublicationService.P_PUBLISHED,
				"hello world payload", PublicationService.D_SUCCESS, "telegram:message:99");

		this.mockMvc.perform(get("/w/private/history/" + seed.publicationId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("publication #" + seed.publicationId)))
			.andExpect(content().string(containsString("Launch post")))
			.andExpect(content().string(containsString("key-1")))
			.andExpect(content().string(containsString("hello world payload")))
			.andExpect(content().string(containsString("-1001234567890")))
			.andExpect(content().string(containsString("telegram:message:99")))
			.andExpect(content().string(containsString("send")))
			.andExpect(content().string(containsString("SUCCESS")))
			.andExpect(content().string(containsString("redacted detail")));
	}

	@Test
	void foreignWorkspaceDetailFailsClosed() throws Exception {
		MockHttpSession session = login();
		AccountDestination account = seedAccount("private", "@testbot", "-1001234567890", "News channel");
		Seed seed = seedPublication(account, "News channel", "Private launch", "key-x", PublicationService.P_PUBLISHED,
				"secret payload", PublicationService.D_SUCCESS, "telegram:message:5");

		this.mockMvc.perform(get("/w/socrates-ch/history/" + seed.publicationId).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/socrates-ch/history"))
			.andExpect(flash().attribute("error", containsString("Unknown publication")));
	}

	@Test
	void unknownWorkspaceRedirectsToRoot() throws Exception {
		MockHttpSession session = login();
		this.mockMvc.perform(get("/w/nope/history").session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));
	}

	/**
	 * Creates one connection + destination account (unique per
	 * workspace+platform+principal).
	 */
	private AccountDestination seedAccount(String slug, String principal, String providerAccountId, String label) {
		Long workspaceId = workspaceId(slug);
		OffsetDateTime now = OffsetDateTime.now();
		var sealed = this.secrets.seal(SecretCipher.associatedData(slug, "telegram", principal), "good-token");
		Connection connection = this.connections.save(new Connection(null, workspaceId, "telegram", "pasted", principal,
				"Main bot", sealed.ciphertext(), sealed.nonce(), sealed.keyId(), "OK", now, true, now, now));
		return this.accounts.save(new AccountDestination(null, workspaceId, connection.id(), "telegram", "channel",
				providerAccountId, null, label, true, false, now, now));
	}

	private Seed seedPublication(AccountDestination account, String label, String postTitle, String key,
			String pubState, String payload, String deliveryState, String externalRef) {
		Long workspaceId = account.workspaceId();
		OffsetDateTime now = OffsetDateTime.now();
		Post post = this.posts
			.save(new Post(null, workspaceId, postTitle, PostService.STATE_DRAFT, null, 0L, now, now));
		PostRevision revision = this.revisions.save(new PostRevision(null, workspaceId, post.id(), 1, payload, now));
		this.posts
			.save(new Post(post.id(), workspaceId, postTitle, PostService.STATE_DRAFT, revision.id(), 1L, now, now));
		Publication publication = this.publications
			.save(new Publication(null, workspaceId, post.id(), revision.id(), key, pubState, "hash-" + key, now, now));
		Delivery delivery = this.deliveries
			.save(new Delivery(null, workspaceId, publication.id(), account.id(), "telegram", label, null,
					account.providerAccountId(), payload, "phash-" + key, deliveryState, externalRef, now, now));
		this.attempts.save(new DeliveryAttempt(null, workspaceId, delivery.id(), 1, "send", deliveryState,
				"redacted detail", externalRef, now));
		return new Seed(publication.id(), account.id());
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

	private record Seed(Long publicationId, Long accountId) {
	}

}
