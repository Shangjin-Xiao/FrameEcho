#!/usr/bin/env python3
"""Resolve release body from RELEASE_NOTES.md with fallback.

Usage:
    python3 scripts/resolve_release_notes.py --version 1.2.1 --output /tmp/release-body.md

Rules (per user request):
- If RELEASE_NOTES.md contains a section for the version, use it (supports
  both `## 1.2.1` and `## v1.2.1` headers, and concatenates duplicate blocks
  for the same version, e.g. Chinese + English notes).
- Otherwise write a short fallback ("Bug 修复与性能优化").
- Always append a Full Changelog compare link below the body.
  No GitHub auto What's Changed list is used.
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path

HEADER_RE = re.compile(r"^##\s+v?(?P<ver>\S+)\s*$")

FALLBACK_TEMPLATE = """> 定格你所爱的每一帧 · Freeze Every Frame You Love

### 变更

- Bug 修复与性能优化
"""


def normalize(version: str) -> str:
    return version.strip().lstrip("vV")


def extract(notes_path: Path, version: str) -> str:
    target = normalize(version)
    chunks: list[str] = []
    capturing = False
    found = False
    for line in notes_path.read_text(encoding="utf-8").splitlines(keepends=True):
        m = HEADER_RE.match(line.strip())
        if m:
            header_ver = normalize(m.group("ver"))
            if header_ver == target:
                # Keep only one title header; turn subsequent duplicate
                # same-version headers into separators.
                if not found:
                    chunks.append(f"## {target}\n")
                else:
                    chunks.append("\n---\n\n")
                found = True
                capturing = True
                continue
            if capturing:
                # Reached the next different version section.
                break
            capturing = False
            continue
        if capturing:
            chunks.append(line)
    body = "".join(chunks).strip()
    # Drop trailing `---` separators that come from the source file layout.
    body = re.sub(r"(?:\n?---\n?)+\s*$", "", body).strip()
    return body


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True, help="Release version, e.g. 1.2.1")
    parser.add_argument(
        "--notes",
        default="RELEASE_NOTES.md",
        help="Path to release notes source",
    )
    parser.add_argument("--output", required=True, help="Where to write release body")
    parser.add_argument("--repo", default="", help="GitHub repo, e.g. owner/name")
    parser.add_argument("--previous-tag", default="", help="Previous tag for compare link")
    args = parser.parse_args()

    version = normalize(args.version)
    notes_path = Path(args.notes)
    body = extract(notes_path, version) if notes_path.is_file() else ""
    if not body:
        body = FALLBACK_TEMPLATE.strip()
        print(f"No section for version {args.version} in {notes_path}, using fallback.")
    else:
        print(f"Using RELEASE_NOTES.md section for version {args.version}.")

    prev = normalize(args.previous_tag) if args.previous_tag else ""
    if args.repo and prev and prev != version:
        body += (
            f"\n\n---\n\n**Full Changelog**: "
            f"https://github.com/{args.repo}/compare/{prev}...{version}\n"
        )
    elif args.repo:
        body += f"\n\n---\n\n**Full Changelog**: https://github.com/{args.repo}/releases\n"

    Path(args.output).write_text(body, encoding="utf-8")


if __name__ == "__main__":
    main()
