package com.patbaumgartner.socialcrossposter.media;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

/** Workspace-scoped access to revision media links (ADR-013). */
public interface RevisionMediaRepository extends CrudRepository<RevisionMedia, Long> {

	List<RevisionMedia> findAllByRevisionIdOrderByPositionAsc(Long revisionId);

	Optional<RevisionMedia> findByIdAndWorkspaceId(Long id, Long workspaceId);

	long countByWorkspaceIdAndMediaId(Long workspaceId, Long mediaId);

}
