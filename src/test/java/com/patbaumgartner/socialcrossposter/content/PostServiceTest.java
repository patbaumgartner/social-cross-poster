package com.patbaumgartner.socialcrossposter.content;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.patbaumgartner.socialcrossposter.accounts.AccountDestination;
import com.patbaumgartner.socialcrossposter.accounts.AccountRepository;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link PostService} (UC-09, ADR-004, ADR-013, ADR-015): restoring an
 * earlier revision copies its text, destinations and overrides into the NEXT immutable
 * revision and moves the pointer (nothing is deleted or modified — the source revision
 * stays intact), foreign post/revision ids fail closed, and the optimistic
 * version-conflict guard throws on a stale expectedVersion while saving nothing (never
 * last-write-wins) and saves normally when the version matches. Mocked repositories, no
 * Spring context.
 */
class PostServiceTest {

	private static final Long WS = 1L;

	private static final Long POST = 10L;

	private static final Long REV1 = 100L;

	private static final Long REV2 = 200L;

	private static final Long ACCOUNT = 7L;

	private static final OffsetDateTime NOW = OffsetDateTime.now();

	private PostRepository posts;

	private PostRevisionRepository revisions;

	private RevisionDestinationRepository destinations;

	private RevisionOverrideRepository overrides;

	private AccountRepository accounts;

	private PostService service;

	@BeforeEach
	void setUp() {
		this.posts = mock(PostRepository.class);
		this.revisions = mock(PostRevisionRepository.class);
		this.destinations = mock(RevisionDestinationRepository.class);
		this.overrides = mock(RevisionOverrideRepository.class);
		this.accounts = mock(AccountRepository.class);
		WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
		this.service = new PostService(workspaces, this.posts, this.revisions, this.destinations, this.overrides,
				this.accounts);
		given(this.posts.save(any(Post.class))).willAnswer((inv) -> inv.getArgument(0));
		given(this.revisions.save(any(PostRevision.class))).willAnswer((inv) -> {
			PostRevision r = inv.getArgument(0);
			return new PostRevision(300L, r.workspaceId(), r.postId(), r.revisionNo(), r.defaultText(), r.createdAt());
		});
		given(this.destinations.save(any(RevisionDestination.class))).willAnswer((inv) -> inv.getArgument(0));
		given(this.overrides.save(any(RevisionOverride.class))).willAnswer((inv) -> inv.getArgument(0));
	}

	@Test
	void restoreRevisionCopiesTextDestinationsOverridesIntoNextRevisionAndMovesPointer() {
		given(this.posts.findByIdAndWorkspaceId(POST, WS)).willReturn(Optional.of(post(2L, REV2)));
		PostRevision source = revision(REV1, 1, "v1 text");
		given(this.revisions.findByIdAndWorkspaceIdAndPostId(REV1, WS, POST)).willReturn(Optional.of(source));
		given(this.destinations.findAllByRevisionId(REV1))
			.willReturn(List.of(new RevisionDestination(50L, WS, REV1, ACCOUNT)));
		given(this.overrides.findAllByRevisionId(REV1))
			.willReturn(List.of(new RevisionOverride(60L, WS, REV1, "platform:telegram", "tg text", NOW)));
		given(this.revisions.findTopByPostIdOrderByRevisionNoDesc(POST))
			.willReturn(Optional.of(revision(REV2, 2, "v2 text")));
		given(this.accounts.findByIdAndWorkspaceId(ACCOUNT, WS))
			.willReturn(Optional.of(mock(AccountDestination.class)));

		PostRevision restored = this.service.restoreRevision(WS, POST, REV1);

		assertThat(restored.revisionNo()).isEqualTo(3);
		ArgumentCaptor<PostRevision> revCaptor = ArgumentCaptor.forClass(PostRevision.class);
		verify(this.revisions).save(revCaptor.capture());
		assertThat(revCaptor.getValue().revisionNo()).isEqualTo(3);
		assertThat(revCaptor.getValue().defaultText()).isEqualTo("v1 text");
		ArgumentCaptor<RevisionDestination> destCaptor = ArgumentCaptor.forClass(RevisionDestination.class);
		verify(this.destinations).save(destCaptor.capture());
		assertThat(destCaptor.getValue().revisionId()).isEqualTo(300L);
		assertThat(destCaptor.getValue().accountId()).isEqualTo(ACCOUNT);
		ArgumentCaptor<RevisionOverride> ovCaptor = ArgumentCaptor.forClass(RevisionOverride.class);
		verify(this.overrides).save(ovCaptor.capture());
		assertThat(ovCaptor.getValue().revisionId()).isEqualTo(300L);
		assertThat(ovCaptor.getValue().target()).isEqualTo("platform:telegram");
		assertThat(ovCaptor.getValue().text()).isEqualTo("tg text");
		ArgumentCaptor<Post> postCaptor = ArgumentCaptor.forClass(Post.class);
		verify(this.posts).save(postCaptor.capture());
		assertThat(postCaptor.getValue().currentRevisionId()).isEqualTo(300L);
		assertThat(postCaptor.getValue().version()).isEqualTo(3L);
		// nothing was deleted or updated in place — the source revision stays intact
		verify(this.destinations, never()).deleteAllByRevisionId(anyLong());
		verify(this.overrides, never()).deleteAllByRevisionId(anyLong());
	}

