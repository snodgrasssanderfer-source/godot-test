# godot-test

> **接手这个项目？先读 [HANDOFF.md](HANDOFF.md)。** 里面有已定的方向、当前进度、哪些代码还没验证过，以及下一步做什么。

用"手机 + 云端（Claude Code 云会话）"做 Godot 网页小游戏的试跑项目。

- 游戏：**Box Jumper** —— 方块左右移动、跳跃，有地面和平台，掉下去自动回到起点；
  场景里的木箱是用 Blender 建模、导出 .glb 再导入 Godot 的（第二轮：美术管线验证）。
  屏幕上有虚拟按键（左下 `<` `>` 移动，右下 `^` 跳跃，支持同时按），电脑上可用方向键 / A D / 空格。
- 在线试玩：https://snodgrasssanderfer-source.github.io/godot-test/

## 第一人称生存恐怖项目（`fps/`，进行中）

仓库里现在有两个 Godot 项目：`game/` 是最早的 2D 网页试跑 Demo（上面说的 Box Jumper），
`fps/` 是正在做的 3D 游戏（暂定名 Greyfog）。两者互不依赖。

**已定的方向**

- 平台：PC（Steam），纯单机。渲染器 Forward+。
- 性能目标：RTX 5060、1080p、高画质稳定 120 帧。
- 卖点：枪械手感和机动做到顶，但枪声引怪、弹药靠搜刮，每一枪都是一个决定；理智值低时会出现假丧尸。
- 美术基调：现代光照 + 压抑的调子。白天主基调是「冷雾」，黄昏是「锈色」时段，
  夜晚分有电（钠灯）和停电（月光 + 手电）两种状态。

**基调概念图**（`art/concept/`）：同一条街在五种光照下的 Blender 渲染，`engine/` 里是 Godot 实际渲染的对照图。
模型全是代码生成的占位体，只看光、雾和色彩。重新生成：

```bash
blender --background --factory-startup --python art/concept/make_concepts.py -- \
    --variant A_cold_fog --out /tmp/raw/A_cold_fog.png      # 还有 B_rust_dusk C_sodium_night D_blackout E_safehouse
python3 art/concept/post.py /tmp/raw art/concept            # 辉光、调色、暗角、颗粒
blender --background --factory-startup --python art/concept/make_concepts.py -- \
    --variant A_cold_fog --export-glb fps/assets/greybox/street.glb   # 导出灰盒街道给 Godot
```

**氛围系统**（`fps/atmosphere/`）

- `atmosphere.tscn`：放进任何场景就接管太阳、月亮、天空、雾、曝光和最终调色，并随时间在四套预设间过渡。
  常用属性：`time_of_day`（0–24）、`day_length_minutes`（一昼夜的真实分钟数，0 为冻结）、`power_on`（是否有电）。
- `presets/*.tres`：四套预设（`cold_fog_day`、`rust_dusk`、`night_powered`、`night_blackout`），
  所有数值都能在编辑器里直接调。
- `post_grade.gdshader`：全屏胶片层（色散、分离调色、暗角、颗粒），算法和 `art/concept/post.py` 一致。
- 加入 `street_lamp` 分组的灯会跟随预设的 `lamp_power` 亮灭，停电时自动熄灭。

**靶场**（`fps/test/range_street.tscn`，当前的主场景）：灰盒街道 + 玩家 + 三把武器 + 七个靶子 + 八只休眠的电视怪，
用来调移动和枪械手感、测帧率。备弹无限，靶子倒下几秒后自己站起来。枪开多了，那一段电网上的电视怪会醒过来找你
（`N` 直接给脚下的电网加惊扰，`M` 收走 / 放回怪物）。

| 按键 | 作用 |
| --- | --- |
| WASD / Shift / Ctrl 或 C / 空格 | 移动 / 冲刺 / 蹲（冲刺中按是滑铲）/ 跳，对着矮墙、栅栏、车是攀爬或翻越 |
| Q E / F | 左右探头 / 手电 |
| 左键 / 右键 / R / 1 2 3 或滚轮 | 开火或挥击 / 开镜 / 换弹 / 手枪、霰弹枪、钢管 |
| `[` `]` / T / P / G / V | 调时间 / 时间流动 / 电力 / SDFGI / 体积雾 |
| B | 基准测试：镜头沿街飞一遍，4 种光照 × 3 种画质设置，约 2 分钟，结果显示在屏幕上并写入 `user://benchmark.txt` |
| `-` `=` / F1 / F2 / F5 / F11 / Esc / F10 | 鼠标灵敏度 / 按键提示 / 左上角数字 / 回出生点 / 全屏 / 释放鼠标 / 退出 |

