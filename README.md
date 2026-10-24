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
| `tools/setup_env.sh` | **环境初始化脚本**：安装 Godot + 网页导出模板 + Blender + godot-mcp（校验哈希，可重复运行） |
| `tools/export_web.sh` | 无界面导出网页版到 `build/web/` |
| `tools/godot-headless.sh` | 给 MCP 用的包装脚本：总是加 `--headless` 启动 Godot |
| `.mcp.json` | Godot MCP + Blender MCP 配置 |
| `.github/workflows/deploy-web.yml` | 自动导出并发布到 GitHub Pages |

## 安装了什么

| 组件 | 版本 | 位置 |
| --- | --- | --- |
| Godot（Linux x86_64，标准版，不是 .NET 版） | 4.7.2-stable | `/opt/godot/`，软链接 `/usr/local/bin/godot` |
| 网页导出模板（只解压了 `web_*`） | 4.7.2.stable（必须和 Godot 版本号完全一致） | `~/.local/share/godot/export_templates/4.7.2.stable/` |
| Blender（官方 Linux 版） | 5.1.2 | `/opt/blender-5.1.2-linux-x64/`，`/opt/blender` 指向它，软链接 `/usr/local/bin/blender` |
| MCP 服务器 Coding-Solo/godot-mcp | GitHub 提交 `1209744`（2026-04-16） | 本地构建到 `/opt/godot-mcp/build/` |
| MCP 服务器 sandraschi/blender-mcp | GitHub 提交 `b786a39`（2026-10-24） | 通过 `uvx` 按需安装 |
| Node.js / uv（云环境自带） | 22 / — | — |

版本号都写在 `tools/setup_env.sh` 开头的变量里，升级时只改那里。

### 为什么选这些版本（2026-10-24 调查）

- **Godot 4.7.2**：本身就是最新稳定版。godot-mcp 只调用 Godot 命令行，issue 里没有 4.7 不兼容的报告
  （#111 只是问什么时候适配 4.7，#125 是放错仓库的 Godot 安卓 bug），实测运行项目、读输出和报错都正常。
- **Blender 5.1.2，不是最新的 5.2.3**：blender-mcp 的 README 写的是支持 "Blender 3.0+"，
  作者自己的配置示例用的是 Blender 5.1，代码也按 5.x 的 API 写（`BLENDER_EEVEE`）；但没有任何文档或 issue 提到 5.2。
  所以选"有人验证过"的 5.1 系列的最新补丁版。
- **godot-mcp 从 GitHub 构建，不用 npm 的 0.1.1**：npm 上的 0.1.1 是 2026-01 发布的，之后 master
  修了一个远程代码执行漏洞（#95，通过 `add_node`/`create_scene` 实例化任意 GDScript），但没发新版到 npm。
  `npx github:...` 构建 git 版本会报 npm 的 `GitFetcher requires an Arborist constructor` 错误，所以改成先 clone 再本地构建。
- **blender-mcp `b786a39`**：已经是最新提交，issue 里没有 Linux 或版本兼容问题。

## 环境初始化（新会话不用重装）

`tools/setup_env.sh` 一次装好全部工具：Godot、版本一致的网页导出模板、Blender、godot-mcp 构建，
并预先下载 blender-mcp。所有下载都会校验官方 SHA512/SHA256；已经装好的部分会跳过，可以重复运行。
从零安装大约 2.5 分钟（导出模板完整包有 1.2 GB，只保留约 90 MB 的网页模板）。

**放进云环境设置**（cloud environment 菜单 → Edit → Setup script）：脚本不依赖仓库里的其他文件，
可以把 `tools/setup_env.sh` 的全部内容直接粘贴进去，新会话启动时就会自动装好。
也可以在会话里手动运行：

```bash
tools/setup_env.sh                 # 全部安装
tools/setup_env.sh --godot-only    # 只装 Godot + 模板（CI 用这个）
godot --headless --version && blender --background --version   # 确认能运行
```

下载来源：Godot 和导出模板来自 GitHub Releases（`github.com/godotengine/godot/releases`），
Blender 来自 `download.blender.org`。网络受限时，需要在环境的 Network access 里放行这些域名。

## MCP 怎么配的

`.mcp.json`（仓库根目录，Claude Code 打开仓库时会自动读取）：

