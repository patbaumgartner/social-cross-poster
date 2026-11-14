package com.patbaumgartner.socialcrossposter.content;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.patbaumgartner.socialcrossposter.accounts.AccountRepository;
import com.patbaumgartner.socialcrossposter.platform.PlatformKeys;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceRepository;

/**
 * Workspace-scoped post/draft service (UC-04, ADR-004, ADR-013, ADR-015). Every save
 * creates a new immutable {@link PostRevision} — revisions are never updated in place —
 * and moves the post's current-revision pointer. Destination account ids and override
 * targets are validated against the workspace before they are stored, so a foreign id
 * fails closed with a generic message. Connected accounts are not required to save a
 * draft (UC-04 precondition).
 */
@Service
public class PostService {

	public static final String STATE_DRAFT = "DRAFT";

	public static final String STATE_READY = "READY";

	public static final String STATE_ARCHIVED = "ARCHIVED";

	private final WorkspaceRepository workspaces;

	private final PostRepository posts;

	private final PostRevisionRepository revisions;

	private final RevisionDestinationRepository destinations;

	private final RevisionOverrideRepository overrides;

	private final AccountRepository accounts;

	public PostService(WorkspaceRepository workspaces, PostRepository posts, PostRevisionRepository revisions,
			RevisionDestinationRepository destinations, RevisionOverrideRepository overrides,
			AccountRepository accounts) {
		this.workspaces = workspaces;
		this.posts = posts;
		this.revisions = revisions;
		this.destinations = destinations;
		this.overrides = overrides;
		this.accounts = accounts;
	}

	/**
	 * Resolves the workspace or fails closed; all operations are workspace-scoped
	 * (ADR-013).
	 */
	public Workspace requireWorkspace(String slug) {
		return this.workspaces.findBySlug(slug).orElseThrow(() -> new PostException("Unknown workspace."));
	}

	/** Lists the workspace's posts, newest first (UC-04 drafts list). */
	public List<Post> listPosts(Long workspaceId) {
		return this.posts.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId);
	}

	/** Creates a new post with its first revision in one transaction (UC-04 step 1/5). */
	@Transactional
	public Post createPost(Long workspaceId, String title, String defaultText, Set<Long> accountIds,
			List<RevisionOverride> overrideRequests) {
		OffsetDateTime now = OffsetDateTime.now();
		Post post = this.posts.save(new Post(null, workspaceId, title, STATE_DRAFT, null, 0L, now, now));
		PostRevision revision = saveRevision(post, defaultText, accountIds, overrideRequests);
		Post saved = new Post(post.id(), post.workspaceId(), post.title(), post.state(), revision.id(),
				post.version() + 1, post.createdAt(), OffsetDateTime.now());
		return this.posts.save(saved);
	}

	/**
	 * Saves a post as a new immutable revision (next revision_no), replacing the selected
	 * destinations and overrides of that new revision only (ADR-015).
	 */
	@Transactional
	public PostRevision saveRevision(Post post, String defaultText, Set<Long> accountIds,
			List<RevisionOverride> overrideRequests) {
		PostRevision current = this.revisions.findTopByPostIdOrderByRevisionNoDesc(post.id()).orElse(null);
		int nextNo = current == null ? 1 : current.revisionNo() + 1;
		validateDestinations(post.workspaceId(), accountIds);
		List<RevisionOverride> validated = validateOverrides(post.workspaceId(), accountIds, overrideRequests);
		OffsetDateTime now = OffsetDateTime.now();
		PostRevision revision = this.revisions
			.save(new PostRevision(null, post.workspaceId(), post.id(), nextNo, defaultText, now));
		for (Long accountId : accountIds) {
			this.destinations.save(new RevisionDestination(null, post.workspaceId(), revision.id(), accountId));
		}
		for (RevisionOverride o : validated) {
			this.overrides
				.save(new RevisionOverride(null, post.workspaceId(), revision.id(), o.target(), o.text(), now));
		}
		Post saved = new Post(post.id(), post.workspaceId(), post.title(), post.state(), revision.id(),
				post.version() + 1, post.createdAt(), OffsetDateTime.now());
		this.posts.save(saved);
		return revision;
	}

	/**
	 * Renames the post container (title is workspace-local metadata, not revision
	 * content).
	 */
	@Transactional
	public Post saveRevisionTitle(Post post, String title) {
		Post saved = new Post(post.id(), post.workspaceId(), title, post.state(), post.currentRevisionId(),
				post.version() + 1, post.createdAt(), OffsetDateTime.now());
		return this.posts.save(saved);
	}

	/** Loads a post fail-closed for another workspace (ADR-013). */
	public Post requirePost(Long workspaceId, Long postId) {
		return this.posts.findByIdAndWorkspaceId(postId, workspaceId)
			.orElseThrow(() -> new PostException("Unknown post for this workspace."));
	}

	/** The post's current revision, fail-closed for foreign workspaces. */
	public PostRevision currentRevision(Long workspaceId, Long postId) {
		Post post = requirePost(workspaceId, postId);
		if (post.currentRevisionId() == null) {
			throw new PostException("This post has no revision yet.");
		}
		return this.revisions.findByIdAndWorkspaceId(post.currentRevisionId(), workspaceId)
			.orElseThrow(() -> new PostException("Unknown revision for this workspace."));
	}

	public List<PostRevision> revisionsOf(Long workspaceId, Long postId) {
		requirePost(workspaceId, postId);
		return this.revisions.findAllByPostIdOrderByRevisionNoAsc(postId);
	}

	public List<RevisionDestination> destinationsOf(Long revisionId) {
		return this.destinations.findAllByRevisionId(revisionId);
	}

	public List<RevisionOverride> overridesOf(Long revisionId) {
		return this.overrides.findAllByRevisionId(revisionId);
	}

	/**
	 * Validates selected destinations belong to this workspace; foreign ids fail closed.
	 */
	private void validateDestinations(Long workspaceId, Set<Long> accountIds) {
		for (Long accountId : accountIds) {
			this.accounts.findByIdAndWorkspaceId(accountId, workspaceId)
				.orElseThrow(() -> new PostException("Unknown destination for this workspace."));
		}
	}

	/**
	 * Validates override targets: {@code platform:<key>} must be a catalog platform and
	 * {@code destination:<id>} must be one of the selected destinations of this
	 * workspace.
	 */
	private List<RevisionOverride> validateOverrides(Long workspaceId, Set<Long> accountIds,
			List<RevisionOverride> requests) {
		return requests.stream().map((r) -> {
			String target = r.target();
			if (target == null || target.isBlank()) {
				throw new PostException("Override target is missing.");
			}
			if (target.startsWith("platform:")) {
				String platform = target.substring("platform:".length());
				if (!PlatformKeys.ALL.contains(platform)) {
					throw new PostException("Unknown platform in override target.");
				}
			}
			else if (target.startsWith("destination:")) {
				Long accountId;
				try {
					accountId = Long.valueOf(target.substring("destination:".length()));
				}
				catch (NumberFormatException ex) {
					throw new PostException("Invalid destination id in override target.");
				}
				if (!accountIds.contains(accountId)
						|| this.accounts.findByIdAndWorkspaceId(accountId, workspaceId).isEmpty()) {
					throw new PostException("Override destination is not selected for this workspace.");
				}
			}
			else {
				throw new PostException("Override target must be platform:<key> or destination:<id>.");
			}
			return r;
		}).toList();
	}

}
