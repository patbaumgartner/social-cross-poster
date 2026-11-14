package com.patbaumgartner.socialcrossposter.accounts;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

public interface AccountRepository extends CrudRepository<AccountDestination, Long> {

	List<AccountDestination> findAllByWorkspaceIdAndArchivedFalseOrderByLabelAsc(Long workspaceId);

	List<AccountDestination> findAllByConnectionId(Long connectionId);

	Optional<AccountDestination> findByIdAndWorkspaceId(Long id, Long workspaceId);

	Optional<AccountDestination> findByWorkspaceIdAndPlatformAndProviderAccountId(Long workspaceId, String platform,
			String providerAccountId);

}