**代码结构**

- `fps/player/player.gd`：第一人称身体。走、冲刺、蹲、滑铲、跳、翻越和攀爬、上台阶、探头、体力、手电，
  以及镜头的晃动、落地下沉、倾斜、后坐力回正、视野变化。
- `fps/player/body_sim.gd`、`body.gd`：部位负伤。没有血条：六个部位各有机能值，流血只掉血量，受伤后移动、体力、换弹、
  开镜、后坐力按部位变差，靠画面褪色、隧道视野、跛行和瞄准晃动告诉玩家。规则在 `BodySim`（可无界面自测），
  其余代码只读 `player.body.mods`。设计和实现偏差见 `docs/design_supplement.md` 第一节。
- `fps/weapons/`：`weapon_data.gd` 是一把武器的全部数值（`data/*.tres` 各一份，可在编辑器里直接调）；
  `weapon.gd` 是手里的一把枪（模型、活动部件、弹药）；`weapon_rig.gd` 挂在相机下，负责开火、泵动、换弹、
  挥击、切枪的状态机和全部程序化姿态（没有关键帧动画）；`fx.gd` 是枪口闪光、曳光、弹孔、命中烟尘和弹壳。
- `fps/ui/hud.gd`：极简 HUD。准星的开口就是真实散布；弹药数只在开镜、换弹、刚开过枪或刚切枪时出现；
  体力条只在不满时出现。
- `fps/test/`：`street_set` 给灰盒街道生成碰撞、路灯和靶子；`target_dummy.gd` 是靶子；
  `benchmark.gd` 是基准测试；`range_auto.gd` 是脚本化的自测和截图。
- `fps/world/`：`noise_bus.gd` 是全局噪音广播；`grid_sim.gd`、`grid.gd`、`grid_node.gd` 是"电网惊扰"（枪声让沿街的电网节点升温，
  升到阈值就叫醒挂在上面的怪）；`nav.gd` 从静态碰撞体烘导航网格并回答寻路。
- `fps/creatures/`：`creature.gd` 是怪物本体（休眠、起身、调查噪音、追击、前摇、扑击、硬直、死亡；外壳和肉核两层受击），
  `creature_data.gd` 是一种怪的全部数值。目前只有 CRT 电视一种。数值和实现偏差见 `docs/design_supplement.md` 的"五之二"。
- 任何能被打的东西只要实现 `take_hit(damage, point, dir, weapon_data) -> Dictionary` 就行（返回值里 `flesh` 为假表示打在硬壳上，画火花不画血）；
  怪物打玩家则调 `player.body.hit(伤害, 伤型, 攻击类型)`。

**自测**：不开窗口，用真实的输入事件驱动玩家把移动、攀爬和三把武器全走一遍，再加上电网惊扰、部位负伤和怪物，共 171 项检查，失败时退出码为 1（`--only=creatures,grid` 可以只跑几段）。
`--fixed-fps 60` 让时间确定，无界面时约 10 秒跑完：

```bash
godot --headless --fixed-fps 60 --path fps -- --selftest
```

**小镇灰盒**（`fps/world/town/town.tscn`，靶场里按 `F3` 切过去）：设计稿那张 250 × 250 米地图的可行走灰盒，四栋核心建筑都能进，
纯色方块，没有物品。电网已经接上（每盏路灯一个节点、每栋核心建筑一条回路），四栋楼里睡着 9 台电视怪，开枪多了会把它们吵醒；`N` 给最近的节点加 25，`M` 开关怪物。`F6`–`F9` 跳到四栋楼，`L` 显示关键物品的标记。平面图在 `art/town/`。它有自己的自测（33 项：布局、寻路、电网和怪、
让玩家真的走进每栋楼、上两处楼梯、下地沟、翻窗上遮雨棚）：

