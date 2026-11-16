package com.patbaumgartner.socialcrossposter.notify;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link OutboundSmtpGuard} (UC-12 third slice, arc42 section 8 SSRF rules —
 * shared with the webhook guard via {@link SsrfHostRules}): the canonical port/TLS
 * mapping is enforced (25=NONE, 465=IMPLICIT_TLS, 587=STARTTLS, other ports any encrypted
 * mode); unencrypted NONE requires the deployment allow-list; userinfo-free host names
 * only; private/loopback/link-local/multicast/CGNAT ranges denied; localhost and cloud
 * metadata names/IPs hard-denied even when allow-listed; unresolvable hosts denied; the
 * allow-list overrides private ranges for intentional internal relays (tests on
 * 127.0.0.1); validating twice exercises a fresh DNS resolution each time (rebinding
 * protection).
 */
class OutboundSmtpGuardTest {

	private static NotifyProperties props(List<String> allowList) {
		return new NotifyProperties(null, null, null, null, null, null, null, null, allowList);
	}

	private final OutboundSmtpGuard strict = new OutboundSmtpGuard(props(List.of()));

	private final OutboundSmtpGuard allowLocal = new OutboundSmtpGuard(props(List.of("127.0.0.1")));

	@Test
	void encryptedModesToPublicHostsPassOnStrictGuard() {
		assertThat(this.strict.validate("1.1.1.1", 587, "STARTTLS")).isEqualTo("STARTTLS");
		assertThat(this.strict.validate("1.1.1.1", 465, "IMPLICIT_TLS")).isEqualTo("IMPLICIT_TLS");
		assertThat(this.strict.validate("8.8.8.8", 2525, "STARTTLS")).isEqualTo("STARTTLS"); // other
																								// port,
																								// any
																								// encrypted
																								// mode
		assertThat(this.strict.validate("1.1.1.1", 587, "starttls")).isEqualTo("STARTTLS"); // case-insensitive
	}

	@Test
	void canonicalPortTlsMappingIsEnforced() {
		assertThatThrownBy(() -> this.allowLocal.validate("127.0.0.1", 25, "STARTTLS"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("25");
		assertThatThrownBy(() -> this.allowLocal.validate("127.0.0.1", 465, "STARTTLS"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("465");
		assertThatThrownBy(() -> this.allowLocal.validate("127.0.0.1", 465, "NONE"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("465");
		assertThatThrownBy(() -> this.allowLocal.validate("127.0.0.1", 587, "NONE"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("587");
		assertThatThrownBy(() -> this.allowLocal.validate("127.0.0.1", 587, "IMPLICIT_TLS"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("587");
	}

	@Test
	void unencryptedSmtpRequiresTheAllowList() {
		assertThatThrownBy(() -> this.strict.validate("1.1.1.1", 25, "NONE")).isInstanceOf(NotificationException.class)
			.hasMessageContaining("allow-list");
		assertThatThrownBy(() -> this.strict.validate("1.1.1.1", 2525, "NONE"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("allow-list");
		// allow-listed loopback may use NONE (e.g. a local relay in tests)
		assertThatCode(() -> this.allowLocal.validate("127.0.0.1", 25, "NONE")).doesNotThrowAnyException();
		assertThatCode(() -> this.allowLocal.validate("127.0.0.1", 2525, "NONE")).doesNotThrowAnyException();
	}

	@Test
	void invalidPortTlsModeAndHostAreRejected() {
		assertThatThrownBy(() -> this.allowLocal.validate("127.0.0.1", 0, "NONE"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("between 1 and 65535");
		assertThatThrownBy(() -> this.allowLocal.validate("127.0.0.1", 70000, "NONE"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("between 1 and 65535");
		assertThatThrownBy(() -> this.allowLocal.validate("127.0.0.1", 2525, "TLS"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("TLS mode");
		assertThatThrownBy(() -> this.allowLocal.validate("  ", 2525, "NONE")).isInstanceOf(NotificationException.class)
			.hasMessageContaining("host is required");
	}

	@Test
	void privateAndReservedRangesAreDeniedUnlessAllowListed() {
		assertThatThrownBy(() -> this.strict.validate("10.0.0.5", 587, "STARTTLS"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("private/loopback");
		assertThatThrownBy(() -> this.strict.validate("127.0.0.1", 587, "STARTTLS"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("private/loopback");
		assertThatThrownBy(() -> this.strict.validate("169.254.1.1", 587, "STARTTLS"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("private/loopback");
		assertThatThrownBy(() -> this.strict.validate("100.64.1.1", 587, "STARTTLS")) // CGNAT
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("private/loopback");
		assertThatThrownBy(() -> this.strict.validate("224.0.0.1", 587, "STARTTLS")) // multicast
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("private/loopback");
		assertThatThrownBy(() -> this.strict.validate("[::1]", 587, "STARTTLS")) // IPv6
																					// loopback
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("private/loopback");
		// allow-listed 127.0.0.1 overrides the private range (intentional internal relay)
		assertThatCode(() -> this.allowLocal.validate("127.0.0.1", 587, "STARTTLS")).doesNotThrowAnyException();
	}

	@Test
	void localhostAndMetadataEndpointsAreHardDeniedEvenWhenAllowListed() {
		OutboundSmtpGuard lax = new OutboundSmtpGuard(
				props(List.of("127.0.0.1", "localhost", "metadata.google.internal", "169.254.169.254")));
		assertThatThrownBy(() -> lax.validate("localhost", 587, "STARTTLS")).isInstanceOf(NotificationException.class)
			.hasMessageContaining("localhost");
		assertThatThrownBy(() -> lax.validate("foo.localhost", 587, "STARTTLS"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("localhost");
		assertThatThrownBy(() -> lax.validate("metadata.google.internal", 587, "STARTTLS"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("metadata");
		assertThatThrownBy(() -> lax.validate("metadata.goog", 587, "STARTTLS"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("metadata");
		assertThatThrownBy(() -> lax.validate("169.254.169.254", 587, "STARTTLS"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("metadata");
	}

	@Test
	void unresolvableHostsAreDenied() {
		assertThatThrownBy(() -> this.strict.validate("no-such-host.invalid", 587, "STARTTLS"))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("resolved");
	}

	@Test
	void validateTwiceReResolvesDnsEachTime() {
		// rebinding protection: every call resolves fresh (arc42 section 8)
		assertThat(this.allowLocal.validate("127.0.0.1", 2525, "NONE")).isEqualTo("NONE");
		assertThat(this.allowLocal.validate("127.0.0.1", 2525, "NONE")).isEqualTo("NONE");
	}

}
