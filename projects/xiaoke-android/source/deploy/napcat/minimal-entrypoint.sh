#!/usr/bin/env bash
set -euo pipefail
umask 077
ulimit -c 0 || true
test "$(id -u)" -ne 0 || { echo 'Run as the configured unprivileged UID.' >&2; exit 1; }
test -s /app/napcat/config/webui.json
test -s /app/napcat/config/onebot11.json
mkdir -p /tmp/xiaoke-napcat
unzip -q /app/NapCat.Shell.zip -d /tmp/xiaoke-napcat
shopt -s dotglob nullglob
for item in /tmp/xiaoke-napcat/*; do
  case "$(basename "$item")" in config|plugins) continue ;; esac
  cp -R "$item" /app/napcat/
done
test -s /app/napcat/napcat.mjs
# No host /proc, /sys, container-identity or Docker-socket modifications.
Xvfb :1 -screen 0 1080x760x16 +extension GLX +render >/tmp/xvfb.log 2>&1 &
for attempt in 1 2 3 4 5; do
  test -S /tmp/.X11-unix/X1 && break
  sleep 0.3
done
cd /app/napcat
exec /opt/QQ/qq --no-sandbox
