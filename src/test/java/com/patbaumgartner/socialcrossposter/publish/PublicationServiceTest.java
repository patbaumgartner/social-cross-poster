package com.patbaumgartner.socialcrossposter.publish;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.patbaumgartner.socialcrossposter.accounts.AccountDestination;
import com.patbaumgartner.socialcrossposter.accounts.AccountRepository;
import com.patbaumgartner.socialcrossposter.accounts.Connection;
import com.patbaumgartner.socialcrossposter.accounts.ConnectionRepository;
import com.patbaumgartner.socialcrossposter.accounts.SecretCipher;
import com.patbaumgartner.socialcrossposter.contacts.MentionResolver;
import com.patbaumgartner.socialcrossposter.media.ImportGrantService;
import com.patbaumgartner.socialcrossposter.media.MediaAsset;
import com.patbaumgartner.socialcrossposter.media.MediaException;
import com.patbaumgartner.socialcrossposter.media.MediaService;
import com.patbaumgartner.socialcrossposter.media.RevisionMedia;
import com.patbaumgartner.socialcrossposter.media.RevisionMediaRepository;
import com.patbaumgartner.socialcrossposter.content.DestinationPreview;
import com.patbaumgartner.socialcrossposter.content.Post;
import com.patbaumgartner.socialcrossposter.content.PostException;
import com.patbaumgartner.socialcrossposter.content.PostRevision;
import com.patbaumgartner.socialcrossposter.content.PostService;
import com.patbaumgartner.socialcrossposter.content.PreviewService;
import com.patbaumgartner.socialcrossposter.content.RevisionDestination;
import com.patbaumgartner.socialcrossposter.platform.ImportSource;
import com.patbaumgartner.socialcrossposter.platform.MediaAttachment;
import com.patbaumgartner.socialcrossposter.platform.PlatformPublisher;
import com.patbaumgartner.socialcrossposter.platform.PlatformReconciler;
import com.patbaumgartner.socialcrossposter.platform.PublishContext;
import com.patbaumgartner.socialcrossposter.platform.PublishResult;
import com.patbaumgartner.socialcrossposter.platform.PublisherRegistry;
import com.patbaumgartner.socialcrossposter.platform.ReconcileContext;
import com.patbaumgartner.socialcrossposter.platform.ReconcileResult;
import com.patbaumgartner.socialcrossposter.platform.ReconcilerRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link PublicationService} (UC-05 first slice, ADR-001, ADR-013,
 * ADR-015): idempotent create (same submission key + same payload hash returns the
 * existing publication, different hash is rejected), blockers and archived posts block,
 * disabled destinations become SKIPPED deliveries, the payload uses mention rendering,
 * delivery execution classifies SUCCESS/FAILED/UNKNOWN and appends redacted attempts,
 * missing connections become NEEDS_REVIEW without a remote write, non-PENDING deliveries
 * are not re-executed, foreign ids fail closed, and the ADR-015 aggregate derivation
 * follows the documented priority order (UC-05). UC-07 additions: retries run only on
 * retryable states (never on SUCCESS/SKIPPED/CANCELED) with the next attempt number, and
 * UNKNOWN deliveries are reconciled through the platform reconciler (CONFIRMED → SUCCESS,
 * INCONCLUSIVE → NEEDS_ATTENTION, non-uncertain states untouched). Mocked dependencies,
 * no Spring context.
 */
class PublicationServiceTest {

	private static final Long WS = 1L;

	private static final Long POST_ID = 2L;

	private static final Long REVISION_ID = 3L;

	private static final Long ACCOUNT = 7L;

	private static final Long CONN = 3L;

	private static final Long PUB = 100L;

	private static final Long DELIVERY = 500L;

	private static final OffsetDateTime NOW = OffsetDateTime.now();

	private PostService posts;

	private PreviewService previewService;

	private MentionResolver mentions;

	private AccountRepository accounts;

	private ConnectionRepository connections;

	private SecretCipher secrets;

	private PublisherRegistry publishers;

	private PublicationRepository publications;

	private DeliveryRepository deliveries;

	private DeliveryAttemptRepository attempts;

	private RevisionMediaRepository revisionMedia;

	private DeliveryMediaRepository deliveryMedia;

	private MediaService media;

	private ImportGrantService importGrants;

	private PlatformPublisher publisher;

	private ReconcilerRegistry reconcilers;

	private PlatformReconciler reconciler;

	private PublicationService service;

	private Post post;

	private PostRevision revision;

	private AccountDestination destination;

	private Connection connection;

