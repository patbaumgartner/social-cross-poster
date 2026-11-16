package com.patbaumgartner.socialcrossposter.notify;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

/**
 * Host-level SSRF rules shared by the webhook and SMTP outbound guards (UC-12, arc42
 * section 8): {@code localhost} (any suffix), the cloud metadata names and the link-local
 * metadata IP {@code 169.254.169.254} are hard-denied even when allow-listed; every
 * resolved address is checked and loopback, any-local, link-local, site-local (private),
 * multicast and CGNAT 100.64.0.0/10 addresses are denied unless the host itself is on the
 * deployment operator allow-list; unresolvable hosts are denied. All rejections throw
 * {@link NotificationException} with a safe, body-free reason.
 */
final class SsrfHostRules {

	/** Host names denied by name, even when allow-listed (cloud metadata surface). */
	static final Set<String> HARD_DENY_HOSTS = Set.of("metadata.google.internal", "metadata.goog");

	/** Link-local cloud metadata IP — denied even when allow-listed. */
	static final String METADATA_IP = "169.254.169.254";

	private SsrfHostRules() {
	}

	static boolean isAllowListed(List<String> allowedInternalHosts, String hostLower) {
		return allowedInternalHosts.contains(hostLower);
	}

	/** Denies localhost (any suffix) and the cloud metadata host names. */
	static void assertHostNameAllowed(String hostLower) {
		if (hostLower.equals("localhost") || hostLower.endsWith(".localhost")) {
			throw new NotificationException("Outbound host 'localhost' is denied.");
		}
		if (HARD_DENY_HOSTS.contains(hostLower)) {
			throw new NotificationException("Outbound host is denied (cloud metadata endpoint).");
		}
	}

	static InetAddress[] resolve(String host) {
		try {
			InetAddress[] addresses = InetAddress.getAllByName(host);
			if (addresses.length == 0) {
				throw new NotificationException("Outbound host cannot be resolved.");
			}
			return addresses;
		}
		catch (UnknownHostException ex) {
			throw new NotificationException("Outbound host cannot be resolved.");
		}
	}

	/**
	 * Checks every resolved address: the metadata IP is hard-denied; unless the host is
	 * allow-listed, private/loopback/link-local/reserved addresses are denied.
	 */
	static void assertAddressesAllowed(InetAddress[] addresses, boolean allowListed) {
		for (InetAddress address : addresses) {
			String literal = address.getHostAddress();
			if (METADATA_IP.equals(literal)) {
				throw new NotificationException("Outbound host resolves to the cloud metadata address — denied.");
			}
			if (!allowListed && isDisallowed(address)) {
				throw new NotificationException(
						"Outbound host resolves to a private/loopback/link-local/reserved address — denied (SSRF).");
			}
		}
	}

	/**
	 * Loopback, any-local, link-local, site-local (private), multicast and CGNAT
	 * 100.64/10.
	 */
	static boolean isDisallowed(InetAddress address) {
		if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return true;
		}
		byte[] b = address.getAddress();
		return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64; // 100.64.0.0/10
																				// CGNAT
	}

}
