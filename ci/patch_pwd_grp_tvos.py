#!/usr/bin/env python3
"""
patch_pwd_grp_tvos.py — CI helper for NodeX VPN

pwd-grp v0.1.1 (transitive dep: arti-client → pwd-grp) references
libc::getresuid and libc::getresgid in lmockable.rs. These POSIX calls
exist on Linux, macOS, and iOS but are ABSENT from tvOS's restricted libc:

  error[E0425]: cannot find value `getresuid` in crate `libc`
    --> pwd-grp-0.1.1/src/lmockable.rs:82:16

This script:
  1. Finds pwd-grp in the Cargo registry cache (~/.cargo/registry/src/)
  2. Adds  #[cfg(not(target_os = "tvos"))]  before every line that
     references getresuid or getresgid across all .rs files in the package
  3. Recalculates .cargo-checksum.json so Cargo accepts the patched source

Run AFTER `cargo fetch` has populated the registry cache.
"""

import hashlib
import json
import pathlib
import re
import sys

GUARD = '#[cfg(not(target_os = "tvos"))]'

# Matches any line containing getresuid or getresgid (field declarations,
# function calls, match arms, etc.) while capturing leading whitespace.
PATTERN = re.compile(
    r'^([ \t]*)(.+getres(?:uid|gid).+)',
    re.MULTILINE
)


def patch_file(path: pathlib.Path) -> bool:
    """Add tvOS cfg guard before problematic lines. Returns True if file changed."""
    text = path.read_text(encoding="utf-8")

    def replacer(m: re.Match) -> str:
        indent, rest = m.group(1), m.group(2)
        # Don't double-guard lines that are already guarded
        if GUARD in text[max(0, m.start() - len(GUARD) - 5) : m.start()]:
            return m.group(0)
        return f"{indent}{GUARD}\n{indent}{rest}"

    new_text = PATTERN.sub(replacer, text)
    if new_text == text:
        return False
    path.write_text(new_text, encoding="utf-8")
    return True


def regenerate_checksum(pkg_dir: pathlib.Path) -> None:
    """Recalculate .cargo-checksum.json after source modification.

    Cargo verifies SHA-256 of every file in the package on each build.
    If we modify a source file without updating this JSON, Cargo aborts with:
      'checksum for `pwd-grp v0.1.1` changed ...'
    """
    file_hashes: dict[str, str] = {}
    for p in sorted(pkg_dir.rglob("*")):
        if p.is_file() and p.name != ".cargo-checksum.json":
            rel = str(p.relative_to(pkg_dir))
            file_hashes[rel] = hashlib.sha256(p.read_bytes()).hexdigest()

    checksum_file = pkg_dir / ".cargo-checksum.json"
    checksum_file.write_text(
        json.dumps({"files": file_hashes, "package": None}),
        encoding="utf-8"
    )
    print(f"  Regenerated {checksum_file.name} ({len(file_hashes)} entries)")


def main() -> None:
    registry_src = pathlib.Path.home() / ".cargo" / "registry" / "src"
    pwd_grp_dirs = list(registry_src.glob("*/pwd-grp-*"))

    if not pwd_grp_dirs:
        print("patch_pwd_grp_tvos: pwd-grp not found in Cargo registry — nothing to patch")
        sys.exit(0)

    pkg_dir = pwd_grp_dirs[0]
    print(f"patch_pwd_grp_tvos: patching {pkg_dir.name}")

    patched: list[str] = []
    for src_file in sorted(pkg_dir.rglob("*.rs")):
        if patch_file(src_file):
            patched.append(src_file.name)
            print(f"  Patched: {src_file.name}")

    if not patched:
        print("  Already patched or no matching lines found — skipping checksum update")
        return

    regenerate_checksum(pkg_dir)
    print(f"patch_pwd_grp_tvos: done ({len(patched)} file(s) patched)")


if __name__ == "__main__":
    main()
