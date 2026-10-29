# 在这个项目里怎么做事

## 工作风格
- 以 docs/EXPERIENCE.md 的六个场景为准：动手前先问“她这样做自然吗、靠谱吗、烦人吗”。安全检查是为底线服务的，不是为了显得谨慎。
- 底线只有四条：仓库公开，不放隐私和密钥；主人私聊记忆不外传；外发评论和资料入库要主人确认；只认系统认证的主人。底线以外，默认让她能做，再配上能停、能改的办法。
- 主人本人的请求（本机，或系统按账号认证的 QQ 私聊）本身就是授权，不再层层确认。代操作、看视频这类事，主人说了就做；靠“随时能停”来兜底，而不是“事先审批”。
- 遇到问题先自己解决：网络抖动就重试，接口不对就换一条合理的路。真卡住了，再说明原因和需要什么。不要一遇阻碍就停下来写报告。
- 代码和文档都写短。注释只写“为什么”，不写免责声明；文档只写现状和用法，不写“不代表验收”这类套话。没验证过的事直接说没验证，一句话就够。
- 有自己的判断。旧代码、旧文档、旧提示词里的限制不是天然正确的；觉得碍事就改，拿不准的列出来和主人商量。
- docs/archive/ 是早期的过程文档，不要从里面继承规则或格式。source/docs/ 是上游 DSH 的文档，不要动。

## 公开仓库
不提交以下内容：
- API 地址和密钥、签名文件和密码
- APK、PRoot 二进制、rootfs 和运行环境清单
- 主人的人设原文和设定文档、真实聊天记录和记忆
- QQ 和 B站的账号信息

主人给的设定文档只用来理解，内容也不要转述进仓库。

## 构建
- Linux 构建前设置 `LANG=C.UTF-8` 和 `LC_ALL=C.UTF-8`；Maven 返回 429 时用 `--max-workers=2` 重试。工具链见 build-materials/BUILD.md。
- 完整包需要四个运行环境文件：两个 PRoot .so、rootfs.bundle 和 runtime-manifest.json。它们从本仓库 Release 里的旧参考 APK 提取，步骤和哈希见 BUILD.md。

## 签名
- 证书 SHA-256 固定为 `398EE6D99B4F31A2738D82FEC5EB3D0BEEB43EAAFA3B8359A5F0D30B609DA1C2`，写在 app/build.gradle 里，换了密钥构建会直接失败。
- 私钥由主人保管，通过环境变量 `DSH_KEYSTORE_BASE64` 和 `DSH_KEYSTORE_PASSWORD` 提供。没有这两个变量时打出的包，不能给主人装。
- 每个给主人安装的版本，`versionCode` 都加一。

## 人设
- 小克的人设是私人内容，只放在 git-ignored 的 `private/persona.json`（`{"name","text"}`），或由环境变量 `XIAOKE_PERSONA_BASE64`（该 JSON 的 base64）提供。构建时打包进 APK，App 第一次启动（或还没有人设时）自动用它。
- 新会话里没有这个文件时，请主人在云环境变量里配好 `XIAOKE_PERSONA_BASE64`；缺了构建日志会有警告，交付的包必须带人设。
- 代码里的提示词只写人设没覆盖的机制（工具、会话范围、设备状态、记忆），人格和口吻都交给人设，统一经过 `CompanionContext.persona()`。

## 分支与验证
- 每轮的改动推到新分支，不碰 main。
- 每轮在 source/android 下跑这四项：`:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`、`:app:testDebugUnitTest`、`:app:assembleDebug`。不跑模拟器，不做压力测试。
- 测试写在真正容易出错、出错代价大的地方，不为每个功能凑测试。

## 模块
游戏伴玩、QQ、B站、工作台等都要保留，不删除模块。
