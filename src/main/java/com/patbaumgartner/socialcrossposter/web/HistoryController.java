package com.patbaumgartner.socialcrossposter.web;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.patbaumgartner.socialcrossposter.accounts.ConnectionService;
import com.patbaumgartner.socialcrossposter.publish.Delivery;
import com.patbaumgartner.socialcrossposter.publish.DeliveryDetail;
import com.patbaumgartner.socialcrossposter.publish.DeliveryJob;
import com.patbaumgartner.socialcrossposter.publish.DeliveryJobRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryJobService;
import com.patbaumgartner.socialcrossposter.publish.HistoryFilter;
import com.patbaumgartner.socialcrossposter.publish.HistoryPage;
import com.patbaumgartner.socialcrossposter.publish.HistoryService;
import com.patbaumgartner.socialcrossposter.publish.PublicationDetail;
import com.patbaumgartner.socialcrossposter.publish.PublicationException;
import com.patbaumgartner.socialcrossposter.publish.PublicationService;
import com.patbaumgartner.socialcrossposter.publish.RemoteAction;
import com.patbaumgartner.socialcrossposter.publish.RemoteActionDetail;
import com.patbaumgartner.socialcrossposter.publish.RemoteActionService;
import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

/**
 * UC-06 first slice + UC-07 owner actions: {@code GET /w/{slug}/history} lists
 * publications with content summary, timestamps, aggregate state and per-destination
 * states, filterable by account, platform, state, text and date range with pagination
 * (UC-06 steps 1-2). {@code GET /w/{slug}/history/{id}} shows the approved revision and
 * the actual per-destination payloads, provider identity, external references, redacted
 * attempts, the durable job state (step 3) and the remote actions of each delivery (UC-08
 * step 5). {@code POST .../deliveries/{deliveryId}/retry} and {@code .../reconcile} are
 * the explicit UC-07 owner actions. {@code GET|POST .../deliveries/{deliveryId}/edit} and
 * {@code .../delete} are the UC-08 verified remote edit/delete actions: the edit form
 * opens the last confirmed payload, the delete page asks for explicit confirmation
 * showing destination and external identity (steps 2-3), and execution queues a separate
 * remote action that never rewrites the original delivery (step 4, ADR-015). Every route
 * carries the explicit workspace slug; unknown slugs or foreign ids fail closed
 * (ADR-013). Duplicate-into-draft lands with UC-11.
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

	private final DeliveryJobService jobs;

	private final DeliveryJobRepository jobRepository;

	private final PublicationService publications;

	private final RemoteActionService remoteActions;

	public HistoryController(WorkspaceService workspaces, HistoryService history, ConnectionService connections,
			DeliveryJobService jobs, DeliveryJobRepository jobRepository, PublicationService publications,
			RemoteActionService remoteActions) {
		this.workspaces = workspaces;
		this.history = history;
		this.connections = connections;
		this.jobs = jobs;
		this.jobRepository = jobRepository;
		this.publications = publications;
		this.remoteActions = remoteActions;
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
	 * Publication detail (UC-06 step 3 + UC-07 job state + UC-08 remote actions). A
	 * foreign/cross-workspace id fails closed back to the history list with an error
	 * (ADR-013) — no content is returned.
	 */
	@GetMapping("/w/{slug}/history/{id}")
	public String detail(@PathVariable String slug, @PathVariable Long id, Model model, RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				PublicationDetail detail = this.history.detail(workspace.id(), id);
				Map<Long, DeliveryJob> jobsByDelivery = this.jobRepository.findAllByWorkspaceId(workspace.id())
					.stream()
					.collect(Collectors.toMap(DeliveryJob::deliveryId, (j) -> j));
				Map<Long, List<RemoteActionDetail>> actionsByDelivery = this.remoteActions
					.actionsByDelivery(workspace.id(), id);
				Map<Long, Boolean> canEditByDelivery = new HashMap<>();
				Map<Long, Boolean> canDeleteByDelivery = new HashMap<>();
				for (DeliveryDetail dd : detail.deliveries()) {
					Delivery d = dd.delivery();
					boolean confirmed = PublicationService.D_SUCCESS.equals(d.state())
							&& StringUtils.hasText(d.externalRef());
					canEditByDelivery.put(d.id(),
							confirmed && this.remoteActions.supports(d.platform(), RemoteActionService.ACTION_EDIT));
					canDeleteByDelivery.put(d.id(),
							confirmed && this.remoteActions.supports(d.platform(), RemoteActionService.ACTION_DELETE));
				}
				model.addAttribute("workspace", workspace);
				model.addAttribute("detail", detail);
				model.addAttribute("jobsByDelivery", jobsByDelivery);
				model.addAttribute("retryableStates", PublicationService.RETRYABLE_DELIVERY_STATES);
				model.addAttribute("actionsByDelivery", actionsByDelivery);
				model.addAttribute("canEditByDelivery", canEditByDelivery);
				model.addAttribute("canDeleteByDelivery", canDeleteByDelivery);
				return "history-detail";
			}
			catch (PublicationException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
				return "redirect:/w/" + slug + "/history";
			}
		});
	}

	/**
	 * Owner retry action (UC-07 step 1): schedules a durable retry of one retryable
	 * delivery now (RETRY_WAIT + PENDING job). Guards — non-retryable state, exhausted
	 * attempt budget, live worker lease — surface as an error flash (ADR-013).
	 */
	@PostMapping("/w/{slug}/history/{publicationId}/deliveries/{deliveryId}/retry")
	public String retry(@PathVariable String slug, @PathVariable Long publicationId, @PathVariable Long deliveryId,
			RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				this.jobs.retryNow(workspace.id(), publicationId, deliveryId);
				redirect.addFlashAttribute("notice",
						"Retry scheduled for delivery #" + deliveryId + " — the worker picks it up shortly.");
			}
			catch (PublicationException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/history/" + publicationId;
		});
	}

	/**
	 * Owner reconcile action (UC-07 step 2): resolves an UNKNOWN/NEEDS_ATTENTION delivery
	 * through the platform's verified read-back (never a blind retry).
	 */
	@PostMapping("/w/{slug}/history/{publicationId}/deliveries/{deliveryId}/reconcile")
	public String reconcile(@PathVariable String slug, @PathVariable Long publicationId, @PathVariable Long deliveryId,
			RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				Delivery result = this.jobs.reconcileNow(workspace.id(), publicationId, deliveryId);
				redirect.addFlashAttribute("notice",
						"Reconciled delivery #" + deliveryId + " — outcome " + result.state() + ".");
			}
			catch (PublicationException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/history/" + publicationId;
		});
	}

	/**
	 * Remote edit form (UC-08 step 2): opens the last confirmed payload of a confirmed
	 * delivery for an action-specific replacement. Only reachable when the platform actor
	 * supports EDIT for this delivery; anything else fails closed to the detail.
	 */
	@GetMapping("/w/{slug}/history/{publicationId}/deliveries/{deliveryId}/edit")
	public String editForm(@PathVariable String slug, @PathVariable Long publicationId, @PathVariable Long deliveryId,
			Model model, RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				Delivery delivery = requireDeliveryOf(workspace, publicationId, deliveryId);
				if (!PublicationService.D_SUCCESS.equals(delivery.state())
						|| !StringUtils.hasText(delivery.externalRef())
						|| !this.remoteActions.supports(delivery.platform(), RemoteActionService.ACTION_EDIT)) {
					throw new PublicationException("Remote edit is not available for this delivery.");
				}
				model.addAttribute("workspace", workspace);
				model.addAttribute("publicationId", publicationId);
				model.addAttribute("delivery", delivery);
				model.addAttribute("maxTextLength", this.remoteActions.maxTextLength(delivery.platform()));
				return "delivery-edit";
			}
			catch (PublicationException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
				return "redirect:/w/" + slug + "/history/" + publicationId;
			}
		});
	}

	/**
	 * Owner remote edit action (UC-08 steps 2 + 4): validates the exact replacement
	 * against the platform text rules and executes a separate remote action
	 * synchronously. The original delivery is preserved (ADR-015).
	 */
	@PostMapping("/w/{slug}/history/{publicationId}/deliveries/{deliveryId}/edit")
	public String edit(@PathVariable String slug, @PathVariable Long publicationId, @PathVariable Long deliveryId,
			@RequestParam String text, RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				RemoteAction action = this.remoteActions.requestEdit(workspace.id(), workspace.slug(), publicationId,
						deliveryId, text);
				redirect.addFlashAttribute("notice", "Remote edit on delivery #" + deliveryId + " executed — outcome "
						+ action.state() + ". The original delivery is preserved.");
			}
			catch (PublicationException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/history/" + publicationId;
		});
	}

	/**
	 * Remote delete confirmation (UC-08 step 3): shows the destination and the external
	 * post identity and asks for explicit confirmation before any remote write.
	 */
	@GetMapping("/w/{slug}/history/{publicationId}/deliveries/{deliveryId}/delete")
	public String deleteForm(@PathVariable String slug, @PathVariable Long publicationId, @PathVariable Long deliveryId,
			Model model, RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				Delivery delivery = requireDeliveryOf(workspace, publicationId, deliveryId);
				if (!PublicationService.D_SUCCESS.equals(delivery.state())
						|| !StringUtils.hasText(delivery.externalRef())
						|| !this.remoteActions.supports(delivery.platform(), RemoteActionService.ACTION_DELETE)) {
					throw new PublicationException("Remote delete is not available for this delivery.");
				}
				model.addAttribute("workspace", workspace);
				model.addAttribute("publicationId", publicationId);
				model.addAttribute("delivery", delivery);
				return "delivery-delete";
			}
			catch (PublicationException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
				return "redirect:/w/" + slug + "/history/" + publicationId;
			}
		});
	}

	/**
	 * Owner remote delete action (UC-08 steps 3 + 4): executes a separate remote action
	 * synchronously; a provider rejection never changes the formerly SUCCESS delivery.
	 */
	@PostMapping("/w/{slug}/history/{publicationId}/deliveries/{deliveryId}/delete")
	public String delete(@PathVariable String slug, @PathVariable Long publicationId, @PathVariable Long deliveryId,
			RedirectAttributes redirect) {
		return withWorkspace(slug, (workspace) -> {
			try {
				RemoteAction action = this.remoteActions.requestDelete(workspace.id(), workspace.slug(), publicationId,
						deliveryId);
				redirect.addFlashAttribute("notice", "Remote delete on delivery #" + deliveryId + " executed — outcome "
						+ action.state() + ". The original delivery is preserved.");
			}
			catch (PublicationException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/history/" + publicationId;
		});
	}

	private Delivery requireDeliveryOf(Workspace workspace, Long publicationId, Long deliveryId) {
		Delivery delivery = this.publications.requireDelivery(workspace.id(), deliveryId);
		if (!delivery.publicationId().equals(publicationId)) {
			throw new PublicationException(
					"Delivery #" + deliveryId + " does not belong to publication #" + publicationId + ".");
		}
		return delivery;
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
