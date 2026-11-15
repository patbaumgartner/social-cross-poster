# Support

## Status of this project

Social Cross-Poster is a **design and documentation baseline with the P0
scaffolding, UC-01 (login + workspace picker), the first UC-02 slice
(telegram/pasted connections, encrypted secrets, destinations), UC-03
(contacts, handles, mention tokens + previews), the first UC-04 slice
(immutable revisions + per-destination previews) and the first UC-05 slice
(publish now: idempotent submissions, telegram text delivery + states)
the first UC-06 slice (history: read-only filtered/paginated publication list + fail-closed detail), the first UC-07 slice (durable retry jobs/worker, bounded backoff, owner retry/reconcile, UNKNOWN reconciliation) the first UC-08 slice (remote edit/delete on confirmed deliveries, separate action records, original delivery preserved), the first UC-09 slice (restore earlier revision as a new revision, optimistic conflict guard) the first UC-10 slice (one-time schedules: explicit timezone/DST choice, frozen payload hash, at-most-one publication within the +15 min cutoff) and the first UC-11 slice (duplicate into a new independent draft in the same workspace) implemented** (2026-11-14; UC-06/UC-07/UC-08/UC-09/UC-10/UC-11 2026-11-15).
There is a Compose baseline (`docker-compose.yml`) but no published release
image yet — see the [README](README.md) status note and [Plan.md](Plan.md) for
the delivery roadmap and acceptance gates.

## Where to ask questions

| Topic | Channel |
|---|---|
| Design questions, product scope, architecture decisions | [GitHub Discussions](https://github.com/patbaumgartner/social-cross-poster/discussions) or an issue |
| Bugs in the planned behavior or documentation mistakes | [GitHub Issues](https://github.com/patbaumgartner/social-cross-poster/issues) (use the bug template) |
| New integration or feature ideas | [GitHub Issues](https://github.com/patbaumgartner/social-cross-poster/issues) (use the feature template) |
| Security vulnerabilities | **Private** — see [SECURITY.md](SECURITY.md), never a public issue |
| Anything else | `contact@patbaumgartner.com` |

## What to include

- The document or use case you are referring to (e.g. `docs/usecases/UC-10-schedule-post.md`)
- What you expected vs. what the documentation says
- Links to the relevant ADR(s) if the question is about a decision

## Response expectations

This is a personal, single-maintainer project. There is no SLA; expect a reply
within a few days. Security reports are handled faster — see
[SECURITY.md](SECURITY.md).
