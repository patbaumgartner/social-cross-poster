# ADR-013 — One owner per installation; workspaces isolate account sets

- **Status:** Accepted; clarified by owner
- **Date:** 2026-11-13
- **Amends:** ADR-004 and ADR-005

## Context

The owner operates Private, socrates-ch, Voxxed Days Zurich, Voxxed Days Ticino, and other account sets. Other people run their own installations. Posting stays within one account set, even when several accounts have the same platform type.

## Decision

- Each installation has one owner login. Shared installations, memberships, invitations, team roles and SaaS registration are outside the product scope.
- A workspace is the logical tenant/account-set boundary within that installation. The owner can open every workspace; operations still verify resource ownership.
- Scope records, media paths, cache/search keys, secrets, AI settings/usage, notifications, callbacks and jobs to an explicit workspace. Missing context fails closed; worker threads cannot inherit another job's context.
- Enforce service/repository ownership and composite workspace foreign keys.
- One connection represents a provider authorization/principal; one account represents a selectable profile/page/chat/audience. Several connections and destinations of the same platform may coexist.
- Multiple destinations can share a connection inside a workspace. No implicit connection/credential sharing across workspaces; detect upstream bot/webhook/session conflicts rather than stealing routing.
- A post selects only destinations from its owning workspace. “Select all” is workspace-local. No cross-workspace publishing, content-copy, or campaign feature.
- Switching workspace never retargets an existing composer, autosave, OAuth flow, AI request, schedule or delivery.
- Copying a post creates a fresh draft **inside the original workspace** with no approval, schedule, external references or delivery outcomes. See UC-11.
- Workspace defaults include label/color, timezone, languages, brand voice, protected event facts/links, AI configuration/budget, notifications and publishing defaults.
- Archive cancels unclaimed work and requires acknowledgement of active/UNKNOWN deliveries; preserve history and private source dependencies. Restore/reopen never resumes schedules automatically.
- The label Private identifies an account set; actual platform audience/visibility is separately previewed and validated.

## Consequences

Tenancy prevents accidental mixing inside an owner-controlled installation. It is not a shared-customer hosting model or a process/database isolation promise. A compromised host/admin can access that installation's workspaces.

## Acceptance examples

Two Mastodon accounts in Private can receive different texts. A Zurich post cannot select Ticino or Private destinations/media. A LinkedIn company page remains a distinct destination from a personal profile. A callback/worker uses its persisted original workspace after tab switches/restarts.
