#!/usr/bin/env bash
# Exports pixel-town as an arm64 APK signed with the Android debug keystore.
# Prerequisite: tools/setup_android.sh. Output: build/pixel-town-v<version>.apk
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GAME="$ROOT/pixel-town"
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
JAVA_HOME="${JAVA_HOME_17:-/usr/lib/jvm/java-17-openjdk-amd64}"
KEYSTORE="$HOME/.android/debug.keystore"
VERSION="$(grep -oP '^version/name="\K[^"]+' "$GAME/export_presets.cfg")"
OUT="$ROOT/build/pixel-town-v$VERSION.apk"

# Point Godot's editor settings at the SDK and JDK 17 (creates them if needed).
SETTINGS="$HOME/.config/godot/editor_settings-4.7.tres"
[ -f "$SETTINGS" ] || godot --headless --path "$GAME" --import >/dev/null 2>&1 || true
set_setting() {
  if grep -q "^$1 = " "$SETTINGS"; then
    sed -i "s|^$1 = .*|$1 = \"$2\"|" "$SETTINGS"
  else
    sed -i "/^\[resource\]/a $1 = \"$2\"" "$SETTINGS"
  fi
}
set_setting export/android/android_sdk_path "$ANDROID_HOME"
set_setting export/android/java_sdk_path "$JAVA_HOME"

# Debug keystore for both debug and release signing (standard debug credentials).
export GODOT_ANDROID_KEYSTORE_DEBUG_PATH="$KEYSTORE"
export GODOT_ANDROID_KEYSTORE_DEBUG_USER=androiddebugkey
export GODOT_ANDROID_KEYSTORE_DEBUG_PASSWORD=android
export GODOT_ANDROID_KEYSTORE_RELEASE_PATH="$KEYSTORE"
export GODOT_ANDROID_KEYSTORE_RELEASE_USER=androiddebugkey
export GODOT_ANDROID_KEYSTORE_RELEASE_PASSWORD=android

mkdir -p "$ROOT/build"
rm -f "$OUT"
godot --headless --path "$GAME" --import
godot --headless --path "$GAME" --export-release "Android" "$OUT"
test -f "$OUT"
"$ANDROID_HOME/build-tools/35.0.1/apksigner" verify --print-certs "$OUT" | head -3
echo "APK: $OUT ($(du -h "$OUT" | cut -f1))"
