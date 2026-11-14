# UC-05 — Approve and publish now

- **Actor:** Owner; system workers execute
- **Trigger:** Confirm publication after UC-04
- **Preconditions:** Logged in; active workspace; valid immutable revision and at least one local destination
- **Related decisions:** ADR-001, ADR-003, ADR-015

## Main flow

1. Present a final summary: workspace, exact destination identities/audiences, final texts/images/alt text, accepted adaptations, and any warning.
2. Bind approval to revision/payload hashes, destinations, and capability versions. Persist a publication, one delivery per destination, and jobs atomically under a workspace-scoped submission key.
3. Return the persisted result page immediately; publication continues if the browser closes.
4. Workers claim jobs, revalidate authorization/capability compatibility, and publish with bounded parallelism. Upload/container stages and acknowledgements are persisted.
5. Record each destination's state and redacted attempts/external references. An uncertain remote write becomes UNKNOWN; a safe transient failure may enter RETRY_WAIT.
6. Derive aggregate status using ADR-015 and send deduplicated workspace notifications ([UC-12](UC-12-failure-notifications.md)) for actionable failures/uncertainty.

## Alternative flows

- Double click or network resubmit with the same key returns the same publication; different content under that key is rejected.
- Timeout/connection loss after a possible write is UNKNOWN, requiring reconciliation before retry (UC-07).
- Authentication/identity/capability changes before dispatch block only affected deliveries, with review or repair details.
- Provider media fetch failure records the stage and asset/grant ID without exposing bearer URLs.
- Cancel before claim is definitive; in-flight cancellation cannot promise a remote post was prevented or deleted.
- Similar-content hashes can warn about accidental repetition; an intentional new publication remains possible.

## Postconditions and UI

Approved payloads and delivery history are immutable. Poll persisted destination states; show external links only when verified. PUBLISHED means every selected delivery succeeded; PARTIAL and NEEDS_ATTENTION have distinct meanings.

## Review log

- 2026-11-13 — Reviewed against ADR-001, ADR-003 and ADR-015. Atomic submission under a workspace-scoped key, immutable payload binding, UNKNOWN for ambiguous writes and the ADR-015 aggregate rule all match. The notification step now links UC-12 explicitly. No behavior change.
- 2026-11-14 — **Implemented (first slice, text-only, synchronous).** Flyway V5 adds `publication` (workspace-unique `submission_key`, `payload_hash`, aggregate `state`), `delivery` (UNIQUE per publication+account; immutable payload snapshot = destination identity + mention-resolved `payload_text` + `payload_hash`; ADR-015 `state`; `external_ref` only when provider-confirmed) and append-only `delivery_attempt` (redacted detail, never credentials or provider bodies). `PlatformPublisher` is the stateless per-platform strategy (ADR-001) with `PublishContext(credential, targetId, text, idempotencyKey)`; `TelegramPublisher` posts form `chat_id`+`text` to Bot API `sendMessage` with a 30 s read timeout — HTTP 4xx = confirmed FAILED, 5xx/transport = UNKNOWN. `GET/POST /w/{slug}/posts/{id}/publish` shows the final previews/blockers with a generated submission key and publishes: `create` is idempotent (same key + same payload hash returns the existing publication, different hash under the same key is rejected; preview blockers such as over-limit or explicit clear block), then every PENDING delivery executes synchronously in the request and the ADR-015 aggregate is re-derived (IN_PROGRESS > NEEDS_ATTENTION > NEEDS_REVIEW > PUBLISHED > PARTIAL > CANCELED > SKIPPED > FAILED). The post page lists publications with per-destination delivery states and provider-confirmed external refs. Deviations from the plan text, to be closed by later slices: execution is synchronous in the request, so it does **not** survive browser close (durable jobs/leases/worker execution + UNKNOWN reconciliation land with UC-07); notifications land with UC-12; media/alt text lands with UC-13; only telegram text is supported (OAuth and other platforms remain UC-02 slices). Verified by `PublicationServiceTest` (17 tests) + `PublishFlowTest` (9 tests), 90/90 green.