```json
{
  "mcpServers": {
    "godot": {
      "command": "node",
      "args": ["/opt/godot-mcp/build/index.js"],
      "env": {
        "GODOT_PATH": "/home/user/godot-test/tools/godot-headless.sh",
        "DEBUG": "false"
      }
    },
    "blender": {
      "command": "uvx",
      "args": ["--from", "git+https://github.com/sandraschi/blender-mcp@b786a397747970a17b74983c9aab657b8a014789",
               "blender-mcp", "--stdio"],
      "env": { "BLENDER_EXECUTABLE": "/opt/blender/blender" }
    }
  }
}
```

### Godot MCP

- `/opt/godot-mcp/build/index.js` 由 `tools/setup_env.sh` 构建。没有运行 setup 脚本的话，这个 MCP 起不来。
- godot-mcp 的 `run_project` 启动 Godot 时**不加 `--headless`**，云容器里没有显示器会直接失败。
  所以 `GODOT_PATH` 指向 `tools/godot-headless.sh`，它会以 `godot --headless ...` 的方式启动。
- `GODOT_PATH` 必须是绝对路径。云会话里仓库固定在 `/home/user/<仓库名>`，复用到其他仓库时改成对应路径。
- 已验证：`run_project`（projectPath 填 `/home/user/godot-test/game`）+ `get_debug_output` 能拿到
  `print` 输出和报错（例如 `ERROR: ...` 以及 `res://scripts/main.gd:26` 这样的脚本行号）。
- MCP 不可用时，直接用命令行效果一样：

  ```bash
  godot --headless --path game --quit-after 120   # 跑 120 帧后退出，看输出有没有 ERROR
  ```

### Blender MCP

- 用的是 **sandraschi/blender-mcp**，它会自动以 `blender --background` 启动 Blender，不需要界面也不需要装插件。
- `BLENDER_EXECUTABLE` 指向 `/opt/blender/blender`。这是个固定路径（软链接），以后升级 Blender 只要改链接，不用改 `.mcp.json`。
- **不要写成 `uvx blender-mcp`**：PyPI 上叫 `blender-mcp` 的包其实是 ahujasid 那个（不支持后台模式），
  所以这里用 `--from git+...` 从 GitHub 安装，并固定到某个提交，避免上游改动导致不可用。
- 已验证：能列出 72 个工具；`script_execute`（参数 `code`）能在后台 Blender 里跑 bpy 代码并返回 `print` 输出，
  也能通过它重新生成木箱，例如：

  ```python
  import runpy; runpy.run_path("/home/user/godot-test/art/crate/make_crate.py", run_name="__main__")
  ```

  脚本出错时会返回 `success: false`，但**不带 Python 报错详情**，要看完整报错就改用命令行跑脚本。
- 每次调用都启动一个新的 Blender 进程，调用之间场景不保留。建模这类多步操作，最好写成一个完整脚本一次跑完。

## Blender 美术管线

### 从建模到导入 Godot

1. **建模 + 渲染预览 + 导出**（一个脚本全部完成，可重复运行；也可以通过 Blender MCP 的 `script_execute` 运行）：

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

`.github/workflows/deploy-web.yml` 在 push 到 `main` 或 `claude/**` 分支时运行（也可以在 Actions 页面手动运行）。
其他分支（例如 `upgrade-versions`）不会部署，合并到 `main` 后才会上线。流程：
1. 用 `tools/setup_env.sh --godot-only` 安装 Godot 和网页模板（有缓存，setup 脚本改动后会重新下载）；
2. 用 `tools/export_web.sh` 导出；
3. 用 `peaceiris/actions-gh-pages` 把 `build/web` 推到 `gh-pages` 分支。

GitHub Pages 的来源设置为 **Deploy from a branch → `gh-pages` / (root)**
（仓库 Settings → Pages）。访问地址：`https://<用户名>.github.io/<仓库名>/`。

复用到新项目：复制 `tools/`（包括 `setup_env.sh`）、`.mcp.json`、`.github/workflows/deploy-web.yml`（要做美术再加上 `art/` 里的脚本写法），
把 Godot 项目放进 `game/`（导出预设名叫 `Web`），改一下 `.mcp.json` 里的路径即可。