	@BeforeEach
	void setUp() {
		this.posts = mock(PostService.class);
		this.previewService = mock(PreviewService.class);
		this.mentions = mock(MentionResolver.class);
		this.accounts = mock(AccountRepository.class);
		this.connections = mock(ConnectionRepository.class);
		this.secrets = mock(SecretCipher.class);
		this.publishers = mock(PublisherRegistry.class);
		this.reconcilers = mock(ReconcilerRegistry.class);
		this.publications = mock(PublicationRepository.class);
		this.deliveries = mock(DeliveryRepository.class);
		this.attempts = mock(DeliveryAttemptRepository.class);
		this.revisionMedia = mock(RevisionMediaRepository.class);
		this.deliveryMedia = mock(DeliveryMediaRepository.class);
		this.media = mock(MediaService.class);
		this.importGrants = mock(ImportGrantService.class);
		this.publisher = mock(PlatformPublisher.class);
		this.reconciler = mock(PlatformReconciler.class);
		this.service = new PublicationService(this.posts, this.previewService, this.mentions, this.accounts,
				this.connections, this.secrets, this.publishers, this.reconcilers, this.publications, this.deliveries,
				this.attempts, this.revisionMedia, this.deliveryMedia, this.media, this.importGrants);

		this.post = new Post(POST_ID, WS, "Title", PostService.STATE_DRAFT, REVISION_ID, 1L, NOW, NOW);
		this.revision = new PostRevision(REVISION_ID, WS, POST_ID, 1, "hello", NOW);
		this.destination = new AccountDestination(ACCOUNT, WS, CONN, "telegram", "channel", "-100123", null,
				"News channel", true, false, NOW, NOW);
		this.connection = new Connection(CONN, WS, "telegram", "pasted", "@testbot", "Main bot", "cipher", "nonce",
				"test-v1", "OK", NOW, true, NOW, NOW);

		given(this.posts.requirePost(WS, POST_ID)).willReturn(this.post);
		given(this.posts.currentRevision(WS, POST_ID)).willReturn(this.revision);
		given(this.posts.destinationsOf(REVISION_ID))
			.willReturn(List.of(new RevisionDestination(9L, WS, REVISION_ID, ACCOUNT)));
		given(this.posts.overridesOf(REVISION_ID)).willReturn(List.of());
		given(this.previewService.previews(eq(WS), eq(this.revision), anyList(), anyList()))
			.willReturn(List.of(preview(this.destination, "hello", List.of())));
		given(this.mentions.render(eq(WS), anyString(), eq("telegram"))).willAnswer((inv) -> inv.getArgument(1));
		given(this.accounts.findByIdAndWorkspaceId(ACCOUNT, WS)).willReturn(Optional.of(this.destination));
		given(this.connections.findByIdAndWorkspaceId(CONN, WS)).willReturn(Optional.of(this.connection));
		given(this.secrets.open(eq(SecretCipher.associatedData("private", "telegram", "@testbot")),
				any(SecretCipher.SealedSecret.class)))
			.willReturn("good-token");
		given(this.publishers.find("telegram")).willReturn(Optional.of(this.publisher));
		given(this.publications.save(any(Publication.class))).willAnswer((inv) -> {
			Publication p = inv.getArgument(0);
			return p.id() == null ? new Publication(PUB, p.workspaceId(), p.postId(), p.revisionId(), p.submissionKey(),
					p.state(), p.payloadHash(), p.createdAt(), p.updatedAt()) : p;
		});
		given(this.deliveries.save(any(Delivery.class))).willAnswer((inv) -> {
			Delivery d = inv.getArgument(0);
			return d.id() == null ? new Delivery(DELIVERY, d.workspaceId(), d.publicationId(), d.accountId(),
					d.platform(), d.destinationLabel(), d.host(), d.providerAccountId(), d.payloadText(),
					d.payloadHash(), d.state(), d.externalRef(), d.createdAt(), d.updatedAt()) : d;
		});
		given(this.attempts.save(any(DeliveryAttempt.class))).willAnswer((inv) -> inv.getArgument(0));
	}

	@Test
	void createPersistsPublicationWithPendingDeliveryAndHashes() {
		Publication publication = this.service.create(WS, "private", POST_ID, "key-1");

		assertThat(publication.submissionKey()).isEqualTo("key-1");
		assertThat(publication.state()).isEqualTo(PublicationService.P_IN_PROGRESS);
		assertThat(publication.payloadHash()).hasSize(64);
		verify(this.publications)
			.save(argThat((Publication p) -> p.id() == null && PublicationService.P_IN_PROGRESS.equals(p.state())
					&& p.postId().equals(POST_ID) && p.revisionId().equals(REVISION_ID)));
		verify(this.deliveries).save(argThat((Delivery d) -> PublicationService.D_PENDING.equals(d.state())
				&& "hello".equals(d.payloadText()) && d.payloadHash() != null && d.payloadHash().length() == 64
				&& "-100123".equals(d.providerAccountId()) && d.accountId().equals(ACCOUNT)));
	}

	@Test
	void sameSubmissionKeyWithSamePayloadReturnsExistingPublication() {
		Publication first = this.service.create(WS, "private", POST_ID, "key-1");
		given(this.publications.findByWorkspaceIdAndSubmissionKey(WS, "key-1")).willReturn(Optional.of(first));

		Publication second = this.service.create(WS, "private", POST_ID, "key-1");

		assertThat(second).isSameAs(first);
		verify(this.publications, times(1)).save(any(Publication.class));
	}

