package com.patbaumgartner.socialcrossposter.web;

import java.util.List;
import java.util.TreeSet;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.patbaumgartner.socialcrossposter.contacts.ContactException;
import com.patbaumgartner.socialcrossposter.contacts.ContactService;
import com.patbaumgartner.socialcrossposter.contacts.MentionMatch;
import com.patbaumgartner.socialcrossposter.contacts.MentionResolver;
import com.patbaumgartner.socialcrossposter.platform.PlatformKeys;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

/**
 * UC-03: workspace-scoped contacts, handles and mention autocomplete/preview. Every route
 * carries the explicit workspace slug; unknown slugs or foreign contact/handle ids fail
 * closed back to the workspace page. Search results and mention previews never contain
 * private notes.
 */
@Controller
public class ContactController {

	private final WorkspaceService workspaces;

	private final ContactService contacts;

	private final MentionResolver mentions;

	public ContactController(WorkspaceService workspaces, ContactService contacts, MentionResolver mentions) {
		this.workspaces = workspaces;
		this.contacts = contacts;
		this.mentions = mentions;
	}

	@GetMapping("/w/{slug}/contacts")
	public String contacts(@PathVariable String slug, Model model) {
		return this.workspaces.findBySlug(slug).map((workspace) -> {
			model.addAttribute("workspace", workspace);
			model.addAttribute("contacts", this.contacts.listContacts(workspace.id()));
			model.addAttribute("platforms", new TreeSet<>(PlatformKeys.ALL));
			model.addAttribute("identityKinds", List.of("none", "handle", "did", "urn", "url"));
			return "contacts";
		}).orElse("redirect:/");
	}

	@PostMapping("/w/{slug}/contacts")
	public String createContact(@PathVariable String slug, @RequestParam String displayName,
			@RequestParam(required = false) String photo, @RequestParam(required = false) String notes,
			RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				var created = this.contacts.createContact(workspace.id(), displayName, photo, notes);
				redirect.addFlashAttribute("notice",
						"Contact saved: " + created.displayName() + " (" + created.token() + ")");
			}
			catch (ContactException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/contacts";
		});
	}

	@PostMapping("/w/{slug}/contacts/{id}/handles")
	public String addHandle(@PathVariable String slug, @PathVariable Long id, @RequestParam String platform,
			@RequestParam String handle, @RequestParam(required = false) String host,
			@RequestParam(required = false) String identityKind, @RequestParam(required = false) String identityValue,
			RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				var saved = this.contacts.addHandle(workspace.id(), id, platform, handle, host, identityKind,
						identityValue);
				redirect.addFlashAttribute("notice", "Handle saved: " + saved.platform() + " " + saved.handle()
						+ (StringUtils.hasText(saved.host()) ? "@" + saved.host() : ""));
			}
			catch (ContactException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/contacts";
		});
	}

	@PostMapping("/w/{slug}/contacts/{id}/handles/{handleId}/delete")
	public String deleteHandle(@PathVariable String slug, @PathVariable Long id, @PathVariable Long handleId,
			RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				this.contacts.deleteHandle(workspace.id(), id, handleId);
				redirect.addFlashAttribute("notice", "Handle removed.");
			}
			catch (ContactException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/contacts";
		});
	}

	@PostMapping("/w/{slug}/contacts/{id}/toggle")
	public String toggleArchived(@PathVariable String slug, @PathVariable Long id, @RequestParam boolean archived,
			RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				this.contacts.setArchived(workspace.id(), id, archived);
				redirect.addFlashAttribute("notice", archived ? "Contact archived." : "Contact restored.");
			}
			catch (ContactException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/contacts";
		});
	}

	/**
	 * Mention autocomplete (UC-03 step 3): HTMX fragment for the composer. The query is
	 * the text after the last {@code @} when present, otherwise the raw input. Results
	 * are workspace-scoped and exclude archived contacts and private notes.
	 */
	@GetMapping("/w/{slug}/contacts/search")
	public String search(@PathVariable String slug, @RequestParam(required = false) String q, Model model) {
		return this.workspaces.findBySlug(slug).map((workspace) -> {
			String raw = q == null ? "" : q;
			int at = raw.lastIndexOf('@');
			String query = at >= 0 ? raw.substring(at + 1) : raw;
			model.addAttribute("workspace", workspace);
			model.addAttribute("query", query);
			model.addAttribute("matches", this.contacts.search(workspace.id(), query));
			return "fragments/contact-results :: results";
		}).orElse("redirect:/");
	}

	/**
	 * Mention preview (UC-03 step 4): resolves the contact's token (or an explicit
	 * {@code text}) for one platform through the verified mention contract and shows
	 * whether it is a native mention or a visible display-name fallback.
	 */
	@GetMapping("/w/{slug}/contacts/{id}/mentions")
	public String mentionPreview(@PathVariable String slug, @PathVariable Long id,
			@RequestParam(required = false) String platform, @RequestParam(required = false) String text, Model model) {
		return this.workspaces.findBySlug(slug).map((workspace) -> {
			String resolvedPlatform = StringUtils.hasText(platform) ? platform.trim().toLowerCase()
					: new TreeSet<>(PlatformKeys.ALL).first();
			return this.contacts.findContact(workspace.id(), id).map((contact) -> {
				String resolvedText = StringUtils.hasText(text) ? text : contact.token();
				List<MentionMatch> matches = this.mentions.resolve(workspace.id(), resolvedText, resolvedPlatform);
				model.addAttribute("workspace", workspace);
				model.addAttribute("contact", contact);
				model.addAttribute("platform", resolvedPlatform);
				model.addAttribute("text", resolvedText);
				model.addAttribute("matches", matches);
				model.addAttribute("platforms", new TreeSet<>(PlatformKeys.ALL));
				return "fragments/mention-preview :: preview";
			}).orElse("redirect:/w/" + slug + "/contacts");
		}).orElse("redirect:/");
	}

	private String withWorkspace(String slug, java.util.function.Function<Workspace, String> action) {
		return this.workspaces.findBySlug(slug).map(action).orElse("redirect:/");
	}

}
