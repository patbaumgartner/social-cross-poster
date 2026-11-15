package com.patbaumgartner.socialcrossposter.media;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.patbaumgartner.socialcrossposter.content.Post;
import com.patbaumgartner.socialcrossposter.content.PostRevision;
import com.patbaumgartner.socialcrossposter.content.PostService;

/**
 * Private workspace media library (UC-13 first slice, ADR-008, ADR-013, ADR-016).
 * Uploaded images are validated (allow-listed image type, size cap), content-hashed
 * (SHA-256) and deduplicated per workspace, then stored on local disk under the
 * configured {@code app.media.dir} — content-addressed as {@code
 *
<dir>
 * /<workspaceId>/<sha256>}. Bytes are served only through the authenticated download
 * route; there is no public hosting (ADR-008).
 *
 * <p>
 * Attachments ({@link RevisionMedia} links) are created with the revision and inherited
 * by the next revision on save; they can be amended only on the post's current revision
 * so historical revisions stay immutable (ADR-015). Every attached image requires a
 * meaningful local alt text (ADR-016). All lookups are workspace-scoped and fail closed
 * on foreign ids (ADR-013).
 */
@Service
public class MediaService {

	private final MediaAssetRepository assets;

	private final RevisionMediaRepository revisionMedia;

	private final PostService posts;

	private final MediaProperties props;

	public MediaService(MediaAssetRepository assets, RevisionMediaRepository revisionMedia, PostService posts,
			MediaProperties props) {
		this.assets = assets;
		this.revisionMedia = revisionMedia;
		this.posts = posts;
		this.props = props;
	}

	/**
	 * Uploads one image into the workspace library. Deduplicated by content hash:
	 * re-uploading identical bytes returns the existing asset and writes nothing.
	 */
	@Transactional
	public MediaAsset upload(Long workspaceId, String filename, String contentType, byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			throw new MediaException("The uploaded file is empty.");
		}
		if (bytes.length > this.props.maxBytes()) {
			throw new MediaException("The image exceeds the " + this.props.maxBytes() + " byte limit.");
		}
		if (contentType == null || !this.props.allowedContentTypes().contains(contentType.toLowerCase())) {
			throw new MediaException("Unsupported content type '" + contentType + "' — allowed: "
					+ String.join(", ", this.props.allowedContentTypes()) + ".");
		}
		String sha = sha256(bytes);
		Optional<MediaAsset> existing = this.assets.findByWorkspaceIdAndSha256(workspaceId, sha);
		if (existing.isPresent()) {
			return existing.get();
		}
		Path file = pathOf(workspaceId, sha);
		try {
			Files.createDirectories(file.getParent());
			Files.write(file, bytes);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not store the uploaded image.", ex);
		}
		String safeName = StringUtils.hasText(filename) ? Path.of(filename).getFileName().toString() : "image";
		return this.assets.save(new MediaAsset(null, workspaceId, safeName, contentType.toLowerCase(),
				(long) bytes.length, sha, OffsetDateTime.now()));
	}

	/** Workspace library, newest first. */
	public List<MediaAsset> assetsOf(Long workspaceId) {
		return this.assets.findAllByWorkspaceIdOrderByIdDesc(workspaceId);
	}

	/** Asset lookup, fail-closed for foreign workspaces (ADR-013). */
	public MediaAsset requireAsset(Long workspaceId, Long mediaId) {
		return this.assets.findByIdAndWorkspaceId(mediaId, workspaceId)
			.orElseThrow(() -> new MediaException("Unknown media asset for this workspace."));
	}

	/** Media links of one revision in order. */
	public List<RevisionMedia> mediaOf(Long workspaceId, Long revisionId) {
		this.posts.requireRevisionById(workspaceId, revisionId);
		return this.revisionMedia.findAllByRevisionIdOrderByPositionAsc(revisionId);
	}

	/** The stored bytes of one asset for the authenticated download route. */
	public byte[] fileBytes(Long workspaceId, Long mediaId) {
		MediaAsset asset = requireAsset(workspaceId, mediaId);
		try {
			return Files.readAllBytes(pathOf(workspaceId, asset.sha256()));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("The stored image is unreadable.", ex);
		}
	}

	/**
	 * Attaches an asset to the post's current revision with a mandatory alt text
	 * (ADR-016). Attaching to a non-current revision fails closed — save a new revision
	 * first, older revisions stay immutable (ADR-015).
	 */
	@Transactional
	public RevisionMedia attach(Long workspaceId, Long postId, Long revisionId, Long mediaId, String altText) {
		Post post = this.posts.requirePost(workspaceId, postId);
		PostRevision revision = this.posts.requireRevision(workspaceId, postId, revisionId);
		if (!revision.id().equals(post.currentRevisionId())) {
			throw new MediaException("Media can only be attached to the current revision — save a new revision "
					+ "first; existing revisions are immutable (ADR-015).");
		}
		requireAsset(workspaceId, mediaId);
		if (!StringUtils.hasText(altText)) {
			throw new MediaException("A meaningful alt text is required for every attached image (ADR-016).");
		}
		List<RevisionMedia> current = this.revisionMedia.findAllByRevisionIdOrderByPositionAsc(revision.id());
		int position = current.stream().mapToInt(RevisionMedia::position).max().orElse(0) + 1;
		return this.revisionMedia.save(new RevisionMedia(null, workspaceId, revision.id(), mediaId, position,
				altText.trim(), OffsetDateTime.now()));
	}

	/** Detaches one link from the post's current revision (fail-closed otherwise). */
	@Transactional
	public void detach(Long workspaceId, Long postId, Long revisionId, Long linkId) {
		Post post = this.posts.requirePost(workspaceId, postId);
		PostRevision revision = this.posts.requireRevision(workspaceId, postId, revisionId);
		RevisionMedia link = this.revisionMedia.findByIdAndWorkspaceId(linkId, workspaceId)
			.orElseThrow(() -> new MediaException("Unknown media attachment for this workspace."));
		if (!link.revisionId().equals(revision.id()) || !revision.id().equals(post.currentRevisionId())) {
			throw new MediaException("Media can only be detached from the current revision (ADR-015).");
		}
		this.revisionMedia.delete(link);
	}

	/**
	 * Deletes a library asset — only when no revision of the workspace still references
	 * it; the stored bytes are removed with it.
	 */
	@Transactional
	public void deleteAsset(Long workspaceId, Long mediaId) {
		MediaAsset asset = requireAsset(workspaceId, mediaId);
		if (this.revisionMedia.countByWorkspaceIdAndMediaId(workspaceId, mediaId) > 0) {
			throw new MediaException("This image is still attached to a revision — detach it first.");
		}
		this.assets.delete(asset);
		try {
			Files.deleteIfExists(pathOf(workspaceId, asset.sha256()));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("The stored image could not be deleted.", ex);
		}
	}

	private Path pathOf(Long workspaceId, String sha256) {
		return Path.of(this.props.dir(), workspaceId.toString(), sha256);
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
