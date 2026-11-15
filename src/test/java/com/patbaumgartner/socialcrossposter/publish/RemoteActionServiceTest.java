package com.patbaumgartner.socialcrossposter.publish;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Unit tests for {@link RemoteActionService} (UC-08 first slice, ADR-013, ADR-015): the
 * request guards (empty text, foreign publication, non-confirmed delivery, missing
 * external ref, one-active-action rule, missing/unsupported actor, text over the platform
 * limit), the synchronous execution with a redacted append-only attempt
 * (SUCCESS/FAILED/UNKNOWN; authorize failures for disabled connections or undecryptable
 * secrets), and the ADR-015 guarantee that a remote action never modifies the original
 * delivery or publication. Mocked dependencies, no Spring context.
 */
class RemoteActionServiceTest {

	private static final Long WS = 1L;

	private static final String SLUG = "private";

	private static final Long PUB = 100L;

	private static final Long DELIVERY = 500L;

	private static final Long ACCOUNT = 50L;

	private static final Long CONNECTION = 60L;

	private static final OffsetDateTime NOW = OffsetDateTime.now();

	private PublicationService publications;

	private AccountRepository accounts;

	private ConnectionRepository connections;

	private SecretCipher secrets;

	private RemoteActorRegistry actors;

	private TextRulesRegistry textRules;

	private RemoteActionRepository actions;

	private RemoteActionAttemptRepository actionAttempts;

	private PlatformRemoteActor actor;

	private TextRules rules;

	private RemoteActionService service;

	@BeforeEach
	void setUp() {
		this.publications = mock(PublicationService.class);
		this.accounts = mock(AccountRepository.class);
		this.connections = mock(ConnectionRepository.class);
		this.secrets = mock(SecretCipher.class);
		this.actors = mock(RemoteActorRegistry.class);
		this.textRules = mock(TextRulesRegistry.class);
		this.actions = mock(RemoteActionRepository.class);
		this.actionAttempts = mock(RemoteActionAttemptRepository.class);
		this.actor = mock(PlatformRemoteActor.class);
		this.rules = mock(TextRules.class);
		given(this.actor.platform()).willReturn("telegram");
		given(this.actor.supportedActions()).willReturn(Set.of("EDIT", "DELETE"));
		given(this.actors.find("telegram")).willReturn(Optional.of(this.actor));
		given(this.rules.maxTextLength()).willReturn(4096);
		given(this.rules.count(any())).willAnswer((inv) -> ((String) inv.getArgument(0)).length());
		given(this.textRules.find("telegram")).willReturn(this.rules);
		given(this.actions.save(any(RemoteAction.class))).willAnswer((inv) -> inv.getArgument(0));
		given(this.actionAttempts.save(any(RemoteActionAttempt.class))).willAnswer((inv) -> inv.getArgument(0));
		this.service = new RemoteActionService(this.publications, this.accounts, this.connections, this.secrets,
				this.actors, this.textRules, this.actions, this.actionAttempts);
	}

	@Test
	void editRejectsEmptyTextBeforeAnyLookup() {
		assertThatThrownBy(() -> this.service.requestEdit(WS, SLUG, PUB, DELIVERY, "  "))
			.isInstanceOf(PublicationException.class)
			.hasMessageContaining("must not be empty");
		verifyNoInteractions(this.publications);
	}

	@Test
	void editRejectsDeliveryOfAnotherPublication() {
		given(this.publications.requirePublication(WS, PUB)).willReturn(publication());
		given(this.publications.requireDelivery(WS, DELIVERY))
			.willReturn(delivery("SUCCESS", "telegram:message:99", 999L));

		assertThatThrownBy(() -> this.service.requestEdit(WS, SLUG, PUB, DELIVERY, "new text"))
			.isInstanceOf(PublicationException.class)
			.hasMessageContaining("does not belong");
	}

	@Test
	void editRejectsNonConfirmedDelivery() {
		givenDelivery("FAILED", null);

		assertThatThrownBy(() -> this.service.requestEdit(WS, SLUG, PUB, DELIVERY, "new text"))
			.isInstanceOf(PublicationException.class)
			.hasMessageContaining("confirmed (SUCCESS)");
		verify(this.actions, never()).save(any());
	}

	@Test
	void editRejectsSuccessWithoutExternalRef() {
		givenDelivery("SUCCESS", null);

		assertThatThrownBy(() -> this.service.requestEdit(WS, SLUG, PUB, DELIVERY, "new text"))
			.isInstanceOf(PublicationException.class)
			.hasMessageContaining("external reference");
		verify(this.actions, never()).save(any());
	}

