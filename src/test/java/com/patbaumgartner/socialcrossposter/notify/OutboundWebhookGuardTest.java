package com.patbaumgartner.socialcrossposter.notify;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link OutboundWebhookGuard} (UC-12 second slice, arc42 section 8 SSRF
 * rules): https required unless the host is on the deployment allow-list; userinfo
 * denied; private/ loopback/link-local/any-local/multicast/CGNAT ranges denied;
 * localhost, cloud metadata names and 169.254.169.254 hard-denied even when allow-listed;
 * unresolvable hosts denied; the allow-list overrides private ranges for intentional
 * internal targets (tests).
 */
class OutboundWebhookGuardTest {

	private static NotifyProperties props(List<String> allowList) {
		return new NotifyProperties(null, null, null, null, null, null, null, null, allowList);
	}

	private final OutboundWebhookGuard strict = new OutboundWebhookGuard(props(List.of()));

	private final OutboundWebhookGuard allowLoopback = new OutboundWebhookGuard(props(List.of("127.0.0.1")));

	@Test
	void httpsToPublicHostIsAccepted() {
		// public IP literals — no DNS needed, so this also passes fully offline
		assertThatCode(() -> this.strict.validate("https://1.1.1.1/hook")).doesNotThrowAnyException();
		assertThatCode(() -> this.strict.validate("https://8.8.8.8:8443/path?q=1")).doesNotThrowAnyException();
	}

	@Test
	void plainHttpIsRejectedUnlessHostIsAllowListed() {
		assertThatThrownBy(() -> this.strict.validate("http://example.com/hook"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("https");
		assertThatThrownBy(() -> this.strict.validate("http://1.1.1.1/hook")).isInstanceOf(NotificationException.class)
			.hasMessageContaining("https");
		// allow-listed internal host may use plain http (tests run a local receiver)
		assertThatCode(() -> this.allowLoopback.validate("http://127.0.0.1:8080/hook")).doesNotThrowAnyException();
		assertThatCode(() -> this.allowLoopback.validate("https://127.0.0.1/hook")).doesNotThrowAnyException();
		// but the allow-list does not extend to other private addresses
		assertThatThrownBy(() -> this.allowLoopback.validate("https://10.0.0.5/hook"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("private/loopback");
	}

	@Test
	void userinfoCredentialsInUrlAreRejected() {
		assertThatThrownBy(() -> this.strict.validate("https://user:secret@1.1.1.1/hook"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("credentials");
	}

	@Test
	void privateLoopbackAndReservedLiteralsAreRejected() {
		for (String url : List.of("https://127.0.0.1/", "https://10.0.0.5/", "https://172.16.0.9/",
				"https://192.168.1.1/", "https://0.0.0.0/", "https://[::1]/", "https://169.254.1.1/",
				"https://100.64.0.1/", "https://224.0.0.1/", "https://[fe80::1]/")) {
			assertThatThrownBy(() -> this.strict.validate(url)).as(url)
				.isInstanceOf(NotificationException.class)
				.hasMessageContaining("denied");
		}
	}

	@Test
	void cloudMetadataEndpointsAreHardDeniedEvenWhenAllowListed() {
		OutboundWebhookGuard allowMetadata = new OutboundWebhookGuard(
				props(List.of("169.254.169.254", "metadata.google.internal", "localhost")));
		assertThatThrownBy(() -> allowMetadata.validate("https://169.254.169.254/latest/meta-data"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("metadata");
		assertThatThrownBy(() -> allowMetadata.validate("http://169.254.169.254/latest"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("metadata");
		assertThatThrownBy(() -> allowMetadata.validate("https://metadata.google.internal/"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("metadata");
		assertThatThrownBy(() -> allowMetadata.validate("https://localhost/")).isInstanceOf(NotificationException.class)
			.hasMessageContaining("localhost");
		assertThatThrownBy(() -> allowMetadata.validate("https://foo.localhost/"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("localhost");
	}

	@Test
	void unresolvableHostIsRejected() {
		assertThatThrownBy(() -> this.strict.validate("https://nonexistent-host.invalid/hook"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("resolved");
	}

	@Test
	void missingHostOrUrlIsRejected() {
		assertThatThrownBy(() -> this.strict.validate("")).isInstanceOf(NotificationException.class)
			.hasMessageContaining("required");
		assertThatThrownBy(() -> this.strict.validate(null)).isInstanceOf(NotificationException.class)
			.hasMessageContaining("required");
		assertThatThrownBy(() -> this.strict.validate("https://")).isInstanceOf(NotificationException.class)
			.hasMessageContaining("valid URL");
		assertThatThrownBy(() -> this.strict.validate("https:///path")).isInstanceOf(NotificationException.class)
			.hasMessageContaining("host");
		assertThatThrownBy(() -> this.strict.validate("not a url at all :::"))
			.isInstanceOf(NotificationException.class);
	}

	@Test
	void unsupportedSchemeIsRejected() {
		assertThatThrownBy(() -> this.strict.validate("ftp://1.1.1.1/hook")).isInstanceOf(NotificationException.class)
			.hasMessageContaining("https");
		assertThatThrownBy(() -> this.strict.validate("file:///etc/passwd")).isInstanceOf(NotificationException.class);
	}

}
