package com.patbaumgartner.socialcrossposter.web;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.patbaumgartner.socialcrossposter.content.DestinationPreview;
import com.patbaumgartner.socialcrossposter.content.Post;
import com.patbaumgartner.socialcrossposter.content.PostException;
import com.patbaumgartner.socialcrossposter.content.PostRevision;
import com.patbaumgartner.socialcrossposter.content.PostService;
import com.patbaumgartner.socialcrossposter.content.PreviewService;
import com.patbaumgartner.socialcrossposter.content.RevisionDestination;
import com.patbaumgartner.socialcrossposter.content.RevisionOverride;
import com.patbaumgartner.socialcrossposter.publish.Delivery;
import com.patbaumgartner.socialcrossposter.publish.Publication;
import com.patbaumgartner.socialcrossposter.publish.PublicationException;
import com.patbaumgartner.socialcrossposter.publish.PublicationService;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

/**
 * UC-05 first slice: publish confirmation page and the publish-now action. The
 * confirmation page shows the final per-destination previews and blockers with a
 * generated workspace-scoped submission key (UC-05 steps 1-2); the POST creates the
 * publication (idempotent under the key), executes every PENDING delivery synchronously
 * in the request and refreshes the ADR-015 aggregate. Durable jobs/leases/worker
 * execution and notifications land with UC-07/UC-12. Every route carries the explicit
 * workspace slug; unknown slugs or foreign post ids fail closed (ADR-013).
 */
@Controller
public class PublishController {

	private final WorkspaceService workspaces;

	private final PostService posts;

	private final PreviewService previews;

	private final PublicationService publications;

	public PublishController(WorkspaceService workspaces, PostService posts, PreviewService previews,
			PublicationService publications) {
		this.workspaces = workspaces;
		this.posts = posts;
		this.previews = previews;
		this.publications = publications;
	}

	/**
	 * Confirmation page (UC-05 step 1): final previews, blockers and a generated
	 * submission key. Blocking previews disable the submit button.
	 */
	@GetMapping("/w/{slug}/posts/{id}/publish")
	public String confirm(@PathVariable String slug, @PathVariable Long id, Model model) {
		return withWorkspace(slug, (workspace) -> {
			try {
				Post post = this.posts.requirePost(workspace.id(), id);
				PostRevision revision = this.posts.currentRevision(workspace.id(), id);
				List<RevisionDestination> selected = this.posts.destinationsOf(revision.id());
				List<RevisionOverride> overrides = this.posts.overridesOf(revision.id());
				List<DestinationPreview> previews = this.previews.previews(workspace.id(), revision, selected,
						overrides);
				List<String> blockers = previews.stream().flatMap((p) -> p.blockers().stream()).distinct().toList();
				model.addAttribute("workspace", workspace);
				model.addAttribute("post", post);
				model.addAttribute("revision", revision);
				model.addAttribute("previews", previews);
				model.addAttribute("blockers", blockers);
				model.addAttribute("submissionKey", UUID.randomUUID().toString());
				return "publish";
			}
			catch (PostException ex) {
				model.addAttribute("error", ex.getMessage());
				return "redirect:/w/" + slug + "/posts/" + id;
			}
		});
	}

	/**
	 * Publishes now (UC-05 steps 2-5, synchronous first slice): creates the publication
	 * under the submitted key — a repeated submit with the same approved payload returns
	 * the existing publication (ADR-015) — executes every PENDING delivery and refreshes
	 * the aggregate state.
	 */
	@PostMapping("/w/{slug}/posts/{id}/publish")
	public String publish(@PathVariable String slug, @PathVariable Long id, @RequestParam String submissionKey,
			RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				boolean existed = this.publications.findBySubmissionKey(workspace.id(), submissionKey).isPresent();
				Publication publication = this.publications.create(workspace.id(), workspace.slug(), id, submissionKey);
				for (Delivery delivery : this.publications.deliveriesOf(workspace.id(), publication.id())) {
					this.publications.executeDelivery(workspace.id(), workspace.slug(), delivery.id());
				}
				Publication refreshed = this.publications.refreshAggregate(workspace.id(), publication.id());
				redirect.addFlashAttribute("notice",
						existed ? "This submission key was already used — returned the existing publication (now "
								+ refreshed.state() + ")."
								: "Publication submitted — aggregate state " + refreshed.state()
										+ " (see Publications below).");
			}
			catch (PublicationException | PostException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/posts/" + id;
		});
	}

	private String withWorkspace(String slug, java.util.function.Function<Workspace, String> action) {
		return this.workspaces.findBySlug(slug).map(action).orElse("redirect:/");
	}

}
