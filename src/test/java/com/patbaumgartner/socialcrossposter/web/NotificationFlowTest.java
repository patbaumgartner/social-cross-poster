package com.patbaumgartner.socialcrossposter.web;

import java.time.LocalDateTime;
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
import com.patbaumgartner.socialcrossposter.content.Post;
import com.patbaumgartner.socialcrossposter.content.PostRepository;
import com.patbaumgartner.socialcrossposter.content.PostRevision;
import com.patbaumgartner.socialcrossposter.content.PostRevisionRepository;
import com.patbaumgartner.socialcrossposter.content.PostService;
import com.patbaumgartner.socialcrossposter.content.RevisionDestination;
import com.patbaumgartner.socialcrossposter.content.RevisionDestinationRepository;
import com.patbaumgartner.socialcrossposter.content.RevisionOverrideRepository;
import com.patbaumgartner.socialcrossposter.notify.NotificationEvent;
import com.patbaumgartner.socialcrossposter.notify.NotificationChannelRepository;
import com.patbaumgartner.socialcrossposter.notify.NotificationDeliveryRepository;
import com.patbaumgartner.socialcrossposter.notify.NotificationEventRepository;
import com.patbaumgartner.socialcrossposter.notify.NotificationService;
import com.patbaumgartner.socialcrossposter.platform.PublishContext;
import com.patbaumgartner.socialcrossposter.platform.PublishResult;
import com.patbaumgartner.socialcrossposter.platform.TelegramPublisher;
import com.patbaumgartner.socialcrossposter.publish.Delivery;
import com.patbaumgartner.socialcrossposter.publish.DeliveryAttemptRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryMediaRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryJobRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryRepository;
import com.patbaumgartner.socialcrossposter.publish.Publication;
import com.patbaumgartner.socialcrossposter.publish.PublicationRepository;
import com.patbaumgartner.socialcrossposter.publish.PublicationService;
import com.patbaumgartner.socialcrossposter.publish.RemoteActionAttemptRepository;
import com.patbaumgartner.socialcrossposter.publish.RemoteActionRepository;
import com.patbaumgartner.socialcrossposter.scheduling.PublicationSchedule;
import com.patbaumgartner.socialcrossposter.scheduling.PublicationScheduleRepository;
import com.patbaumgartner.socialcrossposter.scheduling.ScheduleService;
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
 * UC-12 first slice end-to-end (ADR-004, ADR-013, ADR-015): the dashboard requires login
 * and lists the workspace's in-app events; a confirmed provider failure during publish
 * records one deduplicated {@code delivery:<id>:failed} event (republishing the same
 * submission key creates no second event) and the dashboard shows category, redacted
 * summary and the authenticated publication link; an expired schedule records a
 * {@code schedule:<id>:expired} event; events are strictly workspace-scoped. The worker
 * is off in tests, so {@code runDueSchedules} is driven manually. The H2 database is
 * shared across tests in this context, so cleanup runs in FK-safe order — notification
 * rows reference publication/delivery/schedule, so they go first.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationFlowTest {

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
	PublicationScheduleRepository scheduleRepo;

	@Autowired
	ScheduleService schedules;

	@Autowired
	PublicationService publicationService;

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

	@MockitoBean
	TelegramPublisher telegramPublisher;

	@BeforeEach
	@AfterEach
	void resetState() {
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
		this.scheduleRepo.deleteAll();
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
	void dashboardRequiresLogin() throws Exception {
		this.mockMvc.perform(get("/w/private/dashboard"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void failedPublishRecordsDeduplicatedDeliveryFailedEventShownOnDashboard() throws Exception {
		MockHttpSession session = login();
		Long workspaceId = workspaceId("private");
		Long accountId = seedDestination("private", "News channel");
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
		Delivery delivery = this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(workspaceId, pub.id())
			.get(0);
		NotificationEvent event = this.notificationEvents
			.findByWorkspaceIdAndEventKey(workspaceId, "delivery:" + delivery.id() + ":failed")
			.orElseThrow();
		assertThat(event.category()).isEqualTo(NotificationService.C_DELIVERY_FAILED);
		assertThat(event.publicationId()).isEqualTo(pub.id());
		assertThat(event.deliveryId()).isEqualTo(delivery.id());
		assertThat(event.summary()).contains("telegram").contains("News channel").doesNotContain("hello");

		// The dashboard shows the event with its category, summary and the authenticated
		// publication link (UC-12 steps 3 and 5).
		this.mockMvc.perform(get("/w/private/dashboard").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("DELIVERY_FAILED")))
			.andExpect(content().string(containsString("failed; a bounded retry is scheduled")))
			.andExpect(content().string(containsString("/w/private/history/" + pub.id())));

		// Republishing the same submission key is idempotent and records no second event.
		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", "key-fail"))
			.andExpect(status().is3xxRedirection());
		verify(this.telegramPublisher, times(1)).publish(any(PublishContext.class));
		assertThat(this.notificationEvents.findTop50ByWorkspaceIdOrderByIdDesc(workspaceId)).hasSize(1);
	}

	@Test
	void expiredScheduleRecordsScheduleExpiredEvent() {
		Long workspaceId = workspaceId("private");
		Long accountId = seedDestination("private", "News channel");
		PostSeed seed = seedPost(workspaceId, accountId, "Launch", "hello");
		String hash = this.publicationService.payloadHashFor(workspaceId, seed.revisionId);
		OffsetDateTime now = OffsetDateTime.now();
		PublicationSchedule schedule = insertSchedule(workspaceId, seed.postId, seed.revisionId, "sched-late", hash,
				LocalDateTime.now().minusMinutes(20), "UTC", now.minusMinutes(20), ScheduleService.S_PENDING);

		this.schedules.runDueSchedules(now);

		assertThat(this.scheduleRepo.findById(schedule.id()).orElseThrow().state())
			.isEqualTo(ScheduleService.S_EXPIRED);
		NotificationEvent event = this.notificationEvents
			.findByWorkspaceIdAndEventKey(workspaceId, "schedule:" + schedule.id() + ":expired")
			.orElseThrow();
		assertThat(event.category()).isEqualTo(NotificationService.C_SCHEDULE_EXPIRED);
		assertThat(event.scheduleId()).isEqualTo(schedule.id());
		assertThat(event.summary()).contains("post #" + seed.postId).doesNotContain("hello");
		verify(this.telegramPublisher, times(0)).publish(any(PublishContext.class));
	}

	@Test
	void eventsAreStrictlyWorkspaceScoped() throws Exception {
		MockHttpSession session = login();
		Long workspaceId = workspaceId("private");
		Long accountId = seedDestination("private", "News channel");
		Long postId = createPost(session, "private", "Launch", "private hello", accountId);
		given(this.telegramPublisher.publish(any(PublishContext.class)))
			.willReturn(PublishResult.failed("Telegram rejected the message (HTTP 400)."));
		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", "key-scoped"))
			.andExpect(status().is3xxRedirection());
		assertThat(this.notificationEvents.findTop50ByWorkspaceIdOrderByIdDesc(workspaceId)).hasSize(1);

		// The other workspace's dashboard shows no events and no private payload text
		// (ADR-013).
		this.mockMvc.perform(get("/w/socrates-ch/dashboard").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("No events yet")))
			.andExpect(content().string(org.hamcrest.Matchers.not(containsString("DELIVERY_FAILED"))))
			.andExpect(content().string(org.hamcrest.Matchers.not(containsString("private hello"))));
	}

	private PublicationSchedule insertSchedule(Long workspaceId, Long postId, Long revisionId, String submissionKey,
			String payloadHash, LocalDateTime local, String zone, OffsetDateTime scheduledAt, String state) {
		OffsetDateTime now = OffsetDateTime.now();
		return this.scheduleRepo.save(new PublicationSchedule(null, workspaceId, postId, revisionId, submissionKey,
				payloadHash, local, zone, scheduledAt, state, null, now, now));
	}

	private record PostSeed(Long postId, Long revisionId) {
	}

	private PostSeed seedPost(Long workspaceId, Long accountId, String title, String text) {
		OffsetDateTime now = OffsetDateTime.now();
		Post post = this.posts.save(new Post(null, workspaceId, title, PostService.STATE_DRAFT, null, 0L, now, now));
		PostRevision revision = this.revisions.save(new PostRevision(null, workspaceId, post.id(), 1, text, now));
		this.destinations.save(new RevisionDestination(null, workspaceId, revision.id(), accountId));
		this.posts.save(new Post(post.id(), workspaceId, title, PostService.STATE_DRAFT, revision.id(), 1L,
				post.createdAt(), now));
		return new PostSeed(post.id(), revision.id());
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
