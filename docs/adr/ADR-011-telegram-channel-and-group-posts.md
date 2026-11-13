# ADR-011 — Telegram channel and group announcements

- **Status:** Accepted as design; implementation capabilities must be verified
- **Date:** 2026-11-13

## Context

The application needs independently selectable Telegram announcement destinations, including several channels/groups of the same platform in one workspace.

## Decision

- Use the official [Telegram Bot API](https://core.telegram.org/bots/api) for channel/group posts only.
- A workspace-scoped bot connection supplies credentials; each configured chat is a separate destination with verified identity and bot permissions.
- Support multiple bots and multiple chats in a workspace. Resolve each destination explicitly; never use a shared “current chat”.
- Verify message/photo/album methods, caption-versus-message limits, media/alt-text limitations, and edit/delete rights/windows in the integration record.
- If consuming updates, one connection owns one webhook/update stream. Register through `setWebhook` with an opaque per-connection route key and secret-token validation. Deduplicate `update_id` and persist required updates before acknowledging.
- Bind webhook routing to the stored workspace/connection. Reject registering the same bot connection in another workspace if it would steal upstream update routing; several chats may share it within one workspace.
- Read-only identity/rights probes do not send test messages. Missing permissions disable only dependent destinations.
- Apply normal workspace authorization, approval, durable delivery, and reconciliation rules.

## Consequences

Telegram announcements belong to the initial vertical slice. Multiple channels can share credentials without becoming one publishing target. API-specific restrictions remain capability checks, not generic guarantees.

## Alternatives

Unofficial user sessions, implicit audience substitution, and one installation-wide bot/chat are rejected.
