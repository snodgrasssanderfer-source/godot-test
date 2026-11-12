# textures/ —— 素材库

从 CC0 素材库下载并整理成统一格式的贴图、模型和字体。**这里不是 Godot 工程的一部分**：放在 `fps/` 外面，
所以不会被引擎导入，也不会被打进游戏包。要用哪一套，再把它拷进 `fps/assets/textures/`。

| 目录 | 内容 |
| --- | --- |
| `materials/<素材>/` | 一套材质：`_Color`（颜色；贴花和图集是带透明的 PNG）、`_Normal`（法线，OpenGL 约定）、`_ORM`（红 = 遮蔽，绿 = 粗糙度，蓝 = 金属度） |
| `models/<素材>/` | 现成的三维模型（glTF），带它自己的贴图，原样保存 |
| `panoramas/<素材>/` | 天空全景，2K 的 HDR |
| `procedural/` | 脚本生成的污渍和破损贴花（28 项，29 张图），生成脚本是 `art/textures/gen_decals.py`，这一目录有自己的 `sources.csv` |
| `generated/<编号_名字>/` | **出图工具生成**的贴图（145 套），由 `tools/receive_generated.py` 从小鱼的交付分支接收：平铺和饰条带推出来的法线和 ORM，贴花和遮罩切成了单张，多视图只是建模参考。这一目录有自己的 `sources.csv`。**上架时要申报用了生成内容** |
| `fonts/` | 字体和它的授权文件 |
| `sources.csv` | 每个素材一行：来自哪个库、授权、页面、下载地址、分辨率、文件、清单里哪些编号用它 |

现在有 216 个下载的素材加 28 项程序生成的贴花，约 320 MB：137 套材质和贴花、77 个三维模型、1 张夜空全景、1 套字体，覆盖清单里 286 项。

- 分辨率是 1K。要更大的：`tools/fetch_textures.py --res 2K --force`（体积约四倍）。
- 清单里每一项用哪个素材、接入时还要做什么（改色、叠贴花、配模型），看 `docs/texture_sources.csv`。
- 表里删掉的素材，用 `tools/fetch_textures.py --prune` 清掉。
- 全部重新下载：`tools/fetch_textures.py`。它只读 `docs/texture_sources.csv`，往那份表里加一行就能多下一个素材。
- 授权：ambientCG 和 Poly Haven 的素材是 CC0（可商用，不用署名）；Noto Sans SC 字体是 SIL OFL 1.1（可商用，授权文件在 `fonts/OFL.txt`）。
- 有几个模型上带文字：`plastic_crate_01` 之类带牌子的没有选；`cigarette_pack` 是虚构牌子。用模型前看一眼贴图，带真实商标或外文标签的要换掉。
