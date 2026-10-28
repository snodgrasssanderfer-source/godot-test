# PRoot 原生组件回传：proot-20261028T135544Z-reuse

**本轮复用已核实的源码重建产物，没有重新编译，也没有运行 Android 二进制。** 产物、源码归档和所用脚本均与固定交接记录相符。只新增此目录，其他助手/游戏源码完全未改；Windows 端未推送修改不在本次操作范围。

基线：`b93d665a5a3828f1838d9b01e3503248fb379ec5`。Android 来源为 DSH v0.3.0 / `e9be0e43629f8b744991c2cf9efd160ca7d41abc`；Operit2 来源 `dc4c3a9405dc7ed3ef69b2ac9a6ace65374d77cf`；NDK r29-beta4 / `29.0.14033849-beta4`。

## 可取回产物

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `libdsh_proot.so` | 257088 | `caa5d7d65952b258b3427322448db91b5eebea4aa72ab66c77174b86c8437f94` |
| `libdsh_proot_loader.so` | 1632 | `f149774236db1e69b36cc1e4ed3866c7094db2eee52da0d4956aa63a9bb26929` |

分别复制自此前构建出的 `liboperit_proot.so` / `liboperit_loader.so`，仅改变文件名，不修改字节。虽使用 `.so` 后缀以便安卓打包，这里是 PRoot 的 PIE 可执行程序与独立静态 loader；不是 Windows DLL，也不是用于 `dlopen` 的 JNI 接口库。

## 本轮检查结果

- 实际主机：Debian GNU/Linux 13.6、Linux 6.18.55、`uname -m = x86_64`。
- GCC 14.2.0、GNU Make 4.4.1、Python 3.12.14、GNU readelf 2.44、GNU patch 2.8；NDK clang 21.0.0，目标 `aarch64-unknown-linux-android24`；具体输出和退出码在 `environment.json`。
- 官方 NDK ZIP SHA-256 `c5db6031a549b5274eed920c45ee3c7da1ac3f1f416cbb512dd9d88417bdf009`；正式对应源码包 SHA-256 `3caab4b8457540dc693e617a7da843191e507cf0903b2424773448bf0b3709cc`。本轮均实际读取归档核对，不仅检查文件名。
- Operit2、Termux PRoot、talloc、脚本所需 bash/busybox 归档及三份固定脚本/补丁均匹配，详情见 `input-verification.json`。
- 两份产物均为 little-endian ELF64/AArch64。runner 为 PIE/DYN，解释器 `/system/bin/linker64`，NEEDED 为 `libdl.so`、`libc.so`；loader 为静态 EXEC，无解释器或动态依赖。
- runner 三个 LOAD、loader 两个 LOAD 的 `p_align` 均为 `0x4000`（16384），文件偏移/虚拟地址同余检查通过；未请求可执行栈。原始 `readelf` 结果在 `elf/`，可运行 `python3 verify-artifacts.py` 独立复核，脚本不会执行这两个文件。
- 本轮核验命令退出码均为 0，记录在 `command-results.json`。本轮未更改构建脚本。

**历史构建退出码未单独落盘，不能补填一个推测的 0。** 上轮使用命令见 `BUILD_COMMAND.previous.txt`，保存的完整编译日志见 `build-log.previous.txt`；固定检查点已经记录这两份精确产物摘要。本轮按用户“能证实的既有结果可复用”的要求验证并复用。`output-manifest.json` 明确区分历史构建时间、本轮核验和没有新编译的事实。

静态检查通过不等于 Android 16 真机通过；未安装手机、未测试 PRoot 启动/系统调用/运行 rootfs，也未测试最终 APK 的 ZIP 对齐、原生解包或 Android 厂商限制。没有构建 APK、前端或 Gradle，没有调用中转或操作账号。

## 对应源码与许可

为让产物与对应源码一起取得，`corresponding-source/` 附带本次实际使用且未改动的 Termux PRoot 与 talloc 上游源码归档（合计约 0.94 MiB），不是 SDK 或运行缓存；摘要已列在 `input-verification.json`。`scripts/` 包含本次实际使用的完整构建脚本、PRoot Android 补丁、loader-info Python 辅助脚本及相对固定 Operit2 的既有主机适配补丁。原许可分别保留于 `licenses/`，源码归档内也保留原声明。

完整原始对应源码取得方式：

1. 从 [DSH v0.3.0 正式发布](https://github.com/dphmoblie/deepseek-harness-android/releases/tag/v0.3.0) 下载 `corresponding-source.tar.gz`，核对上述 SHA-256。
2. 解出内层 `proot-corresponding-source.tar.gz`，SHA-256 为 `af69c3c41dd4d8a050c57ff874f87f0cc77aefb19a00697d051bc24ebf766460`；其 `upstream/` 内含固定 Operit2、PRoot、talloc、bash、busybox 源码。
3. 只需重建时，按固定检查点的 `build-materials/RESTORE.md` 第 4 节操作，采用这里相同脚本和补丁；设置 `OPERIT_ANDROID_RUNTIME_ABIS=arm64-v8a`、`OPERIT_ANDROID_RUNTIME_COMPONENTS=proot`、独立 `OPERIT_BUILD_DIR`、固定 `ANDROID_NDK_HOME` 和 `DSH_LOADER_INFO_PY`。无须 APK/rootfs/前端/Gradle。上游脚本即使只构建 proot 也要求 bash/busybox 源目录存在。

既有主机适配仅两项：把构建目录从固定 HOME 缓存位置移到显式 `OPERIT_BUILD_DIR`；用 Python 读取 loader 符号，避免当前 awk 不支持的操作。二者已在原交接检查点存在，本轮没有新改动。源路径可能进入 runner 的字符串，换目录重编译可改变哈希，不要求强行匹配本次字节。

这里没有覆盖助手项目旧 `THIRD_PARTY_NOTICES.md`，因为用户要求只新增回传目录。Windows 集成时须采用本目录的实际原生输出摘要与来源；旧 NOTICE 中 runner `cb40…` 声明仍不能作为本次产物摘要。

## Windows 取件，不覆盖未推送源码

不要把云端旧源码整仓覆盖到 Windows，不要 reset、强推或切换当前开发工作区到旧检查点。可在 Windows 当前仓库只取回这个新增目录：

```powershell
git fetch origin dot/xiaoke-proot-return-20261028-135544
git restore --source=FETCH_HEAD -- projects/xiaoke-android/build-materials/cloud-return/proot-20261028T135544Z-reuse
```

核对两文件 SHA-256 后，仅把它们复制到 Windows 当前助手工程实际的 `android/app/src/main/jniLibs/arm64-v8a/`。不要用本分支的 `source/` 替换 Windows 代码。是否修改本机已有原生文件，由用户按当前工程状态决定；回传本身没有做该操作。

本回传不含 SDK、缓存、rootfs、密钥、账号数据、私人附件、签名文件或 APK；未建立 Release。公开内容仅为此组件产物、构建/核验记录、脚本与必要对应源码/许可。