```bash
godot --headless --fixed-fps 60 --path fps res://world/town/town.tscn -- --selftest
```

小镇要在 Blender 里重做：怎么导出、怎么读回来、哪些尺寸不能动，见 `docs/blender_town_workflow.md`。

**氛围测试场景**（`fps/test/tone_street.tscn`）：WASD + 鼠标飞行，`[` `]` 调时间，
`T` 让时间流动，`P` 开关电力，`F` 手电，`F1` 隐藏左上角的帧率和帧时间。

云端没有显卡，用软件 Vulkan 截图验证（`tools/setup_env.sh` 会装好驱动），大约每秒 3 帧，帧率只能在真机上测：

```bash
# 氛围的四张对照图
xvfb-run -a -s "-screen 0 1280x720x24" godot --path fps --rendering-driver vulkan \
    --resolution 1280x720 res://test/tone_street.tscn -- --shoot=/tmp/shots
# 武器在画面里的样子（腰射、开镜、开火、换弹、冲刺、挥击、弹孔、夜里的手电），约 10 分钟
xvfb-run -a -s "-screen 0 960x540x24" godot --path fps --rendering-driver vulkan --fixed-fps 60 \
    --resolution 960x540 -- --res=960x540 --shoot=/tmp/shots
```

**Windows 测试包**：`tools/export_windows.sh` 导出单个 `Greyfog.exe`（约 120 MB，压缩后约 48 MB）到 `build/`，
附带给测试者看的说明 `fps/TESTBUILD.txt`。推送到 `ccr-**` 分支时 `.github/workflows/build-windows.yml`
会自动跑自测、导出，并把包存成这次运行的 artifact；手动运行这个工作流并勾选 publish 才会发成公开的预发布版本。

## 目录结构

| 路径 | 说明 |
| --- | --- |
| `game/` | Godot 项目（`project.godot`、`main.tscn`、`scripts/`、`assets/`、`export_presets.cfg`） |
| `fps/` | 3D 第一人称项目：`player/`、`weapons/`、`ui/`、`atmosphere/`（氛围系统）、`test/`（靶场和测试场景）、`assets/`（灰盒街道、武器模型、占位音效） |
| `art/weapons/` | 三把武器模型的生成脚本 `make_weapons.py` |
| `art/range/` | 靶场的引擎截图（软件渲染，960×540）：三把武器的腰射、开镜、换弹、冲刺、挥击，弹孔，停电夜里开火；负伤后的画面；电视怪从休眠到死亡的八张 |
| `art/concept/` | 基调概念图、生成脚本 `make_concepts.py`、后期脚本 `post.py`、引擎对照图 `engine/` |
| `art/crate/` | 木箱的 Blender 源文件、建模脚本 `make_crate.py`、预览图 `crate_preview.png` |
| `tools/setup_env.sh` | **环境初始化脚本**：安装 Godot + 网页导出模板 + Blender + godot-mcp（校验哈希，可重复运行） |
| `tools/export_web.sh` | 无界面导出网页版到 `build/web/` |
| `tools/export_windows.sh` | 无界面导出 `fps/` 的 Windows 测试包到 `build/` |
| `tools/make_placeholder_sfx.py` | 合成 23 个占位音效到 `fps/assets/sfx/` |
| `tools/godot-headless.sh` | 给 MCP 用的包装脚本：总是加 `--headless` 启动 Godot |
| `.mcp.json` | Godot MCP + Blender MCP 配置 |
| `.github/workflows/deploy-web.yml` | 自动导出并发布到 GitHub Pages |
| `.github/workflows/build-windows.yml` | 自测并导出 Windows 测试包（artifact；手动触发时可发预发布版本） |

## 安装了什么

| 组件 | 版本 | 位置 |
| --- | --- | --- |
| Godot（Linux x86_64，标准版，不是 .NET 版） | 4.7.2-stable | `/opt/godot/`，软链接 `/usr/local/bin/godot` |
| 导出模板（只解压了网页 `web_*` 和 Windows x86_64） | 4.7.2.stable（必须和 Godot 版本号完全一致） | `~/.local/share/godot/export_templates/4.7.2.stable/` |
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
