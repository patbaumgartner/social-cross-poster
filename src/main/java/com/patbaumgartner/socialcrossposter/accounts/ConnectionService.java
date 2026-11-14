package com.patbaumgartner.socialcrossposter.accounts;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.patbaumgartner.socialcrossposter.platform.PlatformRegistry;
import com.patbaumgartner.socialcrossposter.platform.ProbeException;
import com.patbaumgartner.socialcrossposter.platform.ProbeResult;

/**
 * UC-02 connection/destination operations (ADR-006, ADR-007, ADR-013). Every method takes
 * the explicit workspace id/slug and fails closed on unknown or foreign ids; secrets are
 * encrypted on write, decrypted only for a probe in the owning workspace, and never leave
 * this service in plaintext.
 */
@Service
public class ConnectionService {

	private final ConnectionRepository connections;

	private final AccountRepository accounts;

	private final SecretCipher secrets;

	private final PlatformRegistry platforms;

	public ConnectionService(ConnectionRepository connections, AccountRepository accounts, SecretCipher secrets,
			PlatformRegistry platforms) {
		this.connections = connections;
		this.accounts = accounts;
		this.secrets = secrets;
		this.platforms = platforms;
	}

	public List<ConnectionView> listConnections(Long workspaceId) {
		return this.connections.findAllByWorkspaceIdOrderByPlatformAscProviderPrincipalAsc(workspaceId)
			.stream()
			.map(ConnectionService::toView)
			.toList();
	}

	public List<AccountDestination> listDestinations(Long workspaceId) {
		return this.accounts.findAllByWorkspaceIdAndArchivedFalseOrderByLabelAsc(workspaceId);
	}

	/**
	 * Connects a new provider authorization: probes the credential read-only, then stores
	 * it encrypted with health OK. A failed probe preserves the non-secret configuration
	 * as a disabled connection with a sanitized error (UC-02 alternative flow). An
	 * already-connected principal opens the existing connection instead of duplicating it
	 * (ADR-006).
	 */
	public ConnectResult connect(Long workspaceId, String workspaceSlug, String platform, String mode, String label,
			String credential) {
		var probe = this.platforms.find(platform)
			.orElseThrow(() -> new ProbeException("Unsupported platform: " + platform));
		if (!probe.supportedModes().contains(mode)) {
			throw new ProbeException("Mode '" + mode + "' is not supported for platform " + platform + ".");
		}
		ProbeResult result;
		try {
			result = probe.probe(mode, credential);
		}
		catch (ProbeException ex) {
			persistFailedAttempt(workspaceId, workspaceSlug, platform, mode, label);
			return ConnectResult.failure(ex.getMessage());
		}
		Optional<Connection> existing = this.connections.findByWorkspaceIdAndPlatformAndProviderPrincipal(workspaceId,
				platform, result.principal());
		if (existing.isPresent()) {
			return ConnectResult.existing(toView(existing.get()));
		}
		OffsetDateTime now = OffsetDateTime.now();
		var sealed = this.secrets.seal(SecretCipher.associatedData(workspaceSlug, platform, result.principal()),
				credential);
		Connection saved = this.connections.save(new Connection(null, workspaceId, platform, mode, result.principal(),
				StringUtils.hasText(label) ? label : result.displayName(), sealed.ciphertext(), sealed.nonce(),
				sealed.keyId(), "OK", now, true, now, now));
		return ConnectResult.created(toView(saved));
	}

	private void persistFailedAttempt(Long workspaceId, String workspaceSlug, String platform, String mode,
			String label) {
		OffsetDateTime now = OffsetDateTime.now();
		String placeholder = "unverified-" + UUID.randomUUID();
		var sealed = this.secrets.seal(SecretCipher.associatedData(workspaceSlug, platform, placeholder), "");
		this.connections.save(new Connection(null, workspaceId, platform, mode, placeholder,
				StringUtils.hasText(label) ? label : null, sealed.ciphertext(), sealed.nonce(), sealed.keyId(), "ERROR",
				now, false, now, now));
	}

	/**
	 * Rotates the secret of an existing connection (UC-02 step 6). A changed provider
	 * principal is rejected: it must become a new connection rather than silently
	 * retargeting existing destinations (ADR-006).
	 */
	public ConnectResult rotate(Long workspaceId, String workspaceSlug, Long connectionId, String credential) {
		Connection connection = this.connections.findByIdAndWorkspaceId(connectionId, workspaceId)
			.orElseThrow(() -> new ProbeException("Unknown connection for this workspace."));
		var probe = this.platforms.find(connection.platform())
			.orElseThrow(() -> new ProbeException("Unsupported platform: " + connection.platform()));
		ProbeResult result = probe.probe(connection.mode(), credential);
		if (!result.principal().equals(connection.providerPrincipal())) {
			throw new ProbeException("The provider principal changed; create a new connection instead.");
		}
		OffsetDateTime now = OffsetDateTime.now();
		var sealed = this.secrets.seal(
				SecretCipher.associatedData(workspaceSlug, connection.platform(), connection.providerPrincipal()),
				credential);
		Connection saved = this.connections.save(new Connection(connection.id(), workspaceId, connection.platform(),
				connection.mode(), connection.providerPrincipal(), connection.label(), sealed.ciphertext(),
				sealed.nonce(), sealed.keyId(), "OK", now, connection.enabled(), connection.createdAt(), now));
		return ConnectResult.created(toView(saved));
	}

