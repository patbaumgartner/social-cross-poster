package com.patbaumgartner.socialcrossposter.publish;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.patbaumgartner.socialcrossposter.accounts.AccountDestination;
import com.patbaumgartner.socialcrossposter.accounts.AccountRepository;
import com.patbaumgartner.socialcrossposter.accounts.Connection;
import com.patbaumgartner.socialcrossposter.accounts.ConnectionRepository;
import com.patbaumgartner.socialcrossposter.accounts.SecretCipher;
import com.patbaumgartner.socialcrossposter.platform.PlatformRemoteActor;
import com.patbaumgartner.socialcrossposter.platform.PublishResult;
import com.patbaumgartner.socialcrossposter.platform.RemoteActionContext;
import com.patbaumgartner.socialcrossposter.platform.RemoteActorRegistry;
import com.patbaumgartner.socialcrossposter.platform.TextRules;
import com.patbaumgartner.socialcrossposter.platform.TextRulesRegistry;

/**
 * Workspace-scoped remote action service (UC-08 first slice, ADR-001, ADR-013, ADR-015).
 * {@link #requestEdit} / {@link #requestDelete} queue a SEPARATE remote action on a
 * confirmed (SUCCESS with external ref) delivery of the given publication and execute it
 * synchronously in the request, mirroring the UC-05 initial attempt. Guards (UC-08 steps
 * 1-3): the delivery must belong to the publication, be SUCCESS with a confirmed external
 * ref, the platform actor must exist and support the action, the replacement text must
 * pass the same {@link TextRules} as publishing (preview and execution never disagree),
 * and at most one action per delivery may be active. The original delivery and the
 * publication aggregate are NEVER modified — a provider rejection does not turn a
 * formerly SUCCESS delivery into FAILED (ADR-015); attempts are appended, redacted and
 * credential-free (ADR-007). All lookups are workspace-scoped and fail closed for foreign
 * ids (ADR-013).
 */
@Service
public class RemoteActionService {

	public static final String ACTION_EDIT = PlatformRemoteActor.ACTION_EDIT;

	public static final String ACTION_DELETE = PlatformRemoteActor.ACTION_DELETE;

	// Remote action states (ADR-015): the action lifecycle mirrors delivery states.
	public static final String A_PENDING = "PENDING";

	public static final String A_RUNNING = "RUNNING";

	public static final String A_SUCCESS = "SUCCESS";

	public static final String A_FAILED = "FAILED";

	public static final String A_UNKNOWN = "UNKNOWN";

	public static final String A_CANCELED = "CANCELED";

	/**
	 * States in which an action still occupies its delivery (one active action guard).
	 */
	public static final Set<String> ACTIVE_ACTION_STATES = Set.of(A_PENDING, A_RUNNING);

	private final PublicationService publications;

	private final AccountRepository accounts;

	private final ConnectionRepository connections;

	private final SecretCipher secrets;

	private final RemoteActorRegistry actors;

	private final TextRulesRegistry textRules;

	private final RemoteActionRepository actions;

	private final RemoteActionAttemptRepository actionAttempts;

	public RemoteActionService(PublicationService publications, AccountRepository accounts,
			ConnectionRepository connections, SecretCipher secrets, RemoteActorRegistry actors,
			TextRulesRegistry textRules, RemoteActionRepository actions, RemoteActionAttemptRepository actionAttempts) {
		this.publications = publications;
		this.accounts = accounts;
		this.connections = connections;
		this.secrets = secrets;
		this.actors = actors;
		this.textRules = textRules;
		this.actions = actions;
		this.actionAttempts = actionAttempts;
	}

