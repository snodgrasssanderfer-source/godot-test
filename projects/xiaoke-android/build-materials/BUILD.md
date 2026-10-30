# 构建恢复

需要 Linux x86_64、Node 24.19.0、pnpm 11.19.0、JDK 21.0.13+9、Gradle 8.11.1、Android SDK/build-tools 35、NDK 29.0.14033849-beta4、CMake 3.22.1。固定 URL/摘要在 toolchain-inputs.json 与 release-inputs.json。普通 Node/npm/Gradle 依赖受 lockfile 固定。

1. 用 download-inputs.py 下载并验证 toolchain 与 release 输入到私有工作目录；不要提交下载缓存。NDK 解包保留 Unix 符号链接和执行位。
2. PRoot 构建脚本 build_android_tools_wsl.sh、补丁 termux-proot-operit-android.patch 及 proot-rebuild-status.json 固定对应来源；使用官方 corresponding-source.tar.gz 内 Operit2/Termux/talloc 对应源码，NDK r29-beta4，仅 arm64-v8a / proot。DSH_LOADER_INFO_PY 指向 source/scripts/proot-loader-info.py。原生回传参考提交 4a493c504d33017d414e00748be34aca3ba573a4。不要运行 rootfs 里的主机不兼容程序。
3. 将 liboperit_proot.so / liboperit_loader.so 分别复制为 source/android/app/src/main/jniLibs/arm64-v8a/libdsh_proot.so / libdsh_proot_loader.so。将固定 rootfs.bundle、runtime-manifest.json 放到 source/android/app/src/main/assets/runtime/。
4. Linux 构建必须使用 UTF-8 语言环境：执行 Gradle 前设置 `LANG=C.UTF-8` 与 `LC_ALL=C.UTF-8`。原因：单元测试用中文命名（如 `ApplicationSearchTest` 内的中文测试名），Kotlin 编译会生成含中文的 .class 文件名；容器默认的 POSIX 语言环境下 JVM 的 `sun.jnu.encoding` 不是 UTF-8，`:app:compileDebugUnitTestKotlin` 会以 `InvalidPathException: Malformed input or input contains unmappable characters` 失败。这是环境问题，不需要改代码。Maven Central 偶发 429 时用 `--max-workers=2` 重试即可。
5. JAVA_HOME / ANDROID_HOME / ANDROID_SDK_ROOT / ANDROID_NDK_HOME / ANDROID_USER_HOME / GRADLE_USER_HOME 使用本机路径；source/android/local.properties 配置 sdk.dir、ndk.dir。代理和 CA 按环境配置，保留 TLS 验证，不提交凭据。
6. 在 source 执行 pnpm install --frozen-lockfile、pnpm run build、pnpm exec cap sync android。首次私有签名可运行 scripts/create-companion-signing.py；已有安装包必须继续使用相同签名，不能覆盖不同签名或通过卸载丢掉数据。签名私钥绝不入库。
7. 设置 GRADLE_BIN 后运行 scripts/build-companion-private.sh，或从 android 执行 Gradle :app:lintRelease :app:testDebugUnitTest :app:assembleRelease。输出 app/build/outputs/apk/release/app-release.apk。编译不是运行验收。


## 签名
交付给主人的包必须带固定签名，否则无法覆盖安装、会丢数据。构建前设置环境变量 `DSH_KEYSTORE_BASE64`、`DSH_KEYSTORE_PASSWORD`（别名固定 `dsh-mobile`，密钥密码与库密码相同）。build.gradle 会核对证书 SHA-256 `398EE6D99B4F31A2738D82FEC5EB3D0BEEB43EAAFA3B8359A5F0D30B609DA1C2`，不符即失败。构建后用 `apksigner verify --print-certs` 复核。

## 人设
构建时如果存在 `projects/xiaoke-android/private/persona.json` 或环境变量 `XIAOKE_PERSONA_BASE64`，会把人设打包进 APK（git-ignored，不入库）。缺少时构建只告警，装上的 App 没有默认人设。

