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
