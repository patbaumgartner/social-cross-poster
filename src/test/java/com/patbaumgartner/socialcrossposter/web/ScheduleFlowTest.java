package com.patbaumgartner.socialcrossposter.web;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import com.patbaumgartner.socialcrossposter.contacts.Contact;
import com.patbaumgartner.socialcrossposter.contacts.ContactHandle;
import com.patbaumgartner.socialcrossposter.contacts.ContactHandleRepository;
import com.patbaumgartner.socialcrossposter.contacts.ContactRepository;
import com.patbaumgartner.socialcrossposter.contacts.MentionToken;
import com.patbaumgartner.socialcrossposter.content.Post;
import com.patbaumgartner.socialcrossposter.content.PostRepository;
import com.patbaumgartner.socialcrossposter.content.PostRevision;
import com.patbaumgartner.socialcrossposter.content.PostRevisionRepository;
import com.patbaumgartner.socialcrossposter.content.PostService;
import com.patbaumgartner.socialcrossposter.content.RevisionDestination;
import com.patbaumgartner.socialcrossposter.content.RevisionDestinationRepository;
import com.patbaumgartner.socialcrossposter.content.RevisionOverrideRepository;
import com.patbaumgartner.socialcrossposter.platform.PublishContext;
import com.patbaumgartner.socialcrossposter.platform.PublishResult;
import com.patbaumgartner.socialcrossposter.platform.TelegramPublisher;
import com.patbaumgartner.socialcrossposter.publish.Delivery;
import com.patbaumgartner.socialcrossposter.publish.DeliveryAttemptRepository;
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
 * UC-10 first slice end-to-end (ADR-004, ADR-009, ADR-013, ADR-015, ADR-016): schedule
 * routes require login; approving a schedule persists one PENDING row with the frozen
 * revision, a 64-hex payload hash, a workspace-scoped {@code sched-} submission key and
 * the resolved UTC instant; DST gaps are rejected and overlaps require an explicit offset
 * (never shifted/picked automatically); past times, missing destinations and foreign post
 * ids fail closed; cancel only works on PENDING schedules; and dispatch (driven manually
 * because the worker is off in tests) creates at most one publication per schedule
 * through the UC-05/UC-07 path, expires schedules beyond the scheduled-instant + 15 min
 * cutoff, and flags a changed payload hash as NEEDS_REVIEW. The H2 database is shared
 * across tests in this context, so cleanup runs in FK-safe order (schedule rows reference
 * publication/post, so they go before both).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScheduleFlowTest {

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
	PublicationScheduleRepository scheduleRepo;

	@Autowired
	ScheduleService schedules;

	@Autowired
	PublicationService publicationService;

	@MockitoBean
	TelegramPublisher telegramPublisher;

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
	void scheduleRoutesRequireLogin() throws Exception {
		this.mockMvc
			.perform(post("/w/private/posts/1/schedule").with(csrf())
				.param("localDateTime", "2030-01-15T18:30")
				.param("timezone", "Europe/Zurich"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
		this.mockMvc.perform(post("/w/private/schedules/1/cancel").with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void scheduleHappyPathPersistsPendingScheduleWithUtcInstant() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long workspaceId = workspaceId("private");
		Long postId = createPost(session, "private", "Launch", "hello world", accountId);
		Long revisionId = this.revisions.findTopByPostIdOrderByRevisionNoDesc(postId).orElseThrow().id();

		LocalDateTime future = LocalDateTime.now(ZoneId.of("Europe/Zurich")).plusDays(2).withSecond(0).withNano(0);
		String localParam = future.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
		OffsetDateTime expectedUtc = future.atZone(ZoneId.of("Europe/Zurich"))
			.toOffsetDateTime()
			.withOffsetSameInstant(ZoneOffset.UTC);

		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/schedule").with(csrf())
				.session(session)
				.param("localDateTime", localParam)
				.param("timezone", "Europe/Zurich"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/posts/" + postId))
			.andExpect(flash().attribute("notice", containsString("Scheduled for")))
			.andExpect(flash().attribute("notice", containsString("PENDING")))
			.andExpect(flash().attribute("notice", containsString("15 minutes")));

		List<PublicationSchedule> rows = this.scheduleRepo
			.findAllByWorkspaceIdAndPostIdOrderByScheduledAtAsc(workspaceId, postId);
		assertThat(rows).hasSize(1);
		PublicationSchedule s = rows.get(0);
		assertThat(s.state()).isEqualTo(ScheduleService.S_PENDING);
		assertThat(s.postId()).isEqualTo(postId);
		assertThat(s.revisionId()).isEqualTo(revisionId);
		assertThat(s.submissionKey()).startsWith("sched-");
		assertThat(s.payloadHash()).hasSize(64);
		assertThat(s.timezone()).isEqualTo("Europe/Zurich");
		assertThat(s.localDateTime()).isEqualTo(future);
		assertThat(s.scheduledAt().toInstant()).isEqualTo(expectedUtc.toInstant());
		assertThat(s.scheduledAt().getOffset()).isEqualTo(ZoneOffset.UTC);
		assertThat(s.publicationId()).isNull();
	}

	@Test
	void postPageListsSchedules() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long postId = createPost(session, "private", "Launch", "hello world", accountId);
		String localParam = LocalDateTime.now(ZoneId.of("Europe/Zurich"))
			.plusDays(2)
			.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));

		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/schedule").with(csrf())
				.session(session)
				.param("localDateTime", localParam)
				.param("timezone", "Europe/Zurich"))
			.andExpect(status().is3xxRedirection());

		this.mockMvc.perform(get("/w/private/posts/" + postId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Schedule (UC-10)")))
			.andExpect(content().string(containsString("PENDING")))
			.andExpect(content().string(containsString("Europe/Zurich")));
	}

	@Test
	void dstGapIsRejectedAndNothingIsPersisted() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long workspaceId = workspaceId("private");
		Long postId = createPost(session, "private", "Launch", "hello world", accountId);

		// Europe/Zurich 2027-03-28: clocks jump 02:00 -> 03:00, so 02:30 does not exist.
		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/schedule").with(csrf())
				.session(session)
				.param("localDateTime", "2027-03-28T02:30")
				.param("timezone", "Europe/Zurich"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("DST gap")))
			.andExpect(flash().attribute("error", containsString("never shifted automatically")));

		assertThat(this.scheduleRepo.findAllByWorkspaceIdAndPostIdOrderByScheduledAtAsc(workspaceId, postId)).isEmpty();
	}

	@Test
	void dstOverlapRequiresExplicitOffsetChoice() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long workspaceId = workspaceId("private");
		Long postId = createPost(session, "private", "Launch", "hello world", accountId);

		// Europe/Zurich 2027-10-31: 02:30 occurs twice (+02:00 and +01:00).
		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/schedule").with(csrf())
				.session(session)
				.param("localDateTime", "2027-10-31T02:30")
				.param("timezone", "Europe/Zurich"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("twice")))
			.andExpect(flash().attribute("error", containsString("+02:00")))
			.andExpect(flash().attribute("error", containsString("+01:00")));
		assertThat(this.scheduleRepo.findAllByWorkspaceIdAndPostIdOrderByScheduledAtAsc(workspaceId, postId)).isEmpty();

		// An invalid offset is rejected, not silently mapped.
		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/schedule").with(csrf())
				.session(session)
				.param("localDateTime", "2027-10-31T02:30")
				.param("timezone", "Europe/Zurich")
				.param("offset", "+03:00"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("not valid")));
		assertThat(this.scheduleRepo.findAllByWorkspaceIdAndPostIdOrderByScheduledAtAsc(workspaceId, postId)).isEmpty();

		// An explicit valid offset is accepted and resolves to the matching UTC instant.
		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/schedule").with(csrf())
				.session(session)
				.param("localDateTime", "2027-10-31T02:30")
				.param("timezone", "Europe/Zurich")
				.param("offset", "+01:00"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("Scheduled for")));
		List<PublicationSchedule> rows = this.scheduleRepo
			.findAllByWorkspaceIdAndPostIdOrderByScheduledAtAsc(workspaceId, postId);
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).scheduledAt().toInstant())
			.isEqualTo(OffsetDateTime.parse("2027-10-31T01:30:00Z").toInstant());
	}

	@Test
	void pastTimeIsRejected() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long workspaceId = workspaceId("private");
		Long postId = createPost(session, "private", "Launch", "hello world", accountId);
		String past = LocalDateTime.now(ZoneId.of("Europe/Zurich"))
			.minusDays(1)
			.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));

		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/schedule").with(csrf())
				.session(session)
				.param("localDateTime", past)
				.param("timezone", "Europe/Zurich"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("future")));

		assertThat(this.scheduleRepo.findAllByWorkspaceIdAndPostIdOrderByScheduledAtAsc(workspaceId, postId)).isEmpty();
	}

	@Test
	void scheduleWithoutDestinationFails() throws Exception {
		MockHttpSession session = login();
		Long workspaceId = workspaceId("private");
		Long postId = createPost(session, "private", "No accounts", "lonely draft", null);
		String future = LocalDateTime.now(ZoneId.of("Europe/Zurich"))
			.plusDays(2)
			.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));

		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/schedule").with(csrf())
				.session(session)
				.param("localDateTime", future)
				.param("timezone", "Europe/Zurich"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("at least one destination")));

		assertThat(this.scheduleRepo.findAllByWorkspaceIdAndPostIdOrderByScheduledAtAsc(workspaceId, postId)).isEmpty();
	}

	@Test
	void foreignPostIdFailsClosed() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long postId = createPost(session, "private", "Private launch", "secret", accountId);
		Long foreignWorkspaceId = workspaceId("socrates-ch");
		String future = LocalDateTime.now(ZoneId.of("Europe/Zurich"))
			.plusDays(2)
			.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));

		this.mockMvc
			.perform(post("/w/socrates-ch/posts/" + postId + "/schedule").with(csrf())
				.session(session)
				.param("localDateTime", future)
				.param("timezone", "Europe/Zurich"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/socrates-ch/posts/" + postId))
			.andExpect(flash().attribute("error", containsString("Unknown post")));

		assertThat(this.scheduleRepo.findAllByWorkspaceIdAndPostIdOrderByScheduledAtAsc(foreignWorkspaceId, postId))
			.isEmpty();
	}

	@Test
	void cancelOnlyWorksOnPendingSchedules() throws Exception {
		MockHttpSession session = login();
		Long accountId = seedDestination("private", "News channel");
		Long workspaceId = workspaceId("private");
		Long postId = createPost(session, "private", "Launch", "hello world", accountId);
		Long scheduleId = scheduleViaWeb(session, postId);

		this.mockMvc.perform(post("/w/private/schedules/" + scheduleId + "/cancel").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/posts/" + postId))
			.andExpect(flash().attribute("notice", containsString("canceled")));
		assertThat(this.scheduleRepo.findById(scheduleId).orElseThrow().state()).isEqualTo(ScheduleService.S_CANCELED);

		// A second cancel is rejected — the state transition is guarded.
		this.mockMvc.perform(post("/w/private/schedules/" + scheduleId + "/cancel").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("only PENDING")));
		assertThat(this.scheduleRepo.findById(scheduleId).orElseThrow().state()).isEqualTo(ScheduleService.S_CANCELED);
		assertThat(publicationsOf(workspaceId)).isEmpty();
	}

	@Test
	void dueScheduleCreatesExactlyOnePublicationAndDelivers() {
		Long workspaceId = workspaceId("private");
		Long accountId = seedDestination("private", "News channel");
		PostSeed seed = seedPost(workspaceId, accountId, "Launch", "hello");
		String hash = this.publicationService.payloadHashFor(workspaceId, seed.revisionId);
		OffsetDateTime now = OffsetDateTime.now();
		PublicationSchedule s = insertSchedule(workspaceId, seed.postId, seed.revisionId, "sched-test-1", hash,
				LocalDateTime.now().minusMinutes(1), "UTC", now.minusMinutes(1), ScheduleService.S_PENDING);

		this.schedules.runDueSchedules(now);

		PublicationSchedule after = this.scheduleRepo.findById(s.id()).orElseThrow();
		assertThat(after.state()).isEqualTo(ScheduleService.S_CREATED);
		assertThat(after.publicationId()).isNotNull();
		List<Publication> pubs = publicationsOf(workspaceId);
		assertThat(pubs).hasSize(1);
		assertThat(pubs.get(0).submissionKey()).isEqualTo("sched-test-1");
		assertThat(pubs.get(0).id()).isEqualTo(after.publicationId());
		assertThat(pubs.get(0).state()).isEqualTo("PUBLISHED");
		List<Delivery> ds = this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(workspaceId,
				pubs.get(0).id());
		assertThat(ds).hasSize(1);
		assertThat(ds.get(0).state()).isEqualTo("SUCCESS");
		assertThat(ds.get(0).externalRef()).isEqualTo("telegram:message:99");
		assertThat(ds.get(0).payloadText()).isEqualTo("hello");
		verify(this.telegramPublisher, times(1)).publish(any(PublishContext.class));

		// Running the tick again must not create a second publication (ADR-015).
		this.schedules.runDueSchedules(now);
		assertThat(publicationsOf(workspaceId)).hasSize(1);
		verify(this.telegramPublisher, times(1)).publish(any(PublishContext.class));
	}

	@Test
	void scheduleBeyondCutoffExpiresWithoutPublishing() {
		Long workspaceId = workspaceId("private");
		Long accountId = seedDestination("private", "News channel");
		PostSeed seed = seedPost(workspaceId, accountId, "Launch", "hello");
		String hash = this.publicationService.payloadHashFor(workspaceId, seed.revisionId);
		OffsetDateTime now = OffsetDateTime.now();
		// Scheduled 20 minutes ago: beyond the scheduled instant + 15 min cutoff
		// (ADR-016).
		PublicationSchedule s = insertSchedule(workspaceId, seed.postId, seed.revisionId, "sched-late", hash,
				LocalDateTime.now().minusMinutes(20), "UTC", now.minusMinutes(20), ScheduleService.S_PENDING);

		this.schedules.runDueSchedules(now);

		assertThat(this.scheduleRepo.findById(s.id()).orElseThrow().state()).isEqualTo(ScheduleService.S_EXPIRED);
		assertThat(publicationsOf(workspaceId)).isEmpty();
		verify(this.telegramPublisher, times(0)).publish(any(PublishContext.class));
	}

	@Test
	void changedPayloadHashMarksScheduleNeedsReview() {
		Long workspaceId = workspaceId("private");
		Long accountId = seedDestination("private", "News channel");
		String token = MentionToken.format(UUID.randomUUID());
		OffsetDateTime now = OffsetDateTime.now();
		Contact contact = this.contacts
			.save(new Contact(null, workspaceId, token, "Alice Example", null, "secret note xyzzy", false, now, now));
		ContactHandle handle = this.handles.save(new ContactHandle(null, workspaceId, contact.id(), "telegram",
				"@alice_example", "", "handle", "@alice_example", false, now, now));
		PostSeed seed = seedPost(workspaceId, accountId, "Hi Alice", "thanks " + token);
		String approvedHash = this.publicationService.payloadHashFor(workspaceId, seed.revisionId);
		PublicationSchedule s = insertSchedule(workspaceId, seed.postId, seed.revisionId, "sched-mention", approvedHash,
				LocalDateTime.now().minusMinutes(1), "UTC", now.minusMinutes(1), ScheduleService.S_PENDING);

		// The semantic payload changed after approval (handle renamed -> different
		// render).
		this.handles.save(new ContactHandle(handle.id(), workspaceId, contact.id(), "telegram", "@alice_renamed", "",
				"handle", "@alice_renamed", false, handle.createdAt(), now));

		this.schedules.runDueSchedules(now);

		assertThat(this.scheduleRepo.findById(s.id()).orElseThrow().state()).isEqualTo(ScheduleService.S_NEEDS_REVIEW);
		assertThat(publicationsOf(workspaceId)).isEmpty();
		verify(this.telegramPublisher, times(0)).publish(any(PublishContext.class));
	}

	private Long scheduleViaWeb(MockHttpSession session, Long postId) throws Exception {
		String future = LocalDateTime.now(ZoneId.of("Europe/Zurich"))
			.plusDays(2)
			.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/schedule").with(csrf())
				.session(session)
				.param("localDateTime", future)
				.param("timezone", "Europe/Zurich"))
			.andExpect(status().is3xxRedirection());
		return this.scheduleRepo.findAllByWorkspaceIdAndPostIdOrderByScheduledAtAsc(workspaceId("private"), postId)
			.get(0)
			.id();
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
