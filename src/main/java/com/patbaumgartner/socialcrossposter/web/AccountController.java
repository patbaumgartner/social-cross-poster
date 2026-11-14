package com.patbaumgartner.socialcrossposter.web;

import java.util.Comparator;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.patbaumgartner.socialcrossposter.accounts.AccountDestination;
import com.patbaumgartner.socialcrossposter.accounts.ConnectionService;
import com.patbaumgartner.socialcrossposter.platform.PlatformRegistry;
import com.patbaumgartner.socialcrossposter.platform.ProbeException;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

/**
 * UC-02: workspace-scoped account management. Every route carries the explicit workspace
 * slug; unknown slugs or foreign connection/destination ids fail closed back to the
 * workspace page. Secrets are accepted on POST only and never rendered back (ADR-007).
 */
@Controller
public class AccountController {

	private final WorkspaceService workspaces;

	private final ConnectionService connections;

	private final PlatformRegistry platforms;

	public AccountController(WorkspaceService workspaces, ConnectionService connections, PlatformRegistry platforms) {
		this.workspaces = workspaces;
		this.connections = connections;
		this.platforms = platforms;
	}

	@GetMapping("/w/{slug}/accounts")
	public String accounts(@PathVariable String slug, Model model) {
		return this.workspaces.findBySlug(slug).map((workspace) -> {
			model.addAttribute("workspace", workspace);
			model.addAttribute("connections", this.connections.listConnections(workspace.id()));
			model.addAttribute("destinations", this.connections.listDestinations(workspace.id()));
			model.addAttribute("platforms",
					this.platforms.all()
						.entrySet()
						.stream()
						.sorted(Comparator.comparing(Map.Entry::getKey))
						.map((e) -> Map.entry(e.getKey(), e.getValue().supportedModes().stream().sorted().toList()))
						.toList());
			return "accounts";
		}).orElse("redirect:/");
	}

	@PostMapping("/w/{slug}/accounts/connections")
	public String connect(@PathVariable String slug, @RequestParam String platform, @RequestParam String mode,
			@RequestParam(required = false) String label, @RequestParam String credential,
			RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				ConnectionService.ConnectResult result = this.connections.connect(workspace.id(), workspace.slug(),
						platform, mode, label, credential);
				if (result.connection() == null) {
					redirect.addFlashAttribute("error", result.error());
					redirect.addFlashAttribute("platform", platform);
					redirect.addFlashAttribute("label", label);
					return "redirect:/w/" + slug + "/accounts";
				}
				redirect.addFlashAttribute("notice", (result.existing() ? "Already connected: " : "Connected ")
						+ result.connection().providerPrincipal());
			}
			catch (ProbeException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
				redirect.addFlashAttribute("platform", platform);
				redirect.addFlashAttribute("label", label);
			}
			return "redirect:/w/" + slug + "/accounts";
		});
	}

	@PostMapping("/w/{slug}/accounts/connections/{id}/rotate")
	public String rotate(@PathVariable String slug, @PathVariable Long id, @RequestParam String credential,
			RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				this.connections.rotate(workspace.id(), workspace.slug(), id, credential);
				redirect.addFlashAttribute("notice", "Credentials rotated and verified.");
			}
			catch (ProbeException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/accounts";
		});
	}

	@PostMapping("/w/{slug}/accounts/connections/{id}/health")
	public String health(@PathVariable String slug, @PathVariable Long id, RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				this.connections.checkHealth(workspace.id(), workspace.slug(), id);
				redirect.addFlashAttribute("notice", "Health re-checked.");
			}
			catch (ProbeException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/accounts";
		});
	}

	@PostMapping("/w/{slug}/accounts/connections/{id}/toggle")
	public String toggleConnection(@PathVariable String slug, @PathVariable Long id, @RequestParam boolean enabled,
			RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				this.connections.setConnectionEnabled(workspace.id(), id, enabled);
			}
			catch (ProbeException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/accounts";
		});
	}

	@PostMapping("/w/{slug}/accounts/destinations")
	public String addDestination(@PathVariable String slug, @RequestParam Long connectionId, @RequestParam String kind,
			@RequestParam String providerAccountId, @RequestParam(required = false) String host,
			@RequestParam String label, RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				AccountDestination destination = this.connections.addDestination(workspace.id(), connectionId, kind,
						providerAccountId, host, label);
				redirect.addFlashAttribute("notice",
						"Destination saved: " + destination.label() + " (" + destination.providerAccountId() + ")");
			}
			catch (ProbeException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/accounts";
		});
	}

	@PostMapping("/w/{slug}/accounts/destinations/{id}/toggle")
	public String toggleDestination(@PathVariable String slug, @PathVariable Long id, @RequestParam boolean enabled,
			RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				this.connections.setDestinationEnabled(workspace.id(), id, enabled);
			}
			catch (ProbeException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/accounts";
		});
	}

	@PostMapping("/w/{slug}/accounts/destinations/{id}/delete")
	public String deleteDestination(@PathVariable String slug, @PathVariable Long id, RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				this.connections.archiveDestination(workspace.id(), id);
				redirect.addFlashAttribute("notice", "Destination archived.");
			}
			catch (ProbeException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/accounts";
		});
	}

	private String withWorkspace(String slug, java.util.function.Function<Workspace, String> action) {
		return this.workspaces.findBySlug(slug).map(action).orElse("redirect:/");
	}

}
