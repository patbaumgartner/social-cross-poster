package com.patbaumgartner.socialcrossposter.web;

import java.util.List;

import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.patbaumgartner.socialcrossposter.notify.NotificationService;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

/**
 * UC-01: workspace picker and workspace dashboard. Every workspace-scoped route carries
 * the explicit workspace slug; an unknown slug returns to the picker without selecting
 * another workspace's resources. The dashboard also lists the workspace's in-app
 * notification events (UC-12 step 5), newest first.
 */
@Controller
public class WorkspaceController {

	private final WorkspaceService workspaces;

	private final NotificationService notifications;

	public WorkspaceController(WorkspaceService workspaces, NotificationService notifications) {
		this.workspaces = workspaces;
		this.notifications = notifications;
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
			return "dashboard";
		}).orElse("redirect:/");
	}

}
