# 小克 · 首次使用

## 安装
- 手机上还是 0.3.0 以后的版本：直接覆盖安装，记忆和日记都在。**不要先卸载。**
- 还是旧的 0.2.0 参考包，或者临时签名的测试包：签名不同，要先卸载一次（会清掉旧数据）。旧版有“生活 → 备份”的话，先导出再到新版导入。
- 系统提示有风险时选“仍然安装”。包约 312 MiB，第一次打开要解包运行环境，会慢一点。

## 第一次打开（跟着向导点）
1. **连上模型**：填中转地址和 API Key，点“保存并联网”。日常用 gemini-3.8-flash，遇到代码、日志、长文分析她会自己换 gemini-4-argon。
2. **每天的额度**：两个数字。她自己逛群、看视频、写日记用“活动”额度；和你聊天用你的额度。每天零点重新计数。
3. **让她一直在**：从上到下逐个点“去设置”：
   - 通知、悬浮窗：系统页里打开。
   - 电池优化：弹窗里点“允许”。
   - 自启动、后台高耗电：会打开手机管家，找到“小克”打开开关，回来点“自启动和后台耗电都已打开”。（vivo 在“i管家 → 应用管理 → 自启动”和“电池 → 后台耗电管理”。）
   - 后台常驻、显示悬浮球：点一下就好。
4. **QQ（可以先跳过）**：见下一节。

人设已经装好，她一开始就是小克；“角色”页能看和改。

## 连上 NapCat
App 只用 `wss://` 连接：你和她的私聊会经过这条线，不能明文走公网。服务器上加一层 TLS 就行，以 nginx 为例（NapCat 的 WebSocket 服务器假设在 3001 端口）：

```sh
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=napcat" \
  -keyout /etc/nginx/napcat.key -out /etc/nginx/napcat.crt
```

```nginx
server {
    listen 3002 ssl;
    ssl_certificate     /etc/nginx/napcat.crt;
    ssl_certificate_key /etc/nginx/napcat.key;
    location / {
        proxy_pass http://127.0.0.1:3001;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 1d;
    }
}
```

然后在防火墙里只开放 3002，不对外开放 3001。

在 App 的“连接 → QQ（NapCat）”填：
- 地址：`wss://服务器IP:3002`
- Token：NapCat 里设置的那个
- 你的 QQ 号：她只认这个号是你
- 允许她发言的群：群号，空格隔开

第一次连接会显示服务器证书指纹。在服务器上运行 `openssl x509 -noout -fingerprint -sha256 -in /etc/nginx/napcat.crt`，两边一致就点“信任”，之后就记住了。如果服务器有域名和正规证书，就不会弹这一步。她自己的 QQ 号会在连上时自动读出。

## 日常
- 点悬浮球直接打字聊天，发完键盘自动收起；点“收起”或再点球关掉面板。
- 长按悬浮球五秒：她停下所有点击、输入和外发，说“继续”恢复。
- 她攒下的评论草稿和想存的图片在“活动 → 待你决定”，悬浮球角标是数量。
- 日记在“生活 → 日记”，她每晚写一篇。
