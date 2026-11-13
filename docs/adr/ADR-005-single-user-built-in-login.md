# ADR-005 — One owner login with workspace authorization

- **Status:** Accepted; amended by ADR-013 for tenancy
- **Date:** 2026-11-13

## Context

Each operator runs their own installation with one login and several account sets. “One user” describes authentication; it must not mean one global namespace for content and credentials.

## Decision

- Use Spring Security form login with one env-configured owner (`APP_USER`, `APP_PASSWORD_HASH`, adaptive password hash). Missing configuration fails startup; no default credentials.
- The owner can access every workspace. Each request still declares a workspace, and services verify resource ownership. Background jobs and callbacks carry their own stored workspace context.
- Use HttpOnly, Secure, SameSite=Lax session cookies over production HTTPS, session rotation at login, logout invalidation, expiry, login throttling, and CSRF on browser mutations including HTMX.
- Omit long-lived remember-me from v1. Show persistent workspace identity and destination identity at publication confirmation.
- Explicit public-route exceptions: login and static assets; provider callbacks protected by one-time state; enabled Telegram webhooks protected by per-connection secret; temporary signed provider-import media. Management/Actuator stays on a separate internal/loopback listener.
- OAuth callback CSRF exception is narrow and compensated by state/PKCE where supported; webhook exceptions require their own authentication and replay handling. Neither becomes a general public account-management endpoint.
- Accept forwarded headers only from configured trusted proxies. Build redirects from the fixed configured origin, never arbitrary forwarded host input.

## Consequences

The owner does not need separate logins per event. Workspace isolation prevents accidental cross-brand references. Other people run separate installations; there is no shared-user, invitation, team-role, or SaaS registration roadmap. MFA is not part of the current login design. A proxy/VPN can add access control; it is not automatically a second authentication factor.

## Alternatives

A shared global account list is rejected. Shared multi-user role management is outside this product; workspaces still isolate one owner’s account sets. Relying solely on an external proxy would remove the selected built-in login and is not chosen.
