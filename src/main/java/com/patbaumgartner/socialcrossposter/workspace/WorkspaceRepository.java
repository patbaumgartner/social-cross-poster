package com.patbaumgartner.socialcrossposter.workspace;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WorkspaceRepository extends CrudRepository<Workspace, Long> {

	Optional<Workspace> findBySlug(String slug);

	List<Workspace> findAllByOrderByLabelAsc();

	/**
	 * Test/maintenance helper (UC-15): removes a workspace row by slug; workspace-scoped
	 * FK rows must be cleared first by the caller.
	 */
	@Transactional
	@Modifying
	@Query("DELETE FROM workspace WHERE slug = :slug")
	long deleteBySlug(@Param("slug") String slug);

}
