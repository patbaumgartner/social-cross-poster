package com.patbaumgartner.socialcrossposter.media;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

/** Workspace-scoped access to private media assets (ADR-013). */
public interface MediaAssetRepository extends CrudRepository<MediaAsset, Long> {

	Optional<MediaAsset> findByIdAndWorkspaceId(Long id, Long workspaceId);

	Optional<MediaAsset> findByWorkspaceIdAndSha256(Long workspaceId, String sha256);

	List<MediaAsset> findAllByWorkspaceIdOrderByIdDesc(Long workspaceId);

}
