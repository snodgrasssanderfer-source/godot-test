#!/usr/bin/env bash
# One-shot environment setup for this repo: Godot + matching Web export
# templates, Blender, and the Godot MCP server build. Safe to re-run (skips
# anything already installed). Self-contained, so it can be pasted as-is into
# the cloud environment's "Setup script" setting.
#
# Usage: setup_env.sh [--godot-only]
# Env:   INSTALL_ROOT (default /opt) - where Godot/Blender/godot-mcp go
set -euo pipefail

GODOT_VERSION="4.7.2-stable"
BLENDER_VERSION="5.1.2"
GODOT_MCP_COMMIT="1209744fad78f3998f98c7394fd0f6ef50da5281"
BLENDER_MCP_COMMIT="b786a397747970a17b74983c9aab657b8a014789"

INSTALL_ROOT="${INSTALL_ROOT:-/opt}"
SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# --- Godot editor + Web export templates ------------------------------------
GODOT_URL="https://github.com/godotengine/godot/releases/download/$GODOT_VERSION"
GODOT_BIN="$INSTALL_ROOT/godot/Godot_v${GODOT_VERSION}_linux.x86_64"
TPL_DIR="$HOME/.local/share/godot/export_templates/${GODOT_VERSION/-/.}"   # e.g. 4.7.2.stable

godot_verify() { (cd "$TMP" && grep " $1\$" SHA512-SUMS.txt | sha512sum -c --quiet -); }
if [ ! -x "$GODOT_BIN" ] || [ ! -f "$TPL_DIR/web_nothreads_release.zip" ]; then
  curl -fsSL -o "$TMP/SHA512-SUMS.txt" "$GODOT_URL/SHA512-SUMS.txt"
fi
if [ ! -x "$GODOT_BIN" ]; then
  echo "Installing Godot $GODOT_VERSION ..."
  curl -fsSL -o "$TMP/godot.zip" "$GODOT_URL/Godot_v${GODOT_VERSION}_linux.x86_64.zip"
  mv "$TMP/godot.zip" "$TMP/Godot_v${GODOT_VERSION}_linux.x86_64.zip"
  godot_verify "Godot_v${GODOT_VERSION}_linux.x86_64.zip"
  mkdir -p "$INSTALL_ROOT/godot"
  unzip -oq "$TMP/Godot_v${GODOT_VERSION}_linux.x86_64.zip" -d "$INSTALL_ROOT/godot"
fi
$SUDO ln -sf "$GODOT_BIN" /usr/local/bin/godot

if [ ! -f "$TPL_DIR/web_nothreads_release.zip" ]; then
  echo "Installing Godot Web export templates (full pack is ~1.2 GB, keeping web_* only) ..."
  curl -fsSL -o "$TMP/Godot_v${GODOT_VERSION}_export_templates.tpz" \
    "$GODOT_URL/Godot_v${GODOT_VERSION}_export_templates.tpz"
  godot_verify "Godot_v${GODOT_VERSION}_export_templates.tpz"
  mkdir -p "$TPL_DIR"
  unzip -ojq "$TMP/Godot_v${GODOT_VERSION}_export_templates.tpz" \
    'templates/web_*' 'templates/version.txt' -d "$TPL_DIR"
  rm -f "$TMP/Godot_v${GODOT_VERSION}_export_templates.tpz"
fi
godot --headless --version

if [ "${1:-}" = "--godot-only" ]; then
  exit 0
fi

# --- Blender (official Linux build) -----------------------------------------
BLENDER_DIR="$INSTALL_ROOT/blender-${BLENDER_VERSION}-linux-x64"
if [ ! -x "$BLENDER_DIR/blender" ]; then
  echo "Installing Blender $BLENDER_VERSION ..."
  BLENDER_URL="https://download.blender.org/release/Blender${BLENDER_VERSION%.*}"
  curl -fsSL -o "$TMP/blender-${BLENDER_VERSION}-linux-x64.tar.xz" \
    "$BLENDER_URL/blender-${BLENDER_VERSION}-linux-x64.tar.xz"
  curl -fsSL -o "$TMP/blender.sha256" "$BLENDER_URL/blender-${BLENDER_VERSION}.sha256"
  (cd "$TMP" && grep " blender-${BLENDER_VERSION}-linux-x64.tar.xz\$" blender.sha256 | sha256sum -c --quiet -)
  tar -xJf "$TMP/blender-${BLENDER_VERSION}-linux-x64.tar.xz" -C "$INSTALL_ROOT"
fi
ln -sfn "$BLENDER_DIR" "$INSTALL_ROOT/blender"          # stable path used by .mcp.json
$SUDO ln -sf "$INSTALL_ROOT/blender/blender" /usr/local/bin/blender
blender --background --factory-startup --python-expr \
  "import bpy; print('Blender OK', bpy.app.version_string)" 2>&1 | grep 'Blender OK'

# --- Godot MCP (built from a pinned commit; npm's 0.1.1 lacks later fixes) ---
GODOT_MCP_DIR="$INSTALL_ROOT/godot-mcp"
if [ ! -f "$GODOT_MCP_DIR/build/index.js" ] || \
   [ "$(git -C "$GODOT_MCP_DIR" rev-parse HEAD 2>/dev/null)" != "$GODOT_MCP_COMMIT" ]; then
  echo "Building godot-mcp @ ${GODOT_MCP_COMMIT:0:7} ..."
  rm -rf "$GODOT_MCP_DIR"
  git clone -q https://github.com/Coding-Solo/godot-mcp.git "$GODOT_MCP_DIR"
  git -C "$GODOT_MCP_DIR" checkout -q "$GODOT_MCP_COMMIT"
  (cd "$GODOT_MCP_DIR" && npm ci --silent && npm run build --silent)
fi

# --- Blender MCP: pre-fetch into the uv cache so the first MCP start is fast --
if ! command -v uvx >/dev/null; then
  python3 -m pip install -q uv
fi
uvx --from "git+https://github.com/sandraschi/blender-mcp@$BLENDER_MCP_COMMIT" \
  blender-mcp --version >/dev/null 2>&1 || echo "WARN: blender-mcp pre-fetch failed (MCP will retry on start)"

echo "Setup complete: Godot $GODOT_VERSION, Blender $BLENDER_VERSION, godot-mcp ${GODOT_MCP_COMMIT:0:7}"
