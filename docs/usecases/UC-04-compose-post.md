# UC-04 — Compose and review destination previews

- **Actor:** Owner
- **Trigger:** New post or open draft
- **Preconditions:** Logged in; active workspace; connected accounts are not required to save a draft
- **Related decisions:** ADR-003, ADR-004, ADR-008, ADR-014, ADR-015, ADR-016

## Main flow

1. Show workspace name, language/brand defaults, default text editor, and workspace-local mention autocomplete.
2. Upload up to four still JPEG/PNG images. Validate bytes, dimensions, decode limits, and size; add meaningful alt text and order. Keep originals private and strip GPS/camera metadata from outgoing derivatives before preview/approval.
3. Select individual destinations from this workspace. Multiple accounts of the same platform remain separate, labeled with identity and host/page/chat.
4. Optionally invoke AI writing/review/correction/translation (UC-16), compare output, and explicitly accept changes into a new revision.
5. Apply platform overrides and then individual destination overrides. Absent fields inherit; explicit empty values clear the field.
6. Render each destination's final text, counter/rules, media derivatives/order/alt text, mention fallbacks, required capabilities, and identity.
7. Resolve blocking errors; explicitly accept any proposed lossy conversion. Continue to final publish confirmation (UC-05), save (UC-09), or schedule approval (UC-10).

## Alternative flows

- No destinations/accounts: writing, AI, and saving work; publishing/scheduling requires valid destinations.
- Over-limit text blocks that destination; offer manual editing or AI shortening, never silent truncation.
- Unsupported media/count or missing alt text blocks approval; exclusion/conversion must be visible and accepted.
- Some API cannot transmit alt text: show that limitation, retain meaningful local descriptions, and require explicit destination-specific acknowledgement before approval. Do not claim accessibility metadata was delivered or silently add it to visible text.
- Capability/identity/content changes invalidate approval. A workspace switch saves/leaves the original draft without moving it.
- An AI response based on an older revision requires fresh comparison; it cannot overwrite newer edits.

## Postconditions and UI

The draft has a revision and explicit validation results, not a remote post. Previews describe payload content, not pixel-perfect platform rendering. Keyboard shortcuts open confirmation; they never bypass it. Use readable diffs, keyboard controls, and status labels beyond color.

## Review log

- 2026-11-13 — Reviewed against ADR-003, ADR-004, ADR-008, ADR-014, ADR-015 and ADR-016. Layered override precedence and blocking (never silent truncation/media loss) match ADR-003; media validation, private originals and GPS/camera metadata stripping on outgoing derivatives are owned by ADR-008 and ADR-016, both added to the related decisions; AI acceptance creates a new revision per ADR-014; approval freezes payloads per ADR-015. No behavior change.
- 2026-11-14 — **Implemented (first slice).** Flyway V4 adds the workspace-scoped `post` container (state DRAFT/READY/ARCHIVED, optimistic `version`, `current_revision_id` pointer), immutable `post_revision` (UNIQUE post+revision_no, default text), `revision_destination` (UNIQUE revision+account) and `revision_override` (target `platform:<key>` | `destination:<id>`, NULL text = absent/inherit, `''` = explicit clear, UNIQUE revision+target). `GET/POST /w/{slug}/compose`, `GET /w/{slug}/posts`, `GET/POST /w/{slug}/posts/{id}` and the HTMX fragment `GET /w/{slug}/posts/{id}/preview` render per-destination previews: override precedence destination → platform → default, mention resolution through the same `MentionResolver` used for execution, a counter against the platform's text rules (telegram 4096, conservative 4096 default for unwired platforms), and over-limit or explicit-clear as blocking issues — the preview never truncates. Every save creates a new immutable revision (next `revision_no`) and moves the current pointer; destination account ids and override targets are validated against the workspace and foreign ids fail closed (ADR-013). The composer reuses the UC-03 `@`-autocomplete (`data-mention-composer` + `/contacts/search`). Deviations from the plan text, to be revisited: media upload/GPS-stripping lands with UC-13, AI assistance with UC-16, autosave UI with UC-09, publish/schedule with UC-05/UC-10 — this slice has a manual “Refresh preview” button only (the preview fragment accepts an unsaved `text` parameter), and the override-editing UI is not wired yet (overrides are validated and stored in the service layer but not yet editable from the form). Verified by `PostFlowTest` (10 tests) + `PreviewServiceTest` (8 tests), 64/64 green.
