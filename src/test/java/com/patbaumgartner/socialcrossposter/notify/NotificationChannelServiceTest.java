package com.patbaumgartner.socialcrossposter.notify;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.patbaumgartner.socialcrossposter.accounts.SecretCipher;
import com.patbaumgartner.socialcrossposter.accounts.SecretsProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link NotificationChannelService} (UC-12 second + third slices, step 1,
 * ADR-007, ADR-013): webhook URLs and SMTP targets must pass their SSRF guards, labels
 * are workspace-unique, credentials are stored only sealed and AAD-bound (opening them
 * under another workspace slug or the wrong channel type fails), categories are
 * normalized, foreign ids fail closed, toggle flips enabled while preserving the SMTP
 * fields, and delete removes outbox rows first.
 */
class NotificationChannelServiceTest {

	private static final Long WS = 1L;

	private static final String SLUG = "private";

	private static final OffsetDateTime NOW = OffsetDateTime.now();

	private NotificationChannelRepository channels;

	private NotificationDeliveryRepository deliveries;

	private NotificationChannelService service;

	@BeforeEach
	void setUp() {
		this.channels = mock(NotificationChannelRepository.class);
		this.deliveries = mock(NotificationDeliveryRepository.class);
		NotifyProperties props = new NotifyProperties(null, null, null, null, null, null, null, null,
				List.of("127.0.0.1"));
		SecretCipher cipher = new SecretCipher(
				new SecretsProperties("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "test-v1"));
		this.service = new NotificationChannelService(this.channels, this.deliveries, new OutboundWebhookGuard(props),
				new OutboundSmtpGuard(props), cipher);
		given(this.channels.save(any(NotificationChannel.class))).willAnswer((inv) -> {
			NotificationChannel c = inv.getArgument(0);
			return new NotificationChannel(9L, c.workspaceId(), c.label(), c.url(), c.secretCipher(), c.secretNonce(),
					c.secretKeyId(), c.categories(), c.enabled(), c.createdAt(), c.channelType(), c.smtpHost(),
					c.smtpPort(), c.smtpTlsMode(), c.smtpFrom(), c.smtpTo(), c.smtpUsername());
		});
		given(this.channels.findByWorkspaceIdAndLabel(any(), any())).willReturn(Optional.empty());
	}

	@Test
	void createRejectsUrlsTheSsrfGuardDenies() {
		assertThatThrownBy(() -> this.service.create(WS, SLUG, "ops", "https://10.0.0.5/hook", ""))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("denied");
		assertThatThrownBy(() -> this.service.create(WS, SLUG, "ops", "http://example.com/hook", ""))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("https");
		assertThatThrownBy(() -> this.service.create(WS, SLUG, "ops", "https://user:pw@1.1.1.1/hook", ""))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("credentials");
		verify(this.channels, never()).save(any());
	}

	@Test
	void createRejectsBlankLabelAndDuplicateLabel() {
		assertThatThrownBy(() -> this.service.create(WS, SLUG, "  ", "https://1.1.1.1/hook", ""))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("label");
		given(this.channels.findByWorkspaceIdAndLabel(WS, "ops")).willReturn(Optional.of(channel(9L, "ops", true, "")));
		assertThatThrownBy(() -> this.service.create(WS, SLUG, "ops", "https://1.1.1.1/hook", ""))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("already exists");
		verify(this.channels, never()).save(any());
	}

	@Test
	void createStoresSecretOnlySealedAndAadBound() {
		NotificationChannel saved = this.service.create(WS, SLUG, "ops", "http://127.0.0.1:9090/hook",
				" delivery_failed , schedule_expired ,, ");

		assertThat(saved.id()).isEqualTo(9L);
		assertThat(saved.url()).isEqualTo("http://127.0.0.1:9090/hook");
		assertThat(saved.channelType()).isEqualTo(NotificationChannel.TYPE_WEBHOOK);
		assertThat(saved.enabled()).isTrue();
		// categories normalized to an upper-case csv
		assertThat(saved.categories()).isEqualTo("DELIVERY_FAILED,SCHEDULE_EXPIRED");
		// the stored secret is ciphertext + nonce, never a 64-char plaintext hex secret
		assertThat(saved.secretCipher()).isNotBlank().hasSizeGreaterThan(64);
		assertThat(saved.secretNonce()).isNotBlank();
		assertThat(saved.secretKeyId()).isEqualTo("test-v1");
		String opened = this.service.openSecret(SLUG, saved);
		assertThat(opened).hasSize(64); // random hex HMAC secret
		// AAD binding: the same ciphertext cannot be opened under another workspace slug
		assertThatThrownBy(() -> this.service.openSecret("socrates-ch", saved))
			.isInstanceOf(IllegalStateException.class);
		// a different label yields a different secret (AAD includes the label)
		NotificationChannel other = this.service.create(WS, SLUG, "alerts", "https://1.1.1.1/hook", "");
		assertThat(this.service.openSecret(SLUG, other)).isNotEqualTo(opened);
	}

	@Test
	void createSmtpStoresPasswordSealedWithTheSmtpAadPurpose() {
		NotificationChannel saved = this.service.createSmtp(WS, SLUG, "mail", "127.0.0.1", "2525", "NONE",
				"alerts@example.com", "ops@example.com, oncall@example.com", "relay-user", "s3cret!",
				" delivery_failed ,, ");

		assertThat(saved.id()).isEqualTo(9L);
		assertThat(saved.channelType()).isEqualTo(NotificationChannel.TYPE_SMTP);
		assertThat(saved.isSmtp()).isTrue();
		assertThat(saved.url()).isNull(); // SMTP channels carry no webhook URL
		assertThat(saved.smtpHost()).isEqualTo("127.0.0.1");
		assertThat(saved.smtpPort()).isEqualTo(2525);
		assertThat(saved.smtpTlsMode()).isEqualTo("NONE");
		assertThat(saved.smtpFrom()).isEqualTo("alerts@example.com");
		assertThat(saved.smtpTo()).isEqualTo("ops@example.com,oncall@example.com");
		assertThat(saved.recipients()).containsExactly("ops@example.com", "oncall@example.com");
		assertThat(saved.smtpUsername()).isEqualTo("relay-user");
		assertThat(saved.target()).isEqualTo("127.0.0.1:2525 (NONE)");
		assertThat(saved.categories()).isEqualTo("DELIVERY_FAILED");
		assertThat(saved.enabled()).isTrue();
		// the stored credential is sealed ciphertext, never the plaintext password
		assertThat(saved.secretCipher()).isNotBlank().doesNotContain("s3cret!");
		assertThat(saved.secretNonce()).isNotBlank();
		assertThat(this.service.openSecret(SLUG, saved)).isEqualTo("s3cret!");
		// AAD binding: wrong workspace slug cannot open it
		assertThatThrownBy(() -> this.service.openSecret("socrates-ch", saved))
			.isInstanceOf(IllegalStateException.class);
		// AAD purpose is type-aware: a webhook ciphertext cannot be opened as an SMTP
		// secret
		NotificationChannel hook = this.service.create(WS, SLUG, "ops", "http://127.0.0.1:9090/hook", "");
		NotificationChannel forgedSmtp = new NotificationChannel(saved.id(), WS, "ops", null, hook.secretCipher(),
				hook.secretNonce(), hook.secretKeyId(), "", true, NOW, NotificationChannel.TYPE_SMTP, "127.0.0.1", 2525,
				"NONE", "alerts@example.com", "ops@example.com", null);
		assertThatThrownBy(() -> this.service.openSecret(SLUG, forgedSmtp)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void createSmtpRejectsBadPortTlsMappingAddressesAndGuards() {
		// canonical port/TLS mapping is enforced by the guard
		assertThatThrownBy(() -> this.service.createSmtp(WS, SLUG, "m", "127.0.0.1", "587", "NONE", "a@example.com",
				"b@example.com", null, null, ""))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("587");
		assertThatThrownBy(() -> this.service.createSmtp(WS, SLUG, "m", "127.0.0.1", "465", "STARTTLS", "a@example.com",
				"b@example.com", null, null, ""))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("465");
		assertThatThrownBy(() -> this.service.createSmtp(WS, SLUG, "m", "127.0.0.1", "abc", "NONE", "a@example.com",
				"b@example.com", null, null, ""))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("port");
		// unencrypted SMTP off the allow-list is denied even on a public IP literal
		assertThatThrownBy(() -> this.service.createSmtp(WS, SLUG, "m", "1.1.1.1", "25", "NONE", "a@example.com",
				"b@example.com", null, null, ""))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("allow-list");
		// SSRF host rules apply to SMTP exactly as to webhooks
		assertThatThrownBy(() -> this.service.createSmtp(WS, SLUG, "m", "10.0.0.5", "587", "STARTTLS", "a@example.com",
				"b@example.com", null, null, ""))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("denied");
		assertThatThrownBy(() -> this.service.createSmtp(WS, SLUG, "m", "no-such-host.invalid", "587", "STARTTLS",
				"a@example.com", "b@example.com", null, null, ""))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("resolved");
		// addresses must be valid, control-character free (header injection)
		assertThatThrownBy(() -> this.service.createSmtp(WS, SLUG, "m", "127.0.0.1", "2525", "NONE", "not-an-email",
				"b@example.com", null, null, ""))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("valid email address");
		assertThatThrownBy(() -> this.service.createSmtp(WS, SLUG, "m", "127.0.0.1", "2525", "NONE",
				"a@example.com\r\nBcc: x@y.z", "b@example.com", null, null, ""))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("valid email address");
		assertThatThrownBy(() -> this.service.createSmtp(WS, SLUG, "m", "127.0.0.1", "2525", "NONE", "a@example.com",
				"  ", null, null, ""))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("to address");
		// a username requires a password
		assertThatThrownBy(() -> this.service.createSmtp(WS, SLUG, "m", "127.0.0.1", "2525", "NONE", "a@example.com",
				"b@example.com", "relay-user", null, ""))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("password is required");
		verify(this.channels, never()).save(any());
	}

	@Test
	void createSmtpAllowsStarttlsOnAllowListedRelay() {
		NotificationChannel saved = this.service.createSmtp(WS, SLUG, "relay", "127.0.0.1", "587", "STARTTLS",
				"alerts@example.com", "ops@example.com", null, null, "");
		assertThat(saved.smtpPort()).isEqualTo(587);
		assertThat(saved.smtpTlsMode()).isEqualTo("STARTTLS");
		assertThat(saved.smtpUsername()).isNull(); // no AUTH configured
		assertThat(this.service.openSecret(SLUG, saved)).isEmpty(); // empty sealed
																	// password
	}

	@Test
	void requireByIdFailsClosedForForeignIds() {
		given(this.channels.findByIdAndWorkspaceId(5L, 99L)).willReturn(Optional.empty());
		assertThatThrownBy(() -> this.service.requireById(99L, 5L)).isInstanceOf(NotificationException.class)
			.hasMessageContaining("Unknown notification channel");
	}

	@Test
	void toggleFlipsEnabled() {
		NotificationChannel channel = channel(9L, "ops", true, "");
		given(this.channels.findByIdAndWorkspaceId(9L, 5L)).willReturn(Optional.of(channel));

		NotificationChannel toggled = this.service.toggle(5L, 9L);

		assertThat(toggled.enabled()).isFalse();
		ArgumentCaptor<NotificationChannel> captor = ArgumentCaptor.forClass(NotificationChannel.class);
		verify(this.channels).save(captor.capture());
		assertThat(captor.getValue().enabled()).isFalse();
		assertThat(captor.getValue().secretCipher()).isEqualTo(channel.secretCipher()); // secret
																						// untouched
	}

	@Test
	void togglePreservesAllSmtpFields() {
		NotificationChannel smtp = smtpChannel(9L, "mail", true);
		given(this.channels.findByIdAndWorkspaceId(9L, 5L)).willReturn(Optional.of(smtp));

		NotificationChannel toggled = this.service.toggle(5L, 9L);

		assertThat(toggled.enabled()).isFalse();
		ArgumentCaptor<NotificationChannel> captor = ArgumentCaptor.forClass(NotificationChannel.class);
		verify(this.channels).save(captor.capture());
		NotificationChannel saved = captor.getValue();
		assertThat(saved.channelType()).isEqualTo(NotificationChannel.TYPE_SMTP);
		assertThat(saved.smtpHost()).isEqualTo("127.0.0.1");
		assertThat(saved.smtpPort()).isEqualTo(2525);
		assertThat(saved.smtpTlsMode()).isEqualTo("NONE");
		assertThat(saved.smtpFrom()).isEqualTo("alerts@example.com");
		assertThat(saved.smtpTo()).isEqualTo("ops@example.com");
		assertThat(saved.smtpUsername()).isEqualTo("relay-user");
		assertThat(saved.secretCipher()).isEqualTo(smtp.secretCipher());
	}

	@Test
	void deleteRemovesOutboxRowsBeforeTheChannel() {
		NotificationChannel channel = channel(9L, "ops", true, "");
		given(this.channels.findByIdAndWorkspaceId(9L, 5L)).willReturn(Optional.of(channel));
		NotificationDelivery d1 = delivery(1L, 9L, true);
		NotificationDelivery d2 = delivery(2L, 9L, false);
		given(this.deliveries.findAllByChannelIdOrderByIdDesc(9L)).willReturn(List.of(d1, d2));

		this.service.delete(5L, 9L);

		verify(this.deliveries).delete(d1);
		verify(this.deliveries).delete(d2);
		verify(this.channels).delete(channel);
	}

	@Test
	void categoryFilterSemanticsOnTheRecord() {
		NotificationChannel all = channel(1L, "all", true, "");
		NotificationChannel filtered = channel(2L, "f", true, "DELIVERY_FAILED,SCHEDULE_EXPIRED");
		assertThat(all.interestedIn("DELIVERY_FAILED")).isTrue();
		assertThat(all.interestedIn("anything")).isTrue();
		assertThat(filtered.interestedIn("DELIVERY_FAILED")).isTrue();
		assertThat(filtered.interestedIn("SCHEDULE_EXPIRED")).isTrue();
		assertThat(filtered.interestedIn("DELIVERY_UNKNOWN")).isFalse();
	}

	private static NotificationChannel channel(Long id, String label, boolean enabled, String categories) {
		return new NotificationChannel(id, WS, label, "https://1.1.1.1/hook", "cipher-" + id, "nonce", "test-v1",
				categories, enabled, NOW, NotificationChannel.TYPE_WEBHOOK, null, null, null, null, null, null);
	}

	private static NotificationChannel smtpChannel(Long id, String label, boolean enabled) {
		return new NotificationChannel(id, WS, label, null, "cipher-" + id, "nonce", "test-v1", "", enabled, NOW,
				NotificationChannel.TYPE_SMTP, "127.0.0.1", 2525, "NONE", "alerts@example.com", "ops@example.com",
				"relay-user");
	}

	private static NotificationDelivery delivery(Long id, Long channelId, boolean sent) {
		return new NotificationDelivery(id, WS, 100L + id, channelId, sent ? "SENT" : "PENDING", 1, "HTTP 200", null,
				NOW, NOW);
	}

}
