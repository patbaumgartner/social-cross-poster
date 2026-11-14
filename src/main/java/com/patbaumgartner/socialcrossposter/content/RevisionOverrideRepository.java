package com.patbaumgartner.socialcrossposter.content;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

public interface RevisionOverrideRepository extends CrudRepository<RevisionOverride, Long> {

	List<RevisionOverride> findAllByRevisionId(Long revisionId);

	Optional<RevisionOverride> findByRevisionIdAndTarget(Long revisionId, String target);

	void deleteAllByRevisionId(Long revisionId);

}
