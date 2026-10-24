#!/usr/bin/env bash
# Installs Godot (Linux x86_64) and the Web export templates only.
# Usage: tools/install_godot.sh [version]   (default 4.7.2-stable)
set -euo pipefail
V="${1:-4.7.2-stable}"
TPL_VER="${V/-/.}"                                    # 4.7.2-stable -> 4.7.2.stable
TPL_DIR="$HOME/.local/share/godot/export_templates/$TPL_VER"
INSTALL_DIR="${GODOT_INSTALL_DIR:-/opt/godot}"
BIN="$INSTALL_DIR/Godot_v${V}_linux.x86_64"
URL="https://github.com/godotengine/godot/releases/download/$V"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

curl -fsSL -o "$TMP/SHA512-SUMS.txt" "$URL/SHA512-SUMS.txt"
verify() { (cd "$TMP" && grep " $1\$" SHA512-SUMS.txt | sha512sum -c -); }

if [ ! -x "$BIN" ]; then
  curl -fsSL -o "$TMP/Godot_v${V}_linux.x86_64.zip" "$URL/Godot_v${V}_linux.x86_64.zip"
  verify "Godot_v${V}_linux.x86_64.zip"
  mkdir -p "$INSTALL_DIR"
  unzip -oq "$TMP/Godot_v${V}_linux.x86_64.zip" -d "$INSTALL_DIR"
fi
ln -sf "$BIN" /usr/local/bin/godot 2>/dev/null || sudo ln -sf "$BIN" /usr/local/bin/godot

if [ ! -f "$TPL_DIR/web_nothreads_release.zip" ]; then
  curl -fsSL -o "$TMP/Godot_v${V}_export_templates.tpz" "$URL/Godot_v${V}_export_templates.tpz"
  verify "Godot_v${V}_export_templates.tpz"
  mkdir -p "$TPL_DIR"
  unzip -ojq "$TMP/Godot_v${V}_export_templates.tpz" 'templates/web_*' 'templates/version.txt' -d "$TPL_DIR"
fi
godot --version
