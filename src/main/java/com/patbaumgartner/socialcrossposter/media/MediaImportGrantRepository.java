package com.patbaumgartner.socialcrossposter.media;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

/** Access to revocable media import grants (UC-13 fourth slice, ADR-008). */
public interface MediaImportGrantRepository extends CrudRepository<MediaImportGrant, Long> {

	Optional<MediaImportGrant> findByIdAndWorkspaceId(Long id, Long workspaceId);

	List<MediaImportGrant> findAllByDeliveryIdOrderByIdAsc(Long deliveryId);

	List<MediaImportGrant> findAllByWorkspaceIdOrderByIdDesc(Long workspaceId);

}