	@Test
	void sameSubmissionKeyWithDifferentPayloadIsRejected() {
		Publication existing = new Publication(PUB, WS, POST_ID, REVISION_ID, "key-1", PublicationService.P_PUBLISHED,
				"different-hash", NOW, NOW);
		given(this.publications.findByWorkspaceIdAndSubmissionKey(WS, "key-1")).willReturn(Optional.of(existing));

		assertThatThrownBy(() -> this.service.create(WS, "private", POST_ID, "key-1"))
			.isInstanceOf(PublicationException.class)
			.hasMessageContaining("different content");
		verify(this.publications, never()).save(any(Publication.class));
	}

	@Test
	void previewBlockersBlockCreation() {
		given(this.previewService.previews(eq(WS), eq(this.revision), anyList(), anyList()))
			.willReturn(List.of(preview(this.destination, "a".repeat(5000),
					List.of("Text is 5000 characters but telegram allows at most 4096"))));

		assertThatThrownBy(() -> this.service.create(WS, "private", POST_ID, "key-1"))
			.isInstanceOf(PublicationException.class)
			.hasMessageContaining("blocked");
		verify(this.publications, never()).save(any(Publication.class));
	}

	@Test
	void missingDestinationSelectionBlocksCreation() {
		given(this.posts.destinationsOf(REVISION_ID)).willReturn(List.of());

		assertThatThrownBy(() -> this.service.create(WS, "private", POST_ID, "key-1"))
			.isInstanceOf(PublicationException.class)
			.hasMessageContaining("at least one destination");
	}

	@Test
	void archivedPostCannotBePublished() {
		given(this.posts.requirePost(WS, POST_ID))
			.willReturn(new Post(POST_ID, WS, "t", PostService.STATE_ARCHIVED, REVISION_ID, 1L, NOW, NOW));

		assertThatThrownBy(() -> this.service.create(WS, "private", POST_ID, "key-1"))
			.isInstanceOf(PublicationException.class)
			.hasMessageContaining("Archived");
	}

	@Test
	void archivedDestinationBecomesSkippedDeliveryWithoutRemoteWrite() {
		AccountDestination archived = new AccountDestination(ACCOUNT, WS, CONN, "telegram", "channel", "-100123", null,
				"Old channel", false, true, NOW, NOW);
		given(this.previewService.previews(eq(WS), eq(this.revision), anyList(), anyList()))
			.willReturn(List.of(preview(archived, "hello", List.of())));

		this.service.create(WS, "private", POST_ID, "key-1");

		verify(this.deliveries).save(argThat((Delivery d) -> PublicationService.D_SKIPPED.equals(d.state())));
	}

	@Test
	void payloadTextUsesMentionRendering() {
		String raw = "hi {{contact:00000000-0000-0000-0000-000000000000}}";
		given(this.mentions.render(WS, raw, "telegram")).willReturn("hi @alice");
		given(this.previewService.previews(eq(WS), eq(this.revision), anyList(), anyList()))
			.willReturn(List.of(preview(this.destination, raw, List.of())));

		this.service.create(WS, "private", POST_ID, "key-1");

		verify(this.deliveries).save(argThat((Delivery d) -> "hi @alice".equals(d.payloadText())));
	}

	@Test
	void foreignPostIdFailsClosedOnCreate() {
		given(this.posts.requirePost(WS, 999L)).willThrow(new PostException("Unknown post for this workspace."));

		assertThatThrownBy(() -> this.service.create(WS, "private", 999L, "key-1")).isInstanceOf(PostException.class)
			.hasMessageContaining("Unknown post");
	}

	@Test
	void executeDeliverySuccessAppendsAttemptAndSetsExternalRef() {
		given(this.deliveries.findByIdAndWorkspaceId(DELIVERY, WS)).willReturn(Optional.of(pendingDelivery()));
		given(this.attempts.findTopByDeliveryIdOrderByAttemptNoDesc(DELIVERY)).willReturn(Optional.empty());
		given(this.publisher.publish(any(PublishContext.class)))
			.willReturn(PublishResult.success("telegram:message:42"));

		Delivery result = this.service.executeDelivery(WS, "private", DELIVERY);

		assertThat(result.state()).isEqualTo(PublicationService.D_SUCCESS);
		assertThat(result.externalRef()).isEqualTo("telegram:message:42");
		ArgumentCaptor<PublishContext> context = ArgumentCaptor.forClass(PublishContext.class);
		verify(this.publisher).publish(context.capture());
		assertThat(context.getValue().credential()).isEqualTo("good-token");
		assertThat(context.getValue().targetId()).isEqualTo("-100123");
		assertThat(context.getValue().text()).isEqualTo("hello");
		assertThat(context.getValue().idempotencyKey()).isEqualTo(PUB + ":" + DELIVERY);
		verify(this.attempts).save(argThat((DeliveryAttempt a) -> a.attemptNo() == 1 && "send".equals(a.stage())
				&& "SUCCESS".equals(a.outcome()) && "telegram:message:42".equals(a.externalRef())));
		verify(this.deliveries).save(argThat((Delivery d) -> PublicationService.D_SUCCESS.equals(d.state())));
	}

