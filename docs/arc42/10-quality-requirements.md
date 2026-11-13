# 10. Quality requirements

These are acceptance criteria to test during implementation, not measured results.

| ID | Scenario | Required response / verification |
|---|---|---|
| T1 | One owner’s installation has Private, socrates-ch, Zurich and Ticino; two same-platform destinations in one workspace | Independent selection, overrides, credentials, settings and history; end-to-end identity checks |
| T2 | Crafted cross-workspace IDs in draft/media/contact/account/AI/notification requests | Deny all reads/writes/relationships; composite-key and service negative tests, including jobs and callbacks |
| T3 | Tab switches workspace during OAuth/autosave; worker thread processes another tenant | Original persisted ownership remains; no context leakage or silent fallback |
| E1 | AI corrects/translates text or changes a date/link | Original preserved until acceptance; diff/anchor warning; base-version conflict; fresh preview/approval |
| E2 | AI unavailable, over budget, or disabled | No draft loss; manual publishing remains possible; bounded calls and conservative uncertain usage |
| P1 | Duplicate browser submit or scheduler poll | One publication per submission/schedule locally; no additional deliveries for the same action key |
| P2 | Provider publishes but response is lost; worker dies mid-call | UNKNOWN and reconciliation; no blind automatic resend; unrelated targets proceed |
| P3 | Some targets fail/skip or need review | Aggregate follows ADR-015; each destination remains visible; retries use frozen approved content |
| P4 | Over-limit text or incompatible image | Block or offer an explicit reviewed change; never silent truncation/media loss |
| S1 | Anonymous protected route or forged callback/webhook/media request | Deny; test explicit public-route contracts, state replay and signatures |
| S2 | Database-only dump or provider error includes token/bearer URL | Secret ciphertext remains unreadable without keys; no secret appears in logs/UI/alerts |
| S3 | Internal/metadata URL or DNS/redirect trick supplied | Block outbound access unless an explicit deployment allow-list applies |
| R1 | A scheduled create attempt would start over 15 minutes late, including retry/reconnect/restored/resumed work | Require renewed time approval and notify; preserve success/UNKNOWN and allow read-only reconciliation; test exact deadline boundary |
| R2 | Chosen local datetime falls in a DST gap/overlap | Require an explicit valid replacement time or offset choice, show UTC/offset; never automatically shift/select; deterministic boundary tests |
| R3 | Duplicate and schedule similar announcements | New post/approval/time; no inherited remote IDs; edit/cancel one without changing another; copies cannot leave the workspace |
| R4 | Emergency dispatch pause followed by resume | No new publishing attempts while paused; preserve jobs/audit and permit read-only reconciliation; report in-flight uncertainty; resume enforces original schedule deadlines |
| M1 | Provider image imported from a temporary URL | Revoke source after confirmed ingestion; published platform image still works; private originals remain authenticated |
| M2 | An original contains GPS/camera metadata or a target cannot transmit alt text | Approved outgoing bytes omit GPS/camera metadata; originals remain private; unsupported native alt text requires explicit destination-specific acknowledgement |
| M3 | Unapproved autosave revision is over 30 days old | Prune only eligible revisions/media; preserve current drafts, approved history and dependencies of schedules, copies, AI, delivery/recovery and grants |
| L1 | Initial LinkedIn personal and company-page publication | Verify both author kinds, permissions, direct image upload, and independent same-platform destinations; page access gaps block that release requirement |
| O1 | Backup restored to isolated app with dispatch disabled | Four workspaces, media checksums, credential decryption, schedules/history verified before resuming |
| O2 | amd64/arm64 image release | Buildpacks image per architecture, current-version/SBOM inventory, native smoke tests, combined OCI index |
| O3 | Dependency/builder patch released | Update proposal identifies new stable version and digest; run compatibility/security/migration checks before promotion |
| O4 | Two installations use different hostnames or reverse proxies | The same application image uses each installation's configured origin and trusted-proxy settings for redirects/callbacks/links; no owner's hostname or Traefik-specific app dependency |
| U1 | Keyboard-only composer, diff, confirmation, and status review | Usable focus order, labels, alt text, contrast, no color-only status; keyboard shortcut cannot bypass approval |

## Measurement targets

The reference deployment is the owner's Synology DS918+ with 12 GB installed RAM shared with other containers, Docker Engine 24.0.2, Compose 2.20.1 and Traefik. Record actual available capacity and a representative dataset, and verify compatibility against those owner-reported inputs. Exact host/proxy versions are diagnostic context when running these measurements, not prerequisites for product design. Target local non-provider UI interactions at p95 below 500 ms after debounce and persisted status visibility within two seconds of polling. Measure startup, total RSS, concurrent media memory, queue latency, and backup/restore duration; set capacity limits from those results. Provider latency and single-host availability are not controllable guarantees.
