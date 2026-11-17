package com.patbaumgartner.socialcrossposter.web;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.patbaumgartner.socialcrossposter.platform.ExperimentalPlatforms;
import com.patbaumgartner.socialcrossposter.platform.PairingStart;
import com.patbaumgartner.socialcrossposter.platform.PlatformRegistry;
import com.patbaumgartner.socialcrossposter.platform.ProbeException;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

/**
 * UC-14 step 2 (ADR-010): the locally protected setup/pairing path for experimental
 * bridges (personal WhatsApp Status, Signal). The routes sit behind the owner login and
 * CSRF on the loopback-only deployment (ADR-005, ADR-009, ADR-012) — there is no public
 * pairing/admin UI, and sessions stay isolated per workspace and connection. Without a
 * wired bridge adapter the gate fails closed and no pairing wizard is fabricated (UC-14
 * alternative flow 1): the page only states the precise unavailability reason, and the
 * pair action never persists anything.
 */
@Controller
public class ExperimentController {

	private final WorkspaceService workspaces;

	private final PlatformRegistry platforms;

	private final ExperimentalPlatforms experimentals;

	public ExperimentController(WorkspaceService workspaces, PlatformRegistry platforms,
			ExperimentalPlatforms experimentals) {
		this.workspaces = workspaces;
		this.platforms = platforms;
		this.experimentals = experimentals;
	}

	@GetMapping("/w/{slug}/experiments")
	public String setup(@PathVariable String slug, Model model) {
		return this.workspaces.findBySlug(slug).map((workspace) -> {
			model.addAttribute("workspace", workspace);
			model.addAttribute("experimentalEnabled", this.experimentals.enabled());
			model.addAttribute("experimentalLabel", ExperimentalPlatforms.LABEL);
			model.addAttribute("targets",
					ExperimentalPlatforms.experimentalKeys()
						.stream()
						.map((key) -> new TargetView(key, this.experimentals.availability(key),
								this.experimentals.feasibilityRecord(key)))
						.toList());
			return "experiments";
		}).orElse("redirect:/");
	}

	@PostMapping("/w/{slug}/experiments/{platform}/pair")
	public String pair(@PathVariable String slug, @PathVariable String platform, RedirectAttributes redirect) {
		return this.workspaces.findBySlug(slug).map((workspace) -> {
			try {
				// UC-14 / ADR-010: excluded platforms are always rejected; the protected
				// pairing path exists only for experimental targets and only when the
				// opt-in flag + dated feasibility record + wired bridge adapter all hold.
				this.experimentals.requireConnectable(platform);
				if (!ExperimentalPlatforms.isExperimental(platform)) {
					throw new ProbeException(
							"Only experimental targets have a locally protected pairing path (ADR-010).");
				}
				PairingStart start = this.platforms.find(platform)
					.orElseThrow(() -> new ProbeException("Unsupported platform: " + platform))
					.startPairing();
				// Sanitized, credential-free instructions only; nothing is persisted in
				// this slice — the bridge session/credential storage lands with a real
				// bridge adapter, and until then the path stays closed.
				redirect.addFlashAttribute("notice",
						"Pairing started for " + start.platform() + ": " + start.instructions());
			}
			catch (ProbeException ex) {
				redirect.addFlashAttribute("error", ex.getMessage());
			}
			return "redirect:/w/" + slug + "/experiments";
		}).orElse("redirect:/");
	}

	/** Setup-page view of one experimental target. */
	public record TargetView(String platform, ExperimentalPlatforms.Availability availability, String record) {
	}

}
