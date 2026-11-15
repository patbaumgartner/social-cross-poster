package com.patbaumgartner.socialcrossposter.publish;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.patbaumgartner.socialcrossposter.accounts.AccountDestination;
import com.patbaumgartner.socialcrossposter.accounts.AccountRepository;
import com.patbaumgartner.socialcrossposter.accounts.Connection;
import com.patbaumgartner.socialcrossposter.accounts.ConnectionRepository;
import com.patbaumgartner.socialcrossposter.accounts.SecretCipher;
import com.patbaumgartner.socialcrossposter.contacts.MentionResolver;
import com.patbaumgartner.socialcrossposter.content.DestinationPreview;
import com.patbaumgartner.socialcrossposter.content.Post;
import com.patbaumgartner.socialcrossposter.content.PostRevision;
import com.patbaumgartner.socialcrossposter.content.PostService;
import com.patbaumgartner.socialcrossposter.content.PreviewService;
import com.patbaumgartner.socialcrossposter.content.RevisionDestination;
import com.patbaumgartner.socialcrossposter.content.RevisionOverride;
import com.patbaumgartner.socialcrossposter.platform.PlatformPublisher;
import com.patbaumgartner.socialcrossposter.platform.PlatformReconciler;
import com.patbaumgartner.socialcrossposter.platform.PublishContext;
import com.patbaumgartner.socialcrossposter.platform.PublishResult;
import com.patbaumgartner.socialcrossposter.platform.PublisherRegistry;
import com.patbaumgartner.socialcrossposter.platform.ReconcileContext;
import com.patbaumgartner.socialcrossposter.platform.ReconcileResult;
import com.patbaumgartner.socialcrossposter.platform.ReconcilerRegistry;

/**
 * Workspace-scoped publication service (UC-05 first slice, ADR-001, ADR-004, ADR-013,
 * ADR-015). {@link #create} binds a submission key to the current immutable revision and
 * the exact approved payloads (mention-resolved text + SHA-256 per destination) and is
 * idempotent: the same key with the same payload hash returns the existing publication, a
 * different hash under the same key is rejected. Preview blockers (over-limit, explicit
 * clear) block publishing. {@link #executeDelivery} runs one PENDING delivery
 * synchronously in the request, classifies the provider outcome (SUCCESS / FAILED /
 * UNKNOWN) and appends a redacted attempt; {@link #executeRetry} re-runs one attempt on a
 * retryable (failed/uncertain) delivery and {@link #reconcileDelivery} resolves UNKNOWN
 * outcomes through the platform reconciler — both driven by the durable job worker
 * (UC-07). {@link #refreshAggregate} derives the ADR-015 aggregate state in the
 * documented priority order. All lookups are workspace-scoped and fail closed for foreign
 * ids (ADR-013); credentials are decrypted per delivery with the AAD-bound cipher and
 * never logged (ADR-007).
 */
@Service
public class PublicationService {

	// Delivery states (ADR-015 delivery-state table).
	public static final String D_PENDING = "PENDING";

	public static final String D_RUNNING = "RUNNING";

	public static final String D_RETRY_WAIT = "RETRY_WAIT";

	public static final String D_SUCCESS = "SUCCESS";

	public static final String D_FAILED = "FAILED";

	public static final String D_SKIPPED = "SKIPPED";

	public static final String D_CANCELED = "CANCELED";

	public static final String D_UNKNOWN = "UNKNOWN";

	public static final String D_NEEDS_REVIEW = "NEEDS_REVIEW";

	public static final String D_NEEDS_ATTENTION = "NEEDS_ATTENTION";

	/**
	 * Delivery states from which a retry attempt may be executed (UC-07 step 1): failed
	 * or uncertain deliveries. SUCCESS, SKIPPED and CANCELED deliveries are never re-run.
	 */
	public static final Set<String> RETRYABLE_DELIVERY_STATES = Set.of(D_FAILED, D_UNKNOWN, D_RETRY_WAIT,
			D_NEEDS_ATTENTION, D_NEEDS_REVIEW);

	// Aggregate publication states (ADR-015 priority order).
	public static final String P_IN_PROGRESS = "IN_PROGRESS";

	public static final String P_NEEDS_ATTENTION = "NEEDS_ATTENTION";

	public static final String P_NEEDS_REVIEW = "NEEDS_REVIEW";

	public static final String P_PUBLISHED = "PUBLISHED";

	public static final String P_PARTIAL = "PARTIAL";

	public static final String P_CANCELED = "CANCELED";

