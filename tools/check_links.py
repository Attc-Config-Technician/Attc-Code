#!/usr/bin/env python3
"""Check local links in the README and the documentation site.

The repository is served from a subpath on GitHub Pages, so broken relative
links are easy to miss. This script verifies that every local file referenced
by README.md and docs/*.html exists, and that fragment links point at an id or
name in the target page.

  python3 tools/check_links.py            # local files and fragments
  python3 tools/check_links.py --external # also request absolute http(s) URLs
"""
from __future__ import annotations

import argparse
import html
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
MD_LINK = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
HTML_LINK = re.compile(r"""(?:href|src)\s*=\s*["']([^"']+)["']""", re.I)
HTML_ID = re.compile(r"""\bid\s*=\s*["']([^"']+)["']|\bname\s*=\s*["']([^"']+)["']""", re.I)
SKIP_PREFIXES = ("mailto:", "tel:", "data:", "javascript:", "fdroidrepos:")


def targets() -> list[Path]:
    files = [ROOT / "README.md"]
    for path in sorted(DOCS.glob("*.html")):
        files.append(path)
    fdroid = DOCS / "fdroid" / "index.html"
    if fdroid.exists():
        files.append(fdroid)
    return [p for p in files if p.exists()]


def extract(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8", errors="replace")
    found = MD_LINK.findall(text) if path.suffix == ".md" else []
    found += HTML_LINK.findall(text)
    # Ignore the generated F-Droid repository index and its assets.
    return [html.unescape(x.strip()) for x in found]


def ids_in(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8", errors="replace")
    out: set[str] = set()
    for match in HTML_ID.finditer(text):
        value = match.group(1) or match.group(2)
        if value:
            out.add(value)
    return out


def local_target(source: Path, link: str) -> tuple[Path | None, str]:
    link = link.split("?", 1)[0]
    fragment = ""
    if "#" in link:
        link, fragment = link.split("#", 1)
    if not link:
        return source, fragment
    decoded = urllib.parse.unquote(link)
    candidate = (source.parent / decoded).resolve()
    if candidate.is_dir():
        candidate = candidate / "index.html"
    return candidate, fragment


def check_external(url: str) -> str | None:
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "Attc-Code-link-check"})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            if response.status >= 400:
                return f"HTTP {response.status}"
    except urllib.error.HTTPError as exc:
        if exc.code in (403, 405):
            # Some hosts reject HEAD; retry with a ranged GET.
            try:
                request = urllib.request.Request(
                    url, headers={"User-Agent": "Attc-Code-link-check", "Range": "bytes=0-0"}
                )
                with urllib.request.urlopen(request, timeout=20) as response:
                    if response.status >= 400:
                        return f"HTTP {response.status}"
            except Exception as retry:  # noqa: BLE001 - report the failure verbatim
                return str(retry)
        else:
            return f"HTTP {exc.code}"
    except Exception as exc:  # noqa: BLE001 - network errors are reported, not raised
        return str(exc)
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--external", action="store_true", help="also check absolute http(s) URLs")
    args = parser.parse_args()

    errors: list[str] = []
    checked = 0
    for source in targets():
        for link in extract(source):
            if not link or link.startswith("#"):
                target, fragment = source, link[1:]
                if fragment and fragment not in ids_in(target):
                    errors.append(f"{source.relative_to(ROOT)}: missing fragment #{fragment}")
                continue
            if link.startswith(SKIP_PREFIXES):
                continue
            if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*://", link):
                if args.external:
                    problem = check_external(link)
                    checked += 1
                    if problem:
                        errors.append(f"{source.relative_to(ROOT)}: {link} -> {problem}")
                continue
            target, fragment = local_target(source, link)
            checked += 1
            if target is None or not target.exists():
                errors.append(f"{source.relative_to(ROOT)}: missing file {link}")
                continue
            if fragment and target.suffix == ".html" and fragment not in ids_in(target):
                errors.append(f"{source.relative_to(ROOT)}: {link} -> missing fragment #{fragment}")

    if errors:
        print("Broken links:")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(f"OK: {checked} links checked")
    return 0


if __name__ == "__main__":
    sys.exit(main())
