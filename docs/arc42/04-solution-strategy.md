# 4. Solution strategy

| Requirement | Strategy | Decision |
|---|---|---|
| Several account sets in one owner’s installation | Strictly workspace-local records/queries/jobs and publishing; independent connections/destinations | ADR-013 |
| Browser editing on a NAS | Spring Boot monolith; Thymeleaf + current HTMX | ADR-002 |
| Write once, vary by account | Default → platform → destination overrides; shared deterministic previews | ADR-003 |
| AI preparation before publication | Isolated optional editorial API; diff, accept, revalidate, approve | ADR-014 |
| Heterogeneous provider APIs | Stateless adapter/transport strategies with explicit context/capabilities | ADR-001 |
| Restart-safe, duplicate-aware delivery | Immutable approved payloads, durable queue, leases, idempotency, UNKNOWN reconciliation | ADR-015 |
| Relational isolation and individual schedules | PostgreSQL constraints, one publication per schedule, short claims outside network calls | ADR-004, ADR-009 |
| Similar announcements at different times | Independent copied drafts in the same workspace, fresh approval and one-time schedule | ADR-013, ADR-015 |
| Provider onboarding | Verified OAuth/token modes; state bound to originating workspace | ADR-006 |
| Public HTTPS operation | Built-in login, narrow callback/webhook exceptions, trusted proxy handling | ADR-005, ADR-008 |
| Secrets and backups | Authenticated encryption, separate keys, isolated restore drill | ADR-007, ADR-012 |
| Platform-owned published images | Direct upload first; temporary import grants where required; source revocation cannot break the platform image | ADR-008 |
| Telegram announcements | Explicit bot connections and channel/group destinations | ADR-011 |
| Optional unofficial integrations | Personal Status/Signal feasibility, separate sessions, disabled by default | ADR-010 |
| Current releases and portable images | Latest stable inventory; Buildpacks per architecture, tested OCI index | ADR-012 |

The implementation proves one complete editorial/publication path before broadening integration count. Optional providers and experiments cannot weaken workspace, approval, or recovery rules.
