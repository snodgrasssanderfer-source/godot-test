# textures/ —— 素材库

从 CC0 素材库下载并整理成统一格式的贴图、模型和字体。**这里不是 Godot 工程的一部分**：放在 `fps/` 外面，
所以不会被引擎导入，也不会被打进游戏包。要用哪一套，再把它拷进 `fps/assets/textures/`。

| 目录 | 内容 |
| --- | --- |
| `materials/<素材>/` | 一套材质：`_Color`（颜色；贴花和图集是带透明的 PNG）、`_Normal`（法线，OpenGL 约定）、`_ORM`（红 = 遮蔽，绿 = 粗糙度，蓝 = 金属度） |
| `models/<素材>/` | 现成的三维模型（glTF），带它自己的贴图，原样保存 |
| `fonts/` | 字体和它的授权文件 |
| `sources.csv` | 每个素材一行：来自哪个库、授权、页面、下载地址、分辨率、文件、清单里哪些编号用它 |

- 分辨率是 1K。要更大的：`tools/fetch_textures.py --res 2K --force`（体积约四倍）。
- 清单里每一项用哪个素材、接入时还要做什么（改色、叠贴花、配模型），看 `docs/texture_sources.csv`。
- 全部重新下载：`tools/fetch_textures.py`。它只读 `docs/texture_sources.csv`，往那份表里加一行就能多下一个素材。
- 授权：ambientCG 和 Poly Haven 的素材是 CC0（可商用，不用署名）；Noto Sans SC 字体是 SIL OFL 1.1（可商用，授权文件在 `fonts/OFL.txt`）。
