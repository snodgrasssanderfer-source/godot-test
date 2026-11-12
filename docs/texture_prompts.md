# 贴图生成提示词

给 GPT（或别的出图工具）用，针对《凌晨三点公路镇》的风格写的。共 147 条：34 条是**重做**（已经有图但不合格），113 条是清单里**还没有任何素材**的。
写于 2026-11-12。清单是 `docs/texture_list.md`，还缺什么见 `docs/texture_check/still_missing.md`。

## 怎么用

1. 每个代码块是一条完整的提示词，**整块复制**给出图工具，一条出一张图。提示词是英文的，出图工具对英文更听话；里面的英文招牌字样就是要印在图上的字。
2. 尺寸要 **1024 × 1024**。贴花要求**透明背景的 PNG**（GPT 的出图支持透明背景，记得在要求里说明或在选项里勾上）；如果工具给不了真透明，宁可让它出纯黑底，也不要它画一个"假装透明"的灰白棋盘格。
3. 一条只出一个清单编号，**不要把几项拼在一张图里**。上一批图集的问题就是一张图塞了几十样，单个只有一两百像素。
4. 出来的图先自己看一眼，下面这几种直接重出：
   - 出现了真实品牌、真人模样的脸、或者提示词里没写的字。
   - 物件是斜着的、带透视的、带明显光影和投影的（那是效果图，贴不到模型上）。
   - 图上被加了编号、标题、箭头、水印。
5. 文件名用 `{编号}_{英文名}_Color.png`，例如 `D20_CheckerVinylFloor_Color.png`；同一项出了几张就加小写字母 `D20a`、`D20b`。
   版式是**发光**的把 `Color` 换成 `Emission`，版式是**遮罩**的换成 `Mask`。
6. 放到单独的分支（比如 `textures/20261113-gpt`）的 `textures_incoming/` 目录里，按编号的字母分子目录（`A/`、`B/` …），不要直接放进 `fps/`。
   `sources.csv` 里来源一栏写出图工具的名字和日期就行。详细规矩在 `docs/texture_delivery.md`。
7. 那份规矩要求 2048，出图工具只给得出 1024，**这一批按 1024 收**，需要更大的我来放大。人脸相关的六条（N17、L29、J31、E29、H25、I22）禁令写的是"脸必须糊到认不出"，不是"完全没有人"，出图时重点看这一点。

## 交上来之后我会做什么

- **平铺图**：出图工具保证不了真的无缝。我会检查接缝并修边，修不好的告诉你重出。
- **贴花**：检查透明通道，把边缘收干净。
- **法线和粗糙度**：这些提示词只要颜色图。法线和粗糙度我从颜色图推一版，平面的东西够用。
- **物件**：`正面图`类的我会投到盒子形状的模型上；`多视图`类的是给建模当参考，不是直接能贴的贴图。
- 用到游戏里的每一张都要记录"是生成工具做的"，Steam 上架要申报。

## 提示词是怎么组成的

每条都是"这张图是什么 + 具体内容 + 统一的风格 + 统一的禁令"。后两段每条都一样，是为了不让出图工具跑偏：

```text
Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new.

Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

版式有九种，决定了图怎么构图：

| 版式 | 用在 | 要点 |
| --- | --- | --- |
| 平铺 | 墙面、地面、布料 | 正对着拍，铺满整张图，四边能接上 |
| 饰条 | 窗框、栏杆 | 横向长条从上到下排开，每条左右能接上 |
| 贴花 | 污渍、裂缝、字样 | 透明底，居中，四周留空 |
| 贴花四变体 | 同一种痕迹的几个样子 | 2 × 2 四格，互不相连 |
| 正面图 | 扁平的面：机器正面、门、面板 | 正对着、无透视、灰底，用来投到简单模型上 |
| 多视图 | 立体的东西 | 前后左右顶五个正视图，给建模当参考 |
| 平面印刷品 | 招牌、纸品、品牌标志、图集 | 像扫描件一样平 |
| 发光 | 霓虹、灯串、亮着的面板 | 纯黑底，只有发光的部分是亮的 |
| 遮罩 | 玻璃裂纹 | 黑底白纹 |

所有虚构品牌名（NORTHLINE、Big Cedar Cola、Ironwood Lager 等）都是我起的，**没有查过商标**，上架前要逐个检索，撞了就换。
（2026-11-12 补：粗查后发现 Polar Pop、Lakehead Pilsner、Clean Harbor 三个撞了真实品牌，Big Cedar Cola 出图像可口可乐。下面第一、二节里的原提示词没有改，**要用第四节的新版**。）

---

## 一、重做：已经有图，但不合格（34 条）

每条标题后面写了为什么要重做。

**A07　黄色双实线**（贴花，P0）　——　现有的图上印着编号字样

```text
A single decal on a fully transparent background (PNG with alpha), straight-on orthographic view, centred with clear empty margin on all four sides: double solid yellow road centre line running top to bottom through the frame, thermoplastic paint worn thin, cracked and missing in patches so the asphalt would show through. Only the mark itself; no wall or floor behind it, no drop shadow. Soft, irregular, natural edges that fade into transparency. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**A08　白色边线**（贴花，P0）　——　同上

```text
A single decal on a fully transparent background (PNG with alpha), straight-on orthographic view, centred with clear empty margin on all four sides: single white road edge line running top to bottom through the frame, paint worn, cracked and flaking, stained by tyre rubber. Only the mark itself; no wall or floor behind it, no drop shadow. Soft, irregular, natural edges that fade into transparency. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**A23　圆形井盖**（物件，P0）　——　现有的图上印着编号字样

```text
Straight-on orthographic front view of a round cast-iron manhole cover set in its ring, top-down: concentric anti-slip waffle pattern, two pick holes, the raised cast lettering 'PUBLIC WORKS' around the top and 'SEWER' around the bottom, rusted orange-brown, tar and grit packed into the pattern, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D01　大花碎花墙纸**（平铺，P0）　——　现有的有接缝

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: 1980s floral wallpaper with large faded pink cabbage roses and grey-green leaves on a cream ground, yellowed, with faint brown water tide marks. The frame covers about 1 metre of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D02　小碎花墙纸**（平铺，P1）　——　现有的只占图集里一小条，分辨率不够

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: 1980s bedroom wallpaper with a small-scale ditsy floral sprig pattern in dusty blue and pink on off-white, faded and yellowed. The frame covers about 1 metre of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D03　条纹墙纸**（平铺，P1）　——　同上

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: 1970s hallway wallpaper with vertical stripes in beige, tan and cream of varying widths, slightly embossed, yellowed, scuffed at hand height. The frame covers about 1 metre of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D04　厨房水果图案墙纸**（平铺，P1）　——　同上

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: 1970s kitchen wallpaper with a repeating pattern of apples, pears and oranges on a pale yellow ground, greasy and yellowed. The frame covers about 1 metre of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D05　儿童房图案墙纸**（平铺，P1）　——　同上

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: 1980s child's bedroom wallpaper with an original repeating pattern of small toy trains, clouds and stars in faded primary colours on pale blue. The frame covers about 1 metre of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D20　黑白棋盘地板革**（平铺，P0）　——　现有的接缝明显

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: black and white checkerboard vinyl sheet flooring, 30 cm squares, exactly four squares across and four down so that it tiles, scuffed, yellowed whites, grey traffic wear, fine scratches. The frame covers about 1.2 metres of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D21　仿红砖地板革**（平铺，P1）　——　现有的只占一小块

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: 1970s sheet vinyl flooring printed to imitate red quarry brick pavers with grey grout lines, worn through to the backing in places, yellowed. The frame covers about 1.2 metres of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D22　几何图案黄褐地板革**（平铺，P1）　——　同上

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: 1970s sheet vinyl flooring with a geometric interlocking octagon-and-square pattern in mustard yellow, brown and orange, worn dull, cigarette burns. The frame covers about 1.2 metres of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**E21　自动售货机**（物件，P0）　——　现有的图上是可口可乐、雪碧、七喜、百事的商标

