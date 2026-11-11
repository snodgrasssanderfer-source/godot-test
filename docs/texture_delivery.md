# 贴图交付规范

给找贴图、整理贴图的人（或 AI）看。照这一页做，交上来的东西就能直接验收和接入。
清单是 `docs/texture_list.md`（785 项），验收脚本是 `tools/check_textures.py`。

## 一句话

**一张图只对应清单里的一项；文件名说的是什么，图里就得是什么；每一套都要写清楚从哪来的。**

上一批交付（2026-11-11，已弃用）的问题正是这三条：160 张图套了 233 个编号，抽查 14 张只有 3 张名实相符，没有来源记录。

## 放在哪

- 推到**单独的分支**，名字用 `textures/日期-整理人`，例如 `textures/20261115-hachimi`。
- 分支里只放一个目录 `textures_incoming/`，里面按清单的组分子目录（`A/`、`B/` …），外加一份 `sources.csv`。
- **不要合并进工作分支，不要动 `fps/` 和其他任何现有文件。** 原图很大，进了工作分支会拖垮以后每一次克隆和自动打包。
  验收通过后，由做工程的人把用得上的图拷进 `fps/assets/textures/`。

## 文件怎么命名

```
{清单编号}{变体字母}_{英文名}_{通道}.{扩展名}
```

| 部分 | 规则 | 例子 |
| --- | --- | --- |
| 清单编号 | 一个大写字母加两位数字，必须是清单里有的 | `C01` |
| 变体字母 | 同一项有多张时加小写字母，没有就不写 | `M01a`、`M01b` |
| 英文名 | 字母和数字，不带空格和下划线，说的是**图里实际的内容** | `RedBrickWeathered` |
| 通道 | 见下表 | `Color` |

例：`C01_RedBrickWeathered_Color.jpg`、`C01_RedBrickWeathered_Normal.jpg`、`C01_RedBrickWeathered_ORM.jpg`

| 通道 | 内容 |
| --- | --- |
| `Color` | 基础颜色。贴花、卡片必须是带透明通道的 PNG |
| `Normal` | 法线，OpenGL 约定（绿色朝上） |
| `ORM` | 三合一：红 = 环境光遮蔽，绿 = 粗糙度，蓝 = 金属度 |
| `Emission` | 自发光，只给会亮的东西，不能是全黑 |
| `Mask`、`Height` | 灰度遮罩、高度 |
| `Panorama` | 全景，宽高比 2:1 |
| `Cookie`、`Curve` | 灯光遮罩、亮度曲线条 |
| `Flipbook4x4` | 序列帧，后面写列数 x 行数 |

## 每一类要交什么

| 清单里的类型 | 必须有 | 尺寸 |
| --- | --- | --- |
| 平铺、饰条、物件 | `Color` + `Normal` + `ORM` | 2048 × 2048（长条物可以 2048 × 1024），边长是 2 的幂 |
| 贴花、卡片 | 带透明的 `Color`（PNG）；有凹凸的再加 `Normal` | 边长是 2 的幂 |
| 图集 | `Color` | 边长是 2 的幂 |
| 发光 | `Color` + `Emission` | 同上 |
| 全景 | `Panorama` | 至少 4096 × 2048 |

清单里写了"N 种"的项（比如"竖向水渍流痕，8 种"），每一种是一个变体，各自一套文件。

## 来源记录 `sources.csv`

放在 `textures_incoming/` 根目录，UTF-8，每一套一行：

```
set,source,asset,url,license,note
C01_RedBrickWeathered,ambientCG,Bricks059,https://ambientcg.com/a/Bricks059,CC0,
A01_OldAsphalt,self-shot,IMG_2041..2049,,self-shot,楼下停车场，阴天
N02_GasStationBrand,original,,,original,自己画的，用了生成工具
```

- `set` 是文件名去掉通道的部分。
- `license` 只接受 `CC0`、`self-shot`（自己拍的）、`original`（自己画或生成的）。
- `CC0` 的行必须填素材库里的**原始素材名和链接**，这样上架前能逐个核实。
- 用了生成工具的，在 `note` 里写明。Steam 上架要申报。

## 不能交的

- 同一张图改个名字交给两个编号。一种材质确实适用于多项时，只交一次，挂在最贴切的那个编号下，
  在 `note` 里写"也可用于 B37、F37"。
- 名字和内容对不上的图。找不到合适的就**空着**，不要拿相近的凑。
- 从任何游戏里提取的贴图；带真实品牌、真实车牌、真实人脸的照片；授权说不清的网图。
- 全黑、全白、纯色的占位图。

## 素材库解决不了的

清单"来源"一栏标了 **画** 的 323 项（招牌、包装、纸品、照片、怪物内腔）和血肉组，素材库里没有现成的，
不要用通用材质顶替。这部分要原创设计，单独商量怎么做。
标了 **程** 的 62 项已经做完，在 `fps/assets/textures/proc/`，不用找。

## 交之前自己先验一遍

```bash
python3 tools/check_textures.py textures_incoming --sheet /tmp/sheet --missing
```

- 最后一行是 `RESULT: PASS` 才算过。它查：命名、编号是否存在、每套是否齐全、尺寸、透明通道、
  重复文件、全黑或纯色的图、法线图是否像法线图、`sources.csv` 是否齐全。
- **它查不出"名字和图对不对得上"。** 所以要带 `--sheet`，它会生成总览图（每张颜色图配文件名）。
  交付前把总览图从头看一遍，看到名不副实的就改名或删掉。总览图也一起交上来。
- 已经推到分支上的，可以不下载先粗查命名、覆盖率和重复：
  `python3 tools/check_textures.py --ref origin/textures/20261115-hachimi textures_incoming`
