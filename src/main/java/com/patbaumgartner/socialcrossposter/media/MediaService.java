package com.patbaumgartner.socialcrossposter.media;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

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
 *
 * <p>
 * UC-13 third slice (ADR-016 metadata policy): every upload additionally gets a
 * metadata-stripped outgoing derivative stored as {@code
 *
<dir>
 * /<workspaceId>/out-<sha256>} (EXIF/XMP/IPTC/text/comments removed at the container
 * level by {@link OutgoingImageSanitizer}; pixels, animation and color profile are
 * preserved). The private original keeps its metadata; only the derivative is ever sent
 * to a platform. The derivative is fixed at upload time, so a policy change cannot
 * silently replace the bytes under an existing approval (ADR-016). Unparsable images are
 * rejected fail-closed at upload.
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
	 * re-uploading identical bytes returns the existing asset and writes nothing. Next to
	 * the private original, the metadata-stripped outgoing derivative
	 * ({@code out-<sha256>}, ADR-016) is stored — the only bytes ever sent to a platform.
	 * Images that cannot be parsed/sanitized are rejected fail-closed and nothing is
	 * stored.
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
		final byte[] outgoing;
		try {
			outgoing = OutgoingImageSanitizer.strip(contentType.toLowerCase(), bytes);
		}
		catch (IllegalArgumentException ex) {
			throw new MediaException("The image could not be parsed/sanitized for outgoing upload (" + ex.getMessage()
					+ ") — upload rejected.");
		}
		Path file = pathOf(workspaceId, sha);
		Path outgoingFile = outgoingPathOf(workspaceId, sha);
		try {
			Files.createDirectories(file.getParent());
			if (!Files.exists(file)) {
				Files.write(file, bytes);
			}
			if (!Files.exists(outgoingFile)) {
				Files.write(outgoingFile, outgoing);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not store the uploaded image.", ex);
		}
		Optional<MediaAsset> existing = this.assets.findByWorkspaceIdAndSha256(workspaceId, sha);
		if (existing.isPresent()) {
			return existing.get();
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

	/**
	 * Asset lookup by content hash within the workspace — used by the dispatch path to
	 * resolve the {@code delivery_media} snapshot back to the private source bytes (UC-13
	 * second slice, ADR-008).
	 */
	public Optional<MediaAsset> findBySha(Long workspaceId, String sha256) {
		return this.assets.findByWorkspaceIdAndSha256(workspaceId, sha256);
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
	 * The metadata-stripped outgoing derivative of one asset (UC-13 third slice,
	 * ADR-016), resolved by the asset's content hash. Workspace-scoped and fail-closed: a
	 * foreign/unknown hash or a missing derivative throws {@link MediaException} — the
	 * dispatch path turns that into NEEDS_REVIEW without a remote write.
	 */
	public byte[] outgoingBytes(Long workspaceId, String sha256) {
		if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
			throw new MediaException("Invalid content hash.");
		}
		if (this.assets.findByWorkspaceIdAndSha256(workspaceId, sha256).isEmpty()) {
			throw new MediaException("Unknown media asset for this workspace.");
		}
		try {
			return Files.readAllBytes(outgoingPathOf(workspaceId, sha256));
		}
		catch (IOException ex) {
			throw new MediaException("The outgoing image derivative is unavailable.");
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
	 * it; the stored original and outgoing derivative bytes are removed with it.
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
			Files.deleteIfExists(outgoingPathOf(workspaceId, asset.sha256()));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("The stored image could not be deleted.", ex);
		}
	}

	/**
	 * All stored files of one workspace's media directory — private originals and
	 * outgoing derivatives — for the secret-free workspace export (UC-15 step 5). A
	 * missing directory yields an empty list; an unreadable one fails the export.
	 */
	public List<Path> mediaFilesOf(Long workspaceId) {
		Path dir = Path.of(this.props.dir(), workspaceId.toString());
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(Files::isRegularFile).sorted().toList();
		}
		catch (NoSuchFileException ex) {
			return List.of();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("The media directory could not be listed.", ex);
		}
	}

	private Path pathOf(Long workspaceId, String sha256) {
		return Path.of(this.props.dir(), workspaceId.toString(), sha256);
	}

	private Path outgoingPathOf(Long workspaceId, String sha256) {
		return Path.of(this.props.dir(), workspaceId.toString(), "out-" + sha256);
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
