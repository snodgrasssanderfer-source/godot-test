# Third-party notices

This file records direct dependencies and the provenance of generated runtime
artifacts. Release builds must also generate and review a complete transitive
dependency inventory for the exact lockfile, rootfs, native binaries, and
Android artifact being distributed.

The Android build copies this notice, the application `LICENSE`, the configured
JavaScript dependency license texts, the full GPL-2.0 and AGPL-3.0 texts
applicable to the imported PRoot/Operit artifacts, and archived GPL-3.0 and
LGPL-3.0 reference texts into `assets/legal/` in the APK. The network tool
component group (see below) adds the Ubuntu package copyright files collected by
CI into `assets/legal/ubuntu-packages/`. This generated bundle does not, by
itself, satisfy the release-time review and corresponding-source requirements
for transitive Android dependencies or the Ubuntu, Node.js, DeepSeek Harness,
PRoot, and Operit runtime materials.

| Component | Purpose | License |
| --- | --- | --- |
| Capacitor | Android application shell and native bridge | MIT |
| React | Management UI | MIT |
| xterm.js | Terminal emulator | MIT |
| Lucide | Interface icons | ISC |
| Phosphor Icons | Companion interface icons | MIT |
| LXGW WenKai (lxgw-wenkai-webfont 1.7.0) | Companion interface font | SIL OFL-1.1 |
| Vite | Web build tooling | MIT |
| TypeScript | Type checking and compilation | Apache-2.0 |
| ESLint | Source linting | MIT |
| Kotlin | Android implementation language and runtime | Apache-2.0 |
| AndroidX | Android application support libraries | Apache-2.0 |
| Apache Commons Compress / IO | Rootfs archive handling | Apache-2.0 |
| SnakeYAML Engine | Bounded parsing of Harness credential-presence metadata | Apache-2.0 |
| OkHttp | Digest-pinned HTTPS downloads | Apache-2.0 |
| Shizuku API / provider | Optional Android shell bridge | Apache-2.0 |
| Ubuntu 24.04 ARM64 packages | Embedded or remotely installed userspace runtime | Package-specific licenses |
| Node.js 24.19.0 and bundled dependencies | JavaScript runtime inside the rootfs | MIT and bundled dependency-specific licenses |
| `@deepseek-ai/dsh` 0.2.1-rc.1 | Agent runtime inside the rootfs | MIT |
| `@deepseek-ai/dsh-web-frontend` 0.2.1-rc.1 | Official Harness frontend adapted for the Android WebView | MIT |
| PRoot v5.1.107.78 runner and loader | Userspace rootfs execution | GPL-2.0-or-later |
| Operit2 Android runtime tooling and patch | Source/build provenance for the packaged PRoot artifacts | AGPL-3.0 |
| Operit Terminal Core | Reference implementation consulted for the terminal integration | LGPL-3.0 |
| `git` (Ubuntu 24.04 ARM64) | Version control for the coding agent inside the rootfs | GPL-2.0 |
| `curl` and `libcurl` (Ubuntu 24.04 ARM64) | HTTPS transfer inside the rootfs | curl license |
| `libexpat1` (Ubuntu 24.04 ARM64) | XML parsing runtime dependency of `git` | MIT |
| `ca-certificates` (Ubuntu 24.04 ARM64) | CA trust store for `git`/`curl` TLS verification | MPL-2.0 |
| `openssh-client` (Ubuntu 24.04 ARM64, optional) | SSH remotes for `git` | BSD-style, per-file |

## Native runtime provenance

Release APKs package these renamed ARM64 files:

| APK file | Upstream output | SHA-256 of the current release input |
| --- | --- | --- |
| `lib/arm64-v8a/libdsh_proot.so` | `liboperit_proot.so` | `caa5d7d65952b258b3427322448db91b5eebea4aa72ab66c77174b86c8437f94` |
| `lib/arm64-v8a/libdsh_proot_loader.so` | `liboperit_loader.so` | `f149774236db1e69b36cc1e4ed3866c7094db2eee52da0d4956aa63a9bb26929` |

Their source/build provenance is the Operit2 repository at
`https://github.com/AAswordman/Operit2`, commit
`dc4c3a9405dc7ed3ef69b2ac9a6ace65374d77cf`, especially
`tools/android-runtime/`. At that revision the tooling pins Termux PRoot
v5.1.107.78 and carries the Operit patch at
`tools/android-runtime/patches/termux-proot-operit-android.patch`. Operit2's
top-level license is GNU AGPL version 3. PRoot source headers permit GNU GPL
version 2 or, at the recipient's option, any later version.

