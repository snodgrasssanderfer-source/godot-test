#!/usr/bin/env bash
# Exports the first-person project as a single Windows x86_64 exe plus a short
# readme, zipped into build/Greyfog-windows-<commit>.zip. Uses the
# "Windows Desktop" preset in fps/export_presets.cfg.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/windows"
rm -rf "$OUT" && mkdir -p "$OUT"
godot --headless --path "$ROOT/fps" --import
godot --headless --path "$ROOT/fps" --export-release "Windows Desktop" "$OUT/Greyfog.exe"
test -s "$OUT/Greyfog.exe"
cp "$ROOT/fps/TESTBUILD.txt" "$OUT/README.txt"
REV="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo dev)"
ZIP="$ROOT/build/Greyfog-windows-$REV.zip"
rm -f "$ZIP"
(cd "$OUT" && zip -q -9 "$ZIP" Greyfog.exe README.txt)
ls -l "$OUT" "$ZIP"
echo "Exported to $ZIP"
