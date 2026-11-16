package com.patbaumgartner.socialcrossposter.accounts;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.patbaumgartner.socialcrossposter.notify.NotificationService;
import com.patbaumgartner.socialcrossposter.platform.ExperimentalPlatforms;
import com.patbaumgartner.socialcrossposter.platform.ExperimentalProperties;
import com.patbaumgartner.socialcrossposter.platform.PlatformProbe;
import com.patbaumgartner.socialcrossposter.platform.PlatformRegistry;
import com.patbaumgartner.socialcrossposter.platform.ProbeException;
import com.patbaumgartner.socialcrossposter.platform.ProbeResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link ConnectionService#checkHealth} (UC-02 step 6, UC-12
 * connection/bridge-failure trigger, ADR-013): the re-probe always persists the new
 * health state, but a redacted notification event is emitted only on a transition —
 * OK&rarr;ERROR records CONNECTION_FAILED, ERROR&rarr;OK records CONNECTION_RECOVERED,
 * and steady states stay silent. Unknown/foreign ids fail closed without saving. Mocked
 * repositories/services, no Spring context.
 */
class ConnectionServiceTest {

	private static final Long WS = 1L;

	private static final OffsetDateTime NOW = OffsetDateTime.now();

	private ConnectionRepository connections;

	private AccountRepository accounts;

	private SecretCipher secrets;

	private PlatformRegistry platforms;

	private NotificationService notifications;

	private PlatformProbe probe;

	private ConnectionService service;

	@BeforeEach
	void setUp() {
		this.connections = mock(ConnectionRepository.class);
		this.accounts = mock(AccountRepository.class);
		this.secrets = mock(SecretCipher.class);
		this.platforms = mock(PlatformRegistry.class);
		this.notifications = mock(NotificationService.class);
		this.probe = mock(PlatformProbe.class);
		// UC-14: the gate uses the real component with the opt-in flag off (default).
		this.service = new ConnectionService(this.connections, this.accounts, this.secrets, this.platforms,
				this.notifications, new ExperimentalPlatforms(new ExperimentalProperties(false)));
		given(this.connections.save(any(Connection.class))).willAnswer((inv) -> inv.getArgument(0));
		given(this.secrets.open(anyString(), any(SecretCipher.SealedSecret.class))).willReturn("token");
		given(this.platforms.find("telegram")).willReturn(Optional.of(this.probe));
	}

	@Test
	void okToErrorSavesErrorAndEmitsConnectionFailed() {
		Connection c = connection(7L, "OK");
		given(this.connections.findByIdAndWorkspaceId(7L, WS)).willReturn(Optional.of(c));
		willThrow(new ProbeException("provider rejected the credential")).given(this.probe).probe("pasted", "token");

		this.service.checkHealth(WS, "private", 7L);

		assertThat(savedHealth()).isEqualTo("ERROR");
		verify(this.notifications).notifyConnectionFailed(WS, c);
		verify(this.notifications, never()).notifyConnectionRecovered(any(), any());
	}

	@Test
	void errorToErrorSavesErrorWithoutAnyNotification() {
		Connection c = connection(7L, "ERROR");
		given(this.connections.findByIdAndWorkspaceId(7L, WS)).willReturn(Optional.of(c));
		willThrow(new ProbeException("still rejected")).given(this.probe).probe("pasted", "token");

		this.service.checkHealth(WS, "private", 7L);

		assertThat(savedHealth()).isEqualTo("ERROR");
		verifyNoInteractions(this.notifications);
	}

	@Test
	void errorToOkSavesOkAndEmitsConnectionRecovered() {
		Connection c = connection(7L, "ERROR");
		given(this.connections.findByIdAndWorkspaceId(7L, WS)).willReturn(Optional.of(c));
		given(this.probe.probe("pasted", "token")).willReturn(new ProbeResult("@newsbot", "News bot"));

		this.service.checkHealth(WS, "private", 7L);

		assertThat(savedHealth()).isEqualTo("OK");
		verify(this.notifications).notifyConnectionRecovered(WS, c);
		verify(this.notifications, never()).notifyConnectionFailed(any(), any());
	}

	@Test
	void okToOkSavesOkWithoutAnyNotification() {
		Connection c = connection(7L, "OK");
		given(this.connections.findByIdAndWorkspaceId(7L, WS)).willReturn(Optional.of(c));
		given(this.probe.probe("pasted", "token")).willReturn(new ProbeResult("@newsbot", "News bot"));

		this.service.checkHealth(WS, "private", 7L);

		assertThat(savedHealth()).isEqualTo("OK");
		verifyNoInteractions(this.notifications);
	}

	@Test
	void foreignIdFailsClosedWithoutSaveOrNotification() {
		given(this.connections.findByIdAndWorkspaceId(99L, WS)).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.checkHealth(WS, "private", 99L)).isInstanceOf(ProbeException.class);

		verify(this.connections, never()).save(any());
		verifyNoInteractions(this.notifications);
	}

	@Test
	void connectRejectsWhatsAppBusinessPermanentlyBeforeAnyProbeOrSave() {
		assertThatThrownBy(() -> this.service.connect(WS, "private", "whatsapp-business", "pasted", "Biz", "secret"))
			.isInstanceOf(ProbeException.class)
			.hasMessageContaining("WhatsApp Business is not supported")
			.hasMessageContaining("ADR-010");

		verify(this.platforms, never()).find(anyString());
		verify(this.connections, never()).save(any());
		verifyNoInteractions(this.probe);
	}

	@Test
	void connectRejectsExperimentalTargetWhileOptInDisabled() {
		assertThatThrownBy(() -> this.service.connect(WS, "private", "signal", "pasted", "Sig", "secret"))
			.isInstanceOf(ProbeException.class)
			.hasMessageContaining("disabled");

		verify(this.platforms, never()).find(anyString());
		verify(this.connections, never()).save(any());
		verifyNoInteractions(this.probe);
	}

	@Test
	void connectRejectsExperimentalTargetWithoutFeasibilityRecordEvenWhenEnabled() {
		ConnectionService enabled = new ConnectionService(this.connections, this.accounts, this.secrets, this.platforms,
				this.notifications, new ExperimentalPlatforms(new ExperimentalProperties(true)));

		assertThatThrownBy(() -> enabled.connect(WS, "private", "whatsapp-status", "pasted", "Status", "secret"))
			.isInstanceOf(ProbeException.class)
			.hasMessageContaining("feasibility");

		verify(this.platforms, never()).find(anyString());
		verify(this.connections, never()).save(any());
		verifyNoInteractions(this.probe);
	}

	@Test
	void addDestinationOnExperimentalConnectionFailsClosedWithoutSave() {
		Connection signal = new Connection(7L, WS, "signal", "pasted", "+41791234567", "Signal", "cipher", "nonce",
				"key-1", "OK", NOW, true, NOW, NOW);
		given(this.connections.findByIdAndWorkspaceId(7L, WS)).willReturn(Optional.of(signal));

		assertThatThrownBy(() -> this.service.addDestination(WS, 7L, "message", "acct-1", null, "My signal"))
			.isInstanceOf(ProbeException.class);

		verify(this.accounts, never()).save(any());
	}

	private String savedHealth() {
		ArgumentCaptor<Connection> captor = ArgumentCaptor.forClass(Connection.class);
		verify(this.connections).save(captor.capture());
		return captor.getValue().healthState();
	}

	private static Connection connection(Long id, String health) {
		return new Connection(id, WS, "telegram", "pasted", "@newsbot", "News bot", "cipher", "nonce", "key-1", health,
				NOW, true, NOW, NOW);
	}

}
