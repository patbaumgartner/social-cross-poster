package com.patbaumgartner.socialcrossposter.publish;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

public interface RemoteActionRepository extends CrudRepository<RemoteAction, Long> {

	Optional<RemoteAction> findByIdAndWorkspaceId(Long id, Long workspaceId);

	List<RemoteAction> findAllByWorkspaceIdAndDeliveryIdOrderByIdAsc(Long workspaceId, Long deliveryId);

	List<RemoteAction> findAllByWorkspaceId(Long workspaceId);

	List<RemoteAction> findAllByWorkspaceIdAndDeliveryIdAndStateInOrderByIdAsc(Long workspaceId, Long deliveryId,
			Collection<String> states);

}
