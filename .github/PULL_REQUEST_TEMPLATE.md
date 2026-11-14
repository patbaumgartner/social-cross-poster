# Pull request

## Summary

<!-- What does this PR change and why? Link the issue if one exists. -->

## Type of change

- [ ] Documentation fix (typo, broken link, unclear wording)
- [ ] Documentation addition (new use case, ADR, arc42 section)
- [ ] Architecture decision change (amends an ADR — describe the amendment)
- [ ] Repository tooling (CI, hooks, templates)
- [ ] Code (P0 scaffolding + UC-01 + UC-02 first slice + UC-03 + UC-04 first slice are in; the rest of UC-02…UC-16 follows the plan)

## Affected documents

<!-- List files, e.g. docs/usecases/UC-10-schedule-post.md, docs/adr/ADR-016-*.md -->

## Consistency checks

- [ ] `python3 scripts/check-docs.py` passes (links, anchors, ADR/UC references)
- [ ] `npx markdownlint-cli2 "**/*.md"` passes
- [ ] `./mvnw clean verify` passes (Java build, formatting, tests)
- [ ] Related use cases / ADRs / arc42 sections were updated where behavior changed
- [ ] Implemented-behavior claims match reality (as of 2026-11-14: scaffolding + UC-01 + UC-02 first slice + UC-03 + UC-04 first slice only)

## License

By submitting this PR you agree that your contribution is licensed under the
[Apache-2.0 License](../LICENSE).
