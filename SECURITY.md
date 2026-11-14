# Security policy

## Reporting a vulnerability

Please **do not** open a public issue for security vulnerabilities.

Report suspected vulnerabilities privately to `contact@patbaumgartner.com`
with a description of the issue, the affected component, and reproduction
steps if available. You will receive an acknowledgement within 72 hours.

If the report is confirmed, a fix will be developed and released, the reporter
will be credited (unless they prefer otherwise), and a GitHub Security Advisory
will be published for the affected versions.

## Scope

This repository contains the design documentation plus the P0 scaffolding,
[UC-01 (login)](docs/usecases/UC-01-login.md), the first [UC-02
slice](docs/usecases/UC-02-manage-accounts.md) (encrypted provider secrets) and
[UC-03 (contacts)](docs/usecases/UC-03-manage-contacts.md) (workspace contacts,
handles, mention tokens) implemented on 2026-11-14 — the application exists and
handles the owner password hash, AES-256-GCM-sealed provider secrets, and
workspace-local contact data (photos stored as `data:` URIs only, never fetched
from external URLs; private notes never sent to providers or AI by default), but
there is no released application or published deployment image yet. Security
requirements for the application are specified in:

- [ADR-005 — one owner login with workspace authorization](docs/adr/ADR-005-single-user-built-in-login.md) (authentication, session, CSRF, public-route contracts)
- [ADR-007 — encrypted workspace secrets with externally supplied keys](docs/adr/ADR-007-secrets-in-env-on-volume.md) (secret storage, redaction, key rotation)
- [ADR-008 — HTTPS origin, private sources, platform-hosted images](docs/adr/ADR-008-app-origin-and-platform-media.md) (origin, import grants, SSRF surface)
- [arc42 §8 — cross-cutting concepts](docs/arc42/08-cross-cutting-concepts.md) (SSRF, logging, redaction)

Once code lands, this policy covers the application, its Compose deployment, and
its CI/CD pipelines. The reference deployment (Synology DS918+, Docker Engine
24.0.2, Compose 2.20.1, Traefik) is described in
[docs/operations/README.md](docs/operations/README.md); host hardening of the
NAS itself is the operator's responsibility.

## Supported versions

| Version | Supported |
|---|---|
| Documentation baseline (current `main`) | ✅ |
| Any released application version | See GitHub releases / security advisories |

No application release exists yet, so only the current `main` baseline
(scaffolding + UC-01 + UC-02 first slice + UC-03) is maintained; released
application versions will be listed here once published.

## Security-relevant configuration

When the application exists, the following must never be committed or logged
(see ADR-007 and arc42 §8):

- Provider OAuth tokens, bot tokens, pasted API credentials
- AI provider API keys, SMTP passwords, webhook secrets
- Encryption/signing keys and the owner password hash
- Signed temporary media-grant URLs and provider response bodies

If you believe a secret was committed to this repository, rotate it immediately
at the provider, then contact the maintainer privately as above.