	/**
	 * Re-probes a connection with its stored secret and updates health (UC-02 step 6).
	 */
	public void checkHealth(Long workspaceId, String workspaceSlug, Long connectionId) {
		Connection connection = this.connections.findByIdAndWorkspaceId(connectionId, workspaceId)
			.orElseThrow(() -> new ProbeException("Unknown connection for this workspace."));
		OffsetDateTime now = OffsetDateTime.now();
		String state = "OK";
		try {
			String credential = this.secrets.open(
					SecretCipher.associatedData(workspaceSlug, connection.platform(), connection.providerPrincipal()),
					new SecretCipher.SealedSecret(connection.secretCipher(), connection.secretNonce(),
							connection.secretKeyId()));
			this.platforms.find(connection.platform())
				.orElseThrow(() -> new ProbeException("Unsupported platform: " + connection.platform()))
				.probe(connection.mode(), credential);
		}
		catch (ProbeException ex) {
			state = "ERROR";
		}
		this.connections.save(new Connection(connection.id(), workspaceId, connection.platform(), connection.mode(),
				connection.providerPrincipal(), connection.label(), connection.secretCipher(), connection.secretNonce(),
				connection.secretKeyId(), state, now, connection.enabled(), connection.createdAt(), now));
	}

	public void setConnectionEnabled(Long workspaceId, Long connectionId, boolean enabled) {
		Connection connection = this.connections.findByIdAndWorkspaceId(connectionId, workspaceId)
			.orElseThrow(() -> new ProbeException("Unknown connection for this workspace."));
		this.connections.save(with(connection, enabled, null));
	}

	/**
	 * Adds an explicit destination to a workspace-local connection (UC-02 step 4). A
	 * duplicate exact destination (platform + provider account id) opens the existing
	 * entry instead of creating a second one.
	 */
	public AccountDestination addDestination(Long workspaceId, Long connectionId, String kind, String providerAccountId,
			String host, String label) {
		Connection connection = this.connections.findByIdAndWorkspaceId(connectionId, workspaceId)
			.orElseThrow(() -> new ProbeException("Unknown connection for this workspace."));
		if (!StringUtils.hasText(providerAccountId) || !StringUtils.hasText(label)) {
			throw new ProbeException("Destination requires a provider account id and a label.");
		}
		Optional<AccountDestination> existing = this.accounts
			.findByWorkspaceIdAndPlatformAndProviderAccountId(workspaceId, connection.platform(), providerAccountId);
		OffsetDateTime now = OffsetDateTime.now();
		if (existing.isPresent()) {
			AccountDestination destination = existing.get();
			return this.accounts.save(new AccountDestination(destination.id(), workspaceId, destination.connectionId(),
					destination.platform(), destination.kind(), destination.providerAccountId(), destination.host(),
					destination.label(), true, false, destination.createdAt(), now));
		}
		return this.accounts.save(new AccountDestination(null, workspaceId, connectionId, connection.platform(), kind,
				providerAccountId, StringUtils.hasText(host) ? host : null, label, true, false, now, now));
	}

	public void setDestinationEnabled(Long workspaceId, Long destinationId, boolean enabled) {
		AccountDestination destination = findDestination(workspaceId, destinationId);
		this.accounts.save(with(destination, enabled, null));
	}

	/**
	 * Archives a destination (soft delete, UC-02 alternative flow); history references
	 * survive.
	 */
	public void archiveDestination(Long workspaceId, Long destinationId) {
		AccountDestination destination = findDestination(workspaceId, destinationId);
		this.accounts.save(with(destination, false, true));
	}

	private AccountDestination findDestination(Long workspaceId, Long destinationId) {
		return this.accounts.findByIdAndWorkspaceId(destinationId, workspaceId)
			.orElseThrow(() -> new ProbeException("Unknown destination for this workspace."));
	}

	private Connection with(Connection c, Boolean enabled, OffsetDateTime healthAt) {
		return new Connection(c.id(), c.workspaceId(), c.platform(), c.mode(), c.providerPrincipal(), c.label(),
				c.secretCipher(), c.secretNonce(), c.secretKeyId(), c.healthState(),
				healthAt != null ? healthAt : c.lastHealthAt(), enabled != null ? enabled : c.enabled(), c.createdAt(),
				OffsetDateTime.now());
	}

	private AccountDestination with(AccountDestination d, Boolean enabled, Boolean archived) {
		return new AccountDestination(d.id(), d.workspaceId(), d.connectionId(), d.platform(), d.kind(),
				d.providerAccountId(), d.host(), d.label(), enabled != null ? enabled : d.enabled(),
				archived != null ? archived : d.archived(), d.createdAt(), OffsetDateTime.now());
	}

	private static ConnectionView toView(Connection c) {
		return new ConnectionView(c.id(), c.platform(), c.mode(), c.providerPrincipal(), c.label(), c.healthState(),
				c.lastHealthAt(), c.enabled());
	}

	/** Web-facing connection data — deliberately excludes all secret fields (ADR-007). */
	public record ConnectionView(Long id, String platform, String mode, String providerPrincipal, String label,
			String healthState, OffsetDateTime lastHealthAt, boolean enabled) {
	}

	/** Outcome of a connect/rotate attempt. */
	public record ConnectResult(ConnectionView connection, boolean existing, String error) {

		static ConnectResult created(ConnectionView view) {
			return new ConnectResult(view, false, null);
		}

		static ConnectResult existing(ConnectionView view) {
			return new ConnectResult(view, true, null);
		}

		static ConnectResult failure(String sanitizedError) {
			return new ConnectResult(null, false, sanitizedError);
		}

	}

}
