# UC-08 — Request a verified remote edit or deletion

- **Actor:** Owner
- **Trigger:** Edit/delete on a confirmed published destination
- **Preconditions:** Authorized workspace; external reference known; operation verified for this destination and current rights
- **Related decisions:** ADR-001, ADR-003, ADR-015

## Main flow

1. Check live capability, ownership, provider restrictions, and any time window. Unknown/unsupported operations remain disabled with an explanation.
2. For edit, open the last confirmed destination payload, create an action-specific revision, optionally use UC-16, then validate and approve the exact change.
3. For delete, show the destination and external post identity and request explicit confirmation.
4. Queue a separate remote action and persist its attempts/outcome; preserve the original delivery and publication record.
5. Show last confirmed remote state and any action-specific failure or UNKNOWN outcome.

## Alternative flows

- Multi-destination operations require explicit selection and per-destination validation; some can succeed while others fail.
- Provider rejection or expired window does not change a formerly successful delivery into FAILED.
- A timeout after an edit/delete may require reconciliation; never issue a replacement create-post as a fallback.
- Already-absent posts can be marked absent only with verified evidence; lack of access is not proof of deletion.
- Local history deletion/archiving and remote deletion are different operations. Explain independent post/source references before local cleanup; remote images live on their platforms.

## Postconditions and UI

No invented generic edit endpoint or guarantee of erasing third-party copies. Original content, approved edits, and remote outcomes remain auditable. AI assistance never submits the remote action.

## Review log

- 2026-11-13 — Reviewed against ADR-001, ADR-003 and ADR-015. Capability-gated operations, separate remote_action records that preserve the original delivery, and no replacement create-post after an uncertain edit/delete all match ADR-001/ADR-015; edit payloads are action-specific revisions per ADR-003. No behavior change.
- 2026-11-15 — First slice implemented. Flyway V7 `remote_action`/`remote_action_attempt` (separate records; append-only redacted attempts, credential-free per ADR-007), `PlatformRemoteActor` strategy with a Telegram actor (`editMessageText`/`deleteMessage`, external-ref prefix `telegram:message:`, 4xx → FAILED, 5xx/transport → UNKNOWN, 30 s read timeout), and `RemoteActionService` enforcing the step 1–3 guards: delivery belongs to the publication, state SUCCESS with a confirmed external reference, a wired actor supporting the action, replacement text passing the same `TextRules` as publishing (never truncated), and one active action per delivery. Web: the action-specific edit form opens the last confirmed payload, the delete page shows destination + external post identity and asks for explicit confirmation, and the history detail shows per-delivery Edit/Delete buttons (only for confirmed deliveries of supported platforms) plus a remote-actions table with state, replacement payload and redacted attempts. The original delivery and publication record are never modified (ADR-015). Deviations closed by later slices: no live capability/time-window check beyond `supportedActions`, no UC-16 assist on the edit form, no UC-12 notifications, and multi-destination operations act on one delivery at a time. 161/161 tests green.
