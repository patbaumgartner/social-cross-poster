package com.patbaumgartner.socialcrossposter.web;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import com.patbaumgartner.socialcrossposter.notify.NotificationEventRepository;
import com.patbaumgartner.socialcrossposter.media.MediaAssetRepository;
import com.patbaumgartner.socialcrossposter.media.RevisionMediaRepository;
import org.springframework.test.web.servlet.MvcResult;

import com.patbaumgartner.socialcrossposter.accounts.AccountDestination;
import com.patbaumgartner.socialcrossposter.accounts.AccountRepository;
import com.patbaumgartner.socialcrossposter.accounts.Connection;
import com.patbaumgartner.socialcrossposter.accounts.ConnectionRepository;
import com.patbaumgartner.socialcrossposter.contacts.Contact;
import com.patbaumgartner.socialcrossposter.contacts.ContactHandle;
import com.patbaumgartner.socialcrossposter.contacts.ContactHandleRepository;
import com.patbaumgartner.socialcrossposter.contacts.ContactRepository;
import com.patbaumgartner.socialcrossposter.contacts.MentionToken;
import com.patbaumgartner.socialcrossposter.content.PostRepository;
import com.patbaumgartner.socialcrossposter.content.PostRevisionRepository;
import com.patbaumgartner.socialcrossposter.content.RevisionDestination;
import com.patbaumgartner.socialcrossposter.content.RevisionDestinationRepository;
import com.patbaumgartner.socialcrossposter.content.RevisionOverrideRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryAttemptRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryMediaRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryJobRepository;
import com.patbaumgartner.socialcrossposter.publish.RemoteActionAttemptRepository;
import com.patbaumgartner.socialcrossposter.publish.RemoteActionRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryRepository;
import com.patbaumgartner.socialcrossposter.publish.PublicationRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-04 end-to-end (ADR-003 points 1-3, ADR-004, ADR-013, ADR-015): compose form, draft
 * creation with destination selection, immutable revision on every save, per-destination
 * previews (counter, over-limit blocker, mention resolution, no note leakage), the
 * preview fragment for unsaved text and fail-closed unknown/foreign ids.
 * Connections/destinations are seeded directly through the repositories; no probe is
 * involved.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostFlowTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	WorkspaceService workspaces;

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

	@Autowired
	RemoteActionRepository remoteActions;

	@Autowired
	RemoteActionAttemptRepository remoteActionAttempts;

	@Autowired
	NotificationEventRepository notificationEvents;

	@Autowired
	RevisionMediaRepository revisionMedia;

	@Autowired
	MediaAssetRepository mediaAssets;

	@Autowired
	DeliveryMediaRepository deliveryMedia;

	@BeforeEach
	@AfterEach
	void resetState() {
		// The H2 in-memory database is shared across tests in this context; clean in
		// FK-safe order before and after each test so other test classes (accounts,
		// contacts) are never affected by leftover post rows.
		// notification_event references publication/delivery/schedule, so it goes first.
		this.notificationEvents.deleteAll();
		// revision_media references post_revision/media_asset — delete links, then
		// assets.
		this.revisionMedia.deleteAll();
		this.mediaAssets.deleteAll();
		this.remoteActionAttempts.deleteAll();
		this.remoteActions.deleteAll();
		this.jobs.deleteAll();
		this.attempts.deleteAll();
		// delivery_media references delivery — delete before deliveries.
		this.deliveryMedia.deleteAll();
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
	void composeAndPostsRequireLogin() throws Exception {
		this.mockMvc.perform(get("/w/private/compose"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
		this.mockMvc.perform(get("/w/private/posts"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void composeFormListsDestinationsAndPostsListStartsEmpty() throws Exception {
		MockHttpSession session = login();
		seedDestination("private", "News channel");
		this.mockMvc.perform(get("/w/private/compose").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("New post")))
			.andExpect(content().string(containsString("News channel")))
			.andExpect(content().string(containsString("data-mention-composer")));
		this.mockMvc.perform(get("/w/private/posts").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("No posts yet")));
	}

	@Test
	void createPostWithDestinationPersistsPostRevisionAndSelection() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		this.mockMvc
			.perform(post("/w/private/compose").with(csrf())
				.session(session)
				.param("title", "Launch")
				.param("text", "hello world")
				.param("accountIds", accountId.toString()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrlPattern("/w/private/posts/*"))
			.andExpect(flash().attribute("notice", containsString("revision 1")));

		Long workspaceId = workspaceId("private");
		var all = this.posts.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId);
		assertThat(all).hasSize(1);
		var post = all.get(0);
		assertThat(post.title()).isEqualTo("Launch");
		assertThat(post.state()).isEqualTo("DRAFT");
		assertThat(post.currentRevisionId()).isNotNull();
		var revision = this.revisions.findByIdAndWorkspaceId(post.currentRevisionId(), workspaceId).orElseThrow();
		assertThat(revision.revisionNo()).isEqualTo(1);
		assertThat(revision.defaultText()).isEqualTo("hello world");
		assertThat(this.destinations.findAllByRevisionId(revision.id())).extracting(RevisionDestination::accountId)
			.containsExactly(accountId);
	}

	@Test
	void savingCreatesNewImmutableRevision() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long postId = createPost(session, "private", "Launch", "v1 text", accountId);

		this.mockMvc
			.perform(post("/w/private/posts/" + postId).with(csrf())
				.session(session)
				.param("title", "Launch")
				.param("text", "v2 text")
				.param("accountIds", accountId.toString()))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("revision 2")));

		var revisions = this.revisions.findAllByPostIdOrderByRevisionNoAsc(postId);
		assertThat(revisions).hasSize(2);
		assertThat(revisions.get(0).defaultText()).isEqualTo("v1 text");
		assertThat(revisions.get(1).defaultText()).isEqualTo("v2 text");
		var post = this.posts.findById(postId).orElseThrow();
		assertThat(post.currentRevisionId()).isEqualTo(revisions.get(1).id());
		assertThat(post.version()).isEqualTo(2L);
	}

	@Test
	void postPageShowsPreviewCounterAndMentionResolutionWithoutLeakingNotes() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long workspaceId = workspaceId("private");
		String token = MentionToken.format(UUID.randomUUID());
		OffsetDateTime now = OffsetDateTime.now();
		Contact contact = this.contacts
			.save(new Contact(null, workspaceId, token, "Alice Example", null, "secret note xyzzy", false, now, now));
		this.handles.save(new ContactHandle(null, workspaceId, contact.id(), "telegram", "@alice_example", "", "handle",
				"@alice_example", false, now, now));

		String text = "thanks " + token;
		Long postId = createPost(session, "private", "Hi Alice", text, accountId);

		this.mockMvc.perform(get("/w/private/posts/" + postId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Destination previews")))
			.andExpect(content().string(containsString("News channel")))
			.andExpect(content().string(containsString(text.length() + " / 4096")))
			.andExpect(content().string(containsString("NATIVE")))
			.andExpect(content().string(containsString("@alice_example")))
			.andExpect(content().string(not(containsString("secret note xyzzy"))));
	}

	@Test
	void overLimitPreviewIsBlockedAndNeverTruncated() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		String big = "a".repeat(5000);
		Long postId = createPost(session, "private", "Big", big, accountId);

		this.mockMvc.perform(get("/w/private/posts/" + postId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("over limit")))
			.andExpect(content().string(containsString("5000 / 4096")))
			.andExpect(content().string(containsString("never truncates")))
			.andExpect(content().string(containsString("a".repeat(200))));
	}

	@Test
	void previewFragmentRendersUnsavedTextWithoutSaving() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long postId = createPost(session, "private", "T", "saved text", accountId);

		this.mockMvc
			.perform(
					get("/w/private/posts/" + postId + "/preview").session(session).param("text", "unsaved draft text"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("unsaved draft text")))
			.andExpect(content().string(containsString("18 / 4096")))
			.andExpect(content().string(not(containsString("saved text"))));

		// No new revision was created by previewing.
		assertThat(this.revisions.findAllByPostIdOrderByRevisionNoAsc(postId)).hasSize(1);
	}

	@Test
	void unknownDestinationIdFailsClosed() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/w/private/compose").with(csrf())
				.session(session)
				.param("title", "X")
				.param("text", "hi")
				.param("accountIds", "999999"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/compose"))
			.andExpect(flash().attribute("error", containsString("Unknown destination")));
		assertThat(this.posts.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId("private"))).isEmpty();
	}

	@Test
	void foreignPostIdFailsClosedAndPostsListIsWorkspaceScoped() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long postId = createPost(session, "private", "Private launch", "secret", accountId);

		this.mockMvc.perform(get("/w/socrates-ch/posts/" + postId).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/socrates-ch/posts"));
		this.mockMvc.perform(get("/w/socrates-ch/posts").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("Private launch"))));
	}

	@Test
	void draftSavesWithoutAnyDestination() throws Exception {
		MockHttpSession session = login();
		Long postId = createPost(session, "private", "No accounts", "lonely draft", null);
		this.mockMvc.perform(get("/w/private/posts/" + postId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("No destinations selected")))
			.andExpect(content().string(containsString("lonely draft")));
	}

	@Test
	void restoreEarlierRevisionCreatesNewRevisionAndKeepsAllOldRevisions() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long postId = createPost(session, "private", "Launch", "v1 text", accountId);
		this.mockMvc
			.perform(post("/w/private/posts/" + postId).with(csrf())
				.session(session)
				.param("title", "Launch")
				.param("text", "v2 text")
				.param("accountIds", accountId.toString()))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("revision 2")));
		var before = this.revisions.findAllByPostIdOrderByRevisionNoAsc(postId);
		assertThat(before).hasSize(2);
		Long rev1Id = before.get(0).id();

		this.mockMvc.perform(post("/w/private/posts/" + postId + "/restore/" + rev1Id).with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/posts/" + postId))
			.andExpect(flash().attribute("notice", containsString("Restored")))
			.andExpect(flash().attribute("notice", containsString("revision #3")));

		var after = this.revisions.findAllByPostIdOrderByRevisionNoAsc(postId);
		assertThat(after).hasSize(3);
		assertThat(after.get(0).defaultText()).isEqualTo("v1 text");
		assertThat(after.get(1).defaultText()).isEqualTo("v2 text");
		assertThat(after.get(2).revisionNo()).isEqualTo(3);
		assertThat(after.get(2).defaultText()).isEqualTo("v1 text");
		assertThat(this.destinations.findAllByRevisionId(after.get(2).id())).extracting(RevisionDestination::accountId)
			.containsExactly(accountId);
		var post = this.posts.findById(postId).orElseThrow();
		assertThat(post.currentRevisionId()).isEqualTo(after.get(2).id());
		assertThat(post.version()).isEqualTo(3L);

		this.mockMvc.perform(get("/w/private/posts/" + postId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Restore as new revision")));
	}

	@Test
	void staleExpectedVersionSavesNothingAndReportsConflict() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long postId = createPost(session, "private", "Launch", "v1 text", accountId);
		Long rev1Id = this.revisions.findAllByPostIdOrderByRevisionNoAsc(postId).get(0).id();

		this.mockMvc
			.perform(post("/w/private/posts/" + postId).with(csrf())
				.session(session)
				.param("title", "Launch")
				.param("text", "concurrent edit")
				.param("accountIds", accountId.toString())
				.param("expectedVersion", "99"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/posts/" + postId))
			.andExpect(flash().attribute("error", containsString("Conflict")))
			.andExpect(flash().attribute("error", containsString("expected version 99")));

		assertThat(this.revisions.findAllByPostIdOrderByRevisionNoAsc(postId)).hasSize(1);
		var post = this.posts.findById(postId).orElseThrow();
		assertThat(post.version()).isEqualTo(1L);
		assertThat(post.currentRevisionId()).isEqualTo(rev1Id);
	}

	@Test
	void matchingExpectedVersionSavesNewRevision() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long postId = createPost(session, "private", "Launch", "v1 text", accountId);

		this.mockMvc
			.perform(post("/w/private/posts/" + postId).with(csrf())
				.session(session)
				.param("title", "Launch")
				.param("text", "v2 text")
				.param("accountIds", accountId.toString())
				.param("expectedVersion", "1"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("revision 2")));

		assertThat(this.revisions.findAllByPostIdOrderByRevisionNoAsc(postId)).hasSize(2);
		assertThat(this.posts.findById(postId).orElseThrow().version()).isEqualTo(2L);
	}

	@Test
	void restoreFailsClosedForForeignRevisionForeignWorkspaceAndUnknownSlug() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long postId = createPost(session, "private", "Private launch", "secret", accountId);
		Long rev1Id = this.revisions.findAllByPostIdOrderByRevisionNoAsc(postId).get(0).id();

		this.mockMvc.perform(post("/w/private/posts/" + postId + "/restore/999999").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("Unknown revision")));
		this.mockMvc
			.perform(post("/w/socrates-ch/posts/" + postId + "/restore/" + rev1Id).with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/socrates-ch/posts/" + postId))
			.andExpect(flash().attribute("error", containsString("Unknown post")));
		this.mockMvc
			.perform(post("/w/no-such-workspace/posts/" + postId + "/restore/" + rev1Id).with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));

		assertThat(this.revisions.findAllByPostIdOrderByRevisionNoAsc(postId)).hasSize(1);
		assertThat(this.posts.findById(postId).orElseThrow().version()).isEqualTo(1L);
	}

	@Test
	void duplicateCreatesIndependentCopyAndLeavesSourceUntouched() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long postId = createPost(session, "private", "Launch", "v1 text", accountId);
		Long sourceRevId = this.revisions.findAllByPostIdOrderByRevisionNoAsc(postId).get(0).id();

		this.mockMvc.perform(post("/w/private/posts/" + postId + "/duplicate").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrlPattern("/w/private/posts/*"))
			.andExpect(flash().attribute("notice", containsString("Duplicated as new draft")));

		Long workspaceId = workspaceId("private");
		var all = this.posts.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId);
		assertThat(all).hasSize(2);
		var copy = all.stream().filter((p) -> !p.id().equals(postId)).findFirst().orElseThrow();
		assertThat(copy.title()).isEqualTo("Launch (copy)");
		assertThat(copy.state()).isEqualTo("DRAFT");
		assertThat(copy.version()).isEqualTo(1L);
		var copyRevisions = this.revisions.findAllByPostIdOrderByRevisionNoAsc(copy.id());
		assertThat(copyRevisions).hasSize(1);
		assertThat(copyRevisions.get(0).revisionNo()).isEqualTo(1);
		assertThat(copyRevisions.get(0).defaultText()).isEqualTo("v1 text");
		assertThat(copy.currentRevisionId()).isEqualTo(copyRevisions.get(0).id());
		assertThat(this.destinations.findAllByRevisionId(copyRevisions.get(0).id()))
			.extracting(RevisionDestination::accountId)
			.containsExactly(accountId);

		// the source post is untouched: still one revision, version 1, same pointer
		assertThat(this.revisions.findAllByPostIdOrderByRevisionNoAsc(postId)).hasSize(1);
		var source = this.posts.findById(postId).orElseThrow();
		assertThat(source.version()).isEqualTo(1L);
		assertThat(source.currentRevisionId()).isEqualTo(sourceRevId);
		assertThat(source.title()).isEqualTo("Launch");

		// the copy opens as its own draft and the drafts list offers Duplicate
		this.mockMvc.perform(get("/w/private/posts/" + copy.id()).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Launch (copy)")))
			.andExpect(content().string(containsString("v1 text")))
			.andExpect(content().string(containsString("Duplicate as new draft")));
		this.mockMvc.perform(get("/w/private/posts").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Launch (copy)")))
			.andExpect(content().string(containsString(">Duplicate</button>")));
	}

	@Test
	void duplicateRequiresLogin() throws Exception {
		this.mockMvc.perform(post("/w/private/posts/1/duplicate").with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void duplicateFailsClosedForForeignWorkspaceAndUnknownSlug() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long postId = createPost(session, "private", "Private launch", "secret", accountId);

		this.mockMvc.perform(post("/w/socrates-ch/posts/" + postId + "/duplicate").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/socrates-ch/posts/" + postId))
			.andExpect(flash().attribute("error", containsString("Unknown post")));
		this.mockMvc.perform(post("/w/no-such-workspace/posts/" + postId + "/duplicate").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));

		// no copy was created anywhere in the private workspace
		assertThat(this.posts.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId("private"))).hasSize(1);
	}

	private Long seedDestination(String slug, String label) {
		Long workspaceId = workspaceId(slug);
		OffsetDateTime now = OffsetDateTime.now();
		Connection connection = this.connections.save(new Connection(null, workspaceId, "telegram", "pasted",
				"@testbot", "Main bot", "cipher", "nonce", "test-v1", "OK", now, true, now, now));
		return this.accounts
			.save(new AccountDestination(null, workspaceId, connection.id(), "telegram", "channel", "-1001234567890",
					null, label, true, false, now, now))
			.id();
	}

	private Long createPost(MockHttpSession session, String slug, String title, String text, Long accountId)
			throws Exception {
		var request = post("/w/" + slug + "/compose").with(csrf())
			.session(session)
			.param("title", title)
			.param("text", text);
		if (accountId != null) {
			request = request.param("accountIds", accountId.toString());
		}
		this.mockMvc.perform(request).andExpect(status().is3xxRedirection());
		return this.posts.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId(slug)).get(0).id();
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
