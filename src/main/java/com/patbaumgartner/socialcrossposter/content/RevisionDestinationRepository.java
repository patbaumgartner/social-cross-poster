package com.patbaumgartner.socialcrossposter.content;

import java.util.List;

import org.springframework.data.repository.CrudRepository;

public interface RevisionDestinationRepository extends CrudRepository<RevisionDestination, Long> {

	List<RevisionDestination> findAllByRevisionId(Long revisionId);

	void deleteAllByRevisionId(Long revisionId);

}