	public static final String P_SKIPPED = "SKIPPED";

	public static final String P_FAILED = "FAILED";

	private final PostService posts;

	private final PreviewService previewService;

	private final MentionResolver mentions;

	private final AccountRepository accounts;

	private final ConnectionRepository connections;

	private final SecretCipher secrets;

	private final PublisherRegistry publishers;

	private final ReconcilerRegistry reconcilers;

	private final PublicationRepository publications;

	private final DeliveryRepository deliveries;

	private final DeliveryAttemptRepository attempts;

	public PublicationService(PostService posts, PreviewService previewService, MentionResolver mentions,
			AccountRepository accounts, ConnectionRepository connections, SecretCipher secrets,
			PublisherRegistry publishers, ReconcilerRegistry reconcilers, PublicationRepository publications,
			DeliveryRepository deliveries, DeliveryAttemptRepository attempts) {
		this.posts = posts;
		this.previewService = previewService;
		this.mentions = mentions;
		this.accounts = accounts;
		this.connections = connections;
		this.secrets = secrets;
		this.publishers = publishers;
		this.reconcilers = reconcilers;
		this.publications = publications;
		this.deliveries = deliveries;
		this.attempts = attempts;
	}

	/**
	 * Creates a publication for the post's current revision under the workspace-scoped
	 * {@code submissionKey}, snapshotting one immutable delivery payload per selected
	 * destination. Idempotent per ADR-015: same key + same payload hash returns the
	 * existing publication; same key + different hash is rejected. Blocking previews
	 * (over-limit, explicit clear) prevent creation. Disabled/archived destinations get a
	 * SKIPPED delivery without a remote write.
	 */
	@Transactional
	public Publication create(Long workspaceId, String workspaceSlug, Long postId, String submissionKey) {
		if (submissionKey == null || submissionKey.isBlank()) {
			throw new PublicationException("A submission key is required.");
		}
		Post post = this.posts.requirePost(workspaceId, postId);
		if (PostService.STATE_ARCHIVED.equals(post.state())) {
			throw new PublicationException("Archived posts cannot be published.");
		}
		PostRevision revision = this.posts.currentRevision(workspaceId, postId);
		List<RevisionDestination> destinations = this.posts.destinationsOf(revision.id());
		if (destinations.isEmpty()) {
			throw new PublicationException("Select at least one destination before publishing.");
		}
		List<RevisionOverride> overrides = this.posts.overridesOf(revision.id());
		List<DestinationPreview> previews = this.previewService.previews(workspaceId, revision, destinations,
				overrides);
		List<String> blockers = previews.stream().flatMap((p) -> p.blockers().stream()).distinct().toList();
		if (!blockers.isEmpty()) {
			throw new PublicationException("Publishing is blocked: " + String.join(" ", blockers));
		}
		List<Payload> payloads = previews.stream().map((p) -> {
			String effective = p.effectiveText() == null ? "" : p.effectiveText();
			String rendered = this.mentions.render(workspaceId, effective, p.destination().platform());
			return new Payload(p.destination(), rendered, sha256Hex(rendered));
		}).toList();
		String payloadHash = sha256Hex(payloads.stream().map(Payload::hash).collect(Collectors.joining("|")));
		Optional<Publication> existing = this.publications.findByWorkspaceIdAndSubmissionKey(workspaceId,
				submissionKey);
		if (existing.isPresent()) {
			Publication publication = existing.get();
			if (publication.payloadHash().equals(payloadHash)) {
				return publication;
			}
			throw new PublicationException("This submission key was already used with different content.");
		}
		OffsetDateTime now = OffsetDateTime.now();
		Publication publication = this.publications.save(new Publication(null, workspaceId, postId, revision.id(),
				submissionKey, P_IN_PROGRESS, payloadHash, now, now));
		for (Payload payload : payloads) {
			AccountDestination destination = payload.destination();
			boolean usable = destination.enabled() && !destination.archived();
			this.deliveries.save(new Delivery(null, workspaceId, publication.id(), destination.id(),
					destination.platform(), destination.label(), destination.host(), destination.providerAccountId(),
					payload.text(), payload.hash(), usable ? D_PENDING : D_SKIPPED, null, now, now));
		}
		return publication;
	}

