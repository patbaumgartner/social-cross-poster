# Use cases

These are planned behaviors; **UC-01 (login + workspace picker), the first UC-02 slice (telegram/pasted connections, encrypted secrets, manual destinations) and UC-03 (workspace contacts, handles, neutral mention tokens, mention previews) are implemented as of 2026-11-14** — see their review logs. All tenant operations require an explicit workspace, including jobs and callbacks. One owner login can manage every v1 workspace. [Plan.md](../../Plan.md) defines delivery phases; [ADR-015](../adr/ADR-015-publication-lifecycle-and-recovery.md) owns delivery states. All 16 use cases were cross-checked against the ADRs on 2026-11-13; each document records the outcome in its review log.

| Use case | Behavior |
|---|---|
| [UC-01](UC-01-login.md) | Log in and enter a workspace |
| [UC-02](UC-02-manage-accounts.md) | Manage multiple platform connections and destinations |
| [UC-03](UC-03-manage-contacts.md) | Workspace contacts and mention identities |
| [UC-04](UC-04-compose-post.md) | Compose and review destination previews |
| [UC-05](UC-05-post-now.md) | Approve and publish now |
| [UC-06](UC-06-view-history.md) | Workspace publication history |
| [UC-07](UC-07-retry-failed-target.md) | Reconcile or safely retry a delivery |
| [UC-08](UC-08-delete-edit-published-post.md) | Verified remote edit/delete actions |
| [UC-09](UC-09-drafts.md) | Versioned drafts and autosave |
| [UC-10](UC-10-schedule-post.md) | Approve one-time schedules |
| [UC-11](UC-11-duplicate-and-schedule-posts.md) | Duplicate a post into an independently scheduled instance |
| [UC-12](UC-12-failure-notifications.md) | Workspace failure and attention notifications |
| [UC-13](UC-13-upload-media-to-platform.md) | Upload approved images to platform-owned media |
| [UC-14](UC-14-experimental-targets.md) | Personal Status and Signal experiments |
| [UC-15](UC-15-manage-workspaces.md) | Workspace lifecycle and local export |
| [UC-16](UC-16-ai-content-assistance.md) | AI writing, review, correction and translation |

Editorial flow: UC-01 → UC-15/02 → UC-09/04 → optional UC-16 → UC-05 or UC-10 → UC-06/07/08. UC-11 creates another independent post in the same workspace. Media and notification flows support publication; UC-14 is optional. Every other person runs a separate installation.