```text
Straight-on orthographic front view of a 1990s outdoor soft-drink vending machine: tall steel cabinet with a large backlit front panel showing a splash of cola and the fictional brand name 'BIG CEDAR COLA' in bold italic white letters, a column of eight blank selection buttons, coin slot, bill slot and a delivery hatch at the bottom; sun-faded, scratched, rust at the base, unlit, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**K19　售货机发光面板**（发光，P0）　——　同上

```text
Emissive texture on a pure black (#000000) background, straight-on orthographic: the lit front panel of a soft-drink vending machine, two versions side by side: one with the fictional brand 'BIG CEDAR COLA' in white on dark red with an ice-and-bubbles photo feel, one with the fictional brand 'POLAR POP' in blue on white with a polar bear silhouette; uneven fluorescent backlight with a dead tube leaving one dark band. Only the light-emitting parts are bright; everything else is black. No perspective, no surrounding scene. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**C65　玻璃裂纹**（遮罩，P0）　——　现有的透明底是画上去的棋盘格

```text
Greyscale mask, pure white marks on a pure black (#000000) background, straight-on orthographic: four different glass crack patterns in a 2 x 2 grid: a bullet-hole star with radial and concentric cracks, a single long diagonal crack with branches, a corner impact spreading out, and dense crazing. No grey background, no colour, no perspective. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**C66　破碎玻璃残片边缘**（遮罩，P0）　——　同上

```text
Greyscale mask, pure white marks on a pure black (#000000) background, straight-on orthographic: the jagged remains of broken window glass still stuck in a rectangular frame: white shards along all four edges pointing inwards, black in the middle where the glass is gone; four different versions in a 2 x 2 grid. No grey background, no colour, no perspective. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**N17　寻人启事**（图集，P1）　——　现有的图上是像真人的脸

```text
Flat print artwork, straight-on with no perspective, as if scanned: six MISSING person flyers in a grid, each a letter-size sheet photocopied in black and white: the word 'MISSING' at the top, a halftone portrait photo so degraded by repeated photocopying, creases and rain that the face is only a blur of dots, then lines of smaller handwritten-looking text reduced to illegible grey lines, and 'PLEASE CALL' at the bottom with the number torn off; tape marks, water stains. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no real or recognisable person, and no sharp or detailed face: any face must be as degraded, small or blurred as described above; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**N14　报纸头版**（图集，P1）　——　现有的图上有人脸

```text
Flat print artwork, straight-on with no perspective, as if scanned: three front pages of a fictional small-town newspaper called 'THE COUNTY LEDGER', side by side, dated on three consecutive days in October 1996; headlines 'POWER SURGES REPORTED ACROSS TOWNSHIP', 'UTILITY BLAMES AGING GRID', 'RESIDENTS TOLD TO UNPLUG APPLIANCES'; body text as illegible grey columns; the photos show a substation, a row of utility poles and a dark street, with no people; yellowed newsprint, fold lines, damp spots. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**L29　钱包和驾照**（物件，P1）　——　同上

```text
Flat print artwork, straight-on with no perspective, as if scanned: a worn brown leather wallet opened flat with a driver's licence card beside it; the licence is a fictional generic design headed 'DRIVER LICENSE' with a blue header band, and its small photo is scratched and water-damaged so that only a silhouette of head and shoulders remains; all other fields are illegible smudged lines; a few faded banknote edges and a blank receipt in the wallet. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no real or recognisable person, and no sharp or detailed face: any face must be as degraded, small or blurred as described above; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**J31　楼梯间照片墙**（图集，P1）　——　同上

```text
Flat print artwork, straight-on with no perspective, as if scanned: six framed family photographs for a stairwell wall, one per year from the 1980s to the 1990s, shown flat in a grid: the same family of four posed in front of the same house, seen small and from a distance, faces soft and without detail as in cheap old prints with flash glare and colour fading to magenta; in each later photo one more person's face has been scratched off the print; mismatched cheap frames, dusty glass. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no real or recognisable person, and no sharp or detailed face: any face must be as degraded, small or blurred as described above; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**E29　牛奶盒**（物件，P1）　——　同上

```text
Flat print artwork, straight-on with no perspective, as if scanned: the unfolded flat pattern of a half-gallon milk carton from a fictional dairy, plain blue and white design with the words 'FRESH MILK' and 'GRADE A'; on the side panel a 'MISSING CHILD' notice whose photo is a coarse halftone blur with no recognisable face, and text lines too small to read; creased, damp-stained. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no real or recognisable person, and no sharp or detailed face: any face must be as degraded, small or blurred as described above; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D12　墙洞露出保温棉和电线**（贴花，P1）　——　我用脚本生成的那张太粗糙

```text
A single decal on a fully transparent background (PNG with alpha), straight-on orthographic view, centred with clear empty margin on all four sides: a ragged hole punched through a plasterboard interior wall: torn paper facing and crumbled white gypsum around the rim, split wooden studs at one side, pink-yellow fibreglass insulation spilling out, two grey electrical cables crossing the gap; dark inside. Only the mark itself; no wall or floor behind it, no drop shadow. Soft, irregular, natural edges that fade into transparency. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D18　塌陷露出龙骨的天花**（贴花，P2）　——　同上

```text
A single decal on a fully transparent background (PNG with alpha), straight-on orthographic view, centred with clear empty margin on all four sides: a collapsed section of suspended acoustic-tile ceiling seen from directly below: broken, sagging, water-stained mineral-fibre tiles around the rim, bent white metal grid runners, torn insulation and a loop of cable hanging down, black void above. Only the mark itself; no wall or floor behind it, no drop shadow. Soft, irregular, natural edges that fade into transparency. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**C14　白漆剥落的木护墙板**（平铺，P0）　——　现在用的素材是没掉漆的浅色壁板

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: exterior wooden clapboard siding (horizontal lap boards about 11 cm tall), thick old white paint alligatored and peeling in sheets to reveal grey weathered wood, mildew in the laps, rust runs below nail heads. The frame covers about 2 metres of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**C20　受潮分层的胶合板**（平铺，P0）　——　现在用的素材是干净的胶合板

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: weather-damaged plywood sheet used to board up a window: face veneer delaminating and curling at the edges, grey water staining, black mould spots, a few rusty screw heads. The frame covers about 2 metres of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D10　乳胶漆石膏板**（平铺，P0）　——　现在用的是蓝色灰泥，质感不对

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: interior plasterboard wall with old matt latex paint in neutral off-white (it will be tinted in the engine), roller texture, hairline cracks, scuffs, faint drip stains, a slightly darker greasy band at hand height. The frame covers about 2.5 metres of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D24　商用方块塑胶地板**（平铺，P0）　——　现在用的是水磨石，花纹不同

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: commercial vinyl composition floor tiles, 30 cm squares, beige with brown and white speckle, exactly four tiles across and four down so that it tiles, dull grey wear lanes, black heel marks, wax build-up yellowing in the corners. The frame covers about 1.2 metres of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**K04　发黄的米色塑料**（平铺，P0）　——　现在用的是干净的白塑料

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: aged beige ABS plastic of a 1980s appliance casing, unevenly yellowed by sunlight to a tobacco tone, fine moulded leather-grain texture, greasy fingerprints, scratches, dust in the grain. The frame covers about 40 centimetres of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**K06　拉丝不锈钢和镀铬**（平铺，P0）　——　现在用的是带划痕的普通金属板

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: brushed stainless steel with a clear horizontal grain, smudged with fingerprints, fine scratches, a few rust freckles, grease haze. The frame covers about 50 centimetres of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**M05　天花角落黑霉**（贴花，P0）　——　现在用的是普通的流痕

```text
Four different variants of a decal in a 2 x 2 grid on a fully transparent background (PNG with alpha), each variant centred in its own quarter with clear empty margin so that none touches another or the edge: black mould colonies spreading in the corner where a ceiling meets a wall: dense black-green speckled patches with fuzzy feathered edges and a yellow-brown damp halo. Straight-on orthographic. Only the marks themselves; no surface behind them, no drop shadow. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**M07　锈流痕**（贴花，P0）　——　现在用的是整面的锈铁皮

```text
Four different variants of a decal in a 2 x 2 grid on a fully transparent background (PNG with alpha), each variant centred in its own quarter with clear empty margin so that none touches another or the edge: rust streaks running down a wall from a fixing: an orange-brown stain starting at a point or a short line at the top and washing downwards, fading out, darker at the source. Straight-on orthographic. Only the marks themselves; no surface behind them, no drop shadow. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**M13　墙面裂缝**（贴花，P0）　——　现在用的只是一块破灰泥的边缘

```text
Four different variants of a decal in a 2 x 2 grid on a fully transparent background (PNG with alpha), each variant centred in its own quarter with clear empty margin so that none touches another or the edge: cracks in a plastered wall: a long diagonal settlement crack, a spider-web crack from an impact, a horizontal hairline crack with flaking edges, and a wide crack with missing plaster chips; dark in the gap, light dusty edges. Straight-on orthographic. Only the marks themselves; no surface behind them, no drop shadow. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D15　颗粒喷涂天花**（平铺，P1）　——　现在用的是粗颗粒墙纸

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: popcorn (sprayed acoustic) ceiling texture, lumpy cottage-cheese surface, off-white turned yellow-grey with nicotine, cobweb traces, a faint brown water ring. The frame covers about 1.5 metres of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D46　发黄的插座和开关面板**（图集，P0）　——　现在用的是一块白塑料

```text
Flat print artwork, straight-on with no perspective, as if scanned: an atlas of aged electrical wall plates, straight-on, each separate on plain mid-grey: a duplex power outlet, a single toggle light switch, a double toggle switch, a blank cover plate, a telephone jack and a coaxial TV socket, all American style in ivory plastic yellowed to tan, paint slopped over the edges, grime around the switch; plus the same duplex outlet again with black scorch marks fanning out from the slots. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**B07　路灯杆**（物件，P0）　——　现在用的是欧式复古路灯，和小镇不搭

```text
Orthographic texture and modelling reference sheet of a 1980s American highway street light: tall tapered galvanised steel pole with a hand-hole cover near the base, a curved davit arm, and a cobra-head luminaire with a prismatic glass lens; zinc dulled and streaked, rust at the base plate and bolts, stickers scraped off leaving glue. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

---

## 二、清单里还没有任何素材的（113 条）

### A　街道和路面

**A13　路面反光钉**（物件，P2）

```text
Orthographic texture and modelling reference sheet of a raised reflective road stud (cat's-eye pavement marker): squat cast-aluminium body with two amber retroreflective lenses, scuffed by tyres, tar smeared around the base. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**A46　橡胶路锥**（物件，P1）

```text
Orthographic texture and modelling reference sheet of an orange rubber traffic cone with two white retroreflective bands and a black square base, scuffed, sun-faded, with tyre marks. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### B　市政设施和街道家具

**B03　电线杆上的订书钉和传单残片**（贴花，P1）

```text
Four different variants of a decal in a 2 x 2 grid on a fully transparent background (PNG with alpha), each variant centred in its own quarter with clear empty margin so that none touches another or the edge: a cluster of rusty staples and nails with torn scraps of paper flyers still caught under them, as left on a wooden utility pole after years of posters being ripped off; rust bleeding from each staple. Straight-on orthographic. Only the marks themselves; no surface behind them, no drop shadow. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**B04　变压器桶**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a pole-mounted grey electrical distribution transformer: cylindrical steel tank with cooling fins, porcelain bushings on the lid and lifting lugs, faded grey paint, dark oil leak streaks down one side. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**B10　悬挂的黄色闪灯**（发光，P1）

```text
Orthographic texture and modelling reference sheet of a suspended yellow traffic flasher beacon: a single round amber lens in a yellow painted aluminium housing with a visor, hanging from a span wire, paint peeling, rust at the seams, lens unlit. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**B32　购物手推车**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a steel wire supermarket shopping cart, chrome plating pitted with rust, red plastic handle and child-seat flap with all lettering worn off, bent frame, one wheel missing. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**B43　民用车灯**（发光，P1）

```text
Straight-on orthographic front view of a set of car lamp lenses from a 1980s American sedan: one rectangular sealed-beam headlamp, one amber turn signal and one red tail lamp with a ribbed plastic lens; dusty, hairline cracks, unlit, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### C　建筑外立面

**C08　玻璃砖**（平铺，P1）

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: a glass block wall: square 20 cm frosted wavy glass blocks in a stack bond with grey mortar joints, grimy, slightly green, with only darkness behind. The frame covers about 1.2 metres of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**C32　砖烟囱和烟囱帽**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a red brick chimney stack with a concrete crown, a clay flue pot and a rusted sheet-metal rain cap; mortar eroded, soot staining at the top, lead flashing at the base. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**C34　电视天线和卫星锅**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a rooftop TV antenna: aluminium yagi aerial on a steel mast with a wall bracket, beside a small 1990s grey satellite dish with an LNB arm and no logo; weathered, with bird droppings. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**C42　门铃和投信口**（物件，P2）

```text
Straight-on orthographic front view of front-door hardware on white peeling painted wood: a brass mail-slot flap and a round doorbell button in a brass plate, both tarnished and green at the edges, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**C46　地下室窗井**（物件，P2）

```text
Orthographic texture and modelling reference sheet of a basement window in a galvanised steel window well at the foot of a wall: small hopper window with dirty glass, corrugated steel well liner, dead leaves and mud at the bottom. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**C58　纱门**（物件，P1）

```text
Straight-on orthographic front view of an aluminium-framed screen door, the whole door: mill-finish aluminium frame, grey insect mesh torn and sagging in the lower panel, dented kick plate, simple latch handle; pure black wherever one would see through the mesh or the tear, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**C62　铝推拉窗**（饰条，P1）

```text
Trim sheet texture: aluminium sliding-window frame parts: frame section, sash rail, bottom track and meeting stile in anodised aluminium, oxidised and pitted, plus a strip of black rubber glazing gasket and a strip of grey pile weatherstrip. Horizontal strips stacked from top to bottom, each strip running the full width of the frame and tileable left-right, straight-on orthographic. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**C68　弯折的百叶窗**（平铺，P1）

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: bent horizontal venetian blinds seen from outside through a window: off-white aluminium slats, several kinked and hanging crooked, dusty, with darkness in the gaps. The frame covers about 1.2 metres of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### D　室内通用

**D16　压花锡天花板**（平铺，P1）

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: pressed tin ceiling: a repeating embossed Victorian square panel pattern, painted cream many times over, paint chipping, rust spots, nicotine-yellowed. The frame covers about 1.2 metres of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D43　楼梯栏杆和扶手**（饰条，P1）

```text
Trim sheet texture: wooden staircase parts: a lathe-turned baluster spindle, the face of a square newel post, and a moulded handrail, in dark varnished oak worn pale where hands have touched. Horizontal strips stacked from top to bottom, each strip running the full width of the frame and tileable left-right, straight-on orthographic. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D44　铸铁暖气片**（物件，P1）

```text
Orthographic texture and modelling reference sheet of an old cast-iron column radiator of six sections with ornate scrollwork, many layers of chipped cream paint, rust at the valve, dust between the fins. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D45　踢脚电暖器**（物件，P2）

```text
Orthographic texture and modelling reference sheet of an electric baseboard heater: long, low beige painted steel casing with a slotted grille and end caps, yellowed, with scorched dust marks above the fins. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D47　圆形老式温控器**（物件，P2）

```text
Straight-on orthographic front view of a round wall thermostat from the 1970s: gold-tinted clear plastic dial cover over a temperature scale ring of tick marks only, beige base plate, yellowed, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D48　烟雾报警器**（物件，P1）

```text
Straight-on orthographic front view of a ceiling smoke detector seen from directly below: round off-white plastic body with concentric vent slots, a small test button and an indicator light, yellowed with age, dust in the slots, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D53　布艺灯罩**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a fabric lampshade: pleated beige drum shade with brown trim, water-stained, with a scorch mark on the inside. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D55　金属百叶帘**（平铺，P1）

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: closed metal venetian blinds seen from inside a room: narrow off-white aluminium slats with their cords, a few slats bent, thick grey dust on the top edges. The frame covers about 1.2 metres of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D56　发黄撕裂的卷帘**（物件，P2）

```text
Straight-on orthographic front view of a pull-down roller window shade: yellowed off-white vinyl fabric, torn along one side, water tide marks, a crooked wooden hem bar with a ring pull, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D59　热水器**（物件，P2）

```text
Orthographic texture and modelling reference sheet of a residential gas water heater: tall white enamelled steel cylinder with a sheet-metal draft hood and flue on top, a gas valve and drain tap at the bottom, rust streaks from the seams, and a yellowed label that is blank. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**D60　暖风炉**（物件，P2）

```text
Orthographic texture and modelling reference sheet of a 1980s forced-air gas furnace: tall beige painted sheet-steel cabinet with a louvred front panel and a removable lower door, a round flue collar and a rectangular duct on top, rust at the base, dusty. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### E　加油站和便利店

**E08　油枪、软管和护套**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a petrol pump nozzle and hose: aluminium nozzle with a coloured plastic splash guard and hand grip (shown once in green and once in black), black rubber hose with a breakaway coupling, grimy and fuel-stained. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**E10　挤水桶和刮水器**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a gas-station squeegee bucket: black plastic rectangular bucket with a built-in wringer grid, full of dirty blue water, and a long-handled windscreen squeegee with a worn sponge. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**E37　冷柜内层架和价签**（物件，P1）

```text
Straight-on orthographic front view of the inside of a glass-door drinks cooler: white wire shelves with plastic price-tag rails along the front edges holding small blank yellowed paper tags, frosty white back wall with a fan grille, a few fallen bottles with no labels, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**E38　卧式冷柜**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a chest ice-cream freezer: low white enamelled cabinet with two curved sliding glass lids and chrome trim, frost and condensation on the glass, rust along the bottom edge, side panels faded and blank. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**E43　热狗滚轴机**（物件，P2）

```text
Orthographic texture and modelling reference sheet of a countertop hot-dog roller grill: stainless steel base with a row of chrome rollers, glass sneeze guard, two control knobs without markings, baked-on grease, three shrivelled hot dogs. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**E46　收银台**（物件，P0）

```text
Orthographic texture and modelling reference sheet of a convenience-store checkout counter: wood-grain laminate over particleboard, one chipped corner exposing swollen chipboard, scuffed kick panel, cigarette burns and coffee rings on the top. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**E51　防盗凸面镜**（物件，P1）

```text
Straight-on orthographic front view of a round convex anti-theft mirror with a black rubber rim, dusty; its reflection shows only a blurred dark empty shop aisle with ceiling lights, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**E52　员工储物柜**（物件，P1）

```text
Straight-on orthographic front view of a bank of three grey steel staff lockers: louvred vents, recessed handles with padlock hasps, dents, scratched paint, and the remains of peeled stickers that left only glue rectangles and torn paper, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**E58　小保险箱**（物件，P2）

```text
Orthographic texture and modelling reference sheet of a small under-counter steel safe: dark grey painted cube, a combination dial with tick marks only, chrome handle, chipped paint, a pry mark at the door edge. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**E59　卫生间洁具**（物件，P1）

```text
Orthographic texture and modelling reference sheet of public restroom fixtures: a white vitreous-china toilet with a black seat, a wall-hung urinal and a wall-hung hand basin with one chrome tap; limescale, rust rings, grime in every crevice. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**E62　干手机和纸巾盒**（物件，P2）

```text
Straight-on orthographic front view of a wall-mounted restroom hand dryer and paper-towel dispenser side by side: white enamelled dryer with a chrome nozzle and push button, grey steel towel box with a slot; both scratched, with bare patches where labels were scraped off, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### F　警车

**F04　警灯灯条**（发光，P0）

```text
Orthographic texture and modelling reference sheet of a 1990s police car roof light bar: long clear polycarbonate dome over red and blue rotating beacons with mirror reflector bowls, black aluminium base, centre speaker grille; dusty, hazed plastic, unlit. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F05　推杠**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a police car push bumper: black powder-coated steel tube frame with two vertical rubber-faced uprights, paint chipped to rust. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F06　A 柱探照灯**（发光，P1）

```text
Orthographic texture and modelling reference sheet of a pillar-mounted police spotlight: round chrome lamp head with a clear lens on a shaft with a black interior handle, the chrome pitted. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F16　前格栅**（物件，P1）

```text
Straight-on orthographic front view of the front grille of a 1990s American full-size sedan: chrome egg-crate insert in a black plastic surround, no badge, an empty mounting hole where the emblem was, bug splatter and road grime, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F17　大灯**（发光，P0）

```text
Straight-on orthographic front view of a pair of rectangular composite headlamps of a 1990s American sedan: clear ribbed lens over a chrome reflector bowl with the bulb visible, amber corner marker; hazy, yellowed, unlit, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F18　尾灯和转向灯**（发光，P1）

```text
Straight-on orthographic front view of the tail lamp cluster of a 1990s American sedan: wide red ribbed plastic lens with an amber turn-signal section and a clear reversing light, chrome trim strip, faded, finely cracked, unlit, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F19　镀铬保险杠**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a chrome car bumper with a black rubber rub strip and two bumper guards, 1990s American sedan, scratched and dented, chrome peeling to show rust. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F21　车窗玻璃**（物件，P1）

```text
A single decal on a fully transparent background (PNG with alpha), straight-on orthographic view, centred with clear empty margin on all four sides: what is on a car side window, without the glass itself: rain droplets, a greasy smeared handprint dragged downwards, and dried dirty water runs. Only the mark itself; no wall or floor behind it, no drop shadow. Soft, irregular, natural edges that fade into transparency. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F25　发电机**（物件，P0）

```text
Orthographic texture and modelling reference sheet of a car alternator: finned cast-aluminium housing with a ribbed drive pulley and a rear terminal block, oily dust, white corrosion bloom. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F26　皮带轮组和张紧器**（物件，P0）

```text
Orthographic texture and modelling reference sheet of an engine belt drive: crankshaft pulley, alternator pulley and a spring tensioner arm with an idler wheel, bare cast iron and black steel, rust film, old grease. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F29　散热器和风扇罩**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a car radiator with its plastic fan shroud: aluminium fin core with bent fins and dead insects, black plastic end tanks with a pressure cap, engine fan behind the shroud, coolant stains. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F33　线束和保险丝盒**（物件，P1）

```text
Orthographic texture and modelling reference sheet of an engine-bay wiring harness and fuse box: black split corrugated loom with taped branches and multi-pin connectors, a black plastic fuse box with the lid off showing coloured blade fuses and relays; dusty and brittle. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F34　引擎盖内衬**（物件，P0）

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: the insulation pad under a car bonnet: sagging grey-brown fibre felt with a quilted diamond stitch pattern, oil stains, slightly frayed. The frame covers about 1 metre of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F35　引擎盖支撑杆和机油尺**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a bonnet prop rod and an oil dipstick: thin zinc-plated steel rod with a rubber grommet end, and a yellow plastic loop-handle dipstick with an oily blade. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F36　防火墙**（物件，P1）

```text
Straight-on orthographic front view of the engine-bay firewall of a 1990s sedan: stamped sheet steel in dark blue body colour, brake booster drum and master cylinder, heater hoses through rubber grommets, seam-sealer lines, surface rust, grime, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F43　点火开关和钥匙**（物件，P0）

```text
Straight-on orthographic front view of a steering-column ignition lock in close-up: chrome lock cylinder with a worn brass key inserted and a plain steel ring holding two more keys, black textured plastic column shroud, positions marked by ticks only, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F46　顶棚、遮阳板和地垫**（物件，P1）

```text
Flat print artwork, straight-on with no perspective, as if scanned: a sheet of car interior soft trim, each part flat and separate on plain mid-grey: a sagging grey cloth headliner panel, two cloth sun visors, and a black ribbed rubber floor mat caked with dried mud with a crushed plain paper coffee cup on it. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F48　霰弹枪架**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a police car shotgun rack: black steel vertical gun-lock bracket with a padded clamp and an electric lock box, empty, the paint worn at the clamp. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F49　后备箱衬垫**（物件，P1）

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: a car boot liner: charcoal-grey needle-punched felt carpet, flattened and pilled, with dark oil stains and a rust-coloured ring where a can stood. The frame covers about 1 metre of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### G　五金与汽修行

**G10　配钥匙机和钥匙坯墙**（物件，P2）

```text
Orthographic texture and modelling reference sheet of a hardware-store key-cutting machine from the 1980s: cast-metal duplicator with a vise carriage and a cutting wheel behind a guard, brass filings everywhere; and, as a separate front view, a pegboard wall of blank brass and nickel key blanks on hooks. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**G16　管件箱**（图集，P1）

```text
Flat print artwork, straight-on with no perspective, as if scanned: an atlas of plumbing fittings seen from above, sorted in open cardboard bin compartments: copper elbows and tees, galvanised steel nipples and couplings, white and grey plastic pipe fittings; tarnished and dusty. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**G21　割草机**（物件，P2）

```text
Orthographic texture and modelling reference sheet of a 1980s walk-behind petrol lawn mower: red painted steel deck, exposed engine with a pull-cord recoil starter and round air filter, folding tubular handle, grass-stained, rust on the deck, no decals. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**G32　鹿头标本**（物件，P2）

```text
Orthographic texture and modelling reference sheet of a mounted white-tailed deer head trophy on a dark wooden shield plaque: dusty fur, glass eyes, one antler tip broken, cobwebs. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**G39　举升机**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a two-post car lift: blue painted steel columns with hydraulic carriage arms and rubber pads, yellow hazard stripes on the arms, chipped paint, oil-stained base plates. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**G41　马凳**（物件，P2）

```text
Orthographic texture and modelling reference sheet of a ratcheting axle jack stand: red painted steel three-leg base, bare notched ratchet post with a saddle top, greasy, paint chipped. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**G49　轮胎架和拆胎机**（物件，P2）

```text
Orthographic texture and modelling reference sheet of tyre shop equipment: a wall-mounted steel pipe rack holding worn tyres with no sidewall lettering, and a red 1980s tyre changer with a turntable and bead-breaker arm, grimy. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**G52　零件清洗槽**（物件，P2）

```text
Orthographic texture and modelling reference sheet of a parts washer: red painted steel solvent tank on legs with a hinged lid and a flexible spigot, the basin full of black oily solvent with a parts brush, drips down the front. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**G55　排气管和消音器挂架**（物件，P2）

```text
Orthographic texture and modelling reference sheet of a ceiling rack of used exhaust parts: rusty steel exhaust pipes, oval mufflers and catalytic converter shells hanging from a welded angle-iron frame on wire hooks. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**G56　引擎吊架和吊着的发动机**（物件，P2）

```text
Orthographic texture and modelling reference sheet of an engine hoist: red steel frame on castors with a hydraulic jack and chain, holding a greasy cast-iron V8 engine block with exhaust manifolds in a chain sling. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**G67　加法机和打字机**（物件，P2）

```text
Orthographic texture and modelling reference sheet of two office machines of the 1970s: a beige mechanical adding machine with a paper roll, and a grey manual typewriter; all key tops worn blank, dusty and yellowed. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### H　锈锚小酒馆

**H11　啤酒龙头和生啤塔**（物件，P2）

```text
Orthographic texture and modelling reference sheet of a bar draft-beer tower: chrome column with three taps and plain black tap handles, a stainless drip tray with dried foam residue, tarnished. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**H12　杯具**（物件，P1）

```text
Orthographic texture and modelling reference sheet of bar glassware: a pint glass, a shot glass and a whiskey tumbler, plain, some with dried foam rings or a finger of amber liquid, fingerprints and dust. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**H17　台球桌**（物件，P2）

```text
Orthographic texture and modelling reference sheet of a coin-operated pool table: dark wood-veneer cabinet with chrome corners and a coin slide, green baize torn and stained, a few balls shown with their plain sides up, and a wall cue rack with warped cues. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**H26　保龄球奖杯和鱼标本**（物件，P2）

```text
Orthographic texture and modelling reference sheet of bar shelf trophies: tarnished gold-coloured plastic bowling trophies on fake marble bases with blank plaques, and a mounted largemouth bass on a wooden plaque, dusty. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**H27　常年不摘的彩灯**（发光，P2）

```text
Emissive texture on a pure black (#000000) background, straight-on orthographic: a string of old outdoor Christmas lights, glowing: large bulbs in red, green, blue, amber and white on a twisted cord running in three gentle swags across the frame, several bulbs dead, paint flaking off others. Only the light-emitting parts are bright; everything else is black. No perspective, no surrounding scene. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**H30　后厨油炸锅和不锈钢台**（物件，P2）

```text
Orthographic texture and modelling reference sheet of commercial kitchen equipment: a double-basket stainless steel deep fryer with a grease-caked front and unmarked control knobs, and a stainless steel prep table with a lower shelf; yellow-brown polymerised oil film, burnt residue. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### I　洗衣房

**I06　圆窗玻璃和门圈**（物件，P0）

```text
Straight-on orthographic front view of the door of a front-loading washing machine: thick domed porthole glass in a chrome ring with a grey rubber gasket and a latch handle, soap scum on the glass, the drum dark behind it, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**I14　滑槽式投币器**（物件，P1）

```text
Straight-on orthographic front view of a laundromat coin slide: chrome push-in coin chute with eight vertical coin slots on a brushed steel plate, a scraped bare area where the label was, worn smooth by thumbs, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**I17　折叠台**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a laundry folding table: long white laminate top with brown edge banding on a grey steel frame, chipped edges, detergent stains. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**I18　洗衣车**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a laundromat laundry cart: chrome wire basket on four castors with a tall hanging-rail pole and a canvas liner, grey with grime, one castor bent. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### J　克拉克家

**J01　门廊秋千椅和门垫**（物件，P2）

```text
Orthographic texture and modelling reference sheet of a porch swing bench: slatted wooden two-seater hanging on rusty chains, white paint peeling to grey wood; and beside it, top-down, a worn plain coir doormat. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**J05　门厅鞋架和外套钩**（物件，P2）

```text
Orthographic texture and modelling reference sheet of entry-hall furniture: a wooden wall board with five brass double coat hooks, and a low two-shelf wooden shoe rack, scuffed and muddy. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**J15　布谷鸟挂钟**（物件，P2）

```text
Orthographic texture and modelling reference sheet of a wooden cuckoo clock: carved dark chalet with leaves, a small door above the dial, pine-cone weights on chains and a pendulum; the dial has tick marks only and the hands are stopped at three o'clock; dusty. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**J22　不锈钢水槽和脏碗**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a kitchen sink: double-bowl stainless steel drop-in sink with a chrome mixer tap, water-spotted, holding a stack of dirty plates, a pan of congealed grease and cloudy water. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**J26　洗碗机**（物件，P2）

```text
Straight-on orthographic front view of the front of a 1980s built-in dishwasher: almond enamel door panel, black control strip with a dial and push buttons marked only by ticks, chrome handle bar, rust at the bottom edge, greasy fingerprints, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**J27　墙挂电话**（物件，P1）

```text
Straight-on orthographic front view of a wall-mounted push-button telephone of the 1980s: beige plastic body with a keypad of twelve blank square buttons, handset on the hook, a very long tangled coiled cord hanging down, yellowed, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**J33　立式吸尘器**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a 1980s upright vacuum cleaner: burgundy plastic motor hood with a headlamp, grey cloth dust bag with a zip, chrome handle tube, cord wrapped on its hooks, dusty, no brand. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**J34　浴缸和发霉的浴帘**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a bathroom tub: white enamelled steel bathtub with chrome tap and overflow, a rust stain under the tap and a grey ring of scum; and, as a separate front view, a clear vinyl shower curtain on a chrome rail with black mould spots along the bottom. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**J35　洗手台和镜门药柜**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a bathroom vanity: white pedestal sink with separate hot and cold taps, and above it a mirrored medicine cabinet with one door open on shelves of unlabelled brown pill bottles and a razor; toothpaste spatter, limescale. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**J51　车库冷冻柜和旧冰箱**（物件，P1）

```text
Orthographic texture and modelling reference sheet of garage appliances: a white chest freezer with a rust-spotted lid, and a round-cornered 1960s refrigerator with a chrome pull handle; both yellowed and dented, with only screw holes where a badge used to be. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**J53　车库门电机、导轨和拉绳**（物件，P1）

```text
Orthographic texture and modelling reference sheet of a garage door opener: 1980s beige sheet-metal motor unit with a light lens, a steel T-rail with chain, and a red emergency-release pull cord with a plastic handle; dusty and cobwebbed. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### K　家电怪外壳

**K11　旋钮、按键和兔耳天线**（图集，P1）

```text
Flat print artwork, straight-on with no perspective, as if scanned: an atlas of small appliance controls, straight-on, each separate on plain mid-grey: twelve knobs (chrome-capped, black fluted, beige pointer), eight push buttons and rocker switches, three slide levers, and two telescopic rabbit-ear antenna rods; yellowed plastic, worn where fingers touch, marked by ticks only. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**K33　电风扇网罩和扇叶**（物件，P0）

```text
Orthographic texture and modelling reference sheet of a 1980s oscillating desk fan: chrome wire cage guard, three translucent blue plastic blades, beige plastic motor housing and base with push buttons, dust clumps on the cage and the blade edges. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### P　第一人称

**P18　手铐和手铐套**（物件，P2）

```text
Orthographic texture and modelling reference sheet of police handcuffs and their belt case: nickel-plated steel hinged handcuffs with a small key, and a black basket-weave leather case with a snap flap; scratched, worn at the edges. Show front, back, left side, right side and top as five separate straight-on views at the same scale, well spaced on a plain flat mid-grey (#808080) background. No perspective in any view, no three-quarter view. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**P20　钥匙串**（物件，P1）

```text
Flat print artwork, straight-on with no perspective, as if scanned: a set of keys laid flat and separate on plain mid-grey, seen from above: a steel split ring, a car key with a plain black plastic head, a brass house key, a small padlock key, a tubular lock key and a long old shed key, each with a different coloured blank plastic tag; worn. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### Q　界面

**Q06　键位和手柄图标**（图集，P1）

```text
Flat print artwork, straight-on with no perspective, as if scanned: a set of game input icons as flat white glyphs on a pure black background, in a neat grid: blank rounded keycaps in three widths, a mouse with the left button filled, a mouse with the right button filled, a mouse with the wheel filled, four plain round gamepad face buttons, two triggers, two bumpers, two analogue sticks and a d-pad; slightly rough photocopied edges. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### E　加油站和便利店

**E25　价签条和促销牌**（图集，P0）

```text
Flat print artwork, straight-on with no perspective, as if scanned: an atlas of supermarket shelf-edge tags and promotion cards, each separate on plain mid-grey: white and yellow paper shelf strips with a price box reading '0.99', '1.49' or '2/3.00' and a block of barcode stripes, a red starburst tag reading 'SALE', a round wobbler reading 'NEW', and a card reading '2 FOR 1'; yellowed, curled, some torn. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### G　五金与汽修行

**G24　强力胶吊卡**（物件，P0）

```text
Flat print artwork, straight-on with no perspective, as if scanned: the front and the back of a blister hang-card for a small tube of superglue, shown flat side by side: printed card with a hanging slot, a clear blister over a yellow tube, the fictional brand name 'GRIPTITE' in bold red letters, a generic warning triangle; 1990s packaging design, shelf-worn and scuffed. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### H　锈锚小酒馆

**H02　窗内啤酒霓虹**（发光，P1）

```text
Emissive texture on a pure black (#000000) background, straight-on orthographic: four neon beer window signs, glowing, each separate: 'Ironwood Lager' in red script with a pine-tree outline, 'Lakehead' in blue with a wave, 'Old Foundry' in amber with a gear, 'Tamarack Light' in green; visible glass tubes with black-painted joins and a couple of dead sections. Only the light-emitting parts are bright; everything else is black. No perspective, no surrounding scene. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**H13　杯垫、餐巾和满的烟灰缸**（图集，P1）

```text
Flat print artwork, straight-on with no perspective, as if scanned: an atlas of bar-top clutter seen from above, each item separate on plain mid-grey: round cardboard beer coasters printed 'Ironwood Lager' and 'Old Foundry', stained with glass rings; folded paper napkins with a small anchor print; a glass ashtray overflowing with cigarette butts and ash; a matchbook with a plain anchor on the cover. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**H16　点唱机**（发光，P1）

```text
Straight-on orthographic front view of a 1970s bar jukebox: chrome and wood-grain cabinet, curved glass top over a rack of tiny title cards too small to read, rows of selection push buttons, a coin slot, and a speaker grille behind a backlit amber and red plastic panel that is glowing; fingerprints, cigarette burns, no maker's name, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**H21　墙挂公用电话**（物件，P1）

```text
Straight-on orthographic front view of a wall-mounted bar payphone: black armoured steel coin telephone with a chrome coin slot, a blank keypad and an armoured handset cord, on wood panelling covered in scribbled telephone numbers and doodles in pen and pencil (digits only), filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**H23　卡座桌面**（物件，P1）

```text
Straight-on orthographic front view of a bar booth table top seen from directly above: wood-grain laminate with a dark metal edge, covered in knife-carved initials, hearts and years between 1981 and 1996, cigarette burns and glass rings, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**H25　照片墙**（图集，P1）

```text
Flat print artwork, straight-on with no perspective, as if scanned: an atlas of framed bar-wall snapshots from the 1970s to the 1990s, with faded colour and flash glare: a bowling team in matching shirts, a softball team by a dugout, men holding up a large fish, regulars along a bar counter; every person is invented, small in the frame, with a soft low-detail face as in cheap old prints; mismatched cheap wooden frames, nicotine-yellowed glass. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no real or recognisable person, and no sharp or detailed face: any face must be as degraded, small or blurred as described above; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### I　洗衣房

**I02　窗贴**（贴花，P1）

```text
A single decal on a fully transparent background (PNG with alpha), straight-on orthographic view, centred with clear empty margin on all four sides: vinyl window lettering for a laundromat in white and red sticker letters, sun-cracked and partly peeled, stacked in five lines: 'COIN LAUNDRY', 'OPEN 24 HOURS', 'WASH 75c', 'DRY 25c', 'DROP-OFF SERVICE'. Only the mark itself; no wall or floor behind it, no drop shadow. Soft, irregular, natural edges that fade into transparency. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**I15　换币机**（发光，P1）

```text
Straight-on orthographic front view of a 1980s laundromat bill-to-coin change machine: brushed steel cabinet, a backlit plastic instruction panel glowing pale yellow that shows only arrows and pictograms of a banknote and coins, a bill slot, a coin cup at the bottom, a lock at the side; scratched, the panel cracked, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**I20　旧杂志**（图集，P1）

```text
Flat print artwork, straight-on with no perspective, as if scanned: an atlas of six 1990s magazine covers, flat, with the fictional titles 'HOME & HEARTH', 'LAKES OUTDOORS', 'TV WEEK', 'AUTO TRADER WEEKLY', 'TRUE STORIES' and 'STYLE NOW'; the covers show objects, cars, interiors and landscapes, not people; creased, water-rippled, with coffee rings and a blank address-label box. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**I22　软木布告板**（物件，P1）

```text
Straight-on orthographic front view of a cork bulletin board in an aluminium frame, crowded with pinned paper: several flyers headed 'MISSING' whose photocopied halftone portraits are too degraded to recognise, a flyer headed 'LOST DOG' with a dog photo, a 'ROOM FOR RENT' sheet with tear-off tabs, business cards; push pins, curled yellowed paper; all small text is illegible grey lines, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no real or recognisable person, and no sharp or detailed face: any face must be as degraded, small or blurred as described above; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**I24　停走的挂钟**（物件，P1）

```text
Straight-on orthographic front view of a plain round wall clock: white dial with black numerals 1 to 12 and tick marks, black plastic rim, dusty domed cover, the hands stopped at exactly three o'clock, a brown water stain across the dial, filling the frame and cropped to the outline, on a plain flat mid-grey (#808080) background. It will be projected onto a simple 3D model, so: perfectly head-on, no perspective, no tilt, all edges straight and parallel to the frame. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### L　血肉和遗物

**L05　从缝里挤出的皮肤**（平铺，P0）

```text
Seamless tileable texture, straight-on orthographic view, filling the whole square frame edge to edge with no border: pale human skin bulging out of a narrow gap between two sheet-metal panels: taut, bloodless grey-pink skin with visible pores, a few moles, fine hairs and stretch marks, and a faded fragment of a blue-green tattoo distorted by the stretching. The frame covers about 40 centimetres of real surface. It must repeat seamlessly left-right and top-bottom, with no single feature that would stand out when tiled. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. No perspective, no objects on it. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**L14　断面**（图集，P0）

```text
Flat print artwork, straight-on with no perspective, as if scanned: an atlas of cross-sections of a creature that is a household appliance with living tissue inside, each separate on plain mid-grey, like evidence photographs: cut sheet steel with enamel paint over a layer of yellow fat and red muscle; a severed cable that is half copper wire and half tendon; a snapped beige plastic housing with bone inside; a cut rubber hose that is a blood vessel. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**L36　被顶起的墙纸**（贴花，P1）

```text
A single decal on a fully transparent background (PNG with alpha), straight-on orthographic view, centred with clear empty margin on all four sides: wallpaper lifted and split open by something growing behind it: 1980s floral wallpaper torn along a seam and bulging outwards, a dark wet stain spreading from the split, pale root-like tendrils in the gap. Only the mark itself; no wall or floor behind it, no drop shadow. Soft, irregular, natural edges that fade into transparency. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### N　招牌和印刷品

**N02　加油站品牌整套**（图集，P0）

```text
Flat print artwork, straight-on with no perspective, as if scanned: a brand sheet for a fictional 1990s American gas station chain called 'NORTHLINE': a logo of a white compass needle over dark green and cream stripes with the wordmark in bold italic sans-serif, shown as flat artwork for a pole sign face, a canopy fascia stripe, a pump face decal, a paper coffee cup wrap and a till receipt header; sun-bleached and faded. It must not resemble any real fuel brand. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**N25　饮料和啤酒品牌**（图集，P0）

```text
Flat print artwork, straight-on with no perspective, as if scanned: a sheet of thirteen fictional 1990s drink brand logos as flat artwork on plain off-white: sodas 'Polar Pop', 'Sunvale Orange', 'Kettle Root Beer', 'Lime Rally', 'Big Cedar Cola', 'Grapeline'; beers 'Ironwood Lager', 'Lakehead Pilsner', 'Old Foundry', 'Tamarack Light'; whiskeys 'Three Smokestacks', 'Pine Hollow Rye', 'Judge Abbott'; each in its own style of the period. None may resemble any real brand. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**N27　家电和工具品牌**（图集，P0）

```text
Flat print artwork, straight-on with no perspective, as if scanned: a sheet of thirteen fictional 1980s and 1990s product brand logos as flat artwork on plain off-white: appliances 'Halvern', 'Norvane', 'Emberly', 'Wexford Electric', 'Coldmoor'; tools 'Ridgeback', 'Stoutman', 'Anvil & Oak'; batteries 'Voltree', 'Amberlite'; laundry detergents 'Brightwell', 'Suds-O', 'Clean Harbor'; each also repeated as a small one-colour metal badge. None may resemble any real brand. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

### O　天空与特效

**O28　肉块和碎壳**（图集，P1）

```text
Flat print artwork, straight-on with no perspective, as if scanned: an atlas of sixteen pieces of debris that fly off when a creature made of appliance and flesh is shot, each separate on a fully transparent background (PNG with alpha): torn enamelled sheet-metal shards with chipped paint and rust, cracked beige plastic fragments, glass shards, and wet red chunks of meat, some of them still stuck to a piece of metal. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

---

## 三、如果想一次说清风格再逐条出

在同一个对话里先发下面这段，再一条一条发上面的提示词，出图工具会更稳定：

```text
You are making texture source images for a first-person survival horror game set in a small American Rust Belt highway town in the 1990s, abandoned in one night; it is 3 a.m. and raining. The look is photo-sourced and dirty: real, cheap, worn-out materials rather than clean or stylised art. Every image must work as a game texture, which means: straight-on orthographic views only, flat shadowless lighting, no perspective, no scene around the subject, exactly one subject per image, 1024 x 1024. Never include real brands, real people, recognisable faces, or any text I did not spell out. Never add captions, labels or watermarks. When I ask for transparency, give a real transparent PNG, never a painted checkerboard.
```

---

## 四、第二轮：第一批核对后要重出的 8 条（2026-11-12）

第一批 147 张的核对结论在 `docs/texture_check/xiaoyu_gpt_batch.md`：合格 65、重导 70、重出 8、我来修 4。

**先做重导**（不用重新出图）：除平铺以外的类型，从原图**等比缩小放进 1024 画布，不要裁**（贴花那一批就是这样做的，是对的）；
空出来的边，多视图、正面图、印刷品填中灰 `#808080`，发光和遮罩填纯黑。多视图直接交原始尺寸也可以。要重导的 70 个编号在核对文档的逐项表里。
另外 B10、B43、F04、F06、F17、F18、H16、I15 这 8 个文件名的通道从 `Emission` 改成 `Color`。

**再出下面 8 条**。和第一轮比，每条多了一句"整个东西必须完整地在画面里，四边留空"。Frostbelt Fizz、Millrace、Tidewash 是替换撞名牌子的新名字，**同样没有查过商标**。

**E21　自动售货机**　——　换配色，去掉飘带

```text
Straight-on orthographic front view of a 1990s outdoor soft-drink vending machine, the whole machine from top to bottom: tall steel cabinet with a large front panel in dark forest green showing a row of cream-coloured cedar tree silhouettes and the fictional brand name 'BIG CEDAR COLA' in cream slab-serif capital letters; no red, no wave, no ribbon, no swoosh, no glass of cola; a column of eight blank selection buttons, coin slot, bill slot and a delivery hatch at the bottom; sun-faded, scratched, rust at the base, unlit. On a plain flat mid-grey (#808080) background. Perfectly head-on, no perspective, no tilt. Everything must fit completely inside the frame with clear empty margin on all four sides; nothing may be cut off by the edge of the image. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**K19　售货机发光面板**　——　换配色和牌子

```text
Emissive texture on a pure black (#000000) background, straight-on orthographic: the lit front panels of two soft-drink vending machines, side by side and both complete: one with the fictional brand 'BIG CEDAR COLA' in cream slab-serif letters on dark forest green with cedar tree silhouettes; one with the fictional brand 'FROSTBELT FIZZ' in dark blue block letters on pale yellow with a single large snowflake; no red, no wave, no ribbon, no animals; uneven fluorescent backlight with a dead tube leaving one dark band. Only the light-emitting parts are bright. Everything must fit completely inside the frame with clear empty margin on all four sides; nothing may be cut off by the edge of the image. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**N25　虚构饮料品牌**　——　换掉两个撞名的牌子

```text
Flat print artwork, straight-on with no perspective, as if scanned: a sheet of thirteen fictional 1990s drink brand logos as flat artwork on plain off-white paper, in a neat grid: sodas 'Frostbelt Fizz', 'Sunvale Orange', 'Kettle Root Beer', 'Lime Rally', 'Big Cedar Cola' (dark green and cream, with a cedar tree; not red), 'Grapeline'; beers 'Ironwood Lager', 'Millrace Pilsner', 'Old Foundry', 'Tamarack Light'; whiskeys 'Three Smokestacks', 'Pine Hollow Rye', 'Judge Abbott'; each in its own style of the period. None may resemble any real brand. Everything must fit completely inside the frame with clear empty margin on all four sides; nothing may be cut off by the edge of the image. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**N27　虚构家电 / 工具品牌**　——　换掉一个撞名的牌子

```text
Flat print artwork, straight-on with no perspective, as if scanned: a sheet of thirteen fictional 1980s and 1990s product brand logos as flat artwork on plain off-white paper, in a neat grid: appliances 'Halvern', 'Norvane', 'Emberly', 'Wexford Electric', 'Coldmoor'; tools 'Ridgeback', 'Stoutman', 'Anvil & Oak'; batteries 'Voltree', 'Amberlite'; laundry detergents 'Brightwell', 'Suds-O', 'Tidewash'; each also repeated as a small one-colour metal badge. None may resemble any real brand. Everything must fit completely inside the frame with clear empty margin on all four sides; nothing may be cut off by the edge of the image. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**H02　窗内啤酒霓虹**　——　换掉一个撞名的牌子，四块都要完整

```text
Emissive texture on a pure black (#000000) background, straight-on orthographic: four neon beer window signs, glowing, in a 2 x 2 grid, each one complete and separate: 'Ironwood Lager' in red script with a pine-tree outline, 'Millrace' in blue with a water wheel, 'Old Foundry' in amber with a gear, 'Tamarack Light' in green; visible glass tubes with black-painted joins and a couple of dead sections. Only the light-emitting parts are bright. Everything must fit completely inside the frame with clear empty margin on all four sides; nothing may be cut off by the edge of the image. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**L29　警察钱包和证件**　——　背景要纯灰，不要场景

```text
Flat print artwork, straight-on with no perspective, as if scanned from directly above: a worn brown leather wallet opened flat, and beside it a complete driver's licence card; the licence is a fictional generic design headed 'DRIVER LICENSE' with a blue header band, and its small photo is scratched and water-damaged so that only a silhouette of head and shoulders remains; all other fields are illegible smudged lines; a few faded banknote edges and a blank receipt in the wallet. On a plain flat mid-grey (#808080) background and nothing else: no street, no table, no scene. Everything must fit completely inside the frame with clear empty margin on all four sides; nothing may be cut off by the edge of the image. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no real or recognisable person, and no sharp or detailed face: any face must be as degraded, small or blurred as described above; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**F21　车窗（雨滴、脏手印）**　——　要真透明

```text
A single decal on a fully transparent background (PNG with alpha), straight-on orthographic view, centred: what is on a car side window, without the glass itself: rain droplets, a greasy smeared handprint dragged downwards, and dried dirty water runs. Only the marks themselves; no grey background, no glass, no surface behind them, no drop shadow. Soft, irregular, natural edges that fade into transparency. Everything must fit completely inside the frame with clear empty margin on all four sides; nothing may be cut off by the edge of the image. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```

**O28　怪物碎片**　——　要真透明

```text
An atlas of sixteen pieces of debris in a 4 x 4 grid on a fully transparent background (PNG with alpha), each piece separate and not touching another: what flies off when a creature made of household appliance and flesh is shot: torn enamelled sheet-metal shards with chipped paint and rust, cracked beige plastic fragments, glass shards, and wet red chunks of meat, some of them still stuck to a piece of metal. No grey background, no surface behind them, no drop shadows. Straight-on orthographic. Everything must fit completely inside the frame with clear empty margin on all four sides; nothing may be cut off by the edge of the image. Flat, even, shadowless overcast lighting (this is an albedo map): no cast shadows, no specular highlights, no reflections, no vignette, no depth of field. Setting: a small American Rust Belt highway town of the 1990s, abandoned in a single night; it is 3 a.m. and raining. Everything is old, cheap, heavily used and dirty: water stains, rust bleeding from fixings, grease, nicotine yellowing, peeling paint, sun-faded colour, dust in the corners. Muted, desaturated, slightly cold palette. Photorealistic, like a photo-scanned game texture; not illustrated, not painted, not stylised, not clean, not new. Rules: no real brand names, logos or trademarks; no people and no faces; no letters or numbers unless this prompt gives the exact text; no labels, captions, ID codes, arrows or watermark; no fake checkerboard transparency.
```