	/**
	 * Executes one PENDING delivery synchronously in the request (UC-05 first slice):
	 * decrypts the connection secret with its AAD, calls the platform publisher and
	 * appends a redacted attempt. Missing/disabled connection or destination,
	 * undecryptable secret, or no wired publisher become NEEDS_REVIEW without a remote
	 * write. Non-PENDING deliveries are returned unchanged — retries go through
	 * {@link #executeRetry} (UC-07).
	 */
	@Transactional
	public Delivery executeDelivery(Long workspaceId, String workspaceSlug, Long deliveryId) {
		Delivery delivery = requireDelivery(workspaceId, deliveryId);
		if (!D_PENDING.equals(delivery.state())) {
			return delivery;
		}
		return doExecute(workspaceId, workspaceSlug, delivery);
	}

	/**
	 * Executes one retry attempt on a retryable delivery (UC-07 step 1): FAILED, UNKNOWN,
	 * RETRY_WAIT, NEEDS_ATTENTION or NEEDS_REVIEW. Successful, skipped or canceled
	 * deliveries are never re-run — this throws for them. The exact frozen payload is
	 * re-sent with the same workspace-scoped idempotency key.
	 */
	@Transactional
	public Delivery executeRetry(Long workspaceId, String workspaceSlug, Long deliveryId) {
		Delivery delivery = requireDelivery(workspaceId, deliveryId);
		if (!RETRYABLE_DELIVERY_STATES.contains(delivery.state())) {
			throw new PublicationException("Delivery #" + deliveryId + " in state " + delivery.state()
					+ " is not retryable — successful, skipped or canceled deliveries are never re-run.");
		}
		return doExecute(workspaceId, workspaceSlug, delivery);
	}

	/**
	 * Reconciles an uncertain (UNKNOWN/NEEDS_ATTENTION) delivery through the platform's
	 * verified read-back (UC-07 step 2, ADR-015): CONFIRMED records SUCCESS with the
	 * provider external ref, NOT_CONFIRMED records FAILED (the job layer may schedule a
	 * safe repeat of the frozen payload), INCONCLUSIVE records NEEDS_ATTENTION — never a
	 * blind retry. Appends a redacted {@code reconcile} attempt; other states are
	 * returned unchanged.
	 */
	@Transactional
	public Delivery reconcileDelivery(Long workspaceId, String workspaceSlug, Long deliveryId) {
		Delivery delivery = requireDelivery(workspaceId, deliveryId);
		if (!D_UNKNOWN.equals(delivery.state()) && !D_NEEDS_ATTENTION.equals(delivery.state())) {
			return delivery;
		}
		AccountDestination destination = this.accounts.findByIdAndWorkspaceId(delivery.accountId(), workspaceId)
			.orElseThrow(() -> new PublicationException("Unknown destination for this workspace."));
		int attemptNo = nextAttemptNo(delivery.id());
		OffsetDateTime now = OffsetDateTime.now();
		Optional<Connection> connectionOpt = this.connections.findByIdAndWorkspaceId(destination.connectionId(),
				workspaceId);
		if (connectionOpt.isEmpty() || !connectionOpt.get().enabled() || !destination.enabled()) {
			recordAttempt(workspaceId, delivery.id(), attemptNo, "reconcile", "FAILED",
					"No usable connection for this destination (missing or disabled) — review required.", null, now);
			return saveDeliveryState(delivery, D_NEEDS_REVIEW, null, now);
		}
		Connection connection = connectionOpt.get();
		String credential;
		try {
			credential = this.secrets.open(
					SecretCipher.associatedData(workspaceSlug, connection.platform(), connection.providerPrincipal()),
					new SecretCipher.SealedSecret(connection.secretCipher(), connection.secretNonce(),
							connection.secretKeyId()));
		}
		catch (RuntimeException ex) {
			recordAttempt(workspaceId, delivery.id(), attemptNo, "reconcile", "FAILED",
					"Stored credential could not be decrypted for this destination — review required.", null, now);
			return saveDeliveryState(delivery, D_NEEDS_REVIEW, null, now);
		}
		Optional<PlatformReconciler> reconcilerOpt = this.reconcilers.find(delivery.platform());
		if (reconcilerOpt.isEmpty()) {
			recordAttempt(workspaceId, delivery.id(), attemptNo, "reconcile", "FAILED",
					"No reconciler wired for platform '" + delivery.platform() + "' — review required.", null, now);
			return saveDeliveryState(delivery, D_NEEDS_REVIEW, null, now);
		}
		ReconcileResult result = reconcilerOpt.get()
			.reconcile(new ReconcileContext(credential, delivery.providerAccountId(), delivery.payloadText(),
					delivery.publicationId() + ":" + delivery.id()));
		recordAttempt(workspaceId, delivery.id(), attemptNo, "reconcile", result.outcome().name(), result.detail(),
				result.externalRef(), now);
		String state = switch (result.outcome()) {
			case CONFIRMED -> D_SUCCESS;
			case NOT_CONFIRMED -> D_FAILED;
			case INCONCLUSIVE -> D_NEEDS_ATTENTION;
		};
		return saveDeliveryState(delivery, state, result.externalRef(), now);
	}