	@Test
	void executeDeliveryClassifiesConfirmedFailureAsFailed() {
		given(this.deliveries.findByIdAndWorkspaceId(DELIVERY, WS)).willReturn(Optional.of(pendingDelivery()));
		given(this.attempts.findTopByDeliveryIdOrderByAttemptNoDesc(DELIVERY)).willReturn(Optional.empty());
		given(this.publisher.publish(any(PublishContext.class)))
			.willReturn(PublishResult.failed("Telegram rejected the message (HTTP 400)."));

		Delivery result = this.service.executeDelivery(WS, "private", DELIVERY);

		assertThat(result.state()).isEqualTo(PublicationService.D_FAILED);
		assertThat(result.externalRef()).isNull();
		verify(this.attempts).save(argThat((DeliveryAttempt a) -> "FAILED".equals(a.outcome()) && a.detail() != null
				&& a.detail().contains("HTTP 400") && a.externalRef() == null));
	}

	@Test
	void executeDeliveryClassifiesUncertainOutcomeAsUnknown() {
		given(this.deliveries.findByIdAndWorkspaceId(DELIVERY, WS)).willReturn(Optional.of(pendingDelivery()));
		given(this.attempts.findTopByDeliveryIdOrderByAttemptNoDesc(DELIVERY)).willReturn(Optional.empty());
		given(this.publisher.publish(any(PublishContext.class)))
			.willReturn(PublishResult.unknown("Telegram write could not be confirmed (HTTP 500)."));

		Delivery result = this.service.executeDelivery(WS, "private", DELIVERY);

		assertThat(result.state()).isEqualTo(PublicationService.D_UNKNOWN);
		verify(this.attempts).save(argThat((DeliveryAttempt a) -> "UNKNOWN".equals(a.outcome())));
	}

	@Test
	void executeDeliveryWithoutUsableConnectionNeedsReviewAndNeverCallsProvider() {
		given(this.deliveries.findByIdAndWorkspaceId(DELIVERY, WS)).willReturn(Optional.of(pendingDelivery()));
		given(this.attempts.findTopByDeliveryIdOrderByAttemptNoDesc(DELIVERY)).willReturn(Optional.empty());
		given(this.connections.findByIdAndWorkspaceId(CONN, WS)).willReturn(Optional.empty());

		Delivery result = this.service.executeDelivery(WS, "private", DELIVERY);

		assertThat(result.state()).isEqualTo(PublicationService.D_NEEDS_REVIEW);
		verify(this.attempts)
			.save(argThat((DeliveryAttempt a) -> "authorize".equals(a.stage()) && "FAILED".equals(a.outcome())));
		verifyNoInteractions(this.publisher);
	}

	@Test
	void executeDeliverySkipsNonPendingDeliveries() {
		Delivery done = delivery(PublicationService.D_SUCCESS);
		given(this.deliveries.findByIdAndWorkspaceId(DELIVERY, WS)).willReturn(Optional.of(done));

		Delivery result = this.service.executeDelivery(WS, "private", DELIVERY);

		assertThat(result).isSameAs(done);
		verifyNoInteractions(this.publisher, this.attempts, this.secrets);
	}

