package com.patbaumgartner.socialcrossposter.web;

import java.util.List;

import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.patbaumgartner.socialcrossposter.notify.NotificationChannel;
import com.patbaumgartner.socialcrossposter.notify.NotificationChannelService;
import com.patbaumgartner.socialcrossposter.notify.NotificationDelivery;
import com.patbaumgartner.socialcrossposter.notify.NotificationDeliveryService;
import com.patbaumgartner.socialcrossposter.notify.NotificationService;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

/**
 * UC-01: workspace picker and workspace dashboard. Every workspace-scoped route carries
 * the explicit workspace slug; an unknown slug returns to the picker without selecting
 * another workspace's resources. The dashboard also lists the workspace's in-app
 * notification events (UC-12 step 5), newest first, and its webhook channels with the
 * recent outbox rows (UC-12 second slice).
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
