#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
test -s android/keystore.properties || { echo 'Run scripts/create-companion-signing.py once; preserve the same key for updates.' >&2; exit 1; }
test -s android/app/src/main/assets/runtime/rootfs.bundle || { echo 'Restore the pinned rootfs input using build-materials/RESTORE.md.' >&2; exit 1; }
test -s android/app/src/main/jniLibs/arm64-v8a/libdsh_proot.so
test -s android/app/src/main/jniLibs/arm64-v8a/libdsh_proot_loader.so
pnpm run build
pnpm exec cap sync android
cd android
"${GRADLE_BIN:-./gradlew}" :app:lintRelease :app:testDebugUnitTest :app:assembleRelease --console=plain
printf '%s\n' 'APK: android/app/build/outputs/apk/release/app-release.apk'
printf '%s\n' 'Build success is not Android 16 or real-account acceptance. Keep signing material private.'
