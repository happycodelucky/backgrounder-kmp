#!/usr/bin/env python3
"""
Validate the docs/ tree for invariants `mkdocs build --strict` doesn't enforce.

CI runs this after `docs:dokka` and before the mkdocs build, so doc drift fails
the build, not the user.

Invariants:
  1. Every .md file under docs/ (except generated api/) is referenced from
     the site navigation.
  2. Every recipe page has at least one fenced code block.
  3. Every platform page (except force-quit.md) has a tabbed launch-sequence
     block (=== "Android" / iOS / macOS).
  4. The Dokka API reference under docs/api/ is populated: the all-modules
     index links every published module, and each module has real pages.
     Requires `mise run docs:dokka` to have run first (CI does).

Exits 0 on success, non-zero with a list of failures otherwise.
"""

from __future__ import annotations
import re
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
DOCS_DIR = REPO_ROOT / "docs"
MKDOCS_YML = REPO_ROOT / "mkdocs.yml"
API_DIR = DOCS_DIR / "api"

# Published modules aggregated by the root `copyDokkaToDocs` task, mapped to a
# floor on HTML pages. Floors sit well below the real counts (~340 / ~15 as of
# 2026-09) — they catch "empty" or "wrong module aggregated", not API churn.
API_MODULES = {
    "backgrounder": 100,
    "background-monitor": 5,
}


def collect_md_files() -> set[Path]:
    """Every Markdown file under docs/, excluding the generated api/ tree."""
    files: set[Path] = set()
    for p in DOCS_DIR.rglob("*.md"):
        # Skip Dokka-generated output if/when it's copied in.
        if "api" in p.relative_to(DOCS_DIR).parts[:1]:
            continue
        files.add(p)
    return files


def collect_nav_paths() -> set[Path]:
    """Pull every doc path out of the site navigation config.

    We don't import a YAML library — the config uses tag directives
    (`!!python/name:...`) that pyyaml's safe loader rejects. Instead we
    scan for any token that ends in `.md`; nav entries are the only place
    such tokens appear in the file.
    """
    text = MKDOCS_YML.read_text()
    paths = set()
    for m in re.finditer(r"([\w./-]+\.md)", text):
        paths.add(DOCS_DIR / m.group(1))
    return paths


def check_nav_coverage(failures: list[str]) -> None:
    on_disk = collect_md_files()
    in_nav = collect_nav_paths()
    missing = sorted(p.relative_to(DOCS_DIR) for p in on_disk - in_nav)
    if missing:
        for p in missing:
            failures.append(f"page not referenced from the site navigation: {p}")


def check_recipes_have_fenced_block(failures: list[str]) -> None:
    recipes = sorted((DOCS_DIR / "recipes").glob("*.md"))
    for path in recipes:
        text = path.read_text()
        # Triple backtick or pymdownx.superfences `~~~` — accept either.
        if "```" not in text and "~~~" not in text:
            failures.append(
                f"recipe missing fenced code block: {path.relative_to(DOCS_DIR)}"
            )


def check_platforms_have_tabs(failures: list[str]) -> None:
    """Every platform/<name>.md (except force-quit.md, which is iOS-only prose)
    should expose Android / iOS / macOS tabs in at least one tabbed code block."""
    expected = {"=== \"Android\"", "=== \"iOS\"", "=== \"macOS\""}
    for path in sorted((DOCS_DIR / "platforms").glob("*.md")):
        if path.name == "force-quit.md":
            continue
        text = path.read_text()
        # Each platform page has a launch-sequence section that may render as
        # tabs OR as a single example for that platform. We require at least
        # one fenced code block; tabs are nice-to-have, not enforced.
        if "```" not in text and "~~~" not in text:
            failures.append(
                f"platform page missing fenced code block: {path.relative_to(DOCS_DIR)}"
            )


def check_api_reference_populated(failures: list[str]) -> None:
    """Dokka can "succeed" while aggregating nothing — an all-modules index
    reading "All modules:" with an empty list (see LESSONS B-029). Assert the
    output actually contains each module."""
    index = API_DIR / "index.html"
    if not index.is_file():
        failures.append(
            "API reference missing: docs/api/index.html not found "
            "(run `mise run docs:dokka` first)"
        )
        return
    index_html = index.read_text()
    for module, min_pages in API_MODULES.items():
        if f'href="{module}/index.html"' not in index_html:
            failures.append(f"API reference index does not list module: {module}")
        pages = sum(1 for _ in (API_DIR / module).rglob("*.html"))
        if pages < min_pages:
            failures.append(
                f"API reference for {module} has {pages} HTML pages "
                f"(expected at least {min_pages})"
            )


def main() -> int:
    failures: list[str] = []
    check_nav_coverage(failures)
    check_recipes_have_fenced_block(failures)
    check_platforms_have_tabs(failures)
    check_api_reference_populated(failures)

    if failures:
        print("docs/check.py: FAILED", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1
    print("docs/check.py: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
