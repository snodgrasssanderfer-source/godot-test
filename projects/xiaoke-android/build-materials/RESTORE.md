# 恢复构建材料

此文件保存实际使用的工具版本、命令和可重新取得的输入。交接轮没有再次安装依赖、重编译、运行 APK 或重测界面；下面的迁移命令是对已有成功步骤的整理，不能冒充一次新的干净环境构建验证。

先确认新环境仍是用户指定的位置。所有工具、源码、输出用独立目录。不要修改游戏工程，也不要把密钥写进环境脚本或源码。

## 1. 取回固定输入

在 `projects/xiaoke-android` 下：

```bash
export XIAOKE_HANDOFF_ROOT="$PWD"
export XIAOKE_WORK_ROOT="/workspace/xiaoke-work"
export XIAOKE_TOOL_ROOT="$XIAOKE_WORK_ROOT/tools"
export XIAOKE_INPUT_ROOT="$XIAOKE_WORK_ROOT/inputs"
mkdir -p "$XIAOKE_WORK_ROOT" "$XIAOKE_TOOL_ROOT" "$XIAOKE_INPUT_ROOT"
python3 build-materials/verify-source.py

python3 build-materials/download-inputs.py --group toolchain \
  --out "$XIAOKE_INPUT_ROOT/toolchain" \
  --names jdk.tar.gz gradle.zip ndk.zip platform.zip buildtools.zip cmake.zip pnpm.tgz

python3 build-materials/download-inputs.py --group release \
  --out "$XIAOKE_INPUT_ROOT/release" \
  --names rootfs.bundle runtime-manifest.json corresponding-source.tar.gz
```

下载脚本仅取公开固定 URL 并检查 SHA-256，不解包、不执行。已有同名文件摘要不匹配会停止而不是覆盖。默认不下载原 APK；静态研究已有充分证据。如确需复核，可显式选 `app-release.apk`，不要因此重新开始整轮拆包。

工具清单：`toolchain-inputs.json`；发布输入：`release-inputs.json`。Node v24.19.0 是原环境预装版本；本包没有重复分发 Node。原项目 engines 允许 `^22.19.0 || >=24.0.0`，重现时优先使用 24.19.0，从 Node 官方来源获取并按官方摘要检查。

## 2. 解包工具并设置路径

需要系统已有 Python3、tar、unzip、make、gcc、patch、readelf 等正常构建工具。不要安装未知第三方二进制。

```bash
mkdir -p "$XIAOKE_TOOL_ROOT/jdk" "$XIAOKE_TOOL_ROOT/pnpm"
tar -xzf "$XIAOKE_INPUT_ROOT/toolchain/jdk.tar.gz" --strip-components=1 -C "$XIAOKE_TOOL_ROOT/jdk"
tar -xzf "$XIAOKE_INPUT_ROOT/toolchain/pnpm.tgz" --strip-components=1 -C "$XIAOKE_TOOL_ROOT/pnpm"
unzip -q "$XIAOKE_INPUT_ROOT/toolchain/gradle.zip" -d "$XIAOKE_TOOL_ROOT"
unzip -q "$XIAOKE_INPUT_ROOT/toolchain/ndk.zip" -d "$XIAOKE_TOOL_ROOT"

mkdir -p "$XIAOKE_TOOL_ROOT/android-sdk/platforms" "$XIAOKE_TOOL_ROOT/android-sdk/build-tools" "$XIAOKE_TOOL_ROOT/android-sdk/cmake/3.22.1"
unzip -q "$XIAOKE_INPUT_ROOT/toolchain/platform.zip" -d "$XIAOKE_TOOL_ROOT/android-sdk/platforms"
unzip -q "$XIAOKE_INPUT_ROOT/toolchain/buildtools.zip" -d "$XIAOKE_TOOL_ROOT/android-sdk/build-tools"
mv "$XIAOKE_TOOL_ROOT/android-sdk/build-tools/android-15" "$XIAOKE_TOOL_ROOT/android-sdk/build-tools/35.0.0"
unzip -q "$XIAOKE_INPUT_ROOT/toolchain/cmake.zip" -d "$XIAOKE_TOOL_ROOT/android-sdk/cmake/3.22.1"

export JAVA_HOME="$XIAOKE_TOOL_ROOT/jdk"
export ANDROID_NDK_HOME="$XIAOKE_TOOL_ROOT/android-ndk-r29-beta4"
export ANDROID_USER_HOME="$XIAOKE_WORK_ROOT/android-user"
export PNPM_HOME="$XIAOKE_WORK_ROOT/pnpm-home"
export XDG_DATA_HOME="$XIAOKE_WORK_ROOT/data"
export XDG_CACHE_HOME="$XIAOKE_WORK_ROOT/cache"
export PATH="$JAVA_HOME/bin:$XIAOKE_TOOL_ROOT/gradle-8.11.1/bin:$PATH"
```

