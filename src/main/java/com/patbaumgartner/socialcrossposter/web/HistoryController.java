package com.patbaumgartner.socialcrossposter.web;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.patbaumgartner.socialcrossposter.accounts.ConnectionService;
import com.patbaumgartner.socialcrossposter.publish.HistoryFilter;
import com.patbaumgartner.socialcrossposter.publish.HistoryPage;
import com.patbaumgartner.socialcrossposter.publish.HistoryService;
import com.patbaumgartner.socialcrossposter.publish.PublicationDetail;
import com.patbaumgartner.socialcrossposter.publish.PublicationException;
import com.patbaumgartner.socialcrossposter.publish.PublicationService;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

/**
 * UC-06 first slice: read-only workspace publication history. {@code GET
 * /w/{slug}/history} lists publications with content summary, timestamps, aggregate state
 * and per-destination states, filterable by account, platform, state, text and date range
 * with pagination (UC-06 steps 1-2). {@code GET /w/{slug}/history/{id}} shows the
 * approved revision and the actual per-destination payloads, provider identity, external
 * references and redacted attempts (step 3). Every route carries the explicit workspace
 * slug; unknown slugs or foreign publication ids fail closed (ADR-013). Browsing is
 * read-only — retry/edit/delete are separate explicit actions (UC-07/UC-08),
 * duplicate-into-draft lands with UC-11.
 */
@Controller
public class HistoryController {

	private static final List<String> STATES = List.of(PublicationService.P_IN_PROGRESS,
			PublicationService.P_NEEDS_ATTENTION, PublicationService.P_NEEDS_REVIEW, PublicationService.P_PUBLISHED,
			PublicationService.P_PARTIAL, PublicationService.P_CANCELED, PublicationService.P_SKIPPED,
			PublicationService.P_FAILED);

	private final WorkspaceService workspaces;

	private final HistoryService history;

	private final ConnectionService connections;

	public HistoryController(WorkspaceService workspaces, HistoryService history, ConnectionService connections) {
		this.workspaces = workspaces;
		this.history = history;
		this.connections = connections;
	}

	/** History list with filters + pagination (UC-06 steps 1-2). */
	@GetMapping("/w/{slug}/history")
	public String list(@PathVariable String slug, @RequestParam(required = false) Long account,
			@RequestParam(required = false) String platform, @RequestParam(required = false) String state,
			@RequestParam(required = false) String q, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size, Model model) {
		return this.workspaces.findBySlug(slug).map((workspace) -> {
			HistoryFilter filter = new HistoryFilter(account, text(platform), text(state), text(q), parseStart(from),
					parseEnd(to));
			HistoryPage result = this.history.list(workspace.id(), filter, page, size);
			model.addAttribute("workspace", workspace);
			model.addAttribute("page", result);
			model.addAttribute("accounts", this.connections.listDestinations(workspace.id()));
			model.addAttribute("platforms", this.history.platformsOf(workspace.id()));
			model.addAttribute("states", STATES);
			model.addAttribute("account", account);
			model.addAttribute("platform", platform);
			model.addAttribute("state", state);
			model.addAttribute("q", q);
			model.addAttribute("from", from);
			model.addAttribute("to", to);
			return "history";
		}).orElse("redirect:/");
	}

	/**
	 * Publication detail (UC-06 step 3). A foreign/cross-workspace id fails closed back
	 * to the history list with an error (ADR-013) — no content is returned.
	 */
	@GetMapping("/w/{slug}/history/{id}")
	public String detail(@PathVariable String slug, @PathVariable Long id, Model model, RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				PublicationDetail detail = this.history.detail(workspace.id(), id);
				model.addAttribute("workspace", workspace);
				model.addAttribute("detail", detail);
				return "history-detail";
			}
			catch (PublicationException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
				return "redirect:/w/" + slug + "/history";
			}
		});
	}

	private static String text(String value) {
		return StringUtils.hasText(value) ? value : null;
	}

	private static OffsetDateTime parseStart(String value) {
		try {
			return StringUtils.hasText(value) ? LocalDate.parse(value).atStartOfDay().atOffset(ZoneOffset.UTC) : null;
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	private static OffsetDateTime parseEnd(String value) {
		try {
			return StringUtils.hasText(value) ? LocalDate.parse(value).atTime(LocalTime.MAX).atOffset(ZoneOffset.UTC)
					: null;
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	private String withWorkspace(String slug, Function<Workspace, String> action) {
		return this.workspaces.findBySlug(slug).map(action).orElse("redirect:/");
	}

}
