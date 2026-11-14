package com.patbaumgartner.socialcrossposter.publish;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

public interface DeliveryRepository extends CrudRepository<Delivery, Long> {

	Optional<Delivery> findByIdAndWorkspaceId(Long id, Long workspaceId);

	List<Delivery> findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(Long workspaceId, Long publicationId);

}
