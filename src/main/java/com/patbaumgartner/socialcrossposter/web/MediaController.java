package com.patbaumgartner.socialcrossposter.web;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.patbaumgartner.socialcrossposter.content.Post;
import com.patbaumgartner.socialcrossposter.content.PostException;
import com.patbaumgartner.socialcrossposter.content.PostService;
import com.patbaumgartner.socialcrossposter.media.MediaAsset;
import com.patbaumgartner.socialcrossposter.media.MediaException;
import com.patbaumgartner.socialcrossposter.media.MediaService;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

/**
 * UC-13 first slice: private workspace media library. Upload, authenticated download,
 * attach/detach on the current revision and asset delete — all workspace-scoped and
 * fail-closed (ADR-008, ADR-013, ADR-016). The download route is authenticated like every
 * other page (ADR-005); images are never exposed as public URLs (ADR-008). Platform
 * upload/import is a later UC-13 slice.
 */
@Controller
public class MediaController {

	private final WorkspaceService workspaces;

	private final MediaService media;

	private final PostService posts;

	public MediaController(WorkspaceService workspaces, MediaService media, PostService posts) {
		this.workspaces = workspaces;
		this.media = media;
		this.posts = posts;
	}

	/** Uploads one image into the workspace library (multipart, size/type capped). */
	@PostMapping("/w/{slug}/posts/{id}/media/upload")
	public String upload(@PathVariable String slug, @PathVariable Long id, @RequestParam("file") MultipartFile file,
			RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				MediaAsset asset = this.media.upload(workspace.id(), file.getOriginalFilename(), file.getContentType(),
						file.getBytes());
				redirect.addFlashAttribute("notice",
						"Stored " + asset.filename() + " (" + asset.sizeBytes() + " bytes) in the private library.");
			}
			catch (MediaException | java.io.IOException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/posts/" + id;
		});
	}

	/**
	 * Authenticated download of one private source image (never a public URL, ADR-008).
	 */
	@GetMapping("/w/{slug}/media/{mediaId}/file")
	public ResponseEntity<ByteArrayResource> file(@PathVariable String slug, @PathVariable Long mediaId) {
		// Foreign workspace or unknown asset both map to 404 — existence is not leaked
		// across workspaces (ADR-013).
		return this.workspaces.findBySlug(slug).map((workspace) -> {
			try {
				MediaAsset asset = this.media.requireAsset(workspace.id(), mediaId);
				ByteArrayResource body = new ByteArrayResource(this.media.fileBytes(workspace.id(), mediaId));
				return ResponseEntity.ok()
					.contentType(MediaType.parseMediaType(asset.contentType()))
					.contentLength(asset.sizeBytes())
					.header(HttpHeaders.CONTENT_DISPOSITION,
							"inline; filename=\"" + asset.filename().replace("\"", "_") + "\"")
					.cacheControl(org.springframework.http.CacheControl.noCache())
					.body(body);
			}
			catch (MediaException ex) {
				return ResponseEntity.notFound().<ByteArrayResource>build();
			}
		}).orElse(ResponseEntity.notFound().build());
	}

	/**
	 * Attaches a library asset to the post's current revision with a mandatory alt text.
	 */
	@PostMapping("/w/{slug}/posts/{id}/media/attach")
	public String attach(@PathVariable String slug, @PathVariable Long id, @RequestParam Long mediaId,
			@RequestParam String altText, RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				Post post = this.posts.requirePost(workspace.id(), id);
				this.media.attach(workspace.id(), id, post.currentRevisionId(), mediaId, altText);
				redirect.addFlashAttribute("notice", "Image attached to the current revision.");
			}
			catch (MediaException | PostException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/posts/" + id;
		});
	}

	/** Detaches one media link from the post's current revision. */
	@PostMapping("/w/{slug}/posts/{id}/media/{linkId}/detach")
	public String detach(@PathVariable String slug, @PathVariable Long id, @PathVariable Long linkId,
			RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				Post post = this.posts.requirePost(workspace.id(), id);
				this.media.detach(workspace.id(), id, post.currentRevisionId(), linkId);
				redirect.addFlashAttribute("notice", "Image detached from the current revision.");
			}
			catch (MediaException | PostException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/posts/" + id;
		});
	}

	/** Deletes a library asset when nothing references it anymore. */
	@PostMapping("/w/{slug}/media/{mediaId}/delete")
	public String delete(@PathVariable String slug, @PathVariable Long mediaId, @RequestParam Long postId,
			RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				this.media.deleteAsset(workspace.id(), mediaId);
				redirect.addFlashAttribute("notice", "Image deleted from the library.");
			}
			catch (MediaException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/posts/" + postId;
		});
	}

	private String withWorkspace(String slug, java.util.function.Function<Workspace, String> action) {
		return this.workspaces.findBySlug(slug).map(action).orElse("redirect:/");
	}

}
