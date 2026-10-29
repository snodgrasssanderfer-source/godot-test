# 长期规则

## 公开仓库
本仓库公开且会长期公开。禁止提交：
- API 地址和密钥、签名文件（keystore、密码配置）
- APK、PRoot 二进制（libdsh_proot*.so）、rootfs 及运行环境清单
- 用户人设原文、真实聊天记录和记忆
- QQ / B站账号信息（账号、Cookie、令牌）

需要时用 .gitignore 排除（本目录 .gitignore、source/.gitignore、source/android/.gitignore）。

## 构建
- Linux 构建必须设置 `LANG=C.UTF-8` 和 `LC_ALL=C.UTF-8`（原因见 build-materials/BUILD.md）。
- Maven 遇到 429 时用 `--max-workers=2` 重试。
- 工具链准备见 build-materials/BUILD.md。

## 签名（覆盖安装、保留数据）
- 固定签名证书 SHA-256：`398EE6D99B4F31A2738D82FEC5EB3D0BEEB43EAAFA3B8359A5F0D30B609DA1C2`，已写死在 app/build.gradle，换密钥会直接构建失败。
- 私钥只由主人保管，通过云环境的环境变量提供：`DSH_KEYSTORE_BASE64`（密钥库 base64）与 `DSH_KEYSTORE_PASSWORD`。仓库里绝不出现私钥或密码。
- 给主人安装的包必须在这两个变量存在时构建，并用 `apksigner verify --print-certs` 确认证书指纹；变量缺失时 debug 包会用临时签名（构建日志有警告），这种包不能交付。
- 每个交付版本把 `versionCode` 加一。

## 分支
每轮改动提交到新分支，不碰 main。

## 验证
默认只做基本验证（在 source/android 执行）：
- `:app:compileDebugKotlin`
- `:app:compileDebugAndroidTestKotlin`（只编译，不运行）
- `:app:testDebugUnitTest`
- `:app:assembleDebug`

不跑模拟器，不做压力测试。

## 模块
不删除已有模块。游戏伴玩、QQ、B站等都是保留目标。
