package com.patbaumgartner.socialcrossposter.publish;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

/** Workspace-scoped access to the per-delivery media snapshot (ADR-013). */
public interface DeliveryMediaRepository extends CrudRepository<DeliveryMedia, Long> {

	List<DeliveryMedia> findAllByDeliveryIdOrderByPositionAsc(Long deliveryId);

	Optional<DeliveryMedia> findByIdAndWorkspaceId(Long id, Long workspaceId);

}
