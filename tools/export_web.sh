#!/usr/bin/env bash
# Exports the game to build/web (uses the "Web" preset in game/export_presets.cfg).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
rm -rf "$ROOT/build/web" && mkdir -p "$ROOT/build/web"
godot --headless --path "$ROOT/game" --import
godot --headless --path "$ROOT/game" --export-release "Web" "$ROOT/build/web/index.html"
touch "$ROOT/build/web/.nojekyll"
test -f "$ROOT/build/web/index.wasm"
echo "Exported to $ROOT/build/web"
