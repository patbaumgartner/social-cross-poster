package com.patbaumgartner.socialcrossposter.notify;

import java.net.InetAddress;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * SSRF guard for outbound SMTP targets (UC-12 third slice, arc42 section 8). The same
 * host-level rules as for webhooks apply (see {@link SsrfHostRules}): validated at
 * channel-save time and re-validated — with fresh DNS resolution — immediately before
 * every send, so a DNS rebinding between save and send cannot retarget a delivery.
 *
 * <ul>
 * <li>{@code tlsMode} must be {@code NONE}, {@code STARTTLS} or {@code IMPLICIT_TLS} and
 * must match the canonical port mapping: 25 = NONE, 465 = IMPLICIT_TLS, 587 = STARTTLS;
 * any other port accepts any mode.</li>
 * <li>Plain {@code NONE} (unencrypted SMTP, including port 25) is only accepted when the
 * host is on the deployment operator allow-list {@code app.notify.allowed-internal-hosts}
 * — intentional internal relays, e.g. tests on 127.0.0.1.</li>
 * <li>{@code localhost} (any suffix), cloud metadata names/IPs are hard-denied even when
 * allow-listed; unresolvable hosts are denied.</li>
 * </ul>
 *
 * All rejections throw {@link NotificationException} with a safe, body-free reason.
 */
@Component
public class OutboundSmtpGuard {

	public static final String TLS_NONE = "NONE";

	public static final String TLS_STARTTLS = "STARTTLS";

	public static final String TLS_IMPLICIT = "IMPLICIT_TLS";

	private final List<String> allowedInternalHosts;

	public OutboundSmtpGuard(NotifyProperties properties) {
		this.allowedInternalHosts = properties.allowedInternalHosts() == null ? List.of()
				: properties.allowedInternalHosts().stream().map(h -> h.toLowerCase(Locale.ROOT)).toList();
	}

	/**
	 * Validates an SMTP target and returns the normalized (upper-case) TLS mode. Throws
	 * {@link NotificationException} with a sanitized reason when the target is not safe
	 * to connect to. Call again at send time to re-resolve DNS.
	 */
	public String validate(String rawHost, int port, String rawTlsMode) {
		String host = rawHost == null ? "" : rawHost.trim();
		if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
			host = host.substring(1, host.length() - 1); // IPv6 literal brackets
		}
		if (host.isEmpty()) {
			throw new NotificationException("SMTP host is required.");
		}
		String hostLower = host.toLowerCase(Locale.ROOT);
		String tlsMode = rawTlsMode == null ? "" : rawTlsMode.trim().toUpperCase(Locale.ROOT);
		if (!List.of(TLS_NONE, TLS_STARTTLS, TLS_IMPLICIT).contains(tlsMode)) {
			throw new NotificationException("SMTP TLS mode must be NONE, STARTTLS or IMPLICIT_TLS.");
		}
		if (port < 1 || port > 65535) {
			throw new NotificationException("SMTP port must be between 1 and 65535.");
		}
		// canonical port/TLS mapping (RFC 6409 / common practice)
		if (port == 25 && !TLS_NONE.equals(tlsMode)) {
			throw new NotificationException("SMTP port 25 only allows TLS mode NONE.");
		}
		if (port == 465 && !TLS_IMPLICIT.equals(tlsMode)) {
			throw new NotificationException("SMTP port 465 requires TLS mode IMPLICIT_TLS.");
		}
		if (port == 587 && !TLS_STARTTLS.equals(tlsMode)) {
			throw new NotificationException("SMTP port 587 requires TLS mode STARTTLS.");
		}
		boolean allowListed = SsrfHostRules.isAllowListed(this.allowedInternalHosts, hostLower);
		if (TLS_NONE.equals(tlsMode) && !allowListed) {
			throw new NotificationException(
					"Unencrypted SMTP (TLS NONE) is only allowed for hosts on the deployment allow-list.");
		}
		SsrfHostRules.assertHostNameAllowed(hostLower);
		InetAddress[] addresses = SsrfHostRules.resolve(host);
		SsrfHostRules.assertAddressesAllowed(addresses, allowListed);
		return tlsMode;
	}

}
