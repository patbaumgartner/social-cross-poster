#!/usr/bin/env python3
"""Repository documentation consistency checker.

Validates the Markdown documentation tree:

1. Every relative link in a ``*.md`` file resolves to an existing file.
2. Every in-file anchor (``#section``) of a relative Markdown link exists.
3. Every ``ADR-nnn`` reference points to an existing ``docs/adr/ADR-nnn-*.md``.
4. Every ``UC-nn`` reference points to an existing ``docs/usecases/UC-nn-*.md``.
5. Every use case lists at least one existing ``Related decisions`` ADR.
6. ``README.md`` only links to documentation files that exist.

Exit code 0 means the documentation is consistent; 1 lists every problem found.
Used by ``.githooks/pre-commit`` and the ``docs`` job in ``.github/workflows/ci.yml``.
"""

from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

LINK_RE = re.compile(r"\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
HEADING_RE = re.compile(r"^#{1,6}\s+(.*?)\s*$", re.M)
ADR_RE = re.compile(r"\bADR-\d{3}\b")
UC_RE = re.compile(r"\bUC-\d{2}\b")
RELATED_RE = re.compile(r"^- \*\*Related decisions:\*\* (.+)$", re.M)


def slugify(heading: str) -> str:
    """GitHub-style anchor slug for a Markdown heading."""
    slug = heading.lower().strip()
    slug = re.sub(r"[^\w\s-]", "", slug, flags=re.UNICODE)
    return slug.replace(" ", "-")


def markdown_files() -> list[str]:
    files = []
    skip_dirs = {".git", "node_modules"}
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in skip_dirs and not d.startswith(".")]
        for name in filenames:
            if name.endswith(".md"):
                files.append(os.path.relpath(os.path.join(dirpath, name), ROOT))
    return sorted(files)


def existing_ids(directory: str, prefix: str) -> set[str]:
    ids = set()
    folder = os.path.join(ROOT, directory)
    for name in os.listdir(folder):
        if name.startswith(prefix):
            ids.add("-".join(name.split("-")[:2]))
    return ids


def main() -> int:
    problems: list[str] = []
    files = markdown_files()
    adr_ids = existing_ids("docs/adr", "ADR")
    uc_ids = existing_ids("docs/usecases", "UC")
    referenced_adrs: set[str] = set()
    referenced_ucs: set[str] = set()

    for rel in files:
        path = os.path.join(ROOT, rel)
        with open(path, encoding="utf-8") as fh:
            text = fh.read()

        for match in LINK_RE.finditer(text):
            target = match.group(1)
            if target.startswith(("http://", "https://", "mailto:", "#")):
                continue
            link_path, _, anchor = target.partition("#")
            if link_path:
                resolved = os.path.normpath(os.path.join(os.path.dirname(path), link_path))
                if not os.path.exists(resolved):
                    problems.append(f"BROKEN LINK  {rel} -> {target}")
                    continue
                if anchor and link_path.endswith(".md"):
                    with open(resolved, encoding="utf-8") as fh:
                        heads = {slugify(h) for h in HEADING_RE.findall(fh.read())}
                    if anchor not in heads:
                        problems.append(f"BROKEN ANCHOR {rel} -> {target}")

        for adr in ADR_RE.findall(text):
            referenced_adrs.add(adr)
            if adr not in adr_ids:
                problems.append(f"MISSING ADR   {rel} references {adr}, no such file in docs/adr/")
        for uc in UC_RE.findall(text):
            referenced_ucs.add(uc)
            if uc not in uc_ids:
                problems.append(f"MISSING UC    {rel} references {uc}, no such file in docs/usecases/")

        if os.path.dirname(rel) == "docs/usecases" and os.path.basename(rel).startswith("UC-"):
            related = RELATED_RE.search(text)
            if not related:
                problems.append(f"NO RELATED DECISIONS in {rel}")
            else:
                for adr in ADR_RE.findall(related.group(1)):
                    if adr not in adr_ids:
                        problems.append(f"BAD ADR REF   {rel} lists {adr}, no such file in docs/adr/")

    for rel in files:
        if rel == "README.md":
            with open(os.path.join(ROOT, rel), encoding="utf-8") as fh:
                for match in LINK_RE.finditer(fh.read()):
                    target = match.group(1)
                    if target.startswith(("http://", "https://", "mailto:")):
                        continue
                    link_path = target.partition("#")[0]
                    if link_path and not os.path.exists(os.path.join(ROOT, link_path)):
                        problems.append(f"BROKEN README LINK -> {target}")

    if problems:
        print(f"check-docs: {len(problems)} problem(s) found:")
        for problem in problems:
            print(f"  {problem}")
        return 1

    print(
        f"check-docs: OK — {len(files)} Markdown files, "
        f"{len(referenced_adrs)}/{len(adr_ids)} ADRs referenced, "
        f"{len(referenced_ucs)}/{len(uc_ids)} use cases referenced, all links/anchors resolve"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