	/**
	 * Queues and executes a verified remote EDIT of one confirmed delivery (UC-08 step
	 * 2): the last confirmed payload is replaced by {@code newText} after the same text
	 * rules as publishing pass. The replacement text is stored immutably on the action.
	 */
	@Transactional
	public RemoteAction requestEdit(Long workspaceId, String workspaceSlug, Long publicationId, Long deliveryId,
			String newText) {
		if (!StringUtils.hasText(newText)) {
			throw new PublicationException("The replacement text must not be empty.");
		}
		Delivery delivery = requireActionableDelivery(workspaceId, publicationId, deliveryId);
		PlatformRemoteActor actor = requireActor(delivery.platform(), ACTION_EDIT);
		TextRules rules = this.textRules.find(delivery.platform());
		if (rules.count(newText) > rules.maxTextLength()) {
			throw new PublicationException("The replacement text is over the " + delivery.platform() + " limit of "
					+ rules.maxTextLength() + " characters (" + rules.count(newText)
					+ ") — the payload is never truncated or altered.");
		}
		OffsetDateTime now = OffsetDateTime.now();
		RemoteAction action = this.actions.save(new RemoteAction(null, workspaceId, deliveryId, ACTION_EDIT, A_PENDING,
				newText, sha256Hex(newText), null, now, now));
		return execute(workspaceId, workspaceSlug, delivery, actor, action);
	}

	/**
	 * Queues and executes a verified remote DELETE of one confirmed delivery (UC-08 step
	 * 3); the owner confirms destination and external identity in the UI before this
	 * runs. No payload is stored for DELETE.
	 */
	@Transactional
	public RemoteAction requestDelete(Long workspaceId, String workspaceSlug, Long publicationId, Long deliveryId) {
		Delivery delivery = requireActionableDelivery(workspaceId, publicationId, deliveryId);
		PlatformRemoteActor actor = requireActor(delivery.platform(), ACTION_DELETE);
		OffsetDateTime now = OffsetDateTime.now();
		RemoteAction action = this.actions.save(
				new RemoteAction(null, workspaceId, deliveryId, ACTION_DELETE, A_PENDING, null, null, null, now, now));
		return execute(workspaceId, workspaceSlug, delivery, actor, action);
	}

	/**
	 * Remote actions of one delivery with their attempts, workspace-scoped and fail
	 * closed for foreign ids (ADR-013) — feeds the history detail (UC-08 step 5).
	 */
	public List<RemoteActionDetail> actionsOfDelivery(Long workspaceId, Long deliveryId) {
		this.publications.requireDelivery(workspaceId, deliveryId);
		return this.actions.findAllByWorkspaceIdAndDeliveryIdOrderByIdAsc(workspaceId, deliveryId)
			.stream()
			.map((a) -> new RemoteActionDetail(a, this.actionAttempts.findAllByRemoteActionIdOrderByIdAsc(a.id())))
			.toList();
	}

	/** Remote actions per delivery of one publication, for the history detail view. */
	public Map<Long, List<RemoteActionDetail>> actionsByDelivery(Long workspaceId, Long publicationId) {
		this.publications.requirePublication(workspaceId, publicationId);
		return this.publications.deliveriesOf(workspaceId, publicationId)
			.stream()
			.collect(Collectors.toMap(Delivery::id, (d) -> actionsOfDelivery(workspaceId, d.id())));
	}

	/** Actions the platform's wired actor supports; empty when no actor is wired. */
	public Set<String> supportedActions(String platform) {
		return this.actors.find(platform).map(PlatformRemoteActor::supportedActions).orElse(Set.of());
	}

	public boolean supports(String platform, String action) {
		return supportedActions(platform).contains(action);
	}

	/** Text limit used by the edit form so preview and execution never disagree. */
	public int maxTextLength(String platform) {
		return this.textRules.find(platform).maxTextLength();
	}

	private Delivery requireActionableDelivery(Long workspaceId, Long publicationId, Long deliveryId) {
		Publication publication = this.publications.requirePublication(workspaceId, publicationId);
		Delivery delivery = this.publications.requireDelivery(workspaceId, deliveryId);
		if (!delivery.publicationId().equals(publication.id())) {
			throw new PublicationException(
					"Delivery #" + deliveryId + " does not belong to publication #" + publicationId + ".");
		}
		if (!PublicationService.D_SUCCESS.equals(delivery.state()) || !StringUtils.hasText(delivery.externalRef())) {
			throw new PublicationException("Remote actions are only available for confirmed (SUCCESS) deliveries "
					+ "with an external reference — this delivery is " + delivery.state() + ".");
		}
		if (!this.actions
			.findAllByWorkspaceIdAndDeliveryIdAndStateInOrderByIdAsc(workspaceId, deliveryId, ACTIVE_ACTION_STATES)
			.isEmpty()) {
			throw new PublicationException("A remote action is already in progress for delivery #" + deliveryId + ".");
		}
		return delivery;
	}

