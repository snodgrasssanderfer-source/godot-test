# godot-test

用"手机 + 云端（Claude Code 云会话）"做 Godot 网页小游戏的试跑项目。

- 游戏：**Box Jumper** —— 方块左右移动、跳跃，有地面和平台，掉下去自动回到起点。
  屏幕上有虚拟按键（左下 `<` `>` 移动，右下 `^` 跳跃，支持同时按），电脑上可用方向键 / A D / 空格。
- 在线试玩：https://snodgrasssanderfer-source.github.io/godot-test/

## 目录结构

| 路径 | 说明 |
| --- | --- |
| `game/` | Godot 项目（`project.godot`、`main.tscn`、`scripts/`、`export_presets.cfg`） |
| `tools/install_godot.sh` | 下载并安装 Godot + 网页导出模板（校验 SHA512） |
| `tools/export_web.sh` | 无界面导出网页版到 `build/web/` |
| `tools/godot-headless.sh` | 给 MCP 用的包装脚本：总是加 `--headless` 启动 Godot |
| `.mcp.json` | Godot MCP 配置 |
| `.github/workflows/deploy-web.yml` | 自动导出并发布到 GitHub Pages |

## 安装了什么

| 组件 | 版本 | 位置 |
| --- | --- | --- |
| Godot（Linux x86_64，标准版非 .NET） | 4.7.2-stable | `/opt/godot/`，软链接 `/usr/local/bin/godot` |
| 网页导出模板（只解压了 `web_*`） | 4.7.2.stable | `~/.local/share/godot/export_templates/4.7.2.stable/` |
| MCP 服务器 Coding-Solo/godot-mcp | 0.1.1（npm 包 `@coding-solo/godot-mcp`） | 通过 `npx` 按需下载 |
| Node.js（云环境自带） | 22 | — |

下载来源是 GitHub Releases（`github.com/godotengine/godot/releases`）。
注意：云环境的网络策略屏蔽了 `godotengine.org` / `downloads.godotengine.org`，但 GitHub Releases 可以下载。
完整模板包有 1.2 GB，脚本下载后只保留网页模板（约 90 MB）。

新会话里重新安装（容器是临时的，每次新会话都要装一次）：

```bash
tools/install_godot.sh            # 默认 4.7.2-stable，也可以传版本号，例如 4.8-stable
godot --headless --version        # 确认能运行
```

## MCP 怎么配的

`.mcp.json`（仓库根目录，Claude Code 打开仓库时会自动读取）：

```json
{
  "mcpServers": {
    "godot": {
      "command": "npx",
      "args": ["-y", "@coding-solo/godot-mcp@0.1.1"],
      "env": {
        "GODOT_PATH": "/home/user/godot-test/tools/godot-headless.sh",
        "DEBUG": "false"
      }
    }
  }
}
```

要点：
- godot-mcp 的 `run_project` 启动 Godot 时**不加 `--headless`**，云容器里没有显示器会直接失败。
  所以 `GODOT_PATH` 指向 `tools/godot-headless.sh`，它会以 `godot --headless ...` 的方式启动。
- `GODOT_PATH` 必须是绝对路径。云会话里仓库固定在 `/home/user/<仓库名>`，复用到其他仓库时改成对应路径。
- 已验证：`run_project`（projectPath 填 `/home/user/godot-test/game`）+ `get_debug_output` 能拿到
  `print` 输出和报错（例如 `ERROR: ...` 以及 `res://scripts/main.gd:26` 这样的脚本行号）。
- MCP 不可用时，直接用命令行效果一样：

  ```bash
  godot --headless --path game --quit-after 120   # 跑 120 帧后退出，看输出有没有 ERROR
  ```

## 重新导出网页版

```bash
tools/export_web.sh     # 输出到 build/web/（已在 .gitignore 里）
```

导出预设在 `game/export_presets.cfg`，关键设置：
- `variant/thread_support=false` —— 关闭线程支持，用 `web_nothreads_*` 模板。
  这样不需要 COOP/COEP 响应头（`SharedArrayBuffer`），GitHub Pages 能直接运行。
  浏览器控制台会打印 `Build configuration: ... single-threaded`。
- 渲染器为 `gl_compatibility`（网页必须用这个）。

本地预览（模拟 GitHub Pages：普通静态服务器，没有特殊响应头）：

```bash
cd build/web && python3 -m http.server 8000
```

## 部署到 GitHub Pages

`.github/workflows/deploy-web.yml` 在 push 到 `main` 或 `claude/**` 分支时（也可以在 Actions 页面手动运行）：
1. 用 `tools/install_godot.sh` 安装 Godot 和网页模板（有缓存，第二次起很快）；
2. 用 `tools/export_web.sh` 导出；
3. 用 `peaceiris/actions-gh-pages` 把 `build/web` 推到 `gh-pages` 分支。

GitHub Pages 的来源设置为 **Deploy from a branch → `gh-pages` / (root)**
（仓库 Settings → Pages）。访问地址：`https://<用户名>.github.io/<仓库名>/`。

复用到新项目：复制 `tools/`、`.mcp.json`、`.github/workflows/deploy-web.yml`，
把 Godot 项目放进 `game/`（导出预设名叫 `Web`），改一下 `.mcp.json` 里的路径即可。