	@Test
	void foreignDeliveryIdFailsClosed() {
		given(this.deliveries.findByIdAndWorkspaceId(DELIVERY, WS)).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.executeDelivery(WS, "private", DELIVERY))
			.isInstanceOf(PublicationException.class)
			.hasMessageContaining("Unknown delivery");
	}

	@Test
	void executeRetryRejectsSuccessfulDeliveryAndNeverCallsProvider() {
		given(this.deliveries.findByIdAndWorkspaceId(DELIVERY, WS))
			.willReturn(Optional.of(deliveryWithState(PublicationService.D_SUCCESS)));

		assertThatThrownBy(() -> this.service.executeRetry(WS, "private", DELIVERY))
			.isInstanceOf(PublicationException.class)
			.hasMessageContaining("never re-run");
		verifyNoInteractions(this.publisher);
	}

	@Test
	void executeRetryRunsFailedDeliveryAgainWithNextAttemptNo() {
		given(this.deliveries.findByIdAndWorkspaceId(DELIVERY, WS))
			.willReturn(Optional.of(deliveryWithState(PublicationService.D_FAILED)));
		given(this.attempts.findTopByDeliveryIdOrderByAttemptNoDesc(DELIVERY))
			.willReturn(Optional.of(new DeliveryAttempt(1L, WS, DELIVERY, 1, "send", "FAILED", "HTTP 400", null, NOW)));
		given(this.publisher.publish(any(PublishContext.class)))
			.willReturn(PublishResult.success("telegram:message:43"));

		Delivery result = this.service.executeRetry(WS, "private", DELIVERY);

		assertThat(result.state()).isEqualTo(PublicationService.D_SUCCESS);
		assertThat(result.externalRef()).isEqualTo("telegram:message:43");
		verify(this.attempts).save(argThat((DeliveryAttempt a) -> a.attemptNo() == 2 && "send".equals(a.stage())
				&& "SUCCESS".equals(a.outcome()) && "telegram:message:43".equals(a.externalRef())));
	}

	@Test
	void reconcileConfirmedRecordsSuccessWithExternalRef() {
		given(this.deliveries.findByIdAndWorkspaceId(DELIVERY, WS))
			.willReturn(Optional.of(deliveryWithState(PublicationService.D_UNKNOWN)));
		given(this.attempts.findTopByDeliveryIdOrderByAttemptNoDesc(DELIVERY)).willReturn(Optional.empty());
		given(this.reconcilers.find("telegram")).willReturn(Optional.of(this.reconciler));
		given(this.reconciler.reconcile(any(ReconcileContext.class)))
			.willReturn(ReconcileResult.confirmed("telegram:message:42"));

		Delivery result = this.service.reconcileDelivery(WS, "private", DELIVERY);

		assertThat(result.state()).isEqualTo(PublicationService.D_SUCCESS);
		assertThat(result.externalRef()).isEqualTo("telegram:message:42");
		verify(this.attempts).save(argThat((DeliveryAttempt a) -> "reconcile".equals(a.stage())
				&& "CONFIRMED".equals(a.outcome()) && "telegram:message:42".equals(a.externalRef())));
	}

	@Test
	void reconcileInconclusiveBecomesNeedsAttentionAndIsNeverBlindlyRetried() {
		given(this.deliveries.findByIdAndWorkspaceId(DELIVERY, WS))
			.willReturn(Optional.of(deliveryWithState(PublicationService.D_UNKNOWN)));
		given(this.attempts.findTopByDeliveryIdOrderByAttemptNoDesc(DELIVERY)).willReturn(Optional.empty());
		given(this.reconcilers.find("telegram")).willReturn(Optional.of(this.reconciler));
		given(this.reconciler.reconcile(any(ReconcileContext.class)))
			.willReturn(ReconcileResult.inconclusive("no read-back available"));

		Delivery result = this.service.reconcileDelivery(WS, "private", DELIVERY);

		assertThat(result.state()).isEqualTo(PublicationService.D_NEEDS_ATTENTION);
		assertThat(result.externalRef()).isNull();
		verify(this.attempts)
			.save(argThat((DeliveryAttempt a) -> "reconcile".equals(a.stage()) && "INCONCLUSIVE".equals(a.outcome())));
	}

	@Test
	void reconcileSkipsNonUncertainDeliveries() {
		Delivery done = deliveryWithState(PublicationService.D_SUCCESS);
		given(this.deliveries.findByIdAndWorkspaceId(DELIVERY, WS)).willReturn(Optional.of(done));

		Delivery result = this.service.reconcileDelivery(WS, "private", DELIVERY);

		assertThat(result).isSameAs(done);
		verifyNoInteractions(this.reconciler, this.secrets);
	}

	@Test
	void refreshAggregateDerivesAndPersistsAdr015State() {
		Publication inProgress = new Publication(PUB, WS, POST_ID, REVISION_ID, "key-1",
				PublicationService.P_IN_PROGRESS, "hash", NOW, NOW);
		given(this.publications.findByIdAndWorkspaceId(PUB, WS)).willReturn(Optional.of(inProgress));
		given(this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(WS, PUB))
			.willReturn(List.of(delivery("SUCCESS"), delivery("SUCCESS")));

		Publication refreshed = this.service.refreshAggregate(WS, PUB);

		assertThat(refreshed.state()).isEqualTo(PublicationService.P_PUBLISHED);
		verify(this.publications).save(argThat((Publication p) -> PublicationService.P_PUBLISHED.equals(p.state())));
	}

	@Test
	void deriveAggregateFollowsAdr015PriorityOrder() {
		assertThat(PublicationService.deriveAggregate(List.of(delivery("PENDING"))))
			.isEqualTo(PublicationService.P_IN_PROGRESS);
		assertThat(PublicationService.deriveAggregate(List.of(delivery("SUCCESS"), delivery("RETRY_WAIT"))))
			.isEqualTo(PublicationService.P_IN_PROGRESS);
		assertThat(PublicationService.deriveAggregate(List.of(delivery("UNKNOWN"), delivery("SUCCESS"))))
			.isEqualTo(PublicationService.P_NEEDS_ATTENTION);
		assertThat(PublicationService.deriveAggregate(List.of(delivery("NEEDS_ATTENTION"), delivery("SUCCESS"))))
			.isEqualTo(PublicationService.P_NEEDS_ATTENTION);
		assertThat(PublicationService.deriveAggregate(List.of(delivery("NEEDS_REVIEW"), delivery("SUCCESS"))))
			.isEqualTo(PublicationService.P_NEEDS_REVIEW);
		assertThat(PublicationService.deriveAggregate(List.of(delivery("SUCCESS"), delivery("SUCCESS"))))
			.isEqualTo(PublicationService.P_PUBLISHED);
		assertThat(PublicationService.deriveAggregate(List.of(delivery("SUCCESS"), delivery("FAILED"))))
			.isEqualTo(PublicationService.P_PARTIAL);
		assertThat(PublicationService.deriveAggregate(List.of(delivery("CANCELED"), delivery("CANCELED"))))
			.isEqualTo(PublicationService.P_CANCELED);
		assertThat(PublicationService.deriveAggregate(List.of(delivery("SKIPPED"), delivery("CANCELED"))))
			.isEqualTo(PublicationService.P_SKIPPED);
		assertThat(PublicationService.deriveAggregate(List.of(delivery("FAILED"), delivery("FAILED"))))
			.isEqualTo(PublicationService.P_FAILED);
		assertThat(PublicationService.deriveAggregate(List.of())).isEqualTo(PublicationService.P_FAILED);
	}

	@Test
	void mediaAttachedWithUploadCapablePlatformSnapshotsDeliveryMedia() {
		attachOneImage("a".repeat(64));
		given(this.publisher.supportsMediaUpload()).willReturn(true);

		Publication publication = this.service.create(WS, "private", POST_ID, "key-media");

		assertThat(publication.state()).isEqualTo(PublicationService.P_IN_PROGRESS);
		verify(this.deliveryMedia).save(argThat((DeliveryMedia m) -> m.deliveryId().equals(DELIVERY)
				&& m.position() == 1 && m.sha256().equals("a".repeat(64)) && m.altText().equals("Chart")));
		// no media blocker for an upload-capable platform
		given(this.posts.requireRevisionById(WS, REVISION_ID)).willReturn(this.revision);
		assertThat(this.service.blockersOf(WS, REVISION_ID)).isEmpty();
	}

	@Test
	void mediaAttachedWithUploadIncablePlatformIsBlockedFailClosed() {
		attachOneImage("b".repeat(64));
		given(this.publisher.supportsMediaUpload()).willReturn(false);
		given(this.posts.requireRevisionById(WS, REVISION_ID)).willReturn(this.revision);

		assertThat(this.service.blockersOf(WS, REVISION_ID))
			.contains(PublicationService.BLOCKER_MEDIA_PLATFORM.formatted("telegram"));
		assertThatThrownBy(() -> this.service.create(WS, "private", POST_ID, "key-media"))
			.isInstanceOf(PublicationException.class)
			.hasMessageContaining("blocked")
			.hasMessageContaining("telegram");
		verify(this.publications, never()).save(any(Publication.class));
		verify(this.deliveries, never()).save(any(Delivery.class));
	}

	@Test
	void payloadHashCoversAttachedImageContent() {
		given(this.posts.requireRevisionById(WS, REVISION_ID)).willReturn(this.revision);
		attachOneImage("c".repeat(64));
		String hash1 = this.service.payloadHashFor(WS, REVISION_ID);

		attachOneImage("d".repeat(64));
		String hash2 = this.service.payloadHashFor(WS, REVISION_ID);

		assertThat(hash1).hasSize(64);
		assertThat(hash1).isNotEqualTo(hash2); // changed image -> changed hash (UC-10
												// NEEDS_REVIEW)
	}

	@Test
	void executeDeliveryPassesApprovedStrippedMediaBytesToThePublisher() {
		byte[] outgoing = { 9, 8, 7 }; // the metadata-stripped derivative, not the
										// original (ADR-016)
		String sha = "e".repeat(64);
		given(this.deliveries.findByIdAndWorkspaceId(DELIVERY, WS)).willReturn(Optional.of(pendingDelivery()));
		given(this.attempts.findTopByDeliveryIdOrderByAttemptNoDesc(DELIVERY)).willReturn(Optional.empty());
		given(this.deliveryMedia.findAllByDeliveryIdOrderByPositionAsc(DELIVERY))
			.willReturn(List.of(new DeliveryMedia(50L, WS, DELIVERY, 1, sha, "Chart", NOW)));
		given(this.media.findBySha(WS, sha))
			.willReturn(Optional.of(new MediaAsset(60L, WS, "chart.png", "image/png", 3L, sha, NOW)));
		given(this.media.outgoingBytes(WS, sha)).willReturn(outgoing);
		given(this.publisher.supportsMediaUpload()).willReturn(true);
		given(this.publisher.publish(any(PublishContext.class)))
			.willReturn(PublishResult.success("telegram:message:42"));

		Delivery result = this.service.executeDelivery(WS, "private", DELIVERY);

		assertThat(result.state()).isEqualTo(PublicationService.D_SUCCESS);
		ArgumentCaptor<PublishContext> captor = ArgumentCaptor.forClass(PublishContext.class);
		verify(this.publisher).publish(captor.capture());
		List<MediaAttachment> media = captor.getValue().media();
		assertThat(media).hasSize(1);
		assertThat(media.get(0).filename()).isEqualTo("chart.png");
		assertThat(media.get(0).contentType()).isEqualTo("image/png");
		assertThat(media.get(0).bytes()).isEqualTo(outgoing);
		assertThat(media.get(0).altText()).isEqualTo("Chart");
	}

	@Test
	void executeDeliveryWithMissingOutgoingDerivativeFailsClosedWithoutRemoteWrite() {
		String sha = "e".repeat(64);
		given(this.deliveries.findByIdAndWorkspaceId(DELIVERY, WS)).willReturn(Optional.of(pendingDelivery()));
		given(this.attempts.findTopByDeliveryIdOrderByAttemptNoDesc(DELIVERY)).willReturn(Optional.empty());
		given(this.deliveryMedia.findAllByDeliveryIdOrderByPositionAsc(DELIVERY))
			.willReturn(List.of(new DeliveryMedia(50L, WS, DELIVERY, 1, sha, "Chart", NOW)));
		given(this.media.findBySha(WS, sha))
			.willReturn(Optional.of(new MediaAsset(60L, WS, "chart.png", "image/png", 3L, sha, NOW)));
		given(this.media.outgoingBytes(WS, sha)).willThrow(new MediaException("derivative unavailable"));
		given(this.publisher.supportsMediaUpload()).willReturn(true);

		Delivery result = this.service.executeDelivery(WS, "private", DELIVERY);

		assertThat(result.state()).isEqualTo(PublicationService.D_NEEDS_REVIEW);
		verify(this.attempts).save(argThat((DeliveryAttempt a) -> "authorize".equals(a.stage())
				&& "FAILED".equals(a.outcome()) && a.detail().contains("no longer available")));
		// the capability probe happened, but no remote write
		verify(this.publisher, never()).publish(any(PublishContext.class));
	}

	@Test
	void executeDeliveryWithMissingMediaSourceFailsClosedWithoutRemoteWrite() {
		String sha = "f".repeat(64);
		given(this.deliveries.findByIdAndWorkspaceId(DELIVERY, WS)).willReturn(Optional.of(pendingDelivery()));
		given(this.attempts.findTopByDeliveryIdOrderByAttemptNoDesc(DELIVERY)).willReturn(Optional.empty());
		given(this.deliveryMedia.findAllByDeliveryIdOrderByPositionAsc(DELIVERY))
			.willReturn(List.of(new DeliveryMedia(50L, WS, DELIVERY, 1, sha, "Chart", NOW)));
		given(this.media.findBySha(WS, sha)).willReturn(Optional.empty());
		given(this.publisher.supportsMediaUpload()).willReturn(true);

		Delivery result = this.service.executeDelivery(WS, "private", DELIVERY);

		assertThat(result.state()).isEqualTo(PublicationService.D_NEEDS_REVIEW);
		verify(this.attempts).save(argThat((DeliveryAttempt a) -> "authorize".equals(a.stage())
				&& "FAILED".equals(a.outcome()) && a.detail().contains("no longer available")));
		// the capability probe happened, but no remote write
		verify(this.publisher, never()).publish(any(PublishContext.class));
	}

	@Test
	void mediaAttachedWithImportCapablePlatformIsNotBlocked() {
		attachOneImage("b".repeat(64));
		given(this.publisher.supportsMediaUpload()).willReturn(false);
		given(this.publisher.supportsMediaImport()).willReturn(true);
		given(this.posts.requireRevisionById(WS, REVISION_ID)).willReturn(this.revision);

		assertThat(this.service.blockersOf(WS, REVISION_ID)).isEmpty();
	}

	@Test
	void executeDeliveryMintsImportGrantsForImportCapablePlatformAndRevokesOnSuccess() {
		String sha = "a".repeat(64);
		given(this.deliveries.findByIdAndWorkspaceId(DELIVERY, WS)).willReturn(Optional.of(pendingDelivery()));
		given(this.attempts.findTopByDeliveryIdOrderByAttemptNoDesc(DELIVERY)).willReturn(Optional.empty());
		given(this.deliveryMedia.findAllByDeliveryIdOrderByPositionAsc(DELIVERY))
			.willReturn(List.of(new DeliveryMedia(50L, WS, DELIVERY, 1, sha, "Chart", NOW)));
		given(this.publisher.supportsMediaUpload()).willReturn(false);
		given(this.publisher.supportsMediaImport()).willReturn(true);
		given(this.importGrants.mint(eq(WS), eq(DELIVERY), eq(sha), eq(ImportGrantService.PURPOSE_PLATFORM_IMPORT),
				any(OffsetDateTime.class)))
			.willReturn(new ImportGrantService.MintedGrant(77L,
					"http://localhost:8080/media/grants/77?exp=1&sig=deadbeef", NOW.plusMinutes(30)));
		given(this.publisher.publish(any(PublishContext.class)))
			.willReturn(PublishResult.success("mastodon:status:42"));

		Delivery result = this.service.executeDelivery(WS, "private", DELIVERY);

		assertThat(result.state()).isEqualTo(PublicationService.D_SUCCESS);
		ArgumentCaptor<PublishContext> captor = ArgumentCaptor.forClass(PublishContext.class);
		verify(this.publisher).publish(captor.capture());
		PublishContext ctx = captor.getValue();
		assertThat(ctx.media()).isEmpty();
		assertThat(ctx.importSources()).hasSize(1);
		ImportSource source = ctx.importSources().get(0);
		assertThat(source.sha256()).isEqualTo(sha);
		assertThat(source.url()).contains("/media/grants/77");
		assertThat(source.altText()).isEqualTo("Chart");
		// ADR-008 step 5: a confirmed provider write retires the delivery's grants
		verify(this.importGrants).revokeActiveForDelivery(eq(WS), eq(DELIVERY), any(OffsetDateTime.class));
	}

	@Test
	void executeDeliveryDoesNotRevokeImportGrantsOnUnconfirmedOutcome() {
		String sha = "a".repeat(64);
		given(this.deliveries.findByIdAndWorkspaceId(DELIVERY, WS)).willReturn(Optional.of(pendingDelivery()));
		given(this.attempts.findTopByDeliveryIdOrderByAttemptNoDesc(DELIVERY)).willReturn(Optional.empty());
		given(this.deliveryMedia.findAllByDeliveryIdOrderByPositionAsc(DELIVERY))
			.willReturn(List.of(new DeliveryMedia(50L, WS, DELIVERY, 1, sha, "Chart", NOW)));
		given(this.publisher.supportsMediaUpload()).willReturn(false);
		given(this.publisher.supportsMediaImport()).willReturn(true);
		given(this.importGrants.mint(any(), any(), anyString(), anyString(), any(OffsetDateTime.class)))
			.willReturn(new ImportGrantService.MintedGrant(77L, "http://localhost:8080/media/grants/77?exp=1&sig=x",
					NOW.plusMinutes(30)));
		given(this.publisher.publish(any(PublishContext.class))).willReturn(PublishResult.unknown("timeout"));

		Delivery result = this.service.executeDelivery(WS, "private", DELIVERY);

		assertThat(result.state()).isEqualTo(PublicationService.D_UNKNOWN);
		verify(this.importGrants, never()).revokeActiveForDelivery(any(), any(), any());
	}

	@Test
	void executeDeliveryWithImportCapablePlatformAndMissingAssetFailsClosedWithoutRemoteWrite() {
		String sha = "f".repeat(64);
		given(this.deliveries.findByIdAndWorkspaceId(DELIVERY, WS)).willReturn(Optional.of(pendingDelivery()));
		given(this.attempts.findTopByDeliveryIdOrderByAttemptNoDesc(DELIVERY)).willReturn(Optional.empty());
		given(this.deliveryMedia.findAllByDeliveryIdOrderByPositionAsc(DELIVERY))
			.willReturn(List.of(new DeliveryMedia(50L, WS, DELIVERY, 1, sha, "Chart", NOW)));
		given(this.publisher.supportsMediaUpload()).willReturn(false);
		given(this.publisher.supportsMediaImport()).willReturn(true);
		given(this.importGrants.mint(any(), any(), anyString(), anyString(), any(OffsetDateTime.class)))
			.willThrow(new MediaException("Unknown media asset for this workspace."));

		Delivery result = this.service.executeDelivery(WS, "private", DELIVERY);

		assertThat(result.state()).isEqualTo(PublicationService.D_NEEDS_REVIEW);
		verify(this.attempts).save(argThat((DeliveryAttempt a) -> "authorize".equals(a.stage())
				&& "FAILED".equals(a.outcome()) && a.detail().contains("no longer available")));
		verify(this.publisher, never()).publish(any(PublishContext.class));
	}

	@Test
	void executeDeliveryWithNeitherUploadNorImportSupportFailsClosedWithoutRemoteWrite() {
		String sha = "f".repeat(64);
		given(this.deliveries.findByIdAndWorkspaceId(DELIVERY, WS)).willReturn(Optional.of(pendingDelivery()));
		given(this.attempts.findTopByDeliveryIdOrderByAttemptNoDesc(DELIVERY)).willReturn(Optional.empty());
		given(this.deliveryMedia.findAllByDeliveryIdOrderByPositionAsc(DELIVERY))
			.willReturn(List.of(new DeliveryMedia(50L, WS, DELIVERY, 1, sha, "Chart", NOW)));
		given(this.publisher.supportsMediaUpload()).willReturn(false);
		given(this.publisher.supportsMediaImport()).willReturn(false);

		Delivery result = this.service.executeDelivery(WS, "private", DELIVERY);

		assertThat(result.state()).isEqualTo(PublicationService.D_NEEDS_REVIEW);
		verify(this.attempts).save(argThat((DeliveryAttempt a) -> "authorize".equals(a.stage())
				&& "FAILED".equals(a.outcome()) && a.detail().contains("neither upload nor import")));
		verify(this.publisher, never()).publish(any(PublishContext.class));
		verifyNoInteractions(this.importGrants);
	}

	private void attachOneImage(String sha) {
		given(this.revisionMedia.findAllByRevisionIdOrderByPositionAsc(REVISION_ID))
			.willReturn(List.of(new RevisionMedia(50L, WS, REVISION_ID, 60L, 1, "Chart", NOW)));
		given(this.media.requireAsset(WS, 60L))
			.willReturn(new MediaAsset(60L, WS, "chart.png", "image/png", 3L, sha, NOW));
	}

	private Delivery pendingDelivery() {
		return deliveryWithState(PublicationService.D_PENDING);
	}

	private Delivery deliveryWithState(String state) {
		return new Delivery(DELIVERY, WS, PUB, ACCOUNT, "telegram", "News channel", null, "-100123", "hello",
				"abc123hash", state, null, NOW, NOW);
	}

	private Delivery delivery(String state) {
		return new Delivery(1L, WS, PUB, ACCOUNT, "telegram", "News channel", null, "-100123", "t", "h", state, null,
				NOW, NOW);
	}

	private DestinationPreview preview(String text, List<String> blockers) {
		return preview(this.destination, text, blockers);
	}

	private DestinationPreview preview(AccountDestination destination, String text, List<String> blockers) {
		return new DestinationPreview(destination, text, text.length(), 4096, text.length() > 4096, List.of(),
				blockers);
	}

}
