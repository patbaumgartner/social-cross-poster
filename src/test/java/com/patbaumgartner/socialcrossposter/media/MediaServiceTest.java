package com.patbaumgartner.socialcrossposter.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.patbaumgartner.socialcrossposter.content.Post;
import com.patbaumgartner.socialcrossposter.content.PostRevision;
import com.patbaumgartner.socialcrossposter.content.PostService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link MediaService} (UC-13 first slice, ADR-008, ADR-013, ADR-016).
 * Repositories and {@link PostService} are mocked; file storage uses a real temp dir.
 * Covers upload validation (empty/oversize/type allow-list), sha256 dedup, current-
 * revision-only attach/detach with mandatory alt text, and referenced-asset delete
 * protection.
 */
class MediaServiceTest {

	private static final Long WS = 1L;

	private static final Long POST_ID = 10L;

	private static final Long REV_ID = 20L;

	private static final Long MEDIA_ID = 30L;

	private static final OffsetDateTime NOW = OffsetDateTime.now();

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3 };

	@TempDir
	Path tempDir;

	private MediaAssetRepository assets;

	private RevisionMediaRepository revisionMedia;

	private PostService posts;

	private MediaService service;

	@BeforeEach
	void setUp() {
		this.assets = mock(MediaAssetRepository.class);
		this.revisionMedia = mock(RevisionMediaRepository.class);
		this.posts = mock(PostService.class);
		this.service = new MediaService(this.assets, this.revisionMedia, this.posts,
				new MediaProperties(this.tempDir.toString(), 1024L, List.of("image/png")));
		given(this.assets.save(any(MediaAsset.class))).willAnswer((inv) -> {
			MediaAsset a = inv.getArgument(0);
			return new MediaAsset(MEDIA_ID, a.workspaceId(), a.filename(), a.contentType(), a.sizeBytes(), a.sha256(),
					a.createdAt());
		});
		given(this.assets.findByWorkspaceIdAndSha256(any(), any())).willReturn(Optional.empty());
		Post post = new Post(POST_ID, WS, "t", PostService.STATE_DRAFT, REV_ID, 1L, NOW, NOW);
		given(this.posts.requirePost(WS, POST_ID)).willReturn(post);
		given(this.posts.requireRevision(WS, POST_ID, REV_ID))
			.willReturn(new PostRevision(REV_ID, WS, POST_ID, 1, "hello", NOW));
	}

	@Test
	void uploadStoresBytesAndDedupsIdenticalContent() throws Exception {
		MediaAsset first = this.service.upload(WS, "a.png", "image/png", PNG);

		assertThat(first.id()).isEqualTo(MEDIA_ID);
		assertThat(first.sha256()).hasSize(64);
		assertThat(Files.readAllBytes(this.tempDir.resolve(WS.toString()).resolve(first.sha256()))).isEqualTo(PNG);

		MediaAsset existing = new MediaAsset(MEDIA_ID, WS, "a.png", "image/png", (long) PNG.length, first.sha256(),
				NOW);
		given(this.assets.findByWorkspaceIdAndSha256(WS, first.sha256())).willReturn(Optional.of(existing));

		MediaAsset second = this.service.upload(WS, "copy.png", "image/png", PNG);

		assertThat(second).isSameAs(existing);
		verify(this.assets).save(any(MediaAsset.class)); // only the first upload saved
	}

	@Test
	void uploadRejectsEmptyOversizeAndDisallowedTypes() {
		assertThatThrownBy(() -> this.service.upload(WS, "e.png", "image/png", new byte[0]))
			.isInstanceOf(MediaException.class)
			.hasMessageContaining("empty");
		assertThatThrownBy(() -> this.service.upload(WS, "big.png", "image/png", new byte[2000]))
			.isInstanceOf(MediaException.class)
			.hasMessageContaining("limit");
		assertThatThrownBy(() -> this.service.upload(WS, "x.exe", "application/octet-stream", PNG))
			.isInstanceOf(MediaException.class)
			.hasMessageContaining("Unsupported content type");
		verify(this.assets, never()).save(any());
	}

	@Test
	void attachRequiresCurrentRevisionAndAltText() {
		given(this.assets.findByIdAndWorkspaceId(MEDIA_ID, WS))
			.willReturn(Optional.of(new MediaAsset(MEDIA_ID, WS, "a.png", "image/png", 7L, "sha", NOW)));
		given(this.revisionMedia.save(any(RevisionMedia.class))).willAnswer((inv) -> {
			RevisionMedia l = inv.getArgument(0);
			return new RevisionMedia(99L, l.workspaceId(), l.revisionId(), l.mediaId(), l.position(), l.altText(),
					l.createdAt());
		});
		given(this.revisionMedia.findAllByRevisionIdOrderByPositionAsc(REV_ID))
			.willReturn(List.of(new RevisionMedia(1L, WS, REV_ID, 8L, 1, "first", NOW)));

		RevisionMedia link = this.service.attach(WS, POST_ID, REV_ID, MEDIA_ID, "  A chart  ");

		assertThat(link.position()).isEqualTo(2); // appended after the existing link
		assertThat(link.altText()).isEqualTo("A chart"); // trimmed, non-blank

		assertThatThrownBy(() -> this.service.attach(WS, POST_ID, REV_ID, MEDIA_ID, "   "))
			.isInstanceOf(MediaException.class)
			.hasMessageContaining("alt text");

		// a non-current revision fails closed (ADR-015)
		given(this.posts.requireRevision(WS, POST_ID, 21L))
			.willReturn(new PostRevision(21L, WS, POST_ID, 2, "hello", NOW));
		assertThatThrownBy(() -> this.service.attach(WS, POST_ID, 21L, MEDIA_ID, "alt"))
			.isInstanceOf(MediaException.class)
			.hasMessageContaining("current revision");
	}

	@Test
	void detachOnlyFromCurrentRevision() {
		RevisionMedia link = new RevisionMedia(99L, WS, REV_ID, MEDIA_ID, 1, "alt", NOW);
		given(this.revisionMedia.findByIdAndWorkspaceId(99L, WS)).willReturn(Optional.of(link));

		this.service.detach(WS, POST_ID, REV_ID, 99L);
		verify(this.revisionMedia).delete(link);

		given(this.posts.requireRevision(WS, POST_ID, 21L))
			.willReturn(new PostRevision(21L, WS, POST_ID, 2, "hello", NOW));
		assertThatThrownBy(() -> this.service.detach(WS, POST_ID, 21L, 99L)).isInstanceOf(MediaException.class)
			.hasMessageContaining("current revision");
	}

	@Test
	void deleteAssetRefusesWhileReferencedAndRemovesBytesOtherwise() throws Exception {
		MediaAsset asset = new MediaAsset(MEDIA_ID, WS, "a.png", "image/png", (long) PNG.length,
				this.service.upload(WS, "a.png", "image/png", PNG).sha256(), NOW);
		given(this.assets.findByIdAndWorkspaceId(MEDIA_ID, WS)).willReturn(Optional.of(asset));
		given(this.revisionMedia.countByWorkspaceIdAndMediaId(WS, MEDIA_ID)).willReturn(1L);

		assertThatThrownBy(() -> this.service.deleteAsset(WS, MEDIA_ID)).isInstanceOf(MediaException.class)
			.hasMessageContaining("still attached");
		verify(this.assets, never()).delete(any());

		given(this.revisionMedia.countByWorkspaceIdAndMediaId(WS, MEDIA_ID)).willReturn(0L);
		this.service.deleteAsset(WS, MEDIA_ID);
		verify(this.assets).delete(asset);
		assertThat(Files.exists(this.tempDir.resolve(WS.toString()).resolve(asset.sha256()))).isFalse();
	}

	@Test
	void foreignAssetLookupFailsClosed() {
		given(this.assets.findByIdAndWorkspaceId(MEDIA_ID, 999L)).willReturn(Optional.empty());
		assertThatThrownBy(() -> this.service.requireAsset(999L, MEDIA_ID)).isInstanceOf(MediaException.class)
			.hasMessageContaining("Unknown media asset");
	}

}