	private Delivery doExecute(Long workspaceId, String workspaceSlug, Delivery delivery) {
		AccountDestination destination = this.accounts.findByIdAndWorkspaceId(delivery.accountId(), workspaceId)
			.orElseThrow(() -> new PublicationException("Unknown destination for this workspace."));
		int attemptNo = nextAttemptNo(delivery.id());
		OffsetDateTime now = OffsetDateTime.now();
		Optional<Connection> connectionOpt = this.connections.findByIdAndWorkspaceId(destination.connectionId(),
				workspaceId);
		if (connectionOpt.isEmpty() || !connectionOpt.get().enabled() || !destination.enabled()) {
			recordAttempt(workspaceId, delivery.id(), attemptNo, "authorize", "FAILED",
					"No usable connection for this destination (missing or disabled) — review required.", null, now);
			return saveDeliveryState(delivery, D_NEEDS_REVIEW, null, now);
		}
		Connection connection = connectionOpt.get();
		String credential;
		try {
			credential = this.secrets.open(
					SecretCipher.associatedData(workspaceSlug, connection.platform(), connection.providerPrincipal()),
					new SecretCipher.SealedSecret(connection.secretCipher(), connection.secretNonce(),
							connection.secretKeyId()));
		}
		catch (RuntimeException ex) {
			recordAttempt(workspaceId, delivery.id(), attemptNo, "authorize", "FAILED",
					"Stored credential could not be decrypted for this destination — review required.", null, now);
			return saveDeliveryState(delivery, D_NEEDS_REVIEW, null, now);
		}
		Optional<PlatformPublisher> publisherOpt = this.publishers.find(delivery.platform());
		if (publisherOpt.isEmpty()) {
			recordAttempt(workspaceId, delivery.id(), attemptNo, "authorize", "FAILED",
					"No publisher wired for platform '" + delivery.platform() + "' — review required.", null, now);
			return saveDeliveryState(delivery, D_NEEDS_REVIEW, null, now);
		}
		PublishResult result = publisherOpt.get()
			.publish(new PublishContext(credential, delivery.providerAccountId(), delivery.payloadText(),
					delivery.publicationId() + ":" + delivery.id()));
		recordAttempt(workspaceId, delivery.id(), attemptNo, "send", result.outcome().name(), result.detail(),
				result.externalRef(), now);
		String state = switch (result.outcome()) {
			case SUCCESS -> D_SUCCESS;
			case FAILED -> D_FAILED;
			case UNKNOWN -> D_UNKNOWN;
		};
		return saveDeliveryState(delivery, state, result.externalRef(), now);
	}

	/** Re-derives and persists the ADR-015 aggregate state of a publication. */
	@Transactional
	public Publication refreshAggregate(Long workspaceId, Long publicationId) {
		Publication publication = requirePublication(workspaceId, publicationId);
		List<Delivery> ds = this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(workspaceId,
				publicationId);
		String aggregate = deriveAggregate(ds);
		if (aggregate.equals(publication.state())) {
			return publication;
		}
		return this.publications.save(new Publication(publication.id(), publication.workspaceId(), publication.postId(),
				publication.revisionId(), publication.submissionKey(), aggregate, publication.payloadHash(),
				publication.createdAt(), OffsetDateTime.now()));
	}

