package com.patbaumgartner.socialcrossposter.notify;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * SSRF guard for outbound webhook URLs (UC-12 second slice, arc42 section 8). Validated
 * at channel-save time and re-validated — with fresh DNS resolution — immediately before
 * every send, so a DNS rebinding between save and send cannot retarget a delivery. The
 * host-level rules (hard-denied metadata endpoints,
 * private/loopback/link-local/any-local/ multicast/CGNAT ranges, unresolvable hosts) live
 * in {@link SsrfHostRules} and are shared with {@link OutboundSmtpGuard}.
 *
 * <ul>
 * <li>Scheme must be https; plain http is only accepted when the host is on the
 * deployment operator allow-list {@code app.notify.allowed-internal-hosts} (intentional
 * internal integrations, e.g. tests on 127.0.0.1).</li>
 * <li>Userinfo (credentials in the URL) is always rejected — no credential
 * forwarding.</li>
 * <li>{@code localhost} (any suffix), {@code metadata.google.internal} and the link-local
 * cloud metadata IP {@code 169.254.169.254} are hard-denied, even when allow-listed.</li>
 * </ul>
 *
 * All rejections throw {@link NotificationException} with a safe, body-free reason.
 */
@Component
public class OutboundWebhookGuard {

	private final List<String> allowedInternalHosts;

	public OutboundWebhookGuard(NotifyProperties properties) {
		this.allowedInternalHosts = properties.allowedInternalHosts() == null ? List.of()
				: properties.allowedInternalHosts().stream().map(h -> h.toLowerCase(Locale.ROOT)).toList();
	}

	/**
	 * Validates {@code rawUrl}; throws {@link NotificationException} with a sanitized
	 * reason when the URL is not safe to POST to. Call again at send time to re-resolve
	 * DNS.
	 */
	public void validate(String rawUrl) {
		URI uri = parse(rawUrl);
		if (uri.getUserInfo() != null) {
			throw new NotificationException("Webhook URL must not contain credentials (userinfo is denied).");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			throw new NotificationException("Webhook URL must have a host.");
		}
		String hostLower = host.toLowerCase(Locale.ROOT);
		boolean allowListed = SsrfHostRules.isAllowListed(this.allowedInternalHosts, hostLower);
		if (!"https".equals(scheme)) {
			if (!"http".equals(scheme) || !allowListed) {
				throw new NotificationException(
						"Webhook URL must use https (plain http only for hosts on the deployment allow-list).");
			}
		}
		SsrfHostRules.assertHostNameAllowed(hostLower);
		InetAddress[] addresses = SsrfHostRules.resolve(host);
		SsrfHostRules.assertAddressesAllowed(addresses, allowListed);
	}

	private static URI parse(String rawUrl) {
		if (rawUrl == null || rawUrl.isBlank()) {
			throw new NotificationException("Webhook URL is required.");
		}
		try {
			return new URI(rawUrl.trim());
		}
		catch (URISyntaxException ex) {
			throw new NotificationException("Webhook URL is not a valid URL.");
		}
	}

}
