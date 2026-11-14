package com.patbaumgartner.socialcrossposter.content;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

public interface PostRepository extends CrudRepository<Post, Long> {

	List<Post> findAllByWorkspaceIdOrderByUpdatedAtDesc(Long workspaceId);

	Optional<Post> findByIdAndWorkspaceId(Long id, Long workspaceId);

	/** Test/maintenance helper: clears the revision pointer before deleting revisions (FK). */
	@Modifying
	@Query("UPDATE post SET current_revision_id = NULL")
	void clearCurrentRevisionPointers();

}
