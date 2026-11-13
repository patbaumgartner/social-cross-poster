# Integration catalog and verification policy

**Design inventory, reviewed 2026-11-13. No integration is implemented or certified here.** “Official” describes the upstream API, not this app's support or guaranteed access. The [plan](../../Plan.md) defines release gates.

## Candidate integrations

| Platform / destination kind | Planned approach | Delivery / constraints to verify |
|---|---|---|
| Bluesky | AT Protocol, app-password or supported OAuth | P1; binary images, rich-text facets/DID mentions, current limits |
| Mastodon | Instance REST API and instance-specific authorization | P1; instance capabilities; account identity includes instance |
| Telegram channel/group | Bot API, bot connection plus separate chat destinations | P1; message/caption/album limits, bot rights, verified edit/delete |
| LinkedIn personal | Posts API with approved member permissions | P1 required; member author identity/access, direct image upload and posting |
| LinkedIn organization page | Posts API and permitted organization discovery | P1 required; app approval/page role, distinct page authors and direct image uploads; several pages can share one local connection |
| X | User-context posting and current media upload API | P3; developer access/cost, weighted length, quotas, verified operations |
| Instagram | Official publishing API for eligible accounts | P3; login product, scopes/formats/captions; verify platform import and independence from temporary sources |
| Threads | Official publishing API | P3; platform import from temporary URLs, readiness/source expiry, length/quota rules and token lifecycle |
| Facebook Page | Page API after permissions verification | Optional P4; explicit page identity, app access, media rules |
| Personal WhatsApp Status | Unofficial bridge not yet selected | Optional P4 feasibility only; no promised REST contract or Status support |
| Signal | Unofficial signal-cli-based bridge | Optional P4; messages and stories are separate capabilities |

**WhatsApp Business is excluded:** no Cloud API, Business OAuth/onboarding, Business messaging, or Business credential type. No audience-substitution or bulk messaging workflow.

Initial-release support must include both LinkedIn destination kinds alongside Bluesky, Mastodon and Telegram. Every publication stays inside its owning workspace/account set.

Published images are stored by the platform. Prefer direct uploads; the owner permits temporary import URLs only. Integration tests must revoke the import source after confirmed ingestion and verify that the platform post/image still works. No permanent source hosting is offered. See [ADR-008](../adr/ADR-008-app-origin-and-platform-media.md).

## Primary references and limits

### LinkedIn onboarding status

The owner confirms administering LinkedIn pages and nominates [42talents](https://www.linkedin.com/company/42talents/) as the organization/page for app onboarding, but has no developer apps or API access configured. Page administration and nomination are not evidence of legal-organization eligibility, super-admin app verification, app approval or granted API scopes. Both personal and company-page publishing remain required and unverified.

For personal publishing, investigate the [Share on LinkedIn](https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/share-on-linkedin) product. For company-page access, the current [Community Management review requirements](https://learn.microsoft.com/en-us/linkedin/marketing/community-management-app-review) require a registered legal organization/commercial use case, verified business details/email, website/privacy policy and verification by the associated page's super admin. Confirm the legal entity, business email, website/privacy policy and required verification for the nominated 42talents application during guided setup; page-admin confirmation alone does not establish those details. This nomination does not select 42talents as a publishing destination in any workspace.

Plan the registration paths separately: the current [Community Management overview](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/community-management-overview) says Development-tier requests use a new app without other API products. Do not blindly enable Share on LinkedIn on that same prospective app first. Recheck the onboarding requirements in the portal before registration. Setup will be guided with the owner; no registration, access approval, identity mapping or live test is claimed here. Use the Posts/Images references below for the intended adapter contract rather than copying legacy endpoint examples from an onboarding guide.

### Provider API references

Telegram channel/group behavior must follow its [Bot API](https://core.telegram.org/bots/api). LinkedIn author URNs, commentary, and operations follow the [Posts API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/posts-api); do not derive a universal mention identity from a profile URL or OpenID subject. LinkedIn image transfer follows the [Images API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/images-api), including person/organization ownership. The [Meta Threads collection](https://www.postman.com/meta/threads/documentation/dht3nzz/threads-api?entity=request-34203612-b4339073-a559-4e15-9ffe-974d6f0451ec) documents image import from a public URL. Signal bridge evaluation starts from its [upstream project](https://github.com/bbernhard/signal-cli-rest-api); a message endpoint is not proof of story support.

These references guide verification, not a complete certification of every row. Do not copy consumer product limits into bot/API specs or claim a blanket verification date.

## Required record before enabling a target

Each implementation must have a setup guide and dated record containing:

1. Upstream docs, current supported API version, verification date, destination kind, and adapter version.
2. App registration, account eligibility, scopes, reviews, costs, callback/webhook configuration.
3. Stable identity and host/instance/page/chat scope; second same-platform account and shared-connection destination isolation.
4. Content/media/alt-text support, exact counting algorithms, formatting/mention encoding, quotas, temporary import lifetime where required, and platform-owned image persistence after source revocation.
5. Verified create/edit/delete operations and restrictions. Unknown actions remain disabled; no inferred generic endpoint.
6. Token expiry/refresh/reconnect behavior; actual account/connection/provider-app/instance quota scopes.
7. Provider idempotency, staged-upload recovery, ambiguous-write reconciliation, external reference/URL derivation.
8. Mock contracts plus authorized live tests of text, images, limits, mentions, failures, and identity. Record outstanding gaps.

Use current supported API versions per provider release policy; the library [technology policy](../technologies.md) does not override provider API deprecation/access rules. App self-fetch checks local routing only; provider fetch or external probes supply separate evidence.