	/**
	 * ADR-015 aggregate derivation in the documented priority order: any
	 * PENDING/RUNNING/RETRY_WAIT → IN_PROGRESS; any UNKNOWN/NEEDS_ATTENTION →
	 * NEEDS_ATTENTION; any NEEDS_REVIEW → NEEDS_REVIEW; all SUCCESS → PUBLISHED; ≥1
	 * SUCCESS → PARTIAL; all CANCELED → CANCELED; all SKIPPED/CANCELED with ≥1 SKIPPED →
	 * SKIPPED; otherwise FAILED.
	 */
	static String deriveAggregate(List<Delivery> deliveries) {
		if (deliveries.isEmpty()) {
			return P_FAILED;
		}
		boolean anyInFlight = deliveries.stream()
			.anyMatch((d) -> D_PENDING.equals(d.state()) || D_RUNNING.equals(d.state())
					|| D_RETRY_WAIT.equals(d.state()));
		if (anyInFlight) {
			return P_IN_PROGRESS;
		}
		if (deliveries.stream().anyMatch((d) -> D_UNKNOWN.equals(d.state()) || D_NEEDS_ATTENTION.equals(d.state()))) {
			return P_NEEDS_ATTENTION;
		}
		if (deliveries.stream().anyMatch((d) -> D_NEEDS_REVIEW.equals(d.state()))) {
			return P_NEEDS_REVIEW;
		}
		long successes = deliveries.stream().filter((d) -> D_SUCCESS.equals(d.state())).count();
		if (successes == deliveries.size()) {
			return P_PUBLISHED;
		}
		if (successes > 0) {
			return P_PARTIAL;
		}
		if (deliveries.stream().allMatch((d) -> D_CANCELED.equals(d.state()))) {
			return P_CANCELED;
		}
		boolean anySkipped = deliveries.stream().anyMatch((d) -> D_SKIPPED.equals(d.state()));
		boolean allSkippedOrCanceled = deliveries.stream()
			.allMatch((d) -> D_SKIPPED.equals(d.state()) || D_CANCELED.equals(d.state()));
		if (anySkipped && allSkippedOrCanceled) {
			return P_SKIPPED;
		}
		return P_FAILED;
	}

	public Delivery requireDelivery(Long workspaceId, Long deliveryId) {
		return this.deliveries.findByIdAndWorkspaceId(deliveryId, workspaceId)
			.orElseThrow(() -> new PublicationException("Unknown delivery for this workspace."));
	}

	/**
	 * Updates only the mutable state/external ref of a delivery (workspace-scoped,
	 * ADR-013); used by the UC-07 job layer to move deliveries to
	 * RETRY_WAIT/NEEDS_ATTENTION.
	 */
	@Transactional
	public Delivery updateDeliveryState(Long workspaceId, Long deliveryId, String state, String externalRef) {
		Delivery delivery = requireDelivery(workspaceId, deliveryId);
		return saveDeliveryState(delivery, state, externalRef, OffsetDateTime.now());
	}

	/** Number of attempts recorded so far for a delivery (UC-07 retry budget). */
	public long attemptCount(Long deliveryId) {
		return this.attempts.countByDeliveryId(deliveryId);
	}

	private int nextAttemptNo(Long deliveryId) {
		return this.attempts.findTopByDeliveryIdOrderByAttemptNoDesc(deliveryId)
			.map((a) -> a.attemptNo() + 1)
			.orElse(1);
	}

	public Publication requirePublication(Long workspaceId, Long publicationId) {
		return this.publications.findByIdAndWorkspaceId(publicationId, workspaceId)
			.orElseThrow(() -> new PublicationException("Unknown publication for this workspace."));
	}

	public Optional<Publication> findBySubmissionKey(Long workspaceId, String submissionKey) {
		return this.publications.findByWorkspaceIdAndSubmissionKey(workspaceId, submissionKey);
	}

	public List<Publication> publicationsOf(Long workspaceId, Long postId) {
		this.posts.requirePost(workspaceId, postId);
		return this.publications.findAllByWorkspaceIdAndPostIdOrderByIdDesc(workspaceId, postId);
	}

	public List<Delivery> deliveriesOf(Long workspaceId, Long publicationId) {
		requirePublication(workspaceId, publicationId);
		return this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(workspaceId, publicationId);
	}

	public List<DeliveryAttempt> attemptsOf(Long deliveryId) {
		return this.attempts.findAllByDeliveryIdOrderByIdAsc(deliveryId);
	}

	private void recordAttempt(Long workspaceId, Long deliveryId, int attemptNo, String stage, String outcome,
			String detail, String externalRef, OffsetDateTime now) {
		this.attempts.save(new DeliveryAttempt(null, workspaceId, deliveryId, attemptNo, stage, outcome, detail,
				externalRef, now));
	}

	private Delivery saveDeliveryState(Delivery delivery, String state, String externalRef, OffsetDateTime now) {
		return this.deliveries
			.save(new Delivery(delivery.id(), delivery.workspaceId(), delivery.publicationId(), delivery.accountId(),
					delivery.platform(), delivery.destinationLabel(), delivery.host(), delivery.providerAccountId(),
					delivery.payloadText(), delivery.payloadHash(), state, externalRef, delivery.createdAt(), now));
	}

	private static String sha256Hex(String text) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}

	private record Payload(AccountDestination destination, String text, String hash) {

	}

}
