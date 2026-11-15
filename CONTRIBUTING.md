# Contributing

Thanks for your interest in Social Cross-Poster! This project started as a
documented design baseline (see [README.md](README.md) and [Plan.md](Plan.md));
the P0 scaffolding and [UC-01](docs/usecases/UC-01-login.md) landed on
2026-11-14, so contributions to the documentation, decisions, review, **and
code** are welcome.

## How to contribute

1. **Check existing work first.** Browse the [issues](https://github.com/patbaumgartner/social-cross-poster/issues)
   and the [ADR index](docs/adr/README.md) to avoid duplicating a decision.
2. **Open an issue before large changes.** Describe the problem or proposal and
   link the affected documents/use cases. This keeps the design coherent.
3. **Fork, branch, and make small changes.** Create a topic branch per change
   (e.g. `docs/uc-10-dst-review`). Keep commits small and focused; write
   conventional commit messages (`docs:`, `adr:`, `chore:`, `feat:`, `fix:`).
4. **Keep documentation consistent.** Every behavior change must be reflected
   in the affected use case, ADR, and arc42 section. Run the checks below
   before opening a PR.

## Documentation rules

- Use cases live in `docs/usecases/UC-nn-*.md`, one file per use case, and must
  list their **Related decisions** (ADR numbers) — the checker enforces that
  the referenced ADRs exist.
- Architecture decisions live in `docs/adr/ADR-nnn-*.md` and are indexed in
  `docs/adr/README.md`. Never edit an accepted ADR silently; add an "Amends"
  note and a new ADR instead when a decision changes.
- Architecture views follow the [arc42 structure](https://arc42.org) in
  `docs/arc42/`.
- All Markdown must pass `markdownlint` (config in `.markdownlint.jsonc`) and
  the documentation consistency checker:

  ```sh
  python3 scripts/check-docs.py          # links, anchors, ADR/UC references
  npx markdownlint-cli2 "**/*.md"        # style (uses .markdownlint.jsonc)
  ```

  Both also run automatically in CI (`.github/workflows/ci.yml`) and in the
  `pre-commit` hook (`.githooks/pre-commit`, enable with
  `git config core.hooksPath .githooks`).

- Distinguish **implemented behavior** from **planned/candidate** behavior.
  As of 2026-11-15 the P0 scaffolding, UC-01 (login + workspace picker), the
  first UC-02 slice (connections/destinations + encrypted secrets, telegram
  pasted only), UC-03 (contacts, handles, mention tokens + previews) and the
  first UC-04 slice (immutable revisions + per-destination previews) and
  the first UC-05 slice (idempotent submissions, telegram text publishing
  and delivery states), the first UC-06 slice (read-only publication history: filtered/paginated list + fail-closed detail), the first UC-07 slice (durable `delivery_job` retries with lease-guarded worker claims, bounded backoff, owner retry/reconcile, UNKNOWN reconciliation) the first UC-08 slice (guarded remote edit/delete as separate `remote_action` records via a Telegram remote actor; original delivery preserved), the first UC-09 slice (restore-as-new-revision + optimistic version-conflict guard; never last-write-wins) the first UC-10 slice (one-time schedules with explicit timezone/DST resolution, frozen payload hash and the +15 min dispatch cutoff, at most one publication per schedule), the first UC-11 slice (duplicate into a new independent draft in the same workspace; lifecycle/outcomes not copied) and the first UC-12 slice (in-app redacted/deduplicated notification events on the dashboard; no external channels yet) and the first UC-13 slice (private media library + alt-text attachments on the current revision; media publish blocked fail-closed) are implemented; the rest of UC-02…UC-16 remains
  planned. Do not claim more.
- External links must be dated and sourced where they record a verification
  (see `docs/technologies.md` and `docs/platforms/README.md`).

## Pull requests

- Fill in the [pull request template](.github/PULL_REQUEST_TEMPLATE.md).
- One logical change per PR; keep the diff reviewable.
- CI must pass (Java build/tests + formatting, markdownlint + documentation
  consistency checks). Build locally with `./mvnw clean verify`.
- The maintainer signs and merges; all commits on `main` are GPG-signed.

## Code of conduct

By participating you agree to abide by the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

By contributing, you agree that your contributions are licensed under the
[Apache-2.0 License](LICENSE).
