package com.patbaumgartner.socialcrossposter.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.patbaumgartner.socialcrossposter.content.PostException;
import com.patbaumgartner.socialcrossposter.scheduling.PublicationSchedule;
import com.patbaumgartner.socialcrossposter.scheduling.ScheduleException;
import com.patbaumgartner.socialcrossposter.scheduling.ScheduleService;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceException;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

/**
 * One-time schedule endpoints (UC-10, ADR-013, ADR-016): approve a schedule for a post's
 * current revision with an explicit local time + IANA timezone (DST gap/overlap resolved
 * explicitly by the owner), and cancel a pending schedule. All routes are
 * workspace-scoped and fail closed for foreign ids — errors are sanitized and never leak
 * another workspace's data.
 */
@Controller
public class ScheduleController {

	private final WorkspaceService workspaces;

	private final ScheduleService schedules;

	public ScheduleController(WorkspaceService workspaces, ScheduleService schedules) {
		this.workspaces = workspaces;
		this.schedules = schedules;
	}

	/**
	 * Approves and persists a one-time schedule (UC-10 steps 1-3): local date/time, IANA
	 * timezone (the form defaults to the workspace timezone), and an optional offset for
	 * a DST overlap. The notice shows the resolved UTC instant; errors (DST gap, overlap
	 * without an explicit choice, past time, blockers, foreign post) are shown as-is
	 * because they are sanitized (ADR-013).
	 */
	@PostMapping("/w/{slug}/posts/{id}/schedule")
	public String schedule(@PathVariable String slug, @PathVariable Long id, @RequestParam String localDateTime,
			@RequestParam String timezone, @RequestParam(required = false) String offset, RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				PublicationSchedule s = this.schedules.schedule(workspace.id(), id, localDateTime, timezone, offset);
				redirect.addFlashAttribute("notice", "Scheduled for " + s.localDateTime() + " " + s.timezone() + " = "
						+ s.scheduledAt() + " UTC (schedule #" + s.id() + ", PENDING). Dispatch is best-effort; remote "
						+ "post-create is allowed until 15 minutes after the scheduled instant " + "(ADR-016).");
			}
			catch (ScheduleException | PostException | WorkspaceException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/posts/" + id;
		});
	}

	/**
	 * Cancels a PENDING schedule (UC-10 alternative flow 1). A schedule that already
	 * created its publication is rejected — the publication stays in history.
	 */
	@PostMapping("/w/{slug}/schedules/{scheduleId}/cancel")
	public String cancel(@PathVariable String slug, @PathVariable Long scheduleId, RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				PublicationSchedule s = this.schedules.cancel(workspace.id(), scheduleId);
				redirect.addFlashAttribute("notice",
						"Schedule #" + scheduleId + " canceled — no publication was created.");
				return "redirect:/w/" + slug + "/posts/" + s.postId();
			}
			catch (ScheduleException | WorkspaceException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
				return "redirect:/w/" + slug + "/posts";
			}
		});
	}

	private String withWorkspace(String slug, java.util.function.Function<Workspace, String> action) {
		return this.workspaces.findBySlug(slug).map(action).orElse("redirect:/");
	}

}
