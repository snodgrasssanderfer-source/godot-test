#!/usr/bin/env bash
# Runs Godot with --headless so tools (e.g. godot-mcp) work without a display.
GODOT_BIN="${GODOT_BIN:-$(command -v godot || echo /opt/godot/Godot_v4.7.2-stable_linux.x86_64)}"
exec "$GODOT_BIN" --headless "$@"
