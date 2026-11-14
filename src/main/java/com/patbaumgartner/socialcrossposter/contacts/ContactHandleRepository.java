package com.patbaumgartner.socialcrossposter.contacts;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

public interface ContactHandleRepository extends CrudRepository<ContactHandle, Long> {

	List<ContactHandle> findAllByWorkspaceIdOrderByContactIdAsc(Long workspaceId);

	List<ContactHandle> findAllByContactId(Long contactId);

	Optional<ContactHandle> findByIdAndWorkspaceIdAndContactId(Long id, Long workspaceId, Long contactId);

	Optional<ContactHandle> findByContactIdAndPlatformAndHandleAndHost(Long contactId, String platform, String handle,
			String host);

}
