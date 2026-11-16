package com.patbaumgartner.socialcrossposter.web;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.patbaumgartner.socialcrossposter.notify.NotificationChannelRepository;
import com.patbaumgartner.socialcrossposter.notify.NotificationDeliveryRepository;
import com.patbaumgartner.socialcrossposter.notify.NotificationEventRepository;
import com.patbaumgartner.socialcrossposter.media.MediaAssetRepository;
import com.patbaumgartner.socialcrossposter.media.RevisionMediaRepository;
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
import com.patbaumgartner.socialcrossposter.platform.TelegramPublisher;
import com.patbaumgartner.socialcrossposter.publish.Delivery;
import com.patbaumgartner.socialcrossposter.publish.DeliveryAttempt;
import com.patbaumgartner.socialcrossposter.publish.DeliveryAttemptRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryMediaRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryJob;
import com.patbaumgartner.socialcrossposter.publish.DeliveryJobRepository;
import com.patbaumgartner.socialcrossposter.publish.RemoteActionAttemptRepository;
import com.patbaumgartner.socialcrossposter.publish.RemoteActionRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryJobService;
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
 * UC-07 first slice end-to-end (ADR-013, ADR-015): a confirmed provider failure settles
 * to RETRY_WAIT with a PENDING job on the backoff schedule (aggregate IN_PROGRESS), the
 * owner retry action schedules the job immediately, a manual {@code runDueJobs} tick
 * claims the due job through its lease and delivers successfully (job SUCCEEDED,
 * aggregate PUBLISHED, attempts appended), ticks ignore jobs scheduled in the future,
 * retrying a successful delivery fails closed, UNKNOWN outcomes are reconciled — never
 * blindly retried — to NEEDS_ATTENTION with a CANCELED job, and foreign workspaces fail
 * closed. The scheduled worker is disabled in tests ({@code app.worker.enabled=false});
 * ticks are driven manually. Telegram publisher mocked; the stored secret is really
 * sealed/opened through {@link SecretCipher}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RetryFlowTest {

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

	@Autowired
	NotificationChannelRepository notificationChannels;

	@Autowired
	NotificationDeliveryRepository notificationDeliveries;

	@Autowired
	RevisionMediaRepository revisionMedia;

	@Autowired
	MediaAssetRepository mediaAssets;

	@Autowired
	DeliveryMediaRepository deliveryMedia;

	@Autowired
	DeliveryJobService jobService;

	@MockitoBean
	TelegramPublisher telegramPublisher;

	@BeforeEach
	@AfterEach
	void resetState() {
		// notification_event references publication/delivery/schedule, so it goes first.
		// notification_delivery references notification_event/notification_channel —
		// delete outbox rows and channels first (FK-safe order, UC-12 second slice).
		this.notificationDeliveries.deleteAll();
		this.notificationChannels.deleteAll();
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

	@BeforeEach
	void stubPublisher() {
		given(this.telegramPublisher.platform()).willReturn("telegram");
		given(this.telegramPublisher.publish(any(PublishContext.class)))
			.willReturn(PublishResult.success("telegram:message:99"));
	}

	@Test
	void failedPublishSchedulesRetryAndManualWorkerTickSucceeds() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long workspaceId = workspaceId("private");
		Long postId = createPost(session, "private", "Launch", "hello", accountId);
		given(this.telegramPublisher.publish(any(PublishContext.class)))
			.willReturn(PublishResult.failed("Telegram rejected the message (HTTP 400)."));

		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", "key-r1"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("IN_PROGRESS")));

		Publication pub = publicationsOf(workspaceId).get(0);
		Delivery delivery = this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(workspaceId, pub.id())
			.get(0);
		assertThat(delivery.state()).isEqualTo("RETRY_WAIT");
		DeliveryJob job = this.jobs.findByDeliveryIdAndWorkspaceId(delivery.id(), workspaceId).orElseThrow();
		assertThat(job.state()).isEqualTo("PENDING");
		assertThat(job.nextRunAt()).isAfter(OffsetDateTime.now()); // backoff in the
																	// future

		this.mockMvc
			.perform(post("/w/private/history/" + pub.id() + "/deliveries/" + delivery.id() + "/retry").with(csrf())
				.session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/history/" + pub.id()))
			.andExpect(flash().attribute("notice", containsString("Retry scheduled")));
		job = this.jobs.findByDeliveryIdAndWorkspaceId(delivery.id(), workspaceId).orElseThrow();
		assertThat(job.nextRunAt()).isBefore(OffsetDateTime.now().plusSeconds(5)); // due
																					// now

		given(this.telegramPublisher.publish(any(PublishContext.class)))
			.willReturn(PublishResult.success("telegram:message:7"));
		this.jobService.runDueJobs(OffsetDateTime.now());

		Delivery after = this.deliveries.findByIdAndWorkspaceId(delivery.id(), workspaceId).orElseThrow();
		assertThat(after.state()).isEqualTo("SUCCESS");
		assertThat(after.externalRef()).isEqualTo("telegram:message:7");
		DeliveryJob done = this.jobs.findByDeliveryIdAndWorkspaceId(delivery.id(), workspaceId).orElseThrow();
		assertThat(done.state()).isEqualTo("SUCCEEDED");
		assertThat(this.publications.findByIdAndWorkspaceId(pub.id(), workspaceId).orElseThrow().state())
			.isEqualTo("PUBLISHED");
		List<DeliveryAttempt> att = this.attempts.findAllByDeliveryIdOrderByIdAsc(delivery.id());
		assertThat(att).hasSize(2);
		assertThat(att.get(0).outcome()).isEqualTo("FAILED");
		assertThat(att.get(1).attemptNo()).isEqualTo(2);
		assertThat(att.get(1).outcome()).isEqualTo("SUCCESS");
		verify(this.telegramPublisher, times(2)).publish(any(PublishContext.class));
	}

	@Test
	void workerTickIgnoresJobScheduledInTheFuture() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long workspaceId = workspaceId("private");
		Long postId = createPost(session, "private", "Launch", "hello", accountId);
		given(this.telegramPublisher.publish(any(PublishContext.class)))
			.willReturn(PublishResult.failed("Telegram rejected the message (HTTP 400)."));

		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", "key-r2"))
			.andExpect(status().is3xxRedirection());

		Publication pub = publicationsOf(workspaceId).get(0);
		Delivery delivery = this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(workspaceId, pub.id())
			.get(0);
		assertThat(delivery.state()).isEqualTo("RETRY_WAIT");

		this.jobService.runDueJobs(OffsetDateTime.now()); // job is not due yet — nothing
															// happens

		Delivery after = this.deliveries.findByIdAndWorkspaceId(delivery.id(), workspaceId).orElseThrow();
		assertThat(after.state()).isEqualTo("RETRY_WAIT");
		assertThat(this.attempts.findAllByDeliveryIdOrderByIdAsc(delivery.id())).hasSize(1);
		DeliveryJob job = this.jobs.findByDeliveryIdAndWorkspaceId(delivery.id(), workspaceId).orElseThrow();
		assertThat(job.state()).isEqualTo("PENDING");
		verify(this.telegramPublisher, times(1)).publish(any(PublishContext.class));
	}

	@Test
	void retryActionOnSuccessfulDeliveryFailsClosed() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long workspaceId = workspaceId("private");
		Long postId = createPost(session, "private", "Launch", "hello", accountId);

		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", "key-ok"))
			.andExpect(flash().attribute("notice", containsString("PUBLISHED")));

		Publication pub = publicationsOf(workspaceId).get(0);
		Delivery delivery = this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(workspaceId, pub.id())
			.get(0);
		assertThat(delivery.state()).isEqualTo("SUCCESS");

		this.mockMvc
			.perform(post("/w/private/history/" + pub.id() + "/deliveries/" + delivery.id() + "/retry").with(csrf())
				.session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/history/" + pub.id()))
			.andExpect(flash().attribute("error", containsString("never re-run")));

		DeliveryJob job = this.jobs.findByDeliveryIdAndWorkspaceId(delivery.id(), workspaceId).orElseThrow();
		assertThat(job.state()).isEqualTo("SUCCEEDED");
		verify(this.telegramPublisher, times(1)).publish(any(PublishContext.class));
	}

	@Test
	void unknownOutcomeIsReconciledToNeedsAttentionAndReconcileActionReportsIt() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long workspaceId = workspaceId("private");
		Long postId = createPost(session, "private", "Launch", "hello", accountId);
		given(this.telegramPublisher.publish(any(PublishContext.class)))
			.willReturn(PublishResult.unknown("Telegram did not confirm the message (HTTP 500)."));

		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", "key-u1"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("NEEDS_ATTENTION")));

		Publication pub = publicationsOf(workspaceId).get(0);
		assertThat(pub.state()).isEqualTo("NEEDS_ATTENTION");
		Delivery delivery = this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(workspaceId, pub.id())
			.get(0);
		assertThat(delivery.state()).isEqualTo("NEEDS_ATTENTION");
		assertThat(delivery.externalRef()).isNull();
		DeliveryJob job = this.jobs.findByDeliveryIdAndWorkspaceId(delivery.id(), workspaceId).orElseThrow();
		assertThat(job.state()).isEqualTo("CANCELED");
		List<DeliveryAttempt> att = this.attempts.findAllByDeliveryIdOrderByIdAsc(delivery.id());
		assertThat(att).hasSize(2);
		assertThat(att.get(0).outcome()).isEqualTo("UNKNOWN");
		assertThat(att.get(1).stage()).isEqualTo("reconcile");
		assertThat(att.get(1).outcome()).isEqualTo("INCONCLUSIVE");
		verify(this.telegramPublisher, times(1)).publish(any(PublishContext.class)); // never
																						// blindly
																						// retried

		this.mockMvc
			.perform(post("/w/private/history/" + pub.id() + "/deliveries/" + delivery.id() + "/reconcile").with(csrf())
				.session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/history/" + pub.id()))
			.andExpect(flash().attribute("notice", containsString("NEEDS_ATTENTION")));

		this.mockMvc.perform(get("/w/private/history/" + pub.id()).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("NEEDS_ATTENTION")))
			.andExpect(content().string(containsString("Reconcile")))
			.andExpect(content().string(containsString("CANCELED")));
	}

	@Test
	void retryInForeignWorkspaceFailsClosed() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long workspaceId = workspaceId("private");
		Long postId = createPost(session, "private", "Launch", "hello", accountId);
		given(this.telegramPublisher.publish(any(PublishContext.class)))
			.willReturn(PublishResult.failed("Telegram rejected the message (HTTP 400)."));

		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", "key-fx"))
			.andExpect(status().is3xxRedirection());

		Publication pub = publicationsOf(workspaceId).get(0);
		Delivery delivery = this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(workspaceId, pub.id())
			.get(0);

		this.mockMvc
			.perform(post("/w/socrates-ch/history/" + pub.id() + "/deliveries/" + delivery.id() + "/retry").with(csrf())
				.session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/socrates-ch/history/" + pub.id()))
			.andExpect(flash().attribute("error", containsString("Unknown delivery")));

		assertThat(this.jobs.findByDeliveryIdAndWorkspaceId(delivery.id(), workspaceId).orElseThrow().state())
			.isEqualTo("PENDING"); // unchanged
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