	@Test
	void actionIsRejectedWhileAnotherActionIsActive() {
		givenDelivery("SUCCESS", "telegram:message:99");
		given(this.actions.findAllByWorkspaceIdAndDeliveryIdAndStateInOrderByIdAsc(eq(WS), eq(DELIVERY), any()))
			.willReturn(List.of(new RemoteAction(7L, WS, DELIVERY, "DELETE", "PENDING", null, null, null, NOW, NOW)));

		assertThatThrownBy(() -> this.service.requestDelete(WS, SLUG, PUB, DELIVERY))
			.isInstanceOf(PublicationException.class)
			.hasMessageContaining("already in progress");
		verify(this.actor, never()).execute(any());
	}

	@Test
	void editRejectsWhenNoActorIsWiredForThePlatform() {
		givenDelivery("SUCCESS", "telegram:message:99");
		given(this.actors.find("telegram")).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.requestEdit(WS, SLUG, PUB, DELIVERY, "new text"))
			.isInstanceOf(PublicationException.class)
			.hasMessageContaining("No remote actor wired");
	}

	@Test
	void editRejectsWhenTheActorDoesNotSupportEdit() {
		givenDelivery("SUCCESS", "telegram:message:99");
		given(this.actor.supportedActions()).willReturn(Set.of("DELETE"));

		assertThatThrownBy(() -> this.service.requestEdit(WS, SLUG, PUB, DELIVERY, "new text"))
			.isInstanceOf(PublicationException.class)
			.hasMessageContaining("does not support remote edit");
	}

	@Test
	void editRejectsTextOverThePlatformLimitWithoutSaving() {
		givenDelivery("SUCCESS", "telegram:message:99");
		given(this.rules.maxTextLength()).willReturn(5);

		assertThatThrownBy(() -> this.service.requestEdit(WS, SLUG, PUB, DELIVERY, "123456"))
			.isInstanceOf(PublicationException.class)
			.hasMessageContaining("over the telegram limit of 5");
		verify(this.actions, never()).save(any());
		verify(this.actor, never()).execute(any());
	}

	@Test
	void editSuccessQueuesASeparateActionRecordsTheAttemptAndNeverTouchesTheDelivery() {
		givenDelivery("SUCCESS", "telegram:message:99");
		givenUsableConnection();
		given(this.actor.execute(any(RemoteActionContext.class)))
			.willReturn(PublishResult.success("telegram:message:99"));

		RemoteAction result = this.service.requestEdit(WS, SLUG, PUB, DELIVERY, "new text");

		assertThat(result.action()).isEqualTo("EDIT");
		assertThat(result.state()).isEqualTo("SUCCESS");
		assertThat(result.payloadText()).isEqualTo("new text");
		assertThat(result.payloadHash()).isNotBlank();
		assertThat(result.externalRef()).isEqualTo("telegram:message:99");

		ArgumentCaptor<RemoteAction> actionCaptor = ArgumentCaptor.forClass(RemoteAction.class);
		verify(this.actions, times(2)).save(actionCaptor.capture());
		assertThat(actionCaptor.getAllValues().get(0).state()).isEqualTo("PENDING");
		assertThat(actionCaptor.getAllValues().get(1).state()).isEqualTo("SUCCESS");

		ArgumentCaptor<RemoteActionAttempt> attemptCaptor = ArgumentCaptor.forClass(RemoteActionAttempt.class);
		verify(this.actionAttempts).save(attemptCaptor.capture());
		RemoteActionAttempt attempt = attemptCaptor.getValue();
		assertThat(attempt.attemptNo()).isEqualTo(1);
		assertThat(attempt.stage()).isEqualTo("edit");
		assertThat(attempt.outcome()).isEqualTo("SUCCESS");
		assertThat(attempt.externalRef()).isEqualTo("telegram:message:99");

		ArgumentCaptor<RemoteActionContext> contextCaptor = ArgumentCaptor.forClass(RemoteActionContext.class);
		verify(this.actor).execute(contextCaptor.capture());
		RemoteActionContext context = contextCaptor.getValue();
		assertThat(context.credential()).isEqualTo("token");
		assertThat(context.targetId()).isEqualTo("-1001234567890");
		assertThat(context.action()).isEqualTo("EDIT");
		assertThat(context.text()).isEqualTo("new text");
		assertThat(context.externalRef()).isEqualTo("telegram:message:99");

		// ADR-015: a remote action never modifies the original delivery or publication.
		verify(this.publications).requirePublication(WS, PUB);
		verify(this.publications).requireDelivery(WS, DELIVERY);
		verifyNoMoreInteractions(this.publications);
	}

	@Test
	void deleteSuccessStoresNoPayload() {
		givenDelivery("SUCCESS", "telegram:message:99");
		givenUsableConnection();
		given(this.actor.execute(any(RemoteActionContext.class)))
			.willReturn(PublishResult.success("telegram:message:99"));

		RemoteAction result = this.service.requestDelete(WS, SLUG, PUB, DELIVERY);

		assertThat(result.action()).isEqualTo("DELETE");
		assertThat(result.state()).isEqualTo("SUCCESS");
		assertThat(result.payloadText()).isNull();
		assertThat(result.payloadHash()).isNull();
		ArgumentCaptor<RemoteActionAttempt> captor = ArgumentCaptor.forClass(RemoteActionAttempt.class);
		verify(this.actionAttempts).save(captor.capture());
		assertThat(captor.getValue().stage()).isEqualTo("delete");
		assertThat(captor.getValue().outcome()).isEqualTo("SUCCESS");
	}

	@Test
	void failedOutcomeMarksTheActionFailed() {
		givenDelivery("SUCCESS", "telegram:message:99");
		givenUsableConnection();
		given(this.actor.execute(any(RemoteActionContext.class)))
			.willReturn(PublishResult.failed("Telegram rejected the edit (HTTP 400)."));

		RemoteAction result = this.service.requestEdit(WS, SLUG, PUB, DELIVERY, "new text");

		assertThat(result.state()).isEqualTo("FAILED");
		assertThat(result.externalRef()).isNull();
		ArgumentCaptor<RemoteActionAttempt> captor = ArgumentCaptor.forClass(RemoteActionAttempt.class);
		verify(this.actionAttempts).save(captor.capture());
		assertThat(captor.getValue().outcome()).isEqualTo("FAILED");
		assertThat(captor.getValue().detail()).contains("HTTP 400");
	}

	@Test
	void unknownOutcomeMarksTheActionUnknownForReconciliation() {
		givenDelivery("SUCCESS", "telegram:message:99");
		givenUsableConnection();
		given(this.actor.execute(any(RemoteActionContext.class)))
			.willReturn(PublishResult.unknown("Telegram edit could not be confirmed (HTTP 500)."));

		RemoteAction result = this.service.requestEdit(WS, SLUG, PUB, DELIVERY, "new text");

		assertThat(result.state()).isEqualTo("UNKNOWN");
		ArgumentCaptor<RemoteActionAttempt> captor = ArgumentCaptor.forClass(RemoteActionAttempt.class);
		verify(this.actionAttempts).save(captor.capture());
		assertThat(captor.getValue().outcome()).isEqualTo("UNKNOWN");
	}

	@Test
	void disabledConnectionFailsWithAnAuthorizeAttempt() {
		givenDelivery("SUCCESS", "telegram:message:99");
		given(this.accounts.findByIdAndWorkspaceId(ACCOUNT, WS)).willReturn(Optional.of(destination()));
		given(this.connections.findByIdAndWorkspaceId(CONNECTION, WS)).willReturn(Optional.of(connection(false)));

		RemoteAction result = this.service.requestDelete(WS, SLUG, PUB, DELIVERY);

		assertThat(result.state()).isEqualTo("FAILED");
		verify(this.actor, never()).execute(any());
		ArgumentCaptor<RemoteActionAttempt> captor = ArgumentCaptor.forClass(RemoteActionAttempt.class);
		verify(this.actionAttempts).save(captor.capture());
		assertThat(captor.getValue().stage()).isEqualTo("authorize");
		assertThat(captor.getValue().outcome()).isEqualTo("FAILED");
	}

	@Test
	void undecryptableSecretFailsWithAnAuthorizeAttempt() {
		givenDelivery("SUCCESS", "telegram:message:99");
		given(this.accounts.findByIdAndWorkspaceId(ACCOUNT, WS)).willReturn(Optional.of(destination()));
		given(this.connections.findByIdAndWorkspaceId(CONNECTION, WS)).willReturn(Optional.of(connection(true)));
		given(this.secrets.open(anyString(), any())).willThrow(new RuntimeException("bad key"));

		RemoteAction result = this.service.requestDelete(WS, SLUG, PUB, DELIVERY);

		assertThat(result.state()).isEqualTo("FAILED");
		verify(this.actor, never()).execute(any());
		ArgumentCaptor<RemoteActionAttempt> captor = ArgumentCaptor.forClass(RemoteActionAttempt.class);
		verify(this.actionAttempts).save(captor.capture());
		assertThat(captor.getValue().stage()).isEqualTo("authorize");
	}

	@Test
	void missingDestinationFailsClosed() {
		givenDelivery("SUCCESS", "telegram:message:99");
		given(this.accounts.findByIdAndWorkspaceId(ACCOUNT, WS)).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.requestDelete(WS, SLUG, PUB, DELIVERY))
			.isInstanceOf(PublicationException.class)
			.hasMessageContaining("Unknown destination");
		verify(this.actor, never()).execute(any());
	}

	@Test
	void actionsOfDeliveryReturnsActionsWithTheirAttempts() {
		given(this.publications.requireDelivery(WS, DELIVERY))
			.willReturn(delivery("SUCCESS", "telegram:message:99", PUB));
		RemoteAction action = new RemoteAction(7L, WS, DELIVERY, "EDIT", "SUCCESS", "new", "hash",
				"telegram:message:99", NOW, NOW);
		given(this.actions.findAllByWorkspaceIdAndDeliveryIdOrderByIdAsc(WS, DELIVERY)).willReturn(List.of(action));
		RemoteActionAttempt attempt = new RemoteActionAttempt(8L, WS, 7L, 1, "edit", "SUCCESS", null,
				"telegram:message:99", NOW);
		given(this.actionAttempts.findAllByRemoteActionIdOrderByIdAsc(7L)).willReturn(List.of(attempt));

		List<RemoteActionDetail> details = this.service.actionsOfDelivery(WS, DELIVERY);

		assertThat(details).hasSize(1);
		assertThat(details.get(0).action()).isEqualTo(action);
		assertThat(details.get(0).attempts()).containsExactly(attempt);
	}

	@Test
	void actionsOfDeliveryFailsClosedForAForeignDelivery() {
		given(this.publications.requireDelivery(WS, DELIVERY))
			.willThrow(new PublicationException("Unknown delivery for this workspace."));

		assertThatThrownBy(() -> this.service.actionsOfDelivery(WS, DELIVERY)).isInstanceOf(PublicationException.class)
			.hasMessageContaining("Unknown delivery");
	}

	@Test
	void supportedActionsAndTextLimitComeFromTheRegistries() {
		assertThat(this.service.supportedActions("telegram")).containsExactlyInAnyOrder("EDIT", "DELETE");
		assertThat(this.service.supports("telegram", "EDIT")).isTrue();
		assertThat(this.service.supports("mastodon", "EDIT")).isFalse();
		assertThat(this.service.maxTextLength("telegram")).isEqualTo(4096);
	}

	private Publication publication() {
		return new Publication(PUB, WS, 10L, 20L, "key-1", "PUBLISHED", "hash", NOW, NOW);
	}

	private Delivery delivery(String state, String externalRef, Long publicationId) {
		return new Delivery(DELIVERY, WS, publicationId, ACCOUNT, "telegram", "News channel", null, "-1001234567890",
				"hello", "hash", state, externalRef, NOW, NOW);
	}

	private void givenDelivery(String state, String externalRef) {
		given(this.publications.requirePublication(WS, PUB)).willReturn(publication());
		given(this.publications.requireDelivery(WS, DELIVERY)).willReturn(delivery(state, externalRef, PUB));
		given(this.actions.findAllByWorkspaceIdAndDeliveryIdAndStateInOrderByIdAsc(eq(WS), eq(DELIVERY), any()))
			.willReturn(List.of());
	}

	private void givenUsableConnection() {
		given(this.accounts.findByIdAndWorkspaceId(ACCOUNT, WS)).willReturn(Optional.of(destination()));
		given(this.connections.findByIdAndWorkspaceId(CONNECTION, WS)).willReturn(Optional.of(connection(true)));
		given(this.secrets.open(anyString(), any())).willReturn("token");
	}

	private AccountDestination destination() {
		return new AccountDestination(ACCOUNT, WS, CONNECTION, "telegram", "channel", "-1001234567890", null,
				"News channel", true, false, NOW, NOW);
	}

	private Connection connection(boolean enabled) {
		return new Connection(CONNECTION, WS, "telegram", "pasted", "@testbot", "Main bot", "cipher", "nonce",
				"test-v1", "OK", NOW, enabled, NOW, NOW);
	}

}
