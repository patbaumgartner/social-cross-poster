package com.patbaumgartner.socialcrossposter.notify;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * SSRF guard for outbound webhook URLs (UC-12 second slice, arc42 section 8). Validated
 * at channel-save time and re-validated — with fresh DNS resolution — immediately before
 * every send, so a DNS rebinding between save and send cannot retarget a delivery.
 *
 * <ul>
 * <li>Scheme must be https; plain http is only accepted when the host is on the
 * deployment operator allow-list {@code app.notify.allowed-internal-hosts} (intentional
 * internal integrations, e.g. tests on 127.0.0.1).</li>
 * <li>Userinfo (credentials in the URL) is always rejected — no credential
 * forwarding.</li>
 * <li>{@code localhost} (any suffix), {@code metadata.google.internal} and the link-local
 * cloud metadata IP {@code 169.254.169.254} are hard-denied, even when allow-listed.</li>
 * <li>The host is resolved and every resulting address is checked: loopback, any-local,
 * link-local, site-local (private), multicast and CGNAT 100.64.0.0/10 addresses are
 * denied unless the host itself is allow-listed. Unresolvable hosts are denied.</li>
 * </ul>
 *
 * All rejections throw {@link NotificationException} with a safe, body-free reason.
 */
@Component
public class OutboundWebhookGuard {

	/** Host names denied by name, even when allow-listed (cloud metadata surface). */
	private static final Set<String> HARD_DENY_HOSTS = Set.of("metadata.google.internal", "metadata.goog");

	/** Link-local cloud metadata IP — denied even when allow-listed. */
	private static final String METADATA_IP = "169.254.169.254";

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
		boolean allowListed = this.allowedInternalHosts.contains(hostLower);
		if (!"https".equals(scheme)) {
			if (!"http".equals(scheme) || !allowListed) {
				throw new NotificationException(
						"Webhook URL must use https (plain http only for hosts on the deployment allow-list).");
			}
		}
		if (hostLower.equals("localhost") || hostLower.endsWith(".localhost")) {
			throw new NotificationException("Webhook URL host 'localhost' is denied.");
		}
		if (HARD_DENY_HOSTS.contains(hostLower)) {
			throw new NotificationException("Webhook URL host is denied (cloud metadata endpoint).");
		}
		InetAddress[] addresses = resolve(host);
		for (InetAddress address : addresses) {
			String literal = address.getHostAddress();
			if (METADATA_IP.equals(literal)) {
				throw new NotificationException("Webhook URL resolves to the cloud metadata address — denied.");
			}
			if (!allowListed && isDisallowed(address)) {
				throw new NotificationException(
						"Webhook URL resolves to a private/loopback/link-local/reserved address — denied (SSRF).");
			}
		}
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

	private static InetAddress[] resolve(String host) {
		try {
			InetAddress[] addresses = InetAddress.getAllByName(host);
			if (addresses.length == 0) {
				throw new NotificationException("Webhook URL host cannot be resolved.");
			}
			return addresses;
		}
		catch (UnknownHostException ex) {
			throw new NotificationException("Webhook URL host cannot be resolved.");
		}
	}

	/**
	 * Loopback, any-local, link-local, site-local (private), multicast and CGNAT
	 * 100.64/10.
	 */
	private static boolean isDisallowed(InetAddress address) {
		if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return true;
		}
		byte[] b = address.getAddress();
		return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64; // 100.64.0.0/10
																				// CGNAT
	}

}
