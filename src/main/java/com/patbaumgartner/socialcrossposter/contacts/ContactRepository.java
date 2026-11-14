package com.patbaumgartner.socialcrossposter.contacts;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

public interface ContactRepository extends CrudRepository<Contact, Long> {

	List<Contact> findAllByWorkspaceIdOrderByDisplayNameAsc(Long workspaceId);

	List<Contact> findAllByWorkspaceIdAndArchivedFalseOrderByDisplayNameAsc(Long workspaceId);

	List<Contact> findTop8ByWorkspaceIdAndArchivedFalseAndDisplayNameContainingIgnoreCaseOrderByDisplayNameAsc(
			Long workspaceId, boolean archived, String displayNamePart);

	Optional<Contact> findByIdAndWorkspaceId(Long id, Long workspaceId);

	Optional<Contact> findByTokenAndWorkspaceId(String token, Long workspaceId);

}
