# UC-02 — Connect and manage multiple platform accounts

- **Actor:** Owner
- **Trigger:** Open Accounts in the active workspace
- **Preconditions:** Logged in; workspace active; integration has passed its verification gate
- **Related decisions:** ADR-001, ADR-006, ADR-007, ADR-011, ADR-013

## Main flow

1. List only this workspace's destinations, showing platform, kind, provider identity, host/instance, connection, health timestamp, and enabled state.
2. Add a connection through a provider-supported OAuth or pasted-credential flow. Offer another connection even when the workspace already has that platform.
3. Validate provider identity/rights through a documented read-only probe; encrypt secrets. Health checks do not create test posts.
4. Select explicit destinations exposed by that connection, such as a LinkedIn organization page or Telegram chat. Two pages/channels are two independently selectable destinations.
5. Label and enable destinations after validation. Display them in the workspace composer.
6. Reconnect, rotate secrets, check health, or disable a destination/connection. A connection-level change lists all dependent destinations.

## Alternative flows

- Failed validation preserves entered non-secret configuration as disabled; return a sanitized error.
- Expired credentials refresh only where granted; otherwise reconnect. A changed provider principal creates a new connection rather than retargeting scheduled content.
- OAuth binds to the initiating workspace/session; a tab switch cannot change where credentials land.
- Duplicate exact destination in the same workspace opens the existing entry. Platform alone is never a uniqueness key.
- Deletion archives the destination if history references it, cancels unclaimed dependent work, and reports in-flight uncertainty. A replacement account requires fresh publication approval.
- Telegram bot webhook ownership conflicts are rejected with an explanation; several chats can share one bot within a workspace.
- Experimental personal WhatsApp Status/Signal use UC-14 only after feasibility. **WhatsApp Business is not supported** and has no onboarding option.

## Postconditions and UI

Connections and destinations remain workspace-scoped; secrets never return to the browser after save. Show account labels plus stable identity/instance so similarly named brands are distinguishable. AI provider credentials belong to workspace AI settings, not social destinations.

## Review log

- 2026-11-13 — Reviewed against ADR-001, ADR-006, ADR-007, ADR-011 and ADR-013. Connection/destination split, OAuth workspace binding and encrypted secrets match ADR-006/ADR-007; the Telegram webhook-ownership conflict rule is owned by ADR-011 and was added to the related decisions. No behavior change.
