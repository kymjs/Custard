#!/usr/bin/env python3
"""Package tools/custard-pc-agent into windows_control toolpkg resources as a deterministic ZIP.

Default output:
    examples/windows_control/resources/pc_agent/custard-pc-agent.zip
"""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import zipfile

FIXED_ZIP_TIME = (2024, 1, 1, 0, 0, 0)
DEFAULT_ZIP_ROOT = "custard-pc-agent"

EXCLUDED_DIRS = {
    ".git",
    "node_modules",
    "__pycache__",
    "_deprecated",
}

EXCLUDED_FILES = {
    "Thumbs.db",
    ".DS_Store",
}

EXCLUDED_RELATIVE_PATHS = {
    "data/agent.pid",
    "data/runtime.json",
    "data/config.json",
}


def should_exclude(source_root: Path, file_path: Path) -> bool:
    rel = file_path.relative_to(source_root)
    rel_text = rel.as_posix()

    if rel_text in EXCLUDED_RELATIVE_PATHS:
        return True

    if any(part in EXCLUDED_DIRS for part in rel.parts):
        return True

    if file_path.name in EXCLUDED_FILES:
        return True

    if rel_text.startswith("logs/") and file_path.suffix.lower() == ".log":
        return True

    return False


def iter_source_files(source_root: Path) -> list[Path]:
    files: list[Path] = []
    for path in sorted(source_root.rglob("*")):
        if not path.is_file():
            continue
        if should_exclude(source_root, path):
            continue
        files.append(path)
    return files


def file_sha256(file_path: Path) -> str:
    digest = hashlib.sha256()
    with file_path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_zip(source_root: Path, output_zip: Path, zip_root: str) -> tuple[int, int]:
    files = iter_source_files(source_root)
    output_zip.parent.mkdir(parents=True, exist_ok=True)

    total_bytes = 0
    with zipfile.ZipFile(output_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for file_path in files:
            rel = file_path.relative_to(source_root)
            arcname = Path(zip_root) / rel

            data = file_path.read_bytes()
            total_bytes += len(data)

            info = zipfile.ZipInfo(str(arcname.as_posix()))
            info.date_time = FIXED_ZIP_TIME
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, data)

    return len(files), total_bytes


def parse_args() -> argparse.Namespace:
    repo_root = Path(__file__).resolve().parents[1]
    default_source = repo_root / "tools" / "custard-pc-agent"
    default_output = repo_root / "examples" / "windows_control" / "resources" / "pc_agent" / "custard-pc-agent.zip"

    parser = argparse.ArgumentParser(description="Package custard-pc-agent into windows_control toolpkg resources.")
    parser.add_argument(
        "--source",
        type=Path,
        default=default_source,
        help=f"Source directory (default: {default_source})",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=default_output,
        help=f"Output zip path (default: {default_output})",
    )
    parser.add_argument(
        "--zip-root",
        default=DEFAULT_ZIP_ROOT,
        help=f"Root folder name inside zip (default: {DEFAULT_ZIP_ROOT})",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    source_root = args.source.resolve()
    output_zip = args.output.resolve()

    if not source_root.exists() or not source_root.is_dir():
        print(f"[ERROR] Source directory does not exist: {source_root}")
        return 1

    file_count, total_bytes = write_zip(source_root, output_zip, args.zip_root)
    digest = file_sha256(output_zip)

    print("[OK] Packaged custard-pc-agent asset")
    print(f"  Source : {source_root}")
    print(f"  Output : {output_zip}")
    print(f"  Files  : {file_count}")
    print(f"  Bytes  : {total_bytes}")
    print(f"  SHA256 : {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
