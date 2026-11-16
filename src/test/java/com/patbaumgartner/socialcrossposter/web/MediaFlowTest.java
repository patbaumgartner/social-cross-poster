package com.patbaumgartner.socialcrossposter.web;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.patbaumgartner.socialcrossposter.TestImages;
import com.patbaumgartner.socialcrossposter.accounts.AccountDestination;
import com.patbaumgartner.socialcrossposter.accounts.AccountRepository;
import com.patbaumgartner.socialcrossposter.accounts.Connection;
import com.patbaumgartner.socialcrossposter.accounts.ConnectionRepository;
import com.patbaumgartner.socialcrossposter.accounts.SecretCipher;
import com.patbaumgartner.socialcrossposter.contacts.ContactHandleRepository;
import com.patbaumgartner.socialcrossposter.contacts.ContactRepository;
import com.patbaumgartner.socialcrossposter.content.Post;
import com.patbaumgartner.socialcrossposter.content.PostRepository;
import com.patbaumgartner.socialcrossposter.content.PostRevisionRepository;
import com.patbaumgartner.socialcrossposter.content.PostService;
import com.patbaumgartner.socialcrossposter.content.RevisionDestinationRepository;
import com.patbaumgartner.socialcrossposter.content.RevisionOverrideRepository;
import com.patbaumgartner.socialcrossposter.media.MediaAsset;
import com.patbaumgartner.socialcrossposter.media.MediaAssetRepository;
import com.patbaumgartner.socialcrossposter.media.OutgoingImageSanitizer;
import com.patbaumgartner.socialcrossposter.media.RevisionMedia;
import com.patbaumgartner.socialcrossposter.media.RevisionMediaRepository;
import com.patbaumgartner.socialcrossposter.notify.NotificationChannelRepository;
import com.patbaumgartner.socialcrossposter.notify.NotificationDeliveryRepository;
import com.patbaumgartner.socialcrossposter.notify.NotificationEventRepository;
import com.patbaumgartner.socialcrossposter.platform.PublishContext;
import com.patbaumgartner.socialcrossposter.platform.PublishResult;
import com.patbaumgartner.socialcrossposter.platform.TelegramPublisher;
import com.patbaumgartner.socialcrossposter.publish.DeliveryAttemptRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryMediaRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryJobRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryRepository;
import com.patbaumgartner.socialcrossposter.publish.PublicationRepository;
import com.patbaumgartner.socialcrossposter.publish.PublicationService;
import com.patbaumgartner.socialcrossposter.publish.RemoteActionAttemptRepository;
import com.patbaumgartner.socialcrossposter.publish.RemoteActionRepository;
import com.patbaumgartner.socialcrossposter.scheduling.PublicationScheduleRepository;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-13 first slice end-to-end (ADR-004, ADR-008, ADR-013, ADR-016): all media routes
 * require login; a png upload lands in the private library (row + bytes on disk) and the
 * authenticated download returns the exact bytes with the stored content type; attach
 * needs a non-blank alt text and lands on the current revision; publishing a revision
 * with attached media is blocked fail-closed (UC-13 later slice) while a text-only
 * revision publishes; media links are copied into new revisions and into UC-11
 * duplicates; foreign-workspace access to an asset file is a 404 and a foreign attach
 * fails closed. The worker is off in tests. Cleanup is FK-safe: revision_media before
 * media_asset.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MediaFlowTest {

	/**
	 * A real PNG carrying fake GPS metadata — the outgoing derivative must be stripped
	 * (ADR-016).
	 */
	private static final byte[] PNG = TestImages.pngWithGpsText();

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
	PublicationService publicationService;

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
		given(this.telegramPublisher.supportsMediaUpload()).willReturn(true);
		given(this.telegramPublisher.publish(any(PublishContext.class)))
			.willReturn(PublishResult.success("telegram:message:99"));
	}

	@Test
	void mediaRoutesRequireLogin() throws Exception {
		this.mockMvc.perform(get("/w/private/media/1/file"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
		this.mockMvc
			.perform(multipart("/w/private/posts/1/media/upload")
				.file(new MockMultipartFile("file", "a.png", "image/png", PNG))
				.with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void uploadDownloadAttachBlockDetachPublishDelete() throws Exception {
		MockHttpSession session = login();
		Long workspaceId = workspaceId("private");
		Long accountId = seedDestination("private", "News channel");
		Long postId = createPost(session, "private", "With image", "hello", accountId);
		Long revisionId = this.posts.findById(postId).orElseThrow().currentRevisionId();

		// upload (multipart) -> one library row, bytes on disk
		this.mockMvc
			.perform(multipart("/w/private/posts/" + postId + "/media/upload")
				.file(new MockMultipartFile("file", "chart.png", "image/png", PNG))
				.with(csrf())
				.session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("chart.png")));
		MediaAsset asset = this.mediaAssets.findAllByWorkspaceIdOrderByIdDesc(workspaceId).get(0);
		assertThat(asset.contentType()).isEqualTo("image/png");
		assertThat(asset.sizeBytes()).isEqualTo(PNG.length);
		assertThat(asset.sha256()).hasSize(64);
		// re-uploading identical bytes dedups to the same row
		this.mockMvc
			.perform(multipart("/w/private/posts/" + postId + "/media/upload")
				.file(new MockMultipartFile("file", "chart-copy.png", "image/png", PNG))
				.with(csrf())
				.session(session))
			.andExpect(status().is3xxRedirection());
		assertThat(this.mediaAssets.findAllByWorkspaceIdOrderByIdDesc(workspaceId)).hasSize(1);

		// authenticated download returns the exact original bytes incl. metadata
		// (ADR-008: no public URL)
		this.mockMvc.perform(get("/w/private/media/" + asset.id() + "/file").session(session))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", containsString("image/png")))
			.andExpect(content().bytes(PNG));
		assertThat(new String(PNG, StandardCharsets.ISO_8859_1)).contains("GPS: 48.1867N");

		// attach without alt text is rejected (ADR-016)
		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/media/attach").with(csrf())
				.session(session)
				.param("mediaId", asset.id().toString())
				.param("altText", "  "))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("alt text")));
		assertThat(this.revisionMedia.findAllByRevisionIdOrderByPositionAsc(revisionId)).isEmpty();

		// attach with alt text lands on the current revision
		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/media/attach").with(csrf())
				.session(session)
				.param("mediaId", asset.id().toString())
				.param("altText", "Sales chart Q3"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("attached")));
		List<RevisionMedia> links = this.revisionMedia.findAllByRevisionIdOrderByPositionAsc(revisionId);
		assertThat(links).hasSize(1);
		assertThat(links.get(0).position()).isEqualTo(1);
		assertThat(links.get(0).altText()).isEqualTo("Sales chart Q3");

		// telegram supports media upload — no media blocker (UC-13 second slice)
		assertThat(this.publicationService.blockersOf(workspaceId, revisionId)).isEmpty();

		// publishing a revision with attached media succeeds and uploads the private
		// bytes — but only the metadata-stripped outgoing derivative (ADR-016)
		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/publish").with(csrf())
				.session(session)
				.param("submissionKey", "key-media"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("PUBLISHED")));
		assertThat(publicationsOf(workspaceId)).hasSize(1);
		ArgumentCaptor<PublishContext> captor = ArgumentCaptor.forClass(PublishContext.class);
		verify(this.telegramPublisher, atLeastOnce()).publish(captor.capture());
		PublishContext context = captor.getValue();
		assertThat(context.media()).hasSize(1);
		assertThat(context.media().get(0).filename()).isEqualTo("chart.png");
		assertThat(context.media().get(0).contentType()).isEqualTo("image/png");
		byte[] outgoing = OutgoingImageSanitizer.strip("image/png", PNG);
		assertThat(context.media().get(0).bytes()).isEqualTo(outgoing);
		assertThat(new String(context.media().get(0).bytes(), StandardCharsets.ISO_8859_1))
			.doesNotContain("GPS: 48.1867N")
			.doesNotContain("tEXt"); // GPS/camera metadata never leaves the workspace
		assertThat(context.media().get(0).altText()).isEqualTo("Sales chart Q3");
		// the approved image is snapshotted on the delivery (V11 delivery_media)
		Long deliveryId = this.deliveries
			.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(workspaceId, publicationsOf(workspaceId).get(0).id())
			.get(0)
			.id();
		List<com.patbaumgartner.socialcrossposter.publish.DeliveryMedia> snapshot = this.deliveryMedia
			.findAllByDeliveryIdOrderByPositionAsc(deliveryId);
		assertThat(snapshot).hasSize(1);
		assertThat(snapshot.get(0).sha256()).isEqualTo(asset.sha256());
		assertThat(snapshot.get(0).position()).isEqualTo(1);
		assertThat(snapshot.get(0).altText()).isEqualTo("Sales chart Q3");

		// detach removes the link from the current revision
		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/media/" + links.get(0).id() + "/detach").with(csrf())
				.session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("detached")));
		assertThat(this.revisionMedia.findAllByRevisionIdOrderByPositionAsc(revisionId)).isEmpty();

		// delete removes the row (file cleanup is covered by MediaServiceTest)
		this.mockMvc
			.perform(post("/w/private/media/" + asset.id() + "/delete").with(csrf())
				.session(session)
				.param("postId", postId.toString()))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("deleted")));
		assertThat(this.mediaAssets.findById(asset.id())).isEmpty();
	}

	@Test
	void mediaLinksAreCopiedToNewRevisionsAndDuplicates() throws Exception {
		MockHttpSession session = login();
		Long workspaceId = workspaceId("private");
		Long accountId = seedDestination("private", "News channel");
		Long postId = createPost(session, "private", "With image", "hello", accountId);
		Long revisionId = this.posts.findById(postId).orElseThrow().currentRevisionId();
		MediaAsset asset = uploadPng(session, postId);
		this.mockMvc
			.perform(post("/w/private/posts/" + postId + "/media/attach").with(csrf())
				.session(session)
				.param("mediaId", asset.id().toString())
				.param("altText", "Chart"))
			.andExpect(status().is3xxRedirection());

		// saving a new revision inherits the media links (UC-13 copy semantics)
		this.mockMvc
			.perform(post("/w/private/posts/" + postId).with(csrf())
				.session(session)
				.param("title", "With image")
				.param("text", "hello v2")
				.param("accountIds", accountId.toString())
				.param("expectedVersion", "1"))
			.andExpect(status().is3xxRedirection());
		Long rev2 = this.posts.findById(postId).orElseThrow().currentRevisionId();
		assertThat(rev2).isNotEqualTo(revisionId);
		assertThat(this.revisionMedia.findAllByRevisionIdOrderByPositionAsc(rev2)).hasSize(1);
		// the old revision keeps its link (immutable, ADR-015)
		assertThat(this.revisionMedia.findAllByRevisionIdOrderByPositionAsc(revisionId)).hasSize(1);

		// UC-11 duplicate copies the current revision's media links into the new post
		this.mockMvc.perform(post("/w/private/posts/" + postId + "/duplicate").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection());
		Post copy = this.posts.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId)
			.stream()
			.filter((p) -> p.id() != postId)
			.findFirst()
			.orElseThrow();
		assertThat(copy.title()).isEqualTo("With image (copy)");
		assertThat(this.revisionMedia.findAllByRevisionIdOrderByPositionAsc(copy.currentRevisionId())).hasSize(1);
	}

	@Test
	void mediaIsStrictlyWorkspaceScoped() throws Exception {
		MockHttpSession session = login();
		Long postId = createPost(session, "private", "Private image", "hello", seedDestination("private", "Ch"));
		MediaAsset asset = uploadPng(session, postId);

		// another workspace cannot download the file (404, no bytes leaked)
		this.mockMvc.perform(get("/w/socrates-ch/media/" + asset.id() + "/file").session(session))
			.andExpect(status().isNotFound());
		// and cannot attach it to its own post (fail-closed, ADR-013)
		Long foreignPostId = createPost(session, "socrates-ch", "Foreign", "hi", seedDestination("socrates-ch", "Ch"));
		this.mockMvc
			.perform(post("/w/socrates-ch/posts/" + foreignPostId + "/media/attach").with(csrf())
				.session(session)
				.param("mediaId", asset.id().toString())
				.param("altText", "steal"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("Unknown media asset")));
		// the foreign attach added nothing — no links exist at all in this test
		assertThat(this.revisionMedia.findAllByRevisionIdOrderByPositionAsc(
				this.posts.findById(foreignPostId).orElseThrow().currentRevisionId()))
			.isEmpty();
	}

	private MediaAsset uploadPng(MockHttpSession session, Long postId) throws Exception {
		this.mockMvc
			.perform(multipart("/w/private/posts/" + postId + "/media/upload")
				.file(new MockMultipartFile("file", "chart.png", "image/png", PNG))
				.with(csrf())
				.session(session))
			.andExpect(status().is3xxRedirection());
		return this.mediaAssets.findAllByWorkspaceIdOrderByIdDesc(workspaceId("private")).get(0);
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

	private List<com.patbaumgartner.socialcrossposter.publish.Publication> publicationsOf(Long workspaceId) {
		List<com.patbaumgartner.socialcrossposter.publish.Publication> result = new ArrayList<>();
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