## Ubuntu 运行环境（v0.3.7）
从 0.7.0 起内嵌上游 DSH v0.3.7 的运行环境（上游 CI 按 `scripts/build-embedded-runtime.py` 生成，含构建工具与 Shizuku 插件更新）。两个文件放到 `source/android/app/src/main/assets/runtime/`，不入库：

| 文件 | 来源 | 字节 | SHA-256 |
|---|---|---|---|
| runtime-manifest.json | https://github.com/dphmoblie/deepseek-harness-android/releases/download/v0.3.7/runtime-manifest.json | 1057 | be350fc22da35116a6bdc474e6e728b01b9d92f76969b5f66d1e35a6b4dcf6aa |
| rootfs.bundle | 同一 Release 的 rootfs.bundle | 407862424 | 5714e1fa43596803dfdbbf76f9a8dba32aafaf2bb080b1ca1a9c5fb1cdc0daef |

rootfs 的哈希与清单里的 `rootfs.sha256` 一致才能用。两个 PRoot .so 不变，仍按下一节从旧 APK 提取。

## 本地向量模型（0.8.0 起）
中转没有向量接口时，记忆检索用手机本地的 bge-small-zh-v1.5（BAAI，MIT）。两个文件放到 `source/android/app/src/main/assets/embedding/`，不入库：

| 文件 | 来源 | 字节 | SHA-256 |
|---|---|---|---|
| bge-small-zh-v1.5.onnx | https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/75c43b069aac4d136ba6bc1122f995fedcfd2781/onnx/model_quantized.onnx | 24010842 | 15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc |
| vocab.txt | 同一提交的 vocab.txt | 109540 | 45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c |

缺了这两个文件也能构建，只是中转不支持向量时退回关键词和实体检索。

## 从旧 APK 提取运行环境（PRoot .so；0.6.0 及以前的 rootfs）

没有自行重建 PRoot 时，可以从此前构建的旧 APK 中取出运行环境文件。旧 APK 和取出的文件都放在仓库目录之外的工作目录，**绝不提交**（已由 .gitignore 排除）。

1. 旧 APK 在本仓库 Release 标签 `xiaoke-old-reference-0.2.0-20261029` 的附件 `Xiaoke-0.2.0-arm64-old-reference.apk`（320531589 字节，SHA-256 `14c0bf968695596ed46d213a3a0e75dbb840be0bcfb77a8e9ecff6c100f5eadf`）。下载到仓库外，先核对 SHA-256。只解包，不运行其中任何程序。
2. 取出以下四个文件，逐一核对 SHA-256：

| APK 内路径 | 字节 | SHA-256 | 核对依据 |
|---|---|---|---|
| lib/arm64-v8a/libdsh_proot.so | 257088 | caa5d7d65952b258b3427322448db91b5eebea4aa72ab66c77174b86c8437f94 | proot-rebuild-status.json |
| lib/arm64-v8a/libdsh_proot_loader.so | 1632 | f149774236db1e69b36cc1e4ed3866c7094db2eee52da0d4956aa63a9bb26929 | proot-rebuild-status.json |
| assets/runtime/rootfs.bundle | 317842454 | f7e9736ebc359a761adaf56bf02fe5ac64877c73085aaa1df0ffbe876c7708fb | release-inputs.json |
| assets/runtime/runtime-manifest.json | 1035 | 09f709fd1439f4c305b7ed9012287a0e437b993cfef9dacc387da09b10aa13d4 | release-inputs.json |

   loader 内含构建路径信息；自行重建时它的哈希可能与表中不同，属已知情况。除 loader 以外的文件对不上就停止，不要修改清单去迁就。
3. 两个 .so 放到 source/android/app/src/main/jniLibs/arm64-v8a/，rootfs.bundle 与 runtime-manifest.json 放到 source/android/app/src/main/assets/runtime/，然后按上文构建。
4. 构建后可用 `unzip -p <apk> <路径> | sha256sum` 确认新 APK 内的四个文件与上表一致。完整 debug APK 约 398 MiB。
