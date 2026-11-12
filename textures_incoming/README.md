# 贴图批次 2026-11-12

《凌晨三点公路镇》按 `texture_prompts-1.md` / `texture_prompts-2.md` 生成的贴图，共 147 张，文件名 `{编号}_{英文名}_{Color|Emission|Mask}.png`，子目录按编号首字母分。

## 第二轮（2026-11-12，按 prompts-2 第四节）

- 出图工具：gpt-image-2.6-sunburst（中转 api.apikey.fan），全部 1024x1024 PNG
- 第一轮归一化时裁过方图、原图没留，所以“重导 70”没法从原图重导，改成了非平铺 115 条全部重出：**等比放进 1024 画布、不裁切**；多视图/正面图/印刷品填中灰 `#808080`，发光/遮罩填纯黑
- 第四节 8 条用了新版提示词：E21、K19、N25、N27、H02、L29、F21、O28；F21、O28 是真透明底
- `B10、B43、F04、F06、F17、F18、H16、I15` 通道名由 `Emission` 改为 `Color`
- 贴花 10 条、平铺 20 条、遮罩 2 条沿用第一轮（贴花本来就是等比放进透明画布，未裁）
- 平铺图接缝仍需检查修边；法线/粗糙度从颜色图推
- 改过提示词重出的：F48（安全策略拒 violence，gun-lock bracket → locking bracket）、H25（连接被上游断开，去掉人物描述改空景）、C65/C66（遮罩第一版出成实景照片）
- 每张来源与日期见 `sources.csv`，完整清单见 `INDEX.md`

按规矩这批是生成工具做的，Steam 上架需要申报；虚构品牌名（NORTHLINE、Big Cedar Cola、Frostbelt Fizz、Millrace、Tidewash 等）尚未做商标检索。
