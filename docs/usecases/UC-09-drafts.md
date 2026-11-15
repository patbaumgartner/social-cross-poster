# UC-09 — Save, version, and restore drafts

- **Actor:** Owner
- **Trigger:** Save, autosave, or reopen a draft
- **Preconditions:** Logged in; explicit workspace
- **Related decisions:** ADR-003, ADR-004, ADR-013

## Main flow

1. Save text, layered overrides, local destinations, media references/order/alt text, and language as an immutable revision under a workspace draft.
2. Autosave after a short configurable idle delay; display saving/saved/error status.
3. List drafts only in the owning workspace; reopen with the same revision and destination choices.
4. Restore an earlier revision or accept an AI suggestion by creating a new revision.
5. Publish/schedule requires approval of the chosen revision; saving alone calls no publishing API.

## Alternative flows

- Two tabs: optimistic version conflict preserves both versions and offers comparison; never last-write-wins.
- Network/save error: retain unsaved editor content and explain recovery; switching workspaces must not autosave into the new workspace.
- Draft deletion/archive does not delete approved revisions referenced by history or schedules. Clean unreferenced media only after retention/grant checks.
- Unapproved autosave revisions become eligible for pruning after 30 days. Preserve current draft revisions and dependencies of approvals, schedules, independent copies, active AI requests, deliveries/recovery and grants. Keep approved history and required source media without automatic expiry; this does not authorize whole-draft deletion or workspace purge ([ADR-016](../adr/ADR-016-operational-and-editorial-policies.md)).
- A removed account or contact requires explicit remapping; never substitute another same-platform identity.
- Duplicate only inside the original workspace (UC-11); copied content becomes an independent draft with no approval, schedule or remote identifiers.

## Postconditions and UI

Drafts remain private and recoverable. Show revision timestamps, workspace, and AI acceptance provenance. Unaccepted AI suggestions are separate from draft content.

## Review log

- 2026-11-13 — Reviewed against ADR-003, ADR-004, ADR-013 and ADR-016. Immutable revisions, optimistic conflict handling, workspace-scoped listing and duplicate-only-in-workspace match ADR-003/ADR-004/ADR-013; the 30-day pruning and retention/dependency rules are owned by ADR-016 (already linked inline). No behavior change.
- 2026-11-15 — First slice implemented (main-flow steps 1, 3 and 4 plus alternative flow 1, building on the UC-04 slice): every save still appends a new immutable revision, and POST `/w/{slug}/posts/{id}/restore/{revisionId}` now restores an earlier revision as a NEW revision — default text, selected destinations and layered overrides are copied into the next revision, the current-revision pointer moves there, and nothing is deleted or modified, so revisions referenced by publications stay intact (step 4, ADR-015/ADR-016). The post form carries the post's optimistic `expectedVersion`; a stale version saves nothing and reports a conflict naming the expected/current version and current revision number — never last-write-wins (alternative flow 1). Draft listing stays workspace-scoped and foreign post/revision ids fail closed (step 3, ADR-013); the post page lists all revisions with “Restore as new revision” buttons on non-current ones. No new tables for this slice (Flyway was V1–V7 until the UC-10 slice added V8). Deviations closed by later slices: no autosave/idle saving status (step 2), no media references/order/alt text or language fields in a revision (step 1, UC-13), no draft archive/delete endpoint (alternative flow 3), no 30-day pruning of unapproved revisions (alternative flow 4, ADR-016/UC-16), no comparison view for conflicted tabs beyond the revision list, and duplicate-only-in-workspace stays with UC-11. 170/170 tests green.
