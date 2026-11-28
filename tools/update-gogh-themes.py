#!/usr/bin/env python3
"""Refresh the bundled offline Gogh terminal themes asset from upstream."""

import argparse
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import urllib.request

DEFAULT_URL = "https://raw.githubusercontent.com/Gogh-Co/Gogh/master/data/themes-min.json"
REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUTPUT = REPO_ROOT / "app" / "src" / "main" / "assets" / "gogh_themes.json"
MIN_EXPECTED_THEMES = 1000
HEX_RE = re.compile(r"^#?([0-9a-fA-F]{6})$")


def clean_hex(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    match = HEX_RE.match(value.strip())
    return match.group(1).upper() if match else None


def is_light(hex6: str) -> bool:
    r = int(hex6[0:2], 16)
    g = int(hex6[2:4], 16)
    b = int(hex6[4:6], 16)
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0 > 0.5


def transform_themes(raw: object) -> list[dict[str, object]]:
    if not isinstance(raw, list):
        raise ValueError("Expected top-level JSON array in Gogh feed")

    seen: set[str] = set()
    entries: list[dict[str, object]] = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        name = str(item.get("name") or "").strip()
        if not name:
            continue
        key = name.lower()
        if key in seen:
            continue

        bg = clean_hex(item.get("background"))
        fg = clean_hex(item.get("foreground"))
        if not bg or not fg:
            continue

        ansi: list[str] = []
        valid = True
        for idx in range(1, 17):
            color = clean_hex(item.get(f"color_{idx:02d}"))
            if color is None:
                valid = False
                break
            ansi.append(color)
        if not valid:
            continue

        seen.add(key)
        entry: dict[str, object] = {"n": name, "b": bg, "f": fg}
        cursor = clean_hex(item.get("cursor"))
        if cursor:
            entry["c"] = cursor
        entry["a"] = ansi
        entries.append(entry)

    if len(entries) < MIN_EXPECTED_THEMES:
        raise ValueError(
            f"Refusing to write truncated catalog: found {len(entries)} valid themes (< {MIN_EXPECTED_THEMES})"
        )

    entries.sort(key=lambda e: str(e["n"]).lower())
    return entries


def load_raw_feed(url: str, input_path: Path | None) -> object:
    if input_path is not None:
        return json.loads(input_path.read_text(encoding="utf-8"))
    req = urllib.request.Request(url, headers={"User-Agent": "ShellSurge-update-gogh-themes"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)


def compute_delta(existing_path: Path, updated: list[dict[str, object]]) -> tuple[int, int, int]:
    if not existing_path.exists():
        return len(updated), 0, 0
    existing_raw = json.loads(existing_path.read_text(encoding="utf-8"))
    existing_by_name = {str(e["n"]): e for e in existing_raw if isinstance(e, dict) and "n" in e}
    updated_by_name = {str(e["n"]): e for e in updated}
    added = sum(1 for name in updated_by_name if name not in existing_by_name)
    removed = sum(1 for name in existing_by_name if name not in updated_by_name)
    modified = sum(
        1
        for name, item in updated_by_name.items()
        if name in existing_by_name and existing_by_name[name] != item
    )
    return added, removed, modified


def write_atomic(path: Path, payload: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix=".gogh_themes_", suffix=".json", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(payload)
        os.replace(tmp_name, path)
    except Exception:
        if os.path.exists(tmp_name):
            os.unlink(tmp_name)
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default=DEFAULT_URL, help="Upstream Gogh JSON feed URL")
    parser.add_argument("--input", type=Path, default=None, help="Read feed from local JSON file instead of HTTP")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="Output asset path")
    parser.add_argument("--check", action="store_true", help="Validate and print diff summary without writing")
    args = parser.parse_args()

    raw = load_raw_feed(args.url, args.input)
    entries = transform_themes(raw)
    payload = json.dumps(entries, separators=(",", ":"), ensure_ascii=False)
    added, removed, modified = compute_delta(args.output, entries)

    light_count = sum(1 for e in entries if is_light(str(e["b"])))
    dark_count = len(entries) - light_count

    if not args.check:
        write_atomic(args.output, payload)

    mode = "Checked" if args.check else "Updated"
    print(
        f"{mode} {args.output}: {len(entries)} themes "
        f"({dark_count} dark, {light_count} light, {len(payload.encode('utf-8'))} bytes)"
    )
    print(f"Delta vs previous: +{added} added, -{removed} removed, ~{modified} updated")
    return 0


if __name__ == "__main__":
    sys.exit(main())
