# 独立 QQ 通道部署材料

这是可迁移部署方案。2026-10-29 已在当前任务环境完成固定镜像的受限启动与登录前鉴权测试，测试结束后停用；没有登录 QQ，也没有建立长期托管服务。APK 中的推理、记忆与收发策略仍运行在手机；NapCat 是独立 QQ 登录/消息服务。手机暂停、断网或系统终止助手后，不会由另一个未部署的云端模型继续回复。

## 固定输入

- [官方 NapCat-Docker](https://github.com/NapNeko/NapCat-Docker)，审读脚本提交 `42e4f9e8373c739f4d2ee599980ab8f87cff39b6`。
- Docker Hub `mlikiowa/napcat-docker:v4.18.41` 的多架构摘要：`sha256:b101b8968cc31ae428ec0f8e117386e4ced7773b2d8226ed9f7ddfcec55f1b7e`。
- amd64 摘要 `sha256:69c5ca84ec226d658d0d5cd19997c6c89169da3206faa2948c2dc67d669b6406`，压缩层约 612 MB；arm64 摘要 `sha256:c5a74db0e7ce14dcc42c1ed1facdc8b0a47b29de65afe069c795608c149fa2cb`。
- 已从镜像内部核对 NTQQ `3.2.34-53644`，启动日志确认 NapCat Core `4.18.41`。镜像内 Shell ZIP 的 SHA-256 为 `6801f93e533846cea05f4f531874a0e84371ab2331c5585b57cd7e3b869cf96b`。
- [官方 OneBot 配置说明](https://napneko.github.io/config/basic)：正向 WS、array 消息、独立 token、reportSelfMessage。APK 通过 `get_login_info` 核对账号；收到自身消息后暂缓该会话自动回复。

## 启动边界

`minimal-entrypoint.sh` 是本项目新写的最小入口：解压镜像内的 Shell 文件、启用虚拟显示、启动 QQ。没有采用上游脚本中的主机环境标识改写。以非 root 用户运行，移除 Linux capabilities，不挂载 Docker socket、不使用 host network，端口只绑定宿主 `127.0.0.1`。QQ 的 `--no-sandbox` 仅位于这个受限容器内；不把它变成特权容器来解决失败。

由主人在选定主机授权部署后，先拉取上述固定摘要的镜像，再执行 `python3 prepare-shell-archive.py` 和 `python3 init-config.py`。前者从已下载的镜像复制并校验 Shell ZIP，不启动 QQ；后者生成本机配置，不打印 token、不登录、不下载。若已有配置则停止，防止覆盖账号和访问令牌。宿主目录所有者须匹配 NAPCAT_UID / NAPCAT_GID。

运行文件放在普通私有工作目录 `data/runtime`，配置和登录目录另行挂载。保留容器临时目录的默认执行限制；不 remount、不关闭宿主 noexec、不增加容器 capabilities。固定镜像中的 ZIP 原本仅 root 可读，因此需要上述复制步骤供非 root 服务读取。

普通主机使用 `docker compose up -d`。受管云必须先按当前云环境技能配置代理和 CA 文件，再使用 `docker compose -f compose.yaml -f compose.cloud.yaml up -d`；不得通过关闭 TLS 校验应对网络失败。默认 `restart: no`，因为本轮未确定可长期托管的主机。确认宿主在线保障、重启恢复和维护责任后才改为 `unless-stopped`。

6099 是 WebUI，3001 才是消息接口。扫码登录、帐号授权与对外发送单独由主人决定。日志可能包含登录二维码或管理入口，仅保留在私有主机，不上传报告。数据目录 `data/qq` 含登录状态；`data/config` 含访问凭据，禁止公开 Git。

## 手机连接

需要一个主人控制的可达地址，提供有效 TLS 的 WSS 接入并保留 `Authorization: Bearer ...`。优先已有私有网络/反向代理；此材料不会自动开放公网、注册域名或切换到 dot 的其他环境。把 WS 反向代理到 `127.0.0.1:3001`，不要把管理端 6099 当作消息接口。

在 APK 的设备页填 WSS URL、NapCat 实际登录 QQ 数字 ID、独立 OneBot token。开启联网后连接，核对成功才绑定好友/群 ID。未授权会话丢弃正文；图片另行开启；自动发言另行开启。主人本人 QQ 的记忆共享须绑定确切数字 ID，群聊永远不因此获得主人记忆或设备权限。

## 验收与迁移

已验证普通目录方案下原生模块可加载、WebUI 返回 HTTP 200、错误凭据访问管理 API 返回 Unauthorized。仅绑定 127.0.0.1 的 6099/3001；未登录时 OneBot 收发尚不能验证。当前环境的长期在线、手机入口和任务结束后的保留期限未知。登录后才可验证真实账号握手、接收、群/私聊隔离、主动发送与同步；本次不作该验收声明。

迁移到主人选定的常驻主机时，先停旧实例，再私下转移加密的数据备份，核对目录权限与镜像摘要；不可让两个实例同时争用登录状态。迁移和登录需要主人批准，不能因为当前环境不持久而自行改用其他云电脑。
