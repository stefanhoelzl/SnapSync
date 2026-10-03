#!/usr/bin/env python3
"""Fail if a bundle's executable STRONGLY imports an Objective-C class newer than its own minimum OS.

Usage (macOS, Xcode selected):  scripts/check-weak-links.py <bundle.app|bundle.appex> [...]

Why this exists: clang weak-imports a class whose availability is newer than the deployment target, so dyld
binds it to nil on an older OS; Kotlin/Native does NOT — every class it references is a strong import. A
strong import of a class the running OS lacks kills the process in dyld, before any of our code runs:

    Symbol not found: _OBJC_CLASS_$_PHAssetResourceUploadJob   (iOS 18, a rig build; 2026-10-03)

The authority for "newer than the deployment target" is clang itself, not a header parse: for each framework
the executable imports classes from, this compiles one reference per strongly-imported class against the SDK
for the bundle's `MinimumOSVersion`, and reads back which of them clang emitted as WEAK. Every one of those is a
class the binary imports strongly but must not. The fix is to weak-link that framework in the target's
OTHER_LDFLAGS (`-weak_framework <F>`), which is what clang would have done per symbol.

Scope: Objective-C classes only — a Kotlin/Native-referenced C function or constant newer than the target would
slip past. Classes clang cannot see (Swift-only, private, declared in another framework's headers) are listed
as unchecked, never silently passed.
"""

import plistlib
import re
import subprocess
import sys
import tempfile
from pathlib import Path

STRONG = re.compile(r"\(undefined\) external _OBJC_CLASS_\$_([A-Za-z_][A-Za-z0-9_]*) \(from ([A-Za-z0-9_]+)\)")
WEAK = re.compile(r"\(undefined\) weak external _OBJC_CLASS_\$_([A-Za-z_][A-Za-z0-9_]*)")
UNDECLARED = re.compile(r"(?:undeclared identifier|unknown receiver|forward declaration|receiver type) '([A-Za-z0-9_]+)'")


def run(*cmd: str) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True)


def clang_weak(framework: str, classes: list[str], min_os: str, work: Path) -> tuple[set[str], set[str]]:
    """(the classes clang weak-imports at `min_os`, the classes it could not resolve)."""
    unchecked: set[str] = set()
    pending = sorted(classes)
    while pending:
        src = work / f"{framework}.m"
        obj = work / f"{framework}.o"
        refs = "\n".join(f"void *ref_{i}(void) {{ return (__bridge void *)[{c} class]; }}" for i, c in enumerate(pending))
        src.write_text(f"@import Foundation;\n@import UIKit;\n@import {framework};\n{refs}\n")
        res = run("xcrun", "--sdk", "iphoneos", "clang", "-target", f"arm64-apple-ios{min_os}", "-fmodules",
                  "-fobjc-arc", "-ferror-limit=0", "-Wno-unguarded-availability", "-Wno-unguarded-availability-new",
                  "-c", str(src), "-o", str(obj))
        if res.returncode == 0:
            nm = run("nm", "-m", str(obj)).stdout
            return {m.group(1) for m in WEAK.finditer(nm)}, unchecked
        bad = {m.group(1) for m in UNDECLARED.finditer(res.stderr)} & set(pending)
        if not bad:  # the module itself did not import, or an error we cannot attribute to one class
            return set(), unchecked | set(pending)
        unchecked |= bad
        pending = [c for c in pending if c not in bad]
    return set(), unchecked


def check(bundle: Path) -> bool:
    info = plistlib.loads((bundle / "Info.plist").read_bytes())
    exe = bundle / info["CFBundleExecutable"]
    min_os = info["MinimumOSVersion"]
    sdk = Path(run("xcrun", "--sdk", "iphoneos", "--show-sdk-path").stdout.strip())

    by_framework: dict[str, list[str]] = {}
    for m in STRONG.finditer(run("nm", "-m", str(exe)).stdout):
        by_framework.setdefault(m.group(2), []).append(m.group(1))

    violations: list[str] = []
    unchecked: list[str] = []
    with tempfile.TemporaryDirectory() as tmp:
        for framework, classes in sorted(by_framework.items()):
            if not (sdk / "System/Library/Frameworks" / f"{framework}.framework").is_dir():
                continue  # a dylib (libobjc, a Swift runtime) or an embedded framework, not an SDK framework
            weak, unseen = clang_weak(framework, classes, min_os, Path(tmp))
            violations += [f"{framework}: {c}" for c in sorted(weak)]
            unchecked += [f"{framework}: {c}" for c in sorted(unseen)]

    name = bundle.name
    total = sum(len(c) for c in by_framework.values())
    print(f"{name}: MinimumOSVersion {min_os}, {total} strongly-imported classes checked against {sdk.name}")
    if unchecked:
        print(f"{name}: unchecked (clang could not resolve them): " + ", ".join(unchecked))
    for v in violations:
        print(f"::error::{name} strongly imports {v}, which is newer than iOS {min_os} — dyld kills the process "
              f"at launch on an older OS. Weak-link that framework (-weak_framework) for this target.")
    return not violations


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    sys.exit(0 if all([check(Path(b)) for b in sys.argv[1:]]) else 1)
