package com.patbaumgartner.socialcrossposter.web;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.patbaumgartner.socialcrossposter.notify.NotificationChannel;
import com.patbaumgartner.socialcrossposter.notify.NotificationChannelService;
import com.patbaumgartner.socialcrossposter.notify.NotificationException;
import com.patbaumgartner.socialcrossposter.notify.WebhookSender;
import com.patbaumgartner.socialcrossposter.notify.WebhookSender.SendResult;
import com.patbaumgartner.socialcrossposter.notify.WebhookSender.WebhookPayload;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

/**
 * Webhook notification channel endpoints (UC-12 second slice step 1, ADR-004, ADR-007,
 * ADR-013): add a channel (SSRF-validated URL, server-generated sealed HMAC secret),
 * toggle, send a signed test notification, and delete. All routes are workspace-scoped
 * and fail closed for foreign ids; errors are sanitized and never leak another
 * workspace's data.
 */
@Controller
public class NotificationController {

	private final WorkspaceService workspaces;

	private final NotificationChannelService channels;

	private final WebhookSender sender;

	public NotificationController(WorkspaceService workspaces, NotificationChannelService channels,
			WebhookSender sender) {
		this.workspaces = workspaces;
		this.channels = channels;
		this.sender = sender;
	}

	/**
	 * Adds a webhook channel. The URL must pass the SSRF guard (https to a public host
	 * unless the deployment allow-list permits it); the signing secret is generated
	 * server-side and stored sealed (ADR-007) — it is never shown again.
	 */
	@PostMapping("/w/{slug}/notifications/channels")
	public String create(@PathVariable String slug, @RequestParam String label, @RequestParam String url,
			@RequestParam(required = false) String categories, RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				NotificationChannel channel = this.channels.create(workspace.id(), workspace.slug(), label, url,
						categories);
				redirect.addFlashAttribute("notice",
						"Webhook channel '" + channel.label() + "' added. The signing "
								+ "secret was generated server-side and stored encrypted (ADR-007); keep your receiver "
								+ "verification key private.");
			}
			catch (NotificationException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/dashboard";
		});
	}

	@PostMapping("/w/{slug}/notifications/channels/{id}/toggle")
	public String toggle(@PathVariable String slug, @PathVariable Long id, RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				NotificationChannel channel = this.channels.toggle(workspace.id(), id);
				redirect.addFlashAttribute("notice",
						"Channel '" + channel.label() + "' " + (channel.enabled() ? "enabled." : "disabled."));
			}
			catch (NotificationException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/dashboard";
		});
	}

	/**
	 * Sends one signed test notification to the channel without persisting an event
	 * (UC-12 step 4 signature/timestamp contract, receiver dedup via X-SCP-Event-Id).
	 */
	@PostMapping("/w/{slug}/notifications/channels/{id}/test")
	public String test(@PathVariable String slug, @PathVariable Long id, RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				NotificationChannel channel = this.channels.requireById(workspace.id(), id);
				String secret = this.channels.openSecret(workspace.slug(), channel);
				WebhookPayload payload = new WebhookPayload(0L, "test", workspace.id(), workspace.label(), "TEST",
						"Test notification from social-cross-poster (workspace " + workspace.label() + ").", null, null,
						null, OffsetDateTime.now());
				SendResult result = this.sender.send(channel.url(), secret, payload);
				if ("SENT".equals(result.outcome())) {
					redirect.addFlashAttribute("notice",
							"Test notification delivered to '" + channel.label() + "' (" + result.detail() + ").");
				}
				else {
					redirect.addFlashAttribute("error", "Test notification to '" + channel.label() + "' failed: "
							+ result.outcome() + " — " + result.detail());
				}
			}
			catch (NotificationException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/dashboard";
		});
	}

	@PostMapping("/w/{slug}/notifications/channels/{id}/delete")
	public String delete(@PathVariable String slug, @PathVariable Long id, RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				this.channels.delete(workspace.id(), id);
				redirect.addFlashAttribute("notice",
						"Notification channel #" + id + " deleted (with its outbox rows).");
			}
			catch (NotificationException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/dashboard";
		});
	}

	private String withWorkspace(String slug, java.util.function.Function<Workspace, String> action) {
		return this.workspaces.findBySlug(slug).map(action).orElse("redirect:/");
	}

}
