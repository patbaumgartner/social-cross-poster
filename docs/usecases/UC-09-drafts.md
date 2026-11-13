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