The upstream Operit Terminal Core `LICENSE` file is preserved verbatim as
`operit-terminal-core-LGPL-3.0.txt`. Because that upstream notice abbreviates
the incorporated GNU GPL version 3 text, the APK also carries unabridged FSF
copies as `gnu-LGPL-3.0.txt` and `gnu-GPL-3.0.txt`. Terminal Core was consulted
as a reference; no Terminal Core source or binary is copied into this app, so
these three files are retained for traceability rather than asserted as the
license of the independently implemented terminal bridge.

The native ELF files and generated rootfs are deliberately absent from Git.
Redistributors must preserve copyright and license notices and provide the
complete corresponding source required for the exact binaries they convey,
including the pinned upstream source, Operit patch and any later changes, plus
the scripts and instructions needed to generate, install, and run the object
code. A link to an upstream repository or this commit identifier alone is not
a corresponding-source offer. This provenance statement records the imported
binary hashes; it does not claim that this repository currently performs a
bit-for-bit rebuild of them.

The prepared rootfs must retain Ubuntu package copyright data, the Node.js
license and bundled notices, the DeepSeek Harness license, and the licenses of
all copied npm packages. A remote rootfs is subject to the same obligations as
one embedded in the APK.

## Network tool component provenance

The embedded runtime adds a network tool component group so the agent inside the
guest can clone, diff, and commit: `git` (with its `/usr/lib/git-core`
subcommands), `curl`, `libcurl` and its transitive runtime libraries,
`libexpat1`, `ca-certificates` (including the generated `/etc/ssl/certs` trust
store), and optionally `openssh-client`. The binaries are taken from the
`ubuntu-24.04-arm` build runner, which is the same distribution and architecture
as the image, by `scripts/stage-network-tools.sh`; no `apt` runs inside the
image. Man pages, `/usr/share/doc`, and locales are not copied, and the perl
dependencies of `git` are intentionally excluded, so perl-based helpers such as
`git add -p`, `git send-email`, and `git svn` are unavailable inside the guest.

Because these components are conveyed as Ubuntu binaries, the APK carries the
distribution copyright files collected by `scripts/legal-notices.py` under
`assets/legal/ubuntu-packages/`, together with `inventory.json` listing every
package that contributed a staged file (including `ldd`-resolved transitive
libraries). `git` is GPL-2.0; the unabridged GPL-2.0 text already shipped as
`legal/licenses/proot-GPL-2.0.txt` covers it, and the corresponding-source
obligation is discharged under the same terms stated for the other GPL-2.0
artifacts above and in `docs/RELEASE_CHECKLIST.md`. Stating the upstream URL and
package version alone is not a corresponding-source offer.

## 小克私人构建的增量模块（2026-10-29）

原 DSH 许可证与署名保留。新增陪伴实现包含 AstrBot 的消息分段和路由移植（AGPL-3.0）、MaiBot 的回复概率移植（GPL-3.0）、mem0 的 SQLite 记录/历史管理移植（Apache-2.0）、bilibili-mcp-server 的 WBI 与公开字幕接口移植（MIT）、Google js-genai 的原生协议/continuation/thought-signature 逻辑适配（Apache-2.0）。各固定版本、文件和修改见 docs/COMMUNITY_REUSE.md，完整许可在 third-party/，并随 APK 打包。新增组合部分按 AGPL-3.0 提供，原文件的版权和更宽松许可继续保留；package.json 的原始 MIT 字段不能用来声明整个 APK 只有 MIT。

本次 runner 为固定 Operit2/Termux 源码和补丁经 NDK r29-beta4 重建的输出，使用回传提交 4a493c504d33017d414e00748be34aca3ba573a4 的材料，已替换上表旧 runner 摘要。构建路径可能影响 runner 字节摘要。完整输入、构建命令和对应源码获取/重建说明位于上级 build-materials/，没有执行 APK 或 rootfs 来伪称手机验证通过。

NapCat 是独立可选服务，通过 OneBot v11 互操作。本 APK 不包含 NapCat/NTQQ 代码、登录状态或二进制；私人使用也不自动消除其专有许可限制。SillyTavern 仅采用公开角色卡格式兼容，不捆绑服务端。


## Open-LLM-VTuber 陪伴轮次逻辑

后端固定版本 `992309c0aa19845960228f880013d4685fde93b5`，MIT，Copyright (c) 2025 Yi-Ting Chiu。移植输入输出结构、顺序TTS队列、播放确认和中断语义；改写为原生presence模块。完整许可见 `third-party/Open-LLM-VTuber-MIT.txt`，采用说明见 `docs/COMMUNITY_REUSE-VOICE.md`。未采用受附加商业条件约束的前端源码、Live2D模型或Python运行时。
