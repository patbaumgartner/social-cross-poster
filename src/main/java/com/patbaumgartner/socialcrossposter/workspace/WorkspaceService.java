package com.patbaumgartner.socialcrossposter.workspace;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Workspace queries. Callers always pass an explicit workspace; missing context fails
 * closed (ADR-013).
 */
@Service
public class WorkspaceService {

	private final WorkspaceRepository repository;

	public WorkspaceService(WorkspaceRepository repository) {
		this.repository = repository;
	}

	public List<Workspace> listAll() {
		return this.repository.findAllByOrderByLabelAsc();
	}

	public Optional<Workspace> findBySlug(String slug) {
		if (!StringUtils.hasText(slug)) {
			return Optional.empty();
		}
		return this.repository.findBySlug(slug);
	}

}
