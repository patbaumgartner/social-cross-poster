package com.patbaumgartner.socialcrossposter.publish;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

public interface RemoteActionAttemptRepository extends CrudRepository<RemoteActionAttempt, Long> {

	List<RemoteActionAttempt> findAllByRemoteActionIdOrderByIdAsc(Long remoteActionId);

	Optional<RemoteActionAttempt> findTopByRemoteActionIdOrderByAttemptNoDesc(Long remoteActionId);

	long countByRemoteActionId(Long remoteActionId);

}