	private PlatformRemoteActor requireActor(String platform, String action) {
		Optional<PlatformRemoteActor> actorOpt = this.actors.find(platform);
		if (actorOpt.isEmpty()) {
			throw new PublicationException("No remote actor wired for platform '" + platform + "' — remote "
					+ action.toLowerCase(Locale.ROOT) + " stays disabled.");
		}
		PlatformRemoteActor actor = actorOpt.get();
		if (!actor.supportedActions().contains(action)) {
			throw new PublicationException("Platform '" + platform + "' does not support remote "
					+ action.toLowerCase(Locale.ROOT) + " for this destination.");
		}
		return actor;
	}

	/**
	 * Executes one queued action synchronously: decrypts the connection secret with its
	 * AAD, calls the platform actor and appends a redacted attempt. Missing/disabled
	 * connection or destination, an undecryptable secret, or a missing actor become a
	 * FAILED action with an {@code authorize} attempt. The original delivery and the
	 * publication aggregate are never modified (ADR-015).
	 */
	private RemoteAction execute(Long workspaceId, String workspaceSlug, Delivery delivery, PlatformRemoteActor actor,
			RemoteAction action) {
		int attemptNo = nextActionAttemptNo(action.id());
		OffsetDateTime now = OffsetDateTime.now();
		AccountDestination destination = this.accounts.findByIdAndWorkspaceId(delivery.accountId(), workspaceId)
			.orElseThrow(() -> new PublicationException("Unknown destination for this workspace."));
		Optional<Connection> connectionOpt = this.connections.findByIdAndWorkspaceId(destination.connectionId(),
				workspaceId);
		if (connectionOpt.isEmpty() || !connectionOpt.get().enabled() || !destination.enabled()) {
			recordActionAttempt(workspaceId, action.id(), attemptNo, "authorize", PublicationService.D_FAILED,
					"No usable connection for this destination (missing or disabled).", null, now);
			return saveActionState(action, A_FAILED, null, now);
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
			recordActionAttempt(workspaceId, action.id(), attemptNo, "authorize", PublicationService.D_FAILED,
					"Stored credential could not be decrypted for this destination.", null, now);
			return saveActionState(action, A_FAILED, null, now);
		}
		PublishResult result = actor.execute(new RemoteActionContext(credential, delivery.providerAccountId(),
				action.action(), action.payloadText(), delivery.externalRef()));
		recordActionAttempt(workspaceId, action.id(), attemptNo, action.action().toLowerCase(Locale.ROOT),
				result.outcome().name(), result.detail(), result.externalRef(), now);
		String state = switch (result.outcome()) {
			case SUCCESS -> A_SUCCESS;
			case FAILED -> A_FAILED;
			case UNKNOWN -> A_UNKNOWN;
		};
		return saveActionState(action, state, result.externalRef(), now);
	}

	private int nextActionAttemptNo(Long remoteActionId) {
		return this.actionAttempts.findTopByRemoteActionIdOrderByAttemptNoDesc(remoteActionId)
			.map((a) -> a.attemptNo() + 1)
			.orElse(1);
	}

	private void recordActionAttempt(Long workspaceId, Long remoteActionId, int attemptNo, String stage, String outcome,
			String detail, String externalRef, OffsetDateTime now) {
		this.actionAttempts.save(new RemoteActionAttempt(null, workspaceId, remoteActionId, attemptNo, stage, outcome,
				detail, externalRef, now));
	}

	private RemoteAction saveActionState(RemoteAction action, String state, String externalRef, OffsetDateTime now) {
		return this.actions
			.save(new RemoteAction(action.id(), action.workspaceId(), action.deliveryId(), action.action(), state,
					action.payloadText(), action.payloadHash(), externalRef, action.createdAt(), now));
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

}
