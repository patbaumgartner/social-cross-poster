package com.patbaumgartner.socialcrossposter.web;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.patbaumgartner.socialcrossposter.notify.NotificationEventRepository;
import org.springframework.test.web.servlet.MvcResult;

import com.patbaumgartner.socialcrossposter.accounts.AccountDestination;
import com.patbaumgartner.socialcrossposter.accounts.AccountRepository;
import com.patbaumgartner.socialcrossposter.accounts.Connection;
import com.patbaumgartner.socialcrossposter.accounts.ConnectionRepository;
import com.patbaumgartner.socialcrossposter.accounts.SecretCipher;
import com.patbaumgartner.socialcrossposter.contacts.ContactHandleRepository;
import com.patbaumgartner.socialcrossposter.contacts.ContactRepository;
import com.patbaumgartner.socialcrossposter.content.PostRepository;
import com.patbaumgartner.socialcrossposter.content.PostRevisionRepository;
import com.patbaumgartner.socialcrossposter.content.RevisionDestinationRepository;
import com.patbaumgartner.socialcrossposter.content.RevisionOverrideRepository;
import com.patbaumgartner.socialcrossposter.platform.PublishContext;
import com.patbaumgartner.socialcrossposter.platform.PublishResult;
import com.patbaumgartner.socialcrossposter.platform.RemoteActionContext;
import com.patbaumgartner.socialcrossposter.platform.TelegramPublisher;
import com.patbaumgartner.socialcrossposter.platform.TelegramRemoteActor;
import com.patbaumgartner.socialcrossposter.publish.Delivery;
import com.patbaumgartner.socialcrossposter.publish.DeliveryAttemptRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryJobRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryRepository;
import com.patbaumgartner.socialcrossposter.publish.Publication;
import com.patbaumgartner.socialcrossposter.publish.PublicationRepository;
import com.patbaumgartner.socialcrossposter.publish.RemoteAction;
import com.patbaumgartner.socialcrossposter.publish.RemoteActionAttempt;
import com.patbaumgartner.socialcrossposter.publish.RemoteActionAttemptRepository;
import com.patbaumgartner.socialcrossposter.publish.RemoteActionRepository;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
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
 * UC-08 first slice end-to-end (ADR-013, ADR-015): on a confirmed (SUCCESS + external
 * ref) delivery the edit form opens the last confirmed payload, applying a replacement
 * queues a SEPARATE remote action whose redacted attempt is appended while the original
 * delivery stays untouched (ADR-015), the delete page asks for explicit confirmation
 * showing destination and external identity, over-limit replacement text fails closed
 * without creating an action, non-confirmed deliveries are not editable, a second active
 * action is blocked, foreign workspaces and unknown slugs fail closed, and actions the
 * platform actor does not support stay disabled with an explanation. Both the telegram
 * publisher and the telegram remote actor are mocked; the stored secret is really
 * sealed/opened through {@link SecretCipher}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RemoteActionFlowTest {

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

	@Autowired
	NotificationEventRepository notificationEvents;

	@MockitoBean
	TelegramPublisher telegramPublisher;

	@MockitoBean
	TelegramRemoteActor telegramRemoteActor;

	@BeforeEach
	@AfterEach
	void resetState() {
		// notification_event references publication/delivery/schedule, so it goes first.
		this.notificationEvents.deleteAll();
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
	void stubProviders() {
		given(this.telegramPublisher.platform()).willReturn("telegram");
		given(this.telegramPublisher.publish(any(PublishContext.class)))
			.willReturn(PublishResult.success("telegram:message:99"));
		given(this.telegramRemoteActor.platform()).willReturn("telegram");
		given(this.telegramRemoteActor.supportedActions()).willReturn(Set.of("EDIT", "DELETE"));
		given(this.telegramRemoteActor.execute(any(RemoteActionContext.class)))
			.willReturn(PublishResult.success("telegram:message:99"));
	}

	@Test
	void editFormOpensTheConfirmedPayloadAndAppliesTheReplacement() throws Exception {
		MockHttpSession session = login();
		Seeded seeded = seedPublishedDelivery(session, "key-e1");
		Long workspaceId = workspaceId("private");

		this.mockMvc
			.perform(
					get("/w/private/history/" + seeded.publicationId() + "/deliveries/" + seeded.deliveryId() + "/edit")
						.session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("hello")))
			.andExpect(content().string(containsString("telegram:message:99")))
			.andExpect(content().string(containsString("4096")));

		this.mockMvc.perform(
				post("/w/private/history/" + seeded.publicationId() + "/deliveries/" + seeded.deliveryId() + "/edit")
					.with(csrf())
					.session(session)
					.param("text", "edited text"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/history/" + seeded.publicationId()))
			.andExpect(flash().attribute("notice", containsString("SUCCESS")));

		List<RemoteAction> actions = new ArrayList<>();
		this.remoteActions.findAll().forEach(actions::add);
		assertThat(actions).hasSize(1);
		RemoteAction action = actions.get(0);
		assertThat(action.action()).isEqualTo("EDIT");
		assertThat(action.state()).isEqualTo("SUCCESS");
		assertThat(action.payloadText()).isEqualTo("edited text");
		assertThat(action.payloadHash()).isNotBlank();
		assertThat(action.externalRef()).isEqualTo("telegram:message:99");

		List<RemoteActionAttempt> actionAttempts = this.remoteActionAttempts
			.findAllByRemoteActionIdOrderByIdAsc(action.id());
		assertThat(actionAttempts).hasSize(1);
		assertThat(actionAttempts.get(0).stage()).isEqualTo("edit");
		assertThat(actionAttempts.get(0).outcome()).isEqualTo("SUCCESS");

		// ADR-015: the original delivery is preserved untouched.
		Delivery delivery = this.deliveries.findByIdAndWorkspaceId(seeded.deliveryId(), workspaceId).orElseThrow();
		assertThat(delivery.state()).isEqualTo("SUCCESS");
		assertThat(delivery.payloadText()).isEqualTo("hello");
		assertThat(delivery.externalRef()).isEqualTo("telegram:message:99");

		verify(this.telegramRemoteActor).execute(
				argThat((RemoteActionContext ctx) -> "EDIT".equals(ctx.action()) && "edited text".equals(ctx.text())
						&& "telegram:message:99".equals(ctx.externalRef()) && "-1001234567890".equals(ctx.targetId())));

		this.mockMvc.perform(get("/w/private/history/" + seeded.publicationId()).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Remote actions")))
			.andExpect(content().string(containsString("EDIT")))
			.andExpect(content().string(containsString("edited text")));
	}

	@Test
	void editOverThePlatformLimitFailsClosedAndCreatesNoAction() throws Exception {
		MockHttpSession session = login();
		Seeded seeded = seedPublishedDelivery(session, "key-e2");

		this.mockMvc.perform(
				post("/w/private/history/" + seeded.publicationId() + "/deliveries/" + seeded.deliveryId() + "/edit")
					.with(csrf())
					.session(session)
					.param("text", "x".repeat(5000)))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/history/" + seeded.publicationId()))
			.andExpect(flash().attribute("error", containsString("limit")));

		assertThat(this.remoteActions.findAll()).isEmpty();
		verify(this.telegramRemoteActor, never()).execute(any());
	}

	@Test
	void editOnANonConfirmedDeliveryFailsClosed() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long workspaceId = workspaceId("private");
		Long postId = createPost(session, "private", "Launch", "hello", accountId);
		given(this.telegramPublisher.publish(any(PublishContext.class)))
			.willReturn(PublishResult.failed("Telegram rejected the message (HTTP 400)."));
		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", "key-nc"))
			.andExpect(status().is3xxRedirection());
		Publication pub = publicationsOf(workspaceId).get(0);
		Delivery delivery = this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(workspaceId, pub.id())
			.get(0);
		assertThat(delivery.state()).isEqualTo("RETRY_WAIT");

		this.mockMvc
			.perform(get("/w/private/history/" + pub.id() + "/deliveries/" + delivery.id() + "/edit").session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/history/" + pub.id()))
			.andExpect(flash().attribute("error", containsString("not available")));

		this.mockMvc
			.perform(post("/w/private/history/" + pub.id() + "/deliveries/" + delivery.id() + "/edit").with(csrf())
				.session(session)
				.param("text", "new"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("confirmed")));

		assertThat(this.remoteActions.findAll()).isEmpty();
		verify(this.telegramRemoteActor, never()).execute(any());
	}

	@Test
	void deleteAsksForConfirmationAndPreservesTheOriginalDelivery() throws Exception {
		MockHttpSession session = login();
		Seeded seeded = seedPublishedDelivery(session, "key-d1");
		Long workspaceId = workspaceId("private");

		this.mockMvc
			.perform(get(
					"/w/private/history/" + seeded.publicationId() + "/deliveries/" + seeded.deliveryId() + "/delete")
				.session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("telegram:message:99")))
			.andExpect(content().string(containsString("Confirm remote delete")));

		this.mockMvc.perform(
				post("/w/private/history/" + seeded.publicationId() + "/deliveries/" + seeded.deliveryId() + "/delete")
					.with(csrf())
					.session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/history/" + seeded.publicationId()))
			.andExpect(flash().attribute("notice", containsString("SUCCESS")));

		List<RemoteAction> actions = new ArrayList<>();
		this.remoteActions.findAll().forEach(actions::add);
		assertThat(actions).hasSize(1);
		RemoteAction action = actions.get(0);
		assertThat(action.action()).isEqualTo("DELETE");
		assertThat(action.state()).isEqualTo("SUCCESS");
		assertThat(action.payloadText()).isNull();
		assertThat(action.payloadHash()).isNull();
		List<RemoteActionAttempt> actionAttempts = this.remoteActionAttempts
			.findAllByRemoteActionIdOrderByIdAsc(action.id());
		assertThat(actionAttempts).hasSize(1);
		assertThat(actionAttempts.get(0).stage()).isEqualTo("delete");

		// ADR-015: the confirmed delivery is NOT turned into FAILED by the remote delete.
		Delivery delivery = this.deliveries.findByIdAndWorkspaceId(seeded.deliveryId(), workspaceId).orElseThrow();
		assertThat(delivery.state()).isEqualTo("SUCCESS");
		assertThat(delivery.payloadText()).isEqualTo("hello");

		this.mockMvc.perform(get("/w/private/history/" + seeded.publicationId()).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("DELETE")));
	}

	@Test
	void aSecondActiveActionIsBlocked() throws Exception {
		MockHttpSession session = login();
		Seeded seeded = seedPublishedDelivery(session, "key-b1");
		Long workspaceId = workspaceId("private");
		OffsetDateTime now = OffsetDateTime.now();
		this.remoteActions.save(new RemoteAction(null, workspaceId, seeded.deliveryId(), "DELETE", "PENDING", null,
				null, null, now, now));

		this.mockMvc.perform(
				post("/w/private/history/" + seeded.publicationId() + "/deliveries/" + seeded.deliveryId() + "/edit")
					.with(csrf())
					.session(session)
					.param("text", "new text"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("already in progress")));

		assertThat(this.remoteActions.findAll()).hasSize(1); // only the seeded one
		verify(this.telegramRemoteActor, never()).execute(any());
	}

	@Test
	void foreignWorkspaceFailsClosed() throws Exception {
		MockHttpSession session = login();
		Seeded seeded = seedPublishedDelivery(session, "key-f1");

		this.mockMvc
			.perform(post(
					"/w/socrates-ch/history/" + seeded.publicationId() + "/deliveries/" + seeded.deliveryId() + "/edit")
				.with(csrf())
				.session(session)
				.param("text", "new text"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/socrates-ch/history/" + seeded.publicationId()))
			.andExpect(flash().attribute("error", containsString("Unknown")));

		assertThat(this.remoteActions.findAll()).isEmpty();
		verify(this.telegramRemoteActor, never()).execute(any());
	}

	@Test
	void unknownSlugRedirectsToRoot() throws Exception {
		MockHttpSession session = login();
		this.mockMvc.perform(get("/w/nope/history/1/deliveries/1/edit").session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));
	}

	@Test
	void editStaysDisabledWhenTheActorDoesNotSupportIt() throws Exception {
		MockHttpSession session = login();
		given(this.telegramRemoteActor.supportedActions()).willReturn(Set.of("DELETE"));
		Seeded seeded = seedPublishedDelivery(session, "key-e3");

		this.mockMvc
			.perform(
					get("/w/private/history/" + seeded.publicationId() + "/deliveries/" + seeded.deliveryId() + "/edit")
						.session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("not available")));

		this.mockMvc.perform(
				post("/w/private/history/" + seeded.publicationId() + "/deliveries/" + seeded.deliveryId() + "/edit")
					.with(csrf())
					.session(session)
					.param("text", "new text"))
			.andExpect(flash().attribute("error", containsString("not support")));

		// delete stays available
		this.mockMvc.perform(
				get("/w/private/history/" + seeded.publicationId() + "/deliveries/" + seeded.deliveryId() + "/delete")
					.session(session))
			.andExpect(status().isOk());

		assertThat(this.remoteActions.findAll()).isEmpty();
		verify(this.telegramRemoteActor, never()).execute(any());

		this.mockMvc.perform(get("/w/private/history/" + seeded.publicationId()).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("Edit remote"))))
			.andExpect(content().string(containsString("Delete remote")));
	}

	private record Seeded(Long publicationId, Long deliveryId) {
	}

	private Seeded seedPublishedDelivery(MockHttpSession session, String submissionKey) throws Exception {
		Long accountId = seedDestination("private", "News channel");
		Long postId = createPost(session, "private", "Launch", "hello", accountId);
		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", submissionKey))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("PUBLISHED")));
		Long workspaceId = workspaceId("private");
		Publication pub = publicationsOf(workspaceId).get(0);
		Delivery delivery = this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(workspaceId, pub.id())
			.get(0);
		assertThat(delivery.state()).isEqualTo("SUCCESS");
		assertThat(delivery.externalRef()).isEqualTo("telegram:message:99");
		return new Seeded(pub.id(), delivery.id());
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
