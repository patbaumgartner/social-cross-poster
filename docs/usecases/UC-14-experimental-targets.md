# UC-14 — Evaluate and opt into personal Status / Signal experiments

- **Actor:** Owner
- **Trigger:** Enable an experimental integration after its feasibility gate
- **Preconditions:** ADR-010 probe passed; current setup guide and compatible pinned bridge available
- **Related decisions:** ADR-010, ADR-013, ADR-015

## Main flow

1. Review the exact operation/audience proven by the feasibility record and acknowledge its unofficial limitations.
2. Enable the private optional bridge profile and pair a workspace-specific connection/session through a locally protected setup path.
3. Validate identity, audience, supported media, acknowledgement/reconciliation, and architecture support.
4. Add only the verified destination kind to that workspace, with a permanent experimental label.
5. Compose, review, approve, and publish through the normal durable pipeline. Several sessions/accounts require separate protected routing/storage.

## Alternative flows

- No proven personal WhatsApp Status bridge: show unavailable; do not fabricate an endpoint or ship a misleading wizard.
- **WhatsApp Business is excluded:** never offer Cloud API, Business credentials, OAuth, or Business messaging as a substitute.
- Signal message and story targets are separate capabilities; no story-to-message fallback.
- Bridge unlinked/down or rights unclear: block dependent delivery and notify; do not affect healthy official targets.
- Ambiguous bridge acknowledgement is UNKNOWN and follows UC-07.
- Upstream restriction or unsupported operation produces a sanitized error; no workaround attempts or audience changes.

## Postconditions and UI

No experiment is a core-release requirement. Pairing/admin interfaces remain private and credentials/session files isolated per workspace. Experimental opt-in does not imply guaranteed delivery, account safety, or official API support.

## Review log

- 2026-11-13 — Reviewed against ADR-010, ADR-013 and ADR-015. Feasibility-gated opt-in, permanent experimental label, WhatsApp Business exclusion, separate Signal capabilities and UNKNOWN acknowledgements match ADR-010's feasibility policy; workspace isolation matches ADR-013; delivery/reconciliation matches ADR-015. No behavior change.
- 2026-11-17 — First slice implemented (steps 1 and 4's guardrails, alternative flows 1–2): no schema change (Flyway stays V1–V15). `ExperimentalPlatforms` is the ADR-010 gate: `app.experimental.enabled` (`APP_EXPERIMENTAL_ENABLED`, default `false`) is the opt-in profile flag; every `whatsapp*business*` platform key is permanently rejected (alternative flow 2 — never offered, never a fallback); `whatsapp-status` and `signal` are the only experimental candidates and stay `unavailable` because `FEASIBILITY_PASSED` is empty — no bridge has passed the feasibility probe, so no endpoint, pairing wizard or Status/story posting is fabricated (alternative flow 1). `ConnectionService.connect`/`addDestination` run the gate before any probe and fail closed without persisting; the accounts page lists experimental targets with the permanent “experimental / unofficial” label only when the flag is on, and a wired probe for an unproven experimental platform is never offered in the connect form. 340/340 tests green. Deviations closed by later slices: no feasibility record/bridge selection, no protected pairing/setup path (step 2), no verified destination kind with experimental label (step 4), no Signal message-vs-story capability split (alternative flow 3), no bridge-health blocking of dependent deliveries (alternative flow 4).
- 2026-11-17 — Second slice implemented (step 4 kind gate + alternative flow 3): no schema change. `ExperimentalPlatforms.DESTINATION_KINDS` fixes the verified kinds per experimental target — `status` for whatsapp-status, `message` for signal; Signal `story` is a separate capability that has not passed its probe, so `requireVerifiedKind` rejects it with an explicit "never sent as a fallback" message instead of substituting a chat message. `ConnectionService.addDestination` now calls `requireDestinationKind` (connect gate + kind gate) and fails closed without saving; the accounts page lists the candidate kinds per target next to the permanent label. 345/345 tests green. Deviations closed by later slices: no feasibility record/bridge selection, no protected pairing/setup path (step 2), no verified destination kind actually addable yet (step 4 — the gate stays closed), no bridge-health blocking of dependent deliveries (alternative flow 4).
- 2026-11-17 — Third slice implemented (step 1's feasibility record made real, still fail-closed): no schema change. `ExperimentalProperties` gains `app.experimental.feasibility` (platform key → dated ADR-010 record, set via e.g. `APP_EXPERIMENTAL_FEASIBILITY_SIGNAL`; empty by default). `ExperimentalPlatforms` validates every record at startup — excluded (`whatsapp*business*`), non-experimental or blank entries throw and fail the boot closed — and normalizes keys case-insensitively. A target now needs three things to be available: opt-in flag on, record on file, and a bridge adapter (`PlatformProbe` bean) wired in the build (`bridgeWired`); otherwise `requireConnectable` throws a precise sanitized reason (disabled / no feasibility record / no bridge adapter) and `ConnectionService.connect`/`addDestination` fail closed before any probe or save. Default config still opens nothing: no record, no adapter, so whatsapp-status/signal stay unavailable and no endpoint is fabricated. 351/351 tests green. Deviations closed by later slices: no protected pairing/setup path (step 2), no verified destination kind actually addable yet (step 4 — the gate stays closed), no bridge-health blocking of dependent deliveries (alternative flow 4).
- 2026-11-17 — Fourth slice implemented (alternative flow 4 + the permanent label on history views): no schema change. `PublicationService.doExecute` blocks any delivery whose connection rides an experimental platform (`whatsapp-status`/`signal`) while that bridge's health is not `OK`: a redacted `authorize`/`BLOCKED` attempt naming platform and health state is recorded and the delivery becomes `NEEDS_REVIEW` before any credential is decrypted — no remote write happens, and healthy official targets are unaffected (pinned by tests). `HistoryController` passes the experimental keys and the permanent label to the history list and detail, and both templates render “experimental / unofficial” next to experimental deliveries only. 355/355 tests green. Deviations closed by later slices: no protected pairing/setup path (step 2), no verified destination kind actually addable yet (step 4 — the gate stays closed).