	@Test
	void restoreRevisionFailsClosedOnRevisionOfAnotherPost() {
		given(this.posts.findByIdAndWorkspaceId(POST, WS)).willReturn(Optional.of(post(2L, REV2)));
		given(this.revisions.findByIdAndWorkspaceIdAndPostId(REV1, WS, POST)).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.restoreRevision(WS, POST, REV1)).isInstanceOf(PostException.class)
			.hasMessageContaining("Unknown revision for this post.");

		verify(this.revisions, never()).save(any());
		verify(this.posts, never()).save(any());
	}

	@Test
	void restoreRevisionFailsClosedOnForeignPost() {
		given(this.posts.findByIdAndWorkspaceId(POST, WS)).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.restoreRevision(WS, POST, REV1)).isInstanceOf(PostException.class)
			.hasMessageContaining("Unknown post for this workspace.");

		verify(this.revisions, never()).save(any());
		verify(this.posts, never()).save(any());
	}

	@Test
	void saveRevisionIfVersionSavesNewRevisionWhenVersionMatches() {
		Post post = post(5L, REV2);
		given(this.revisions.findTopByPostIdOrderByRevisionNoDesc(POST))
			.willReturn(Optional.of(revision(REV2, 5, "v5 text")));

		PostRevision saved = this.service.saveRevisionIfVersion(post, 5L, "v6 text", Set.of(), List.of());

		assertThat(saved.revisionNo()).isEqualTo(6);
		ArgumentCaptor<Post> postCaptor = ArgumentCaptor.forClass(Post.class);
		verify(this.posts).save(postCaptor.capture());
		assertThat(postCaptor.getValue().version()).isEqualTo(6L);
		assertThat(postCaptor.getValue().currentRevisionId()).isEqualTo(300L);
	}

	@Test
	void saveRevisionIfVersionThrowsOnStaleVersionAndSavesNothing() {
		Post post = post(5L, REV2);
		given(this.revisions.findTopByPostIdOrderByRevisionNoDesc(POST))
			.willReturn(Optional.of(revision(REV2, 5, "v5 text")));

		assertThatThrownBy(() -> this.service.saveRevisionIfVersion(post, 3L, "concurrent edit", Set.of(), List.of()))
			.isInstanceOf(PostException.class)
			.hasMessageContaining("Conflict")
			.hasMessageContaining("expected version 3")
			.hasMessageContaining("Nothing was saved");

		verify(this.revisions, never()).save(any());
		verify(this.posts, never()).save(any());
		verify(this.destinations, never()).save(any());
		verify(this.overrides, never()).save(any());
	}

	private Post post(long version, Long currentRevisionId) {
		return new Post(POST, WS, "T", PostService.STATE_DRAFT, currentRevisionId, version, NOW, NOW);
	}

	private PostRevision revision(Long id, int no, String text) {
		return new PostRevision(id, WS, POST, no, text, NOW);
	}

}
