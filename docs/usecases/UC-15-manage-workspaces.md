# UC-15 — Create, switch, export, and archive account sets

- **Actor:** Installation owner
- **Trigger:** Workspace picker or Settings
- **Preconditions:** One owner logged into their own installation
- **Related decisions:** ADR-005, ADR-007, ADR-013

## Main flow

1. Create Private, socrates-ch, Voxxed Days Zurich, Voxxed Days Ticino, or another workspace with unique slug and display label.
2. Configure its timezone, languages, brand voice, event facts/links, AI settings/budget/credentials and notification channels.
3. Connect several local destinations, including multiple accounts of one platform and distinct LinkedIn profiles/company pages.
4. Switch explicitly. Each tab/composer/autosave/job stays in its original workspace; target choices never follow into another account set.
5. Export that workspace's content/history/private media and non-secret configuration into a protected archive with a manifest. Content export excludes credentials and cannot replace a full recovery backup.
6. Archive after listing pending schedules/jobs and active/UNKNOWN deliveries. Cancel unclaimed work, acknowledge unresolved effects, block new publication/AI, and retain history/private source dependencies.

## Alternative flows

- Duplicate a post only inside its source workspace (UC-11). There is no cross-workspace copy or campaign action.
- Do not move live accounts, media references or schedules between account sets.
- Archiving cannot undo in-flight remote writes; reconcile afterward in the original workspace.
- Reopen without automatically resuming schedules; review future work first.
- Hard purge remains excluded until retention/dependency behavior is implemented.
- Another person runs a separate installation; no invitations, memberships or additional user roles are needed.

## Postconditions and UI

Account sets remain logically isolated inside one owner's installation. Display workspace and actual destination audience throughout publication; Private is a set label rather than a platform privacy guarantee.

## Review log

- 2026-11-13 — Reviewed against ADR-005, ADR-007 and ADR-013. One-owner installations with workspace-local account sets, explicit switching, secret-free export and archive-with-reconciliation match ADR-005/ADR-007/ADR-013. Backup, restore-drill and retention behavior for exported/archived workspaces are specified in the [operations and recovery design](../operations/README.md). No behavior change.
