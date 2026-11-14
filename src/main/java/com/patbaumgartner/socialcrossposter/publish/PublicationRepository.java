package com.patbaumgartner.socialcrossposter.publish;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

public interface PublicationRepository extends CrudRepository<Publication, Long> {

	Optional<Publication> findByWorkspaceIdAndSubmissionKey(Long workspaceId, String submissionKey);

	Optional<Publication> findByIdAndWorkspaceId(Long id, Long workspaceId);

	List<Publication> findAllByWorkspaceIdAndPostIdOrderByIdDesc(Long workspaceId, Long postId);

}
