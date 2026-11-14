package com.patbaumgartner.socialcrossposter.web;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.patbaumgartner.socialcrossposter.accounts.ConnectionService;
import com.patbaumgartner.socialcrossposter.content.DestinationPreview;
import com.patbaumgartner.socialcrossposter.content.Post;
import com.patbaumgartner.socialcrossposter.content.PostException;
import com.patbaumgartner.socialcrossposter.content.PostRevision;
import com.patbaumgartner.socialcrossposter.content.PostService;
import com.patbaumgartner.socialcrossposter.content.PreviewService;
import com.patbaumgartner.socialcrossposter.content.RevisionDestination;
import com.patbaumgartner.socialcrossposter.content.RevisionOverride;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

/**
 * UC-04: compose page, drafts list, post detail and per-destination preview fragment.
 * Every route carries the explicit workspace slug; unknown slugs or foreign post ids fail
 * closed back to the workspace page (ADR-013). Saving always creates a new immutable
 * revision (ADR-015); media upload, AI assistance, autosave and publication land with
 * UC-13/UC-16/ UC-09/UC-05. The composer reuses the UC-03 mention autocomplete endpoint.
 */
@Controller
public class ComposeController {

	private final WorkspaceService workspaces;

	private final PostService posts;

	private final PreviewService previews;

	private final ConnectionService connections;

	public ComposeController(WorkspaceService workspaces, PostService posts, PreviewService previews,
			ConnectionService connections) {
		this.workspaces = workspaces;
		this.posts = posts;
		this.previews = previews;
		this.connections = connections;
	}

	/** New-post form (UC-04 step 1/3/5): title, default text, destination selection. */
	@GetMapping("/w/{slug}/compose")
	public String composeForm(@PathVariable String slug, Model model) {
		return this.workspaces.findBySlug(slug).map((workspace) -> {
			model.addAttribute("workspace", workspace);
			model.addAttribute("destinations", this.connections.listDestinations(workspace.id()));
			return "compose";
		}).orElse("redirect:/");
	}

	/** Creates a post with its first revision and opens it (UC-04 main flow). */
	@PostMapping("/w/{slug}/compose")
	public String compose(@PathVariable String slug, @RequestParam(required = false) String title,
			@RequestParam(required = false) String text, @RequestParam(required = false) List<Long> accountIds,
			RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				Post created = this.posts.createPost(workspace.id(), title, text == null ? "" : text,
						accountIds == null ? Set.of() : new TreeSet<>(accountIds), List.of());
				redirect.addFlashAttribute("notice",
						"Draft saved as revision 1" + (created.title() != null ? " — " + created.title() : "") + ".");
				return "redirect:/w/" + slug + "/posts/" + created.id();
			}
			catch (PostException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
				return "redirect:/w/" + slug + "/compose";
			}
		});
	}

	/** Drafts list of the workspace (UC-04). */
	@GetMapping("/w/{slug}/posts")
	public String posts(@PathVariable String slug, Model model) {
		return this.workspaces.findBySlug(slug).map((workspace) -> {
			model.addAttribute("workspace", workspace);
			model.addAttribute("posts", this.posts.listPosts(workspace.id()));
			return "posts";
		}).orElse("redirect:/");
	}

	/**
	 * Post detail: current immutable revision, selected destinations, overrides,
	 * previews.
	 */
	@GetMapping("/w/{slug}/posts/{id}")
	public String post(@PathVariable String slug, @PathVariable Long id, Model model) {
		return this.workspaces.findBySlug(slug).map((workspace) -> {
			try {
				Post post = this.posts.requirePost(workspace.id(), id);
				PostRevision revision = this.posts.currentRevision(workspace.id(), id);
				List<RevisionDestination> selected = this.posts.destinationsOf(revision.id());
				List<RevisionOverride> overrides = this.posts.overridesOf(revision.id());
				model.addAttribute("workspace", workspace);
				model.addAttribute("post", post);
				model.addAttribute("revision", revision);
				model.addAttribute("revisions", this.posts.revisionsOf(workspace.id(), id));
				model.addAttribute("selected", selected);
				model.addAttribute("selectedIds",
						selected.stream().map(RevisionDestination::accountId).collect(Collectors.toSet()));
				model.addAttribute("overrides", overrides);
				model.addAttribute("destinations", this.connections.listDestinations(workspace.id()));
				model.addAttribute("previews", this.previews.previews(workspace.id(), revision, selected, overrides));
				return "post";
			}
			catch (PostException ex) {
				model.addAttribute("error", ex.getMessage());
				return "redirect:/w/" + slug + "/posts";
			}
		}).orElse("redirect:/");
	}

	/** Saves the post as a new immutable revision (next revision_no), never in place. */
	@PostMapping("/w/{slug}/posts/{id}")
	public String save(@PathVariable String slug, @PathVariable Long id, @RequestParam(required = false) String title,
			@RequestParam(required = false) String text, @RequestParam(required = false) List<Long> accountIds,
			RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				Post post = this.posts.requirePost(workspace.id(), id);
				PostRevision revision = this.posts.saveRevision(post, text == null ? "" : text,
						accountIds == null ? Set.of() : new TreeSet<>(accountIds), List.of());
				if (StringUtils.hasText(title) && !title.equals(post.title())) {
					// title lives on the post container; update it together with the new
					// revision
					this.posts.saveRevisionTitle(post, title);
				}
				redirect.addFlashAttribute("notice", "Saved as revision " + revision.revisionNo() + ".");
			}
			catch (PostException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/posts/" + id;
		});
	}

	/**
	 * HTMX preview fragment (UC-04 step 6): per-destination preview of the current
	 * revision. An optional {@code text} parameter previews unsaved edits without
	 * creating a revision.
	 */
	@GetMapping("/w/{slug}/posts/{id}/preview")
	public String preview(@PathVariable String slug, @PathVariable Long id, @RequestParam(required = false) String text,
			Model model) {
		return this.workspaces.findBySlug(slug).map((workspace) -> {
			PostRevision revision = this.posts.currentRevision(workspace.id(), id);
			PostRevision effective = StringUtils.hasText(text) ? new PostRevision(revision.id(), revision.workspaceId(),
					revision.postId(), revision.revisionNo(), text, revision.createdAt()) : revision;
			List<RevisionDestination> selected = this.posts.destinationsOf(revision.id());
			List<RevisionOverride> overrides = this.posts.overridesOf(revision.id());
			List<DestinationPreview> previews = this.previews.previews(workspace.id(), effective, selected, overrides);
			model.addAttribute("workspace", workspace);
			model.addAttribute("previews", previews);
			return "fragments/post-previews :: previews";
		}).orElse("redirect:/");
	}

	private String withWorkspace(String slug, java.util.function.Function<Workspace, String> action) {
		return this.workspaces.findBySlug(slug).map(action).orElse("redirect:/");
	}

}
