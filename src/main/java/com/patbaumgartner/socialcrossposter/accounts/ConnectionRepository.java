package com.patbaumgartner.socialcrossposter.accounts;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

public interface ConnectionRepository extends CrudRepository<Connection, Long> {

	List<Connection> findAllByWorkspaceIdOrderByPlatformAscProviderPrincipalAsc(Long workspaceId);

	Optional<Connection> findByIdAndWorkspaceId(Long id, Long workspaceId);

	Optional<Connection> findByWorkspaceIdAndPlatformAndProviderPrincipal(Long workspaceId, String platform,
			String providerPrincipal);

}
