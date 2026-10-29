# 构建恢复

需要 Linux x86_64、Node 24.19.0、pnpm 11.19.0、JDK 21.0.13+9、Gradle 8.11.1、Android SDK/build-tools 35、NDK 29.0.14033849-beta4、CMake 3.22.1。固定 URL/摘要在 toolchain-inputs.json 与 release-inputs.json。普通 Node/npm/Gradle 依赖受 lockfile 固定。

1. 用 download-inputs.py 下载并验证 toolchain 与 release 输入到私有工作目录；不要提交下载缓存。NDK 解包保留 Unix 符号链接和执行位。
2. PRoot 构建脚本 build_android_tools_wsl.sh、补丁 termux-proot-operit-android.patch 及 proot-rebuild-status.json 固定对应来源；使用官方 corresponding-source.tar.gz 内 Operit2/Termux/talloc 对应源码，NDK r29-beta4，仅 arm64-v8a / proot。DSH_LOADER_INFO_PY 指向 source/scripts/proot-loader-info.py。原生回传参考提交 4a493c504d33017d414e00748be34aca3ba573a4。不要运行 rootfs 里的主机不兼容程序。
3. 将 liboperit_proot.so / liboperit_loader.so 分别复制为 source/android/app/src/main/jniLibs/arm64-v8a/libdsh_proot.so / libdsh_proot_loader.so。将固定 rootfs.bundle、runtime-manifest.json 放到 source/android/app/src/main/assets/runtime/。
4. JAVA_HOME / ANDROID_HOME / ANDROID_SDK_ROOT / ANDROID_NDK_HOME / ANDROID_USER_HOME / GRADLE_USER_HOME 使用本机路径；source/android/local.properties 配置 sdk.dir、ndk.dir。代理和 CA 按环境配置，保留 TLS 验证，不提交凭据。
5. 在 source 执行 pnpm install --frozen-lockfile、pnpm run build、pnpm exec cap sync android。首次私有签名可运行 scripts/create-companion-signing.py；已有安装包必须继续使用相同签名，不能覆盖不同签名或通过卸载丢掉数据。签名私钥绝不入库。
6. 设置 GRADLE_BIN 后运行 scripts/build-companion-private.sh，或从 android 执行 Gradle :app:lintRelease :app:testDebugUnitTest :app:assembleRelease。输出 app/build/outputs/apk/release/app-release.apk。编译不是运行验收。

原源码文件清单与 verify-source.py 是历史静态交接基线；本轮新增文件不适用那个旧摘要集合。新 APK/源码的最终摘要及真实测试记录由本轮集成后更新。无硬件加速的 Android16 首启已经失败，不要重复该路径消耗资源；仪器测试保留供授权真机/加速环境运行。
