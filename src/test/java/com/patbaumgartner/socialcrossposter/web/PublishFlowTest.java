package com.patbaumgartner.socialcrossposter.web;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import com.patbaumgartner.socialcrossposter.accounts.SecretCipher;
import com.patbaumgartner.socialcrossposter.contacts.Contact;
import com.patbaumgartner.socialcrossposter.contacts.ContactHandle;
import com.patbaumgartner.socialcrossposter.contacts.ContactHandleRepository;
import com.patbaumgartner.socialcrossposter.contacts.ContactRepository;
import com.patbaumgartner.socialcrossposter.contacts.MentionToken;
import com.patbaumgartner.socialcrossposter.content.PostRepository;
import com.patbaumgartner.socialcrossposter.content.PostRevisionRepository;
import com.patbaumgartner.socialcrossposter.content.RevisionDestinationRepository;
import com.patbaumgartner.socialcrossposter.content.RevisionOverrideRepository;
import com.patbaumgartner.socialcrossposter.platform.PublishContext;
import com.patbaumgartner.socialcrossposter.platform.PublishResult;
import com.patbaumgartner.socialcrossposter.platform.TelegramPublisher;
import com.patbaumgartner.socialcrossposter.publish.Delivery;
import com.patbaumgartner.socialcrossposter.publish.DeliveryAttempt;
import com.patbaumgartner.socialcrossposter.publish.DeliveryAttemptRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryJob;
import com.patbaumgartner.socialcrossposter.publish.DeliveryJobRepository;
import com.patbaumgartner.socialcrossposter.publish.RemoteActionAttemptRepository;
import com.patbaumgartner.socialcrossposter.publish.RemoteActionRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryRepository;
import com.patbaumgartner.socialcrossposter.publish.Publication;
import com.patbaumgartner.socialcrossposter.publish.PublicationRepository;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
 * UC-05 first slice end-to-end (ADR-001, ADR-013, ADR-015): the publish routes require
 * login, the confirmation page shows previews/blockers and a submission key, publishing
 * creates an idempotent publication and executes every delivery synchronously with the
 * mention-rendered payload (telegram publisher mocked; the stored secret is really
 * sealed/opened through {@link SecretCipher}), repeated submits under the same key return
 * the existing publication while changed content under the same key is rejected,
 * blockers/missing destinations block, provider failures classify FAILED and settle into
 * a durable retry (RETRY_WAIT + PENDING job, UC-07), and foreign post ids fail closed.
 * The H2 database is shared across tests in this context, so cleanup runs in FK-safe
 * order (delivery_job first) before and after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublishFlowTest {

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

	@Autowired
	RemoteActionRepository remoteActions;

	@Autowired
	RemoteActionAttemptRepository remoteActionAttempts;

	@MockitoBean
	TelegramPublisher telegramPublisher;

	@BeforeEach
	@AfterEach
	void resetState() {
		this.remoteActionAttempts.deleteAll();
		this.remoteActions.deleteAll();
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

	@BeforeEach
	void stubPublisher() {
		given(this.telegramPublisher.platform()).willReturn("telegram");
		given(this.telegramPublisher.publish(any(PublishContext.class)))
			.willReturn(PublishResult.success("telegram:message:99"));
	}

	@Test
	void publishRoutesRequireLogin() throws Exception {
		this.mockMvc.perform(get("/w/private/posts/1/publish"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
		this.mockMvc.perform(post("/w/private/posts/1/publish").with(csrf()).param("submissionKey", "k"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void confirmPageShowsPreviewsAndGeneratedSubmissionKey() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long postId = createPost(session, "private", "Launch", "hello world", accountId);

		this.mockMvc.perform(get("/w/private/posts/" + postId + "/publish").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Confirm publication")))
			.andExpect(content().string(containsString("News channel")))
			.andExpect(content().string(containsString("hello world")))
			.andExpect(content().string(containsString("name=\"submissionKey\"")));
	}

	@Test
	void publishNowCreatesPublicationAndDeliversMentionRenderedPayload() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long workspaceId = workspaceId("private");
		String token = MentionToken.format(UUID.randomUUID());
		OffsetDateTime now = OffsetDateTime.now();
		Contact contact = this.contacts
			.save(new Contact(null, workspaceId, token, "Alice Example", null, "secret note xyzzy", false, now, now));
		this.handles.save(new ContactHandle(null, workspaceId, contact.id(), "telegram", "@alice_example", "", "handle",
				"@alice_example", false, now, now));
		Long postId = createPost(session, "private", "Hi Alice", "thanks " + token, accountId);

		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", "key-1"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/posts/" + postId))
			.andExpect(flash().attribute("notice", containsString("PUBLISHED")));

		List<Publication> pubs = publicationsOf(workspaceId);
		assertThat(pubs).hasSize(1);
		assertThat(pubs.get(0).submissionKey()).isEqualTo("key-1");
		assertThat(pubs.get(0).state()).isEqualTo("PUBLISHED");
		List<Delivery> ds = this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(workspaceId,
				pubs.get(0).id());
		assertThat(ds).hasSize(1);
		assertThat(ds.get(0).state()).isEqualTo("SUCCESS");
		assertThat(ds.get(0).externalRef()).isEqualTo("telegram:message:99");
		assertThat(ds.get(0).payloadText()).isEqualTo("thanks @alice_example");
		assertThat(ds.get(0).payloadHash()).hasSize(64);
		List<DeliveryAttempt> att = this.attempts.findAllByDeliveryIdOrderByIdAsc(ds.get(0).id());
		assertThat(att).hasSize(1);
		assertThat(att.get(0).outcome()).isEqualTo("SUCCESS");

		ArgumentCaptor<PublishContext> captor = ArgumentCaptor.forClass(PublishContext.class);
		verify(this.telegramPublisher).publish(captor.capture());
		assertThat(captor.getValue().credential()).isEqualTo("good-token");
		assertThat(captor.getValue().targetId()).isEqualTo("-1001234567890");
		assertThat(captor.getValue().text()).isEqualTo("thanks @alice_example");

		this.mockMvc.perform(get("/w/private/posts/" + postId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Publications")))
			.andExpect(content().string(containsString("key-1")))
			.andExpect(content().string(containsString("PUBLISHED")))
			.andExpect(content().string(containsString("telegram:message:99")));
	}

	@Test
	void doubleSubmitWithSameKeyReturnsExistingPublicationWithoutResending() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long workspaceId = workspaceId("private");
		Long postId = createPost(session, "private", "Launch", "hello", accountId);

		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", "key-1"))
			.andExpect(flash().attribute("notice", containsString("PUBLISHED")));
		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", "key-1"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("already used")));

		assertThat(publicationsOf(workspaceId)).hasSize(1);
		Publication pub = publicationsOf(workspaceId).get(0);
		assertThat(this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(workspaceId, pub.id())).hasSize(1);
		verify(this.telegramPublisher, times(1)).publish(any(PublishContext.class));
	}

	@Test
	void sameKeyWithChangedContentIsRejected() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long workspaceId = workspaceId("private");
		Long postId = createPost(session, "private", "Launch", "v1 text", accountId);
		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", "key-1"))
			.andExpect(flash().attribute("notice", containsString("PUBLISHED")));

		// New revision changes the approved payload under the same key.
		this.mockMvc
			.perform(post("/w/private/posts/" + postId).with(csrf())
				.session(session)
				.param("title", "Launch")
				.param("text", "changed text")
				.param("accountIds", accountId.toString()))
			.andExpect(status().is3xxRedirection());
		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", "key-1"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("different content")));

		assertThat(publicationsOf(workspaceId)).hasSize(1);
	}

	@Test
	void overLimitPostIsBlockedAndPublishCreatesNothing() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long workspaceId = workspaceId("private");
		Long postId = createPost(session, "private", "Big", "a".repeat(5000), accountId);

		this.mockMvc.perform(get("/w/private/posts/" + postId + "/publish").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Publishing is blocked")));
		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", "key-big"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("blocked")));

		assertThat(publicationsOf(workspaceId)).isEmpty();
		verify(this.telegramPublisher, times(0)).publish(any(PublishContext.class));
	}

	@Test
	void publishWithoutSelectedDestinationFails() throws Exception {
		MockHttpSession session = login();
		Long workspaceId = workspaceId("private");
		Long postId = createPost(session, "private", "No accounts", "lonely draft", null);

		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", "key-none"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("at least one destination")));

		assertThat(publicationsOf(workspaceId)).isEmpty();
	}

	@Test
	void confirmedProviderFailureSchedulesDurableRetry() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long workspaceId = workspaceId("private");
		Long postId = createPost(session, "private", "Launch", "hello", accountId);
		given(this.telegramPublisher.publish(any(PublishContext.class)))
			.willReturn(PublishResult.failed("Telegram rejected the message (HTTP 400)."));

		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", "key-fail"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("IN_PROGRESS")));

		Publication pub = publicationsOf(workspaceId).get(0);
		assertThat(pub.state()).isEqualTo("IN_PROGRESS");
		List<Delivery> ds = this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(workspaceId, pub.id());
		assertThat(ds.get(0).state()).isEqualTo("RETRY_WAIT");
		assertThat(ds.get(0).externalRef()).isNull();
		List<DeliveryAttempt> att = this.attempts.findAllByDeliveryIdOrderByIdAsc(ds.get(0).id());
		assertThat(att.get(0).outcome()).isEqualTo("FAILED");
		assertThat(att.get(0).detail()).contains("HTTP 400");
		DeliveryJob job = this.jobs.findByDeliveryIdAndWorkspaceId(ds.get(0).id(), workspaceId).orElseThrow();
		assertThat(job.state()).isEqualTo("PENDING");
		assertThat(job.nextRunAt()).isAfter(ds.get(0).updatedAt());
	}

	@Test
	void foreignPostIdFailsClosed() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long postId = createPost(session, "private", "Private launch", "secret", accountId);
		Long foreignWorkspaceId = workspaceId("socrates-ch");

		this.mockMvc.perform(get("/w/socrates-ch/posts/" + postId + "/publish").session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/socrates-ch/posts/" + postId));
		this.mockMvc
			.perform(post("/w/socrates-ch/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", "key-foreign"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/socrates-ch/posts/" + postId))
			.andExpect(flash().attribute("error", containsString("Unknown post")));

		assertThat(publicationsOf(foreignWorkspaceId)).isEmpty();
		verify(this.telegramPublisher, times(0)).publish(any(PublishContext.class));
	}

	private Long seedDestination(String slug, String label) {
		Long workspaceId = workspaceId(slug);
		OffsetDateTime now = OffsetDateTime.now();
		var sealed = this.secrets.seal(SecretCipher.associatedData(slug, "telegram", "@testbot"), "good-token");
		Connection connection = this.connections
			.save(new Connection(null, workspaceId, "telegram", "pasted", "@testbot", "Main bot", sealed.ciphertext(),
					sealed.nonce(), sealed.keyId(), "OK", now, true, now, now));
		return this.accounts
			.save(new AccountDestination(null, workspaceId, connection.id(), "telegram", "channel", "-1001234567890",
					null, label, true, false, now, now))
			.id();
	}

	private List<Publication> publicationsOf(Long workspaceId) {
		List<Publication> result = new ArrayList<>();
		this.publications.findAll().forEach((p) -> {
			if (p.workspaceId().equals(workspaceId)) {
				result.add(p);
			}
		});
		return result;
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
