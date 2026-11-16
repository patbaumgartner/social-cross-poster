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
import com.patbaumgartner.socialcrossposter.notify.NotificationPayload;
import com.patbaumgartner.socialcrossposter.notify.SendResult;
import com.patbaumgartner.socialcrossposter.notify.SmtpSender;
import com.patbaumgartner.socialcrossposter.notify.WebhookSender;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

/**
 * External notification channel endpoints (UC-12 second + third slices, step 1, ADR-004,
 * ADR-007, ADR-013): add a webhook channel (SSRF-validated URL, server-generated sealed
 * HMAC secret) or an SMTP channel (SSRF-validated host/port/TLS, sealed password),
 * toggle, send a test notification, and delete. All routes are workspace-scoped and fail
 * closed for foreign ids; errors are sanitized and never leak another workspace's data.
 */
@Controller
public class NotificationController {

	private final WorkspaceService workspaces;

	private final NotificationChannelService channels;

	private final WebhookSender webhookSender;

	private final SmtpSender smtpSender;

	public NotificationController(WorkspaceService workspaces, NotificationChannelService channels,
			WebhookSender webhookSender, SmtpSender smtpSender) {
		this.workspaces = workspaces;
		this.channels = channels;
		this.webhookSender = webhookSender;
		this.smtpSender = smtpSender;
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

	/**
	 * Adds an SMTP channel. Host/port/TLS must pass the SSRF guard with the canonical
	 * mapping (25=NONE allow-listed only, 465=IMPLICIT_TLS, 587=STARTTLS); the password
	 * is stored only sealed (ADR-007) and never shown again.
	 */
	@PostMapping("/w/{slug}/notifications/smtp-channels")
	public String createSmtp(@PathVariable String slug, @RequestParam String label, @RequestParam String host,
			@RequestParam String port, @RequestParam String tlsMode, @RequestParam String from, @RequestParam String to,
			@RequestParam(required = false) String username, @RequestParam(required = false) String password,
			@RequestParam(required = false) String categories, RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				NotificationChannel channel = this.channels.createSmtp(workspace.id(), workspace.slug(), label, host,
						port, tlsMode, from, to, username, password, categories);
				redirect.addFlashAttribute("notice", "SMTP channel '" + channel.label() + "' added (" + channel.target()
						+ "). The password was stored encrypted (ADR-007) and is never shown again.");
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
	 * Sends one test notification to the channel without persisting an event (UC-12 step
	 * 4 contract; receiver dedup via the event id). The payload and the dashboard flash
	 * name the workspace and the exact recipient (postconditions: a test notification
	 * names the workspace and exact recipient before sending). Type-aware: webhook
	 * channels get a signed POST, SMTP channels a mail.
	 */
	@PostMapping("/w/{slug}/notifications/channels/{id}/test")
	public String test(@PathVariable String slug, @PathVariable Long id, RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				NotificationChannel channel = this.channels.requireById(workspace.id(), id);
				String credential = this.channels.openSecret(workspace.slug(), channel);
				String recipient = recipientOf(channel);
				NotificationPayload payload = new NotificationPayload(
						0L, "test", workspace.id(), workspace.label(), "TEST", "Test notification from workspace '"
								+ workspace.label() + "' (" + workspace.slug() + ") to " + recipient + ".",
						null, null, null, null, OffsetDateTime.now());
				SendResult result = channel.isSmtp() ? this.smtpSender.send(channel, credential, payload)
						: this.webhookSender.send(channel.url(), credential, payload);
				String where = "workspace '" + workspace.label() + "' (" + workspace.slug() + "), channel '"
						+ channel.label() + "' at " + recipient;
				if ("SENT".equals(result.outcome())) {
					redirect.addFlashAttribute("notice",
							"Test notification from " + where + " delivered (" + result.detail() + ").");
				}
				else {
					redirect.addFlashAttribute("error", "Test notification from " + where + " failed: "
							+ result.outcome() + " — " + result.detail());
				}
			}
			catch (NotificationException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/dashboard";
		});
	}

	/**
	 * The exact recipient of a channel: the webhook URL, or for SMTP the
	 * {@code host:port (tls)} target plus the parsed recipient addresses.
	 */
	private static String recipientOf(NotificationChannel channel) {
		if (channel.isSmtp()) {
			String recipients = String.join(", ", channel.recipients());
			return recipients.isEmpty() ? channel.target() : channel.target() + " to " + recipients;
		}
		return channel.target();
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
