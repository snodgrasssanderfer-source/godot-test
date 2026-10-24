#!/usr/bin/env bash
# Android export toolchain for Godot: OpenJDK 17, Android SDK (cmdline-tools,
# platform-tools, build-tools, platform), Godot Android export templates and a
# debug keystore. Safe to re-run.
# Env: ANDROID_HOME (default /opt/android-sdk), GODOT_VERSION (default 4.7.2-stable)
set -euo pipefail
GODOT_VERSION="${GODOT_VERSION:-4.7.2-stable}"
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-13114758_latest.zip"
SDK_PACKAGES=("platform-tools" "build-tools;35.0.1" "platforms;android-35")
KEYSTORE="$HOME/.android/debug.keystore"
SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# OpenJDK 17 (recommended by the Godot 4.7 docs)
if [ ! -x /usr/lib/jvm/java-17-openjdk-amd64/bin/javac ]; then
  $SUDO apt-get update -qq
  DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y -qq --no-install-recommends openjdk-17-jdk-headless
fi
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Android SDK command-line tools + packages
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fsSL -o "$TMP/clt.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  $SUDO mkdir -p "$ANDROID_HOME/cmdline-tools" && $SUDO chown -R "$(id -u)" "$ANDROID_HOME"
  unzip -q "$TMP/clt.zip" -d "$TMP/clt"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$TMP/clt/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" "${SDK_PACKAGES[@]}" >/dev/null

# Godot Android export templates (same version as the editor)
TPL_DIR="$HOME/.local/share/godot/export_templates/${GODOT_VERSION/-/.}"
if [ ! -f "$TPL_DIR/android_release.apk" ]; then
  URL="https://github.com/godotengine/godot/releases/download/$GODOT_VERSION"
  curl -fsSL -o "$TMP/SHA512-SUMS.txt" "$URL/SHA512-SUMS.txt"
  curl -fsSL -o "$TMP/Godot_v${GODOT_VERSION}_export_templates.tpz" "$URL/Godot_v${GODOT_VERSION}_export_templates.tpz"
  (cd "$TMP" && grep " Godot_v${GODOT_VERSION}_export_templates.tpz\$" SHA512-SUMS.txt | sha512sum -c --quiet -)
  mkdir -p "$TPL_DIR"
  unzip -ojq "$TMP/Godot_v${GODOT_VERSION}_export_templates.tpz" \
    'templates/android_*' 'templates/version.txt' -d "$TPL_DIR"
fi

# Debug keystore (standard Android debug credentials)
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  "$JAVA_HOME/bin/keytool" -genkeypair -v -keystore "$KEYSTORE" -storepass android \
    -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

echo "Android toolchain ready: JDK 17, SDK at $ANDROID_HOME, templates in $TPL_DIR"
ls "$ANDROID_HOME/build-tools" "$ANDROID_HOME/platforms"