以上 `mv` 只针对首次恢复；已有目标目录时先检查，不盲目重做。原下载脚本曾用 Python `zipfile.extractall()`，把 NDK 的 37 个符号链接提成普通文本文件，导致 clang 无法启动，后来已修复。**使用保留 Unix 符号链接和可执行位的解包工具**，不要照搬那个缺陷。

## 3. Gradle 网络与可写目录

新建独立 `XIAOKE_WORK_ROOT/gradle-cache`。如网络要求代理，把新环境实际参数放入该目录的 `gradle.properties`，不要提交它：

```properties
# 仅在新环境确实需要时填写实际值；以下为占位示例。
systemProp.http.proxyHost=YOUR_PROXY_HOST
systemProp.http.proxyPort=YOUR_PROXY_PORT
systemProp.https.proxyHost=YOUR_PROXY_HOST
systemProp.https.proxyPort=YOUR_PROXY_PORT
systemProp.http.nonProxyHosts=localhost|127.*
# 原环境成功使用此系统 truststore；新环境需确认存在并含正确 CA。
systemProp.javax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts
```

原 Gradle 不自动继承 shell 的 HTTP_PROXY；Temurin 自带证书库起初不信任执行环境 CA。不要用关闭证书校验解决。不要上传代理密码、认证 URL、旧 Gradle 用户配置或整个缓存。

`ANDROID_USER_HOME` 避免 SDK 状态落到只读用户目录。原编译日志仍有 analytics 初始化警告但任务成功；不要把该警告本身当作构建失败。SDK license 按新环境正常安装流程处理，不能杜撰已接受的许可。

## 4. 恢复 PRoot 对应源码并重建

```bash
mkdir -p "$XIAOKE_WORK_ROOT/corresponding" "$XIAOKE_WORK_ROOT/proot-source"
tar -xzf "$XIAOKE_INPUT_ROOT/release/corresponding-source.tar.gz" -C "$XIAOKE_WORK_ROOT/corresponding"
mkdir -p "$XIAOKE_WORK_ROOT/corresponding/proot"
tar -xzf "$XIAOKE_WORK_ROOT/corresponding/proot-corresponding-source.tar.gz" -C "$XIAOKE_WORK_ROOT/corresponding/proot"
export XIAOKE_UPSTREAM_ROOT="$XIAOKE_WORK_ROOT/corresponding/proot/upstream"
```

外层归档含 `SOURCE_MANIFEST.json`，其中 PRoot 内层归档 SHA-256 为 `af69c3c41dd4d8a050c57ff874f87f0cc77aefb19a00697d051bc24ebf766460`。内层各 upstream 归档摘要见 `proot-rebuild-status.json`，使用前核对该清单。原工作已核验，不是要求重新调查来源。

```bash
tar -xzf "$XIAOKE_UPSTREAM_ROOT/operit2-source.tar.gz" -C "$XIAOKE_WORK_ROOT/proot-source"
export XIAOKE_OPERIT_ROOT="$XIAOKE_WORK_ROOT/proot-source/operit2"
mkdir -p "$XIAOKE_OPERIT_ROOT/tools/android-runtime/sources"
tar -xzf "$XIAOKE_UPSTREAM_ROOT/termux-proot-v5.1.107.78.tar.gz" -C "$XIAOKE_OPERIT_ROOT/tools/android-runtime/sources"
tar -xzf "$XIAOKE_UPSTREAM_ROOT/talloc-2.4.3.tar.gz" -C "$XIAOKE_OPERIT_ROOT/tools/android-runtime/sources"
tar -xzf "$XIAOKE_UPSTREAM_ROOT/bash-5.2.37.tar.gz" -C "$XIAOKE_OPERIT_ROOT/tools/android-runtime/sources"
tar -xjf "$XIAOKE_UPSTREAM_ROOT/busybox-1.38.0.tar.bz2" -C "$XIAOKE_OPERIT_ROOT/tools/android-runtime/sources"
cp "$XIAOKE_HANDOFF_ROOT/build-materials/build_android_tools_wsl.sh" "$XIAOKE_OPERIT_ROOT/tools/android-runtime/build_android_tools_wsl.sh"
cp "$XIAOKE_HANDOFF_ROOT/build-materials/termux-proot-operit-android.patch" "$XIAOKE_OPERIT_ROOT/tools/android-runtime/patches/termux-proot-operit-android.patch"

DSH_LOADER_INFO_PY="$XIAOKE_HANDOFF_ROOT/source/scripts/proot-loader-info.py" \
OPERIT_BUILD_DIR="$XIAOKE_WORK_ROOT/proot-output" \
OPERIT_ANDROID_RUNTIME_ABIS=arm64-v8a \
OPERIT_ANDROID_RUNTIME_COMPONENTS=proot \
bash "$XIAOKE_OPERIT_ROOT/tools/android-runtime/build_android_tools_wsl.sh"
```

