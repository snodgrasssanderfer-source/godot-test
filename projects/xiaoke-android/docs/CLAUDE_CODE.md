# Claude Code

Xiaoke uses Claude Code in four places: the owner's private chat, tasks the owner hands her, the groups the owner turns on in Connect → QQ, and the code workspace. Everything else stays on Gemini, which is also the fallback when Claude is unavailable.

## How it runs: inside DSH's Ubuntu (PRoot), using the official linux-arm64 build

| Route | Verdict |
|---|---|
| 1. The official linux-arm64 build inside PRoot Ubuntu | **Chosen.** DSH already ships Ubuntu 24.04 (glibc, bash, git, ssh, Node), so the official binary runs as published. Our PRoot is the Termux fork and already handles the SIGSYS that Android seccomp raises for new syscalls (epoll_pwait2, faccessat2, and so on), turning them into error returns. If seccomp acceleration fails, it falls back to `PROOT_NO_SECCOMP`, which DSH already does. No new native code is needed. |
| 2. Port cccodroid's native route (Termux bootstrap + glibc-runner) | No. It relies on rewriting the bootstrap path to the same length as the package name (cccodroid uses a 10-character package). Our package is `io.github.dshcompanion.mobile` and can't be renamed without losing data. It also means maintaining a second Linux userspace. |
| 3. Run the cccodroid toolkit through PRoot path mapping | No. Its devkit is 330–700 MB and duplicates what DSH's rootfs already has, and stacking path mapping on top of path mapping adds another place for things to break. |

The DSH runtime doesn't conflict with this, so it stays.

## What we guarantee

- **Official and unmodified:** We first fetch `/<version>/manifest.json` and `.sig` from `downloads.claude.ai/claude-code-releases`. The signature is checked against the pinned Anthropic release key (fingerprint `31DDDE24DDFAB679F42D7BD2BAA929FF1A7ECACE`). Then `linux-arm64/claude` is downloaded from the same host, and it's only used if its sha256 matches the signed manifest. Downloads resume after interruption. A file that fails the check is deleted. There's no npm or third-party mirror. Auto-update is turned off; updates also go through the installer.
- **Login:** Only Claude Code's own `claude auth login`. The app forwards terminal bytes (you open the URL, paste the code back) and doesn't parse or store them. The credentials Claude Code saves live in `/root/.claude` inside the rootfs, which the app never reads. Launches start from `env -i`, so no app key or token reaches Claude Code. Login status comes only from `claude auth status --json`.
- **Groups:** Each group is set to Claude or Gemini on its own, and the default is Gemini. Messages from a group that isn't turned on never go through Claude.

## Where things live

- Binary: `/root/.xiaoke/claude/<version>/claude` (about 290 MB, downloaded on first use)
- Workspace: `/root/workspace`
- Code workspace: `/root/workspace/godot-test`, a clone of this repo (public). In the owner's private chat, Claude works in `projects/xiaoke-android`, so this project's CLAUDE.md applies. Code changes always go on a new branch with a commit, and Claude tells the owner what changed. Pushing uses a GitHub token the owner enters under 我的 → Claude → 代码工作区. It's stored with Keystore encryption, like the Gemini key, and only the owner's own turns and workspace fetches get it. Launches start from `env -i`, so the token can't be passed as a host environment variable. Instead it goes in as the first line of the process's stdin: a guest bash wrapper reads it into `GH_TOKEN` and sets git's credential helper with `GIT_CONFIG_*`, so nothing is written to a file in the rootfs or into argv. The token is redacted from what Claude outputs, MCP arguments, and the logs. Without a token, she only commits locally. Signing and releases stay in the cloud, and the private key never reaches the phone.

## First run

Onboarding goes in this order: runtime (expanded from the APK's own copy) and Claude Code, then Claude login, then Shizuku, then Gemini, budget, keep-alive and QQ.
