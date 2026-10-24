# godot-test

用"手机 + 云端（Claude Code 云会话）"做 Godot 网页小游戏的试跑项目。

- 游戏：**Box Jumper** —— 方块左右移动、跳跃，有地面和平台，掉下去自动回到起点；
  场景里的木箱是用 Blender 建模、导出 .glb 再导入 Godot 的（第二轮：美术管线验证）。
  屏幕上有虚拟按键（左下 `<` `>` 移动，右下 `^` 跳跃，支持同时按），电脑上可用方向键 / A D / 空格。
- 在线试玩：https://snodgrasssanderfer-source.github.io/godot-test/

## 目录结构

| 路径 | 说明 |
| --- | --- |
| `game/` | Godot 项目（`project.godot`、`main.tscn`、`scripts/`、`assets/`、`export_presets.cfg`） |
| `art/crate/` | 木箱的 Blender 源文件、建模脚本 `make_crate.py`、预览图 `crate_preview.png` |
| `tools/install_blender.sh` | 安装 Blender（apt）+ numpy |
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
| Blender（Ubuntu 软件包） | 4.0.2 | `/usr/bin/blender` |
| python3-numpy（Blender 的 glTF 导出器需要） | Ubuntu 24.04 源 | — |
| MCP 服务器 sandraschi/blender-mcp | git 提交 `b786a39`（2026-10-24） | 通过 `uvx` 按需安装 |
| Node.js / uv（云环境自带） | 22 / — | — |

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

## Blender 美术管线

### 安装

```bash
tools/install_blender.sh          # apt 安装 Blender 4.0.2 + python3-numpy
blender --background --version    # 确认能运行
```

为什么是 4.0.2：云环境的网络策略屏蔽了 `download.blender.org` 及其镜像，只能用 Ubuntu 源里的版本。
想用最新版（5.x）的话，在环境设置的 Network access 里放行 `download.blender.org`，
然后下载官方 Linux tar.xz 解压，再把 `.mcp.json` 里的 `BLENDER_EXECUTABLE` 改成解压后的 `blender` 路径。
（PyPI 上的 `bpy` 5.x 也能装，但它只是 Python 模块，没有 `blender` 命令，MCP 用不了。）

Ubuntu 版 Blender 用的是系统 Python，所以 glTF 导出需要 `python3-numpy`，不装会报 `No module named 'numpy'`。

### Blender MCP

`.mcp.json` 里的 `blender` 服务器：

```json
"blender": {
  "command": "uvx",
  "args": ["--from", "git+https://github.com/sandraschi/blender-mcp@b786a397747970a17b74983c9aab657b8a014789",
           "blender-mcp", "--stdio"],
  "env": { "BLENDER_EXECUTABLE": "/usr/bin/blender" }
}
```

要点：
- 用的是 **sandraschi/blender-mcp**，它会自动以 `blender --background` 启动 Blender，不需要界面也不需要装插件。
- **不要写成 `uvx blender-mcp`**：PyPI 上叫 `blender-mcp` 的包其实是 ahujasid 那个（不支持后台模式），
  所以这里用 `--from git+...` 从 GitHub 安装，并固定到某个提交，避免上游改动导致不可用。
- 已验证：工具列表能列出 72 个工具；`script_execute`（参数 `code`）能在后台 Blender 里跑 bpy 代码并返回 `print` 输出。
  脚本出错时会返回 `success: false`，但**不带 Python 报错详情**，要看完整报错就改用命令行跑脚本。
- 每次调用都启动一个新的 Blender 进程，调用之间场景不保留。建模这类多步操作，最好写成一个完整脚本一次跑完。

### 从建模到导入 Godot

1. **建模 + 渲染预览 + 导出**（一个脚本全部完成，可重复运行）：

   ```bash
   blender --background --factory-startup --python art/crate/make_crate.py
   ```

   这个脚本会：
   - 用方块拼出木箱：浅色面板 + 深色框架（12 根边框梁，互不重叠）+ 四面斜撑，
     加 1 段倒角，共 204 个三角面；材质用 Principled BSDF 的颜色（glTF 会带上）；
   - 用 Cycles CPU、32 采样、800×600 渲染预览到 `art/crate/crate_preview.png`
     （色彩管理设为 Standard，默认的 AgX 会让颜色发灰）；
   - 只导出木箱，生成 `game/assets/crate.glb`（原点在底面中心，1 米见方）；
   - 保存可编辑的源文件 `art/crate/crate.blend`（预览用的地面和灯光不参与渲染）。

2. **导入 Godot**：.glb 放进 `game/assets/` 后运行一次 `godot --headless --path game --import`，
   会生成 `crate.glb.import`（这个文件要提交）。

3. **在 2D 游戏里使用 3D 模型**：游戏是 2D 的，所以 `main.tscn` 里有一个 `CrateRender`（SubViewport，
   有独立的 3D 世界和透明背景），里面放着 `crate.glb` 的实例，再加上正交相机、平行光和环境光。
   `scripts/main.gd` 把这个 SubViewport 的画面当作每个木箱 `Sprite2D` 的贴图，
   再配一个 48×48 的 `StaticBody2D` 碰撞体。木箱的位置在 `CRATES` 列表里改。
   以后换模型：改脚本、重跑第 1 步、重新导出网页版就行。

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

复用到新项目：复制 `tools/`、`.mcp.json`、`.github/workflows/deploy-web.yml`（要做美术再加上 `art/` 里的脚本写法），
把 Godot 项目放进 `game/`（导出预设名叫 `Web`），改一下 `.mcp.json` 里的路径即可。