上游脚本即使仅选择 proot，也检查 bash/busybox 源目录，所以一并解出；它们不会因此全部构建。`ANDROID_NDK_HOME` 使用第 2 节导出的固定 NDK。修改后的完整脚本是原机器实际使用版本，`host-build-adjustments.patch` 保存与固定上游脚本的差异。

此步骤交叉编译，不运行产物。然后复制：

```bash
mkdir -p "$XIAOKE_HANDOFF_ROOT/source/android/app/src/main/jniLibs/arm64-v8a"
cp "$XIAOKE_OPERIT_ROOT/apps/flutter/app/android/app/src/main/jniLibs/arm64-v8a/liboperit_proot.so" "$XIAOKE_HANDOFF_ROOT/source/android/app/src/main/jniLibs/arm64-v8a/libdsh_proot.so"
cp "$XIAOKE_OPERIT_ROOT/apps/flutter/app/android/app/src/main/jniLibs/arm64-v8a/liboperit_loader.so" "$XIAOKE_HANDOFF_ROOT/source/android/app/src/main/jniLibs/arm64-v8a/libdsh_proot_loader.so"
```

记录新输出哈希及编译路径。原产物哈希只描述原路径构建，runner 的 talloc 路径字符串会影响字节；不要以哈希不同直接认定失败，也不要宣称已证明逐字节复现。`source/THIRD_PARTY_NOTICES.md` 仍是旧上游声明，最终打包需更新准确来源/输出清单。对应源码、补丁和许可必须随实际交付关系整理。

## 5. 前端、Capacitor 与原生编译

```bash
export XIAOKE_PROJECT_ROOT="$XIAOKE_HANDOFF_ROOT/source"
mkdir -p "$XIAOKE_PROJECT_ROOT/android/app/src/main/assets/runtime"
cp "$XIAOKE_INPUT_ROOT/release/rootfs.bundle" "$XIAOKE_PROJECT_ROOT/android/app/src/main/assets/runtime/rootfs.bundle"
cp "$XIAOKE_INPUT_ROOT/release/runtime-manifest.json" "$XIAOKE_PROJECT_ROOT/android/app/src/main/assets/runtime/runtime-manifest.json"
printf 'sdk.dir=%s\nndk.dir=%s\n' "$XIAOKE_TOOL_ROOT/android-sdk" "$ANDROID_NDK_HOME" > "$XIAOKE_PROJECT_ROOT/android/local.properties"

cd "$XIAOKE_PROJECT_ROOT"
node "$XIAOKE_TOOL_ROOT/pnpm/bin/pnpm.cjs" install --frozen-lockfile
node "$XIAOKE_TOOL_ROOT/pnpm/bin/pnpm.cjs" run build
node "$XIAOKE_TOOL_ROOT/pnpm/bin/pnpm.cjs" run android:sync

cd android
gradle -g "$XIAOKE_WORK_ROOT/gradle-cache" --no-daemon :app:compileDebugKotlin
```

根 pnpm workspace 包含 `harness-web`，并提供 Gradle legal asset 检查所需 `@deepseek-ai/dsh-web-frontend@0.2.1-rc.1` 许可证。不要删除 legal 检查来跳过缺依赖。上游 Gradle wrapper JAR 本来不提交；上述步骤直接用固定 Gradle，若改用 wrapper，按上游 `bootstrap:wrapper` 生成。

原工作已经分别完成依赖安装、较早一次 sync、最新前端生产构建和最新 Kotlin 编译。新增最终界面之后没有重新 sync；下一轮须执行。原 rootfs 仍包含旧 DSH/Google SDK，不会因上述前端 sync 自动升级。

## 6. 继续测试与打包前

下一轮可按改动执行 `pnpm run lint`、针对性 Vitest/脚本测试、Gradle `lint testDebugUnitTest`、本地协议/SQLite/备份测试；本交接不宣称它们已通过。具体优先项见 HANDOFF。

浏览器开发服务器由 `vite.config.ts` 配置为 4173；日志也是 4173。旧 `evidence/inspect-ui.mjs` 为保留失败过程，写死 5173，**不要直接沿用而认为界面测试有效**。不应在原截屏上美化或伪造成功。

只有源码完成并检查后才构建交付 APK。新独立签名尚未建立；`android/keystore.properties` 和 keystore 必须本地忽略、安全保管，不放 Git。旧构建脚本“团队签名”的词句属于上游遗留，不表示掌握上游签名或已完成新签名方案。

禁止把本包的源代码交接理解成已获真实 API 调用、设备安装、平台登录或外发许可。不得使用曾在聊天中出现的 API key。
