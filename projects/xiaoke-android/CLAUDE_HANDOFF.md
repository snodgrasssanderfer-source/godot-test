# 后续协作交接

先读 README、docs/REQUIREMENTS.md、docs/COLLABORATION-CONTRACT.md、PROGRESS.md、UPSTREAM_REUSE.md。以分支当前 commit 为准；历史候选包不是本轮完成物。

只在独立工作分支修改 projects/xiaoke-android，不覆盖 main、其他开发分支、未推送本地改动或游戏目录。不要强推。A/B/C 所有权见契约，主入口和共享 schema 由集成方负责。

当前源码完整在 source/，harness-web 为普通文件；构建材料在 build-materials/。工具与 runtime 根据固定公开输入恢复，不上传 SDK/cache/rootfs。保留上游许可证与修改说明。项目组合包含 AGPL/GPL 组件，不把原包 MIT 字段解释为整个 APK 都是 MIT。

优先推进可用陪伴体验、真实数据流与恢复，不重复整轮 APK 审计。完成新模块后用小提交保存、更新实际复用与测试证据；不要把 mock、按钮或编译成功称为设备/账号通过。

真实模型付费调用、账号登录/发消息/点赞/评论、手机安装、用户电脑部署均不在本轮执行授权内。不得读取或使用旧聊天泄露凭据。任何用户数据进入公开 Git 前必须按产品设计端侧加密；本轮不上传真实数据。

新 APK 必须是本轮实际集成构建；签名材料保留本机且不入库。记录包名/版本/摘要/签名公钥指纹和测试边界，APK 以私有附件交付，不自动创建公开 Release。需要声线、素材、首个 RPG 或实际服务器条件时，说明确切缺口；不要编造支持。
