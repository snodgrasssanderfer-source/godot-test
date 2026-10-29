# 构建恢复

需要 Linux x86_64、Node 24.19.0、pnpm 11.19.0、JDK 21.0.13+9、Gradle 8.11.1、Android SDK/build-tools 35、NDK 29.0.14033849-beta4、CMake 3.22.1。固定 URL/摘要在 toolchain-inputs.json 与 release-inputs.json。普通 Node/npm/Gradle 依赖受 lockfile 固定。

1. 用 download-inputs.py 下载并验证 toolchain 与 release 输入到私有工作目录；不要提交下载缓存。NDK 解包保留 Unix 符号链接和执行位。
2. PRoot 构建脚本 build_android_tools_wsl.sh、补丁 termux-proot-operit-android.patch 及 proot-rebuild-status.json 固定对应来源；使用官方 corresponding-source.tar.gz 内 Operit2/Termux/talloc 对应源码，NDK r29-beta4，仅 arm64-v8a / proot。DSH_LOADER_INFO_PY 指向 source/scripts/proot-loader-info.py。原生回传参考提交 4a493c504d33017d414e00748be34aca3ba573a4。不要运行 rootfs 里的主机不兼容程序。
3. 将 liboperit_proot.so / liboperit_loader.so 分别复制为 source/android/app/src/main/jniLibs/arm64-v8a/libdsh_proot.so / libdsh_proot_loader.so。将固定 rootfs.bundle、runtime-manifest.json 放到 source/android/app/src/main/assets/runtime/。
4. Linux 构建必须使用 UTF-8 语言环境：执行 Gradle 前设置 `LANG=C.UTF-8` 与 `LC_ALL=C.UTF-8`。原因：单元测试用中文命名（如 `ApplicationSearchTest` 内的中文测试名），Kotlin 编译会生成含中文的 .class 文件名；容器默认的 POSIX 语言环境下 JVM 的 `sun.jnu.encoding` 不是 UTF-8，`:app:compileDebugUnitTestKotlin` 会以 `InvalidPathException: Malformed input or input contains unmappable characters` 失败。这是环境问题，不需要改代码。Maven Central 偶发 429 时用 `--max-workers=2` 重试即可。
5. JAVA_HOME / ANDROID_HOME / ANDROID_SDK_ROOT / ANDROID_NDK_HOME / ANDROID_USER_HOME / GRADLE_USER_HOME 使用本机路径；source/android/local.properties 配置 sdk.dir、ndk.dir。代理和 CA 按环境配置，保留 TLS 验证，不提交凭据。
6. 在 source 执行 pnpm install --frozen-lockfile、pnpm run build、pnpm exec cap sync android。首次私有签名可运行 scripts/create-companion-signing.py；已有安装包必须继续使用相同签名，不能覆盖不同签名或通过卸载丢掉数据。签名私钥绝不入库。
7. 设置 GRADLE_BIN 后运行 scripts/build-companion-private.sh，或从 android 执行 Gradle :app:lintRelease :app:testDebugUnitTest :app:assembleRelease。输出 app/build/outputs/apk/release/app-release.apk。编译不是运行验收。

原源码文件清单与 verify-source.py 是历史静态交接基线；本轮新增文件不适用那个旧摘要集合。新 APK/源码的最终摘要及真实测试记录由本轮集成后更新。无硬件加速的 Android16 首启已经失败，不要重复该路径消耗资源；仪器测试保留供授权真机/加速环境运行。

## 从旧 APK 提取运行环境

没有自行重建 PRoot 时，可以从此前构建的旧 APK 中取出运行环境文件。旧 APK 和取出的文件都放在仓库目录之外的工作目录，**绝不提交**（已由 .gitignore 排除）。

1. 先核对旧 APK 本身的 SHA-256 与其发布说明一致。只解包，不运行其中任何程序。
2. 取出以下四个文件，逐一核对 SHA-256：

| APK 内路径 | 字节 | SHA-256 | 核对依据 |
|---|---|---|---|
| lib/arm64-v8a/libdsh_proot.so | 257088 | caa5d7d65952b258b3427322448db91b5eebea4aa72ab66c77174b86c8437f94 | proot-rebuild-status.json |
| lib/arm64-v8a/libdsh_proot_loader.so | 1632 | f149774236db1e69b36cc1e4ed3866c7094db2eee52da0d4956aa63a9bb26929 | proot-rebuild-status.json |
| assets/runtime/rootfs.bundle | 317842454 | f7e9736ebc359a761adaf56bf02fe5ac64877c73085aaa1df0ffbe876c7708fb | release-inputs.json |
| assets/runtime/runtime-manifest.json | 1035 | 09f709fd1439f4c305b7ed9012287a0e437b993cfef9dacc387da09b10aa13d4 | release-inputs.json |

   loader 内含构建路径信息；自行重建时它的哈希可能与表中不同，属已知情况。除 loader 以外的文件对不上就停止，不要修改清单去迁就。
3. 两个 .so 放到 source/android/app/src/main/jniLibs/arm64-v8a/，rootfs.bundle 与 runtime-manifest.json 放到 source/android/app/src/main/assets/runtime/，然后按上文构建。
4. 构建后可用 `unzip -p <apk> <路径> | sha256sum` 确认新 APK 内的四个文件与上表一致。完整 debug APK 约 312 MiB。
