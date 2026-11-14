package com.patbaumgartner.socialcrossposter.content;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

public interface PostRevisionRepository extends CrudRepository<PostRevision, Long> {

	Optional<PostRevision> findByIdAndWorkspaceId(Long id, Long workspaceId);

	List<PostRevision> findAllByPostIdOrderByRevisionNoAsc(Long postId);

	Optional<PostRevision> findTopByPostIdOrderByRevisionNoDesc(Long postId);

}
