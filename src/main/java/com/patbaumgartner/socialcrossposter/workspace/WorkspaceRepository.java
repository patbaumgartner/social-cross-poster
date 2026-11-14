package com.patbaumgartner.socialcrossposter.workspace;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

public interface WorkspaceRepository extends CrudRepository<Workspace, Long> {

	Optional<Workspace> findBySlug(String slug);

	List<Workspace> findAllByOrderByLabelAsc();

}
