package com.patbaumgartner.socialcrossposter.web;

import java.util.List;

import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.patbaumgartner.socialcrossposter.notify.NotificationChannel;
import com.patbaumgartner.socialcrossposter.notify.NotificationChannelService;
import com.patbaumgartner.socialcrossposter.notify.NotificationDelivery;
import com.patbaumgartner.socialcrossposter.notify.NotificationDeliveryService;
import com.patbaumgartner.socialcrossposter.notify.NotificationService;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceException;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

/**
 * UC-01: workspace picker and workspace dashboard. Every workspace-scoped route carries
 * the explicit workspace slug; an unknown slug returns to the picker without selecting
 * another workspace's resources. The dashboard also lists the workspace's in-app
 * notification events (UC-12 step 5), newest first, and its webhook channels with the
 * recent outbox rows (UC-12 second slice). UC-15 step 1: the owner creates a new
 * workspace from the picker; validation failures fail closed and persist nothing. UC-15
 * step 2: the owner edits an existing workspace's label/color/timezone, profile
 * (languages/brand voice) and protected event facts/links on /w/{slug}/settings — the
 * slug is immutable (ADR-013).
 */
@Controller
public class WorkspaceController {

	private final WorkspaceService workspaces;

	private final NotificationService notifications;

	private final NotificationChannelService channels;

	private final NotificationDeliveryService deliveries;

	public WorkspaceController(WorkspaceService workspaces, NotificationService notifications,
			NotificationChannelService channels, NotificationDeliveryService deliveries) {
		this.workspaces = workspaces;
		this.notifications = notifications;
		this.channels = channels;
		this.deliveries = deliveries;
	}

	@GetMapping("/")
	public String picker(Model model) {
		List<Workspace> all = this.workspaces.listAll();
		model.addAttribute("workspaces", all);
		return "workspaces";
	}

	/**
	 * UC-15 step 1: create a new workspace. Validation failures are flashed as a
	 * sanitized error and nothing is persisted; success enters the new workspace's
	 * dashboard directly.
	 */
	@PostMapping("/workspaces")
	public String create(@RequestParam String slug, @RequestParam String label,
			@RequestParam(required = false) String color, @RequestParam(required = false) String timezone,
			RedirectAttributes redirect) {
		try {
			Workspace created = this.workspaces.create(slug, label, color, timezone);
			redirect.addFlashAttribute("notice",
					"Workspace '" + created.label() + "' created — open it to connect accounts and contacts.");
			return "redirect:/w/" + created.slug() + "/dashboard";
		}
		catch (WorkspaceException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
			return "redirect:/";
		}
	}

	/**
	 * UC-15 step 2: settings form for one workspace. An unknown slug returns to the
	 * picker without touching another workspace's settings.
	 */
	@GetMapping("/w/{slug}/settings")
	public String settingsForm(@PathVariable String slug, Model model) {
		return this.workspaces.findBySlug(slug).map((workspace) -> {
			model.addAttribute("workspace", workspace);
			return "workspace-settings";
		}).orElse("redirect:/");
	}

	/**
	 * UC-15 step 2: save label/color/timezone plus the profile (languages, brand voice)
	 * and the protected event anchors (event facts, event links). The slug is immutable
	 * and never read from the form. Validation failures are flashed as a sanitized error
	 * and the stored row is left unchanged; success flashes a notice and returns to the
	 * dashboard.
	 */
	@PostMapping("/w/{slug}/settings")
	public String updateSettings(@PathVariable String slug, @RequestParam String label,
			@RequestParam(required = false) String color, @RequestParam(required = false) String timezone,
			@RequestParam(required = false) String languages, @RequestParam(required = false) String brandVoice,
			@RequestParam(required = false) String eventFacts, @RequestParam(required = false) String eventLinks,
			RedirectAttributes redirect) {
		if (this.workspaces.findBySlug(slug).isEmpty()) {
			return "redirect:/";
		}
		try {
			Workspace updated = this.workspaces.updateSettings(slug, label, color, timezone, languages, brandVoice,
					eventFacts, eventLinks);
			redirect.addFlashAttribute("notice", "Settings for '" + updated.label() + "' saved.");
			return "redirect:/w/" + updated.slug() + "/dashboard";
		}
		catch (WorkspaceException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
			return "redirect:/w/" + slug + "/settings";
		}
	}

	@GetMapping("/w/{slug}/dashboard")
	public String dashboard(@PathVariable String slug, Model model, HttpSession session) {
		return this.workspaces.findBySlug(slug).map((workspace) -> {
			session.setAttribute("workspaceSlug", workspace.slug());
			model.addAttribute("workspace", workspace);
			model.addAttribute("events", this.notifications.eventsOf(workspace.id()));
			List<NotificationChannel> channelList = this.channels.channelsOf(workspace.id());
			model.addAttribute("channels", channelList);
			java.util.Map<Long, List<NotificationDelivery>> recentByChannel = new java.util.LinkedHashMap<>();
			for (NotificationChannel channel : channelList) {
				recentByChannel.put(channel.id(),
						this.deliveries.recentForChannel(channel.id()).stream().limit(5).toList());
			}
			model.addAttribute("recentByChannel", recentByChannel);
			return "dashboard";
		}).orElse("redirect:/");
	}

}
