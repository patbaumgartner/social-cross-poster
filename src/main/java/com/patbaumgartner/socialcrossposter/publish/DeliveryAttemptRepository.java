package com.patbaumgartner.socialcrossposter.publish;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

public interface DeliveryAttemptRepository extends CrudRepository<DeliveryAttempt, Long> {

	List<DeliveryAttempt> findAllByDeliveryIdOrderByIdAsc(Long deliveryId);

	Optional<DeliveryAttempt> findTopByDeliveryIdOrderByAttemptNoDesc(Long deliveryId);

	long countByDeliveryId(Long deliveryId);

}
