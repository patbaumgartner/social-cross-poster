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
 * Tests for {@link NotificationChannelService} (UC-12 second slice step 1, ADR-007,
 * ADR-013): the URL must pass the SSRF guard, labels are workspace-unique, the HMAC
 * secret is stored only sealed and AAD-bound (opening it under another workspace slug
 * fails), categories are normalized, foreign ids fail closed, toggle flips, delete
 * removes outbox rows first.
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
				cipher);
		given(this.channels.save(any(NotificationChannel.class))).willAnswer((inv) -> {
			NotificationChannel c = inv.getArgument(0);
			return new NotificationChannel(9L, c.workspaceId(), c.label(), c.url(), c.secretCipher(), c.secretNonce(),
					c.secretKeyId(), c.categories(), c.enabled(), c.createdAt());
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
				categories, enabled, NOW);
	}

	private static NotificationDelivery delivery(Long id, Long channelId, boolean sent) {
		return new NotificationDelivery(id, WS, 100L + id, channelId, sent ? "SENT" : "PENDING", 1, "HTTP 200", null,
				NOW, NOW);
	}

}
