# 贴图材质需求清单

对应 `docs/GDD.md`（《凌晨三点公路镇》）。写于 2026-11-11。这是独立的一份，`docs/design_supplement.md` 第五节指向这里。

共 **785 项**：P0 260 项，P1 375 项，P2 150 项。一项可能是一套材质，也可能是一张包含多个变体的图集，说明里写了变体数量。

按来源：需要原创绘制的 323 项，适合实拍的 205 项，素材库现成的 195 项，程序生成的 62 项。

**现状（2026-11-12）**：每一项最后一列"素材"写明了它现在的着落。

| 着落 | 项数 | 含义 |
| --- | --- | --- |
| 素材名（带链接） | 125 | 素材库里有名实相符的现成素材，点开就是素材页面 |
| 素材名（凑合） | 161 | 有能当底材用的素材，但要改色、叠贴花或配模型才是这一项。要做什么写在 `docs/texture_sources.csv` |
| 已做 | 62 | 标"程"的项：45 项出了图（`fps/assets/textures/proc/`），17 项是着色器、实时反光或网格，不出图 |
| 程序生成 | 28 | 素材库里没有、但能用脚本做出来的污渍和破损贴花，在 `textures/procedural/`（生成脚本 `art/textures/gen_decals.py`）。第一版，没在引擎里看过 |
| 待找 | 93 | 标"拍"或"库"的项，还没有合格的素材 |
| 有初稿 #n | 296 | 标"画"的项，小鱼上传的图集里有（`#n` 是第几张）。是设定图和素材拼板，不是成品贴图，见 `docs/texture_check/xiaoyu_atlases.md` |
| 待画 | 20 | 标"画"的项，还没有任何图 |

有素材的 286 项，素材本身**已经下载好放在仓库的 `textures/`** 里（1K，见 `textures/README.md`），共 216 个素材：
137 套材质和贴花、77 个三维模型、1 张夜空全景、1 套字体。每一项用哪个素材、接入时还要做什么，在 `docs/texture_sources.csv`。
这些素材分两轮选出：先核对用户整理的三份表格留下 111 项，再直接翻 ambientCG 和 Poly Haven 的完整目录逐项挑选补到现在的数。

还剩 93 项"待找"，两个素材库里都没有合适的现成素材，名单和原因在 `docs/texture_check/still_missing.md`。
这些项和需要重做的项，给出图工具用的提示词在 `docs/texture_prompts.md`。
小鱼按这些提示词出的第一批 147 张在分支 `xiaoyu/textures-20261112`，核对结论在 `docs/texture_check/xiaoyu_gpt_batch.md`（合格 65、重导 70、重出 8、我来修 4）；**下面各项的"素材"栏还没有按这批图更新**，等图接收进 `textures/` 时再改。

交付规范见 `docs/texture_delivery.md`，验收用 `tools/check_textures.py`。

## 怎么读这份清单

**优先级**

| 级 | 含义 |
| --- | --- |
| P0 | 没有它，灰盒换上贴图后第一眼不成立，或者某个玩法读不懂 |
| P1 | 让四栋建筑各有辨识度，让叙事小物件到位 |
| P2 | 细节密度，有余力再做 |

**类型**

| 类型 | 含义和规格 |
| --- | --- |
| 平铺 | 可重复的墙面和地面材质。颜色、法线、粗糙度/金属/遮蔽合并图各一张，2K，覆盖 4 米 |
| 饰条 | 门窗框、踢脚线这类长条构件共用的一张图集，2K |
| 物件 | 某件东西专属的一套贴图。普通物件每米 512 像素，标了每米 1024 的组除外 |
| 贴花 | 叠在别的表面上的污渍和痕迹，带透明。同类的多个变体拼成一张 2K 到 4K 的图集 |
| 图集 | 许多小图拼在一张里（包装、纸品、标志） |
| 遮罩 | 灰度图，控制别的效果出现在哪里 |
| 发光 | 会亮的面，除了常规贴图还要一张自发光图 |
| 序列帧 | 逐帧动画拼成的一张图 |
| 着色器 | 主要靠程序实现，贴图只是辅助 |
| 灯光遮罩 | 套在灯上决定光斑形状，或者一条亮度曲线 |
| 全景 | 天空或远景 |
| 卡片 | 带透明的面片（草、蜘蛛网、雨） |

**来源**

| 代号 | 含义 |
| --- | --- |
| 拍 | 实拍照片处理。首选你自己拍；拍不到的用 CC0 照片素材库补 |
| 库 | CC0 素材库现成的（ambientCG、Poly Haven 等），可商用、不用署名 |
| 画 | 需要原创设计或绘制：招牌、包装、纸品、人像、怪物内腔。用了生成工具的要记录，Steam 上架要申报 |
| 程 | 程序生成或在引擎里实现 |

**不能用的来源**：从任何游戏（包括《恐惧之泣》）里提取的贴图；带真实品牌、真实车牌、真实人脸的照片；授权不明的网图。
所有招牌、商品、厂牌都用虚构品牌；所有人像都要原创。

**实拍怎么拍**：阴天或背阴处，镜头正对表面，不开闪光；同一面墙错位连拍九张，方便拼成可重复的平铺图；
污渍和裂缝单独拍特写，背景越干净越好抠。

**编号**：组别字母加序号（例如 A01）。实现时贴图文件按这个编号命名，方便对照进度。
之后新增的条目接在本组末尾，不重排已有编号。

## 各组数量

| 组 | 内容 | 总数 | P0 | P1 | P2 |
| --- | --- | --- | --- | --- | --- |
| A | 街道和路面 | 54 | 13 | 30 | 11 |
| B | 市政设施和街道家具 | 45 | 9 | 23 | 13 |
| C | 建筑外立面 | 72 | 19 | 38 | 15 |
| D | 室内通用 | 63 | 15 | 38 | 10 |
| E | 加油站和便利店（起点和安全屋） | 67 | 21 | 28 | 18 |
| F | 警车 | 51 | 27 | 20 | 4 |
| G | 米勒父子五金与汽修行 | 68 | 17 | 28 | 23 |
| H | 锈锚小酒馆 | 33 | 4 | 16 | 13 |
| I | 24 小时投币洗衣房 | 28 | 9 | 14 | 5 |
| J | 克拉克家（终局） | 56 | 9 | 31 | 16 |
| K | 家电怪和设施怪的外壳 | 45 | 26 | 19 | 0 |
| L | 血肉、异变和遗物 | 39 | 19 | 18 | 2 |
| M | 通用污渍和痕迹贴花 | 41 | 16 | 16 | 9 |
| N | 招牌和印刷品（全部原创） | 27 | 6 | 16 | 5 |
| O | 天空、天气、光和特效 | 28 | 16 | 9 | 3 |
| P | 第一人称：身体、装备和武器 | 47 | 23 | 23 | 1 |
| Q | 界面、菜单和商店页 | 12 | 6 | 5 | 1 |
| R | 技术贴图 | 9 | 5 | 3 | 1 |
| | **合计** | **785** | **260** | **375** | **150** |

## A　街道和路面

主街是微弯的单车道老国道，雨夜，路面常湿。湿润程度用材质参数控制，不为干湿各做一套。

| 编号 | 名称 | 说明 | 类型 | 级 | 来源 | 素材 |
| --- | --- | --- | --- | --- | --- | --- |
| A01 | 旧柏油路面 | 主街基底，颗粒粗、发灰 | 平铺 | P0 | 拍 | [Asphalt015](https://ambientcg.com/view?id=Asphalt015) |
| A02 | 新补的柏油块 | 颜色更黑的方形补丁 | 平铺 | P1 | 拍 | [Asphalt023S](https://ambientcg.com/view?id=Asphalt023S)（凑合） |
| A03 | 重度龟裂柏油 | 路肩和停车场 | 平铺 | P1 | 拍 | [Asphalt013](https://ambientcg.com/view?id=Asphalt013)（凑合） |
| A04 | 沥青封缝条 | 蛇形的黑色灌缝 | 贴花 | P0 | 拍 | 程序生成 |
| A05 | 柏油裂缝 | 6 种走向 | 贴花 | P0 | 拍 | [AsphaltDamageSet001](https://ambientcg.com/view?id=AsphaltDamageSet001) |
| A06 | 坑洼 | 带积水和碎石的破洞，3 种 | 贴花 | P1 | 拍 | [AsphaltDamageSet001](https://ambientcg.com/view?id=AsphaltDamageSet001)（凑合） |
| A07 | 黄色双实线 | 路中线，磨损断续 | 贴花 | P0 | 画 | 有初稿 #1 |
| A08 | 白色边线 | 路缘一侧 | 贴花 | P0 | 画 | 有初稿 #1 |
| A09 | 停止线和地面 STOP 字 | 路口 | 贴花 | P1 | 画 | 有初稿 #19 |
| A10 | 人行横道线 | 加油站对面 | 贴花 | P1 | 画 | 有初稿 #19 |
| A11 | 地面箭头和限速数字 | 镇口 | 贴花 | P2 | 画 | 有初稿 #28 |
| A12 | 停车位线和残障车位标记 | 加油站、酒馆门口 | 贴花 | P1 | 画 | 有初稿 #19 |
| A13 | 路面反光钉 | 中线上的凸起反光片 | 物件 | P2 | 库 | 待找 |
| A14 | 减速带 | 加油站入口，黄黑漆 | 物件 | P2 | 画 | 有初稿 #28 |
| A15 | 人行道混凝土板 | 旧，带伸缩缝 | 平铺 | P0 | 拍 | [Concrete040](https://ambientcg.com/view?id=Concrete040) |
| A16 | 破碎翘起的人行道板 | 树根顶起的那几块 | 平铺 | P1 | 拍 | [Concrete042C](https://ambientcg.com/view?id=Concrete042C)（凑合） |
| A17 | 人行道扫帚纹混凝土 | 新修补段 | 平铺 | P2 | 拍 | [brushed_concrete](https://polyhaven.com/a/brushed_concrete) |
| A18 | 路缘石 | 混凝土，掉角 | 饰条 | P0 | 拍 | [gravel_concrete_03](https://polyhaven.com/a/gravel_concrete_03)（凑合） |
| A19 | 路缘石黄漆 | 禁停段 | 饰条 | P1 | 拍 | [gravel_concrete_03](https://polyhaven.com/a/gravel_concrete_03)（凑合） |
| A20 | 路缘石红漆 | 消防栓旁 | 饰条 | P1 | 拍 | [gravel_concrete_03](https://polyhaven.com/a/gravel_concrete_03)（凑合） |
| A21 | 残障坡道防滑砖 | 路口凸点砖 | 平铺 | P2 | 库 | [TactilePaving003](https://ambientcg.com/view?id=TactilePaving003) |
| A22 | 雨水箅子 | 铸铁格栅 | 物件 | P0 | 拍 | [Grate002](https://ambientcg.com/view?id=Grate002) |
| A23 | 圆形井盖 | 原创市政字样 | 物件 | P0 | 画 | 有初稿 #1 |
| A24 | 方形检修盖 | 电信和燃气 | 物件 | P1 | 画 | 有初稿 #19 |
| A25 | 碎石路肩 | 街道边缘 | 平铺 | P1 | 拍 | [Gravel029](https://ambientcg.com/view?id=Gravel029) |
| A26 | 砂石停车场 | 酒馆后面 | 平铺 | P1 | 拍 | [Gravel029](https://ambientcg.com/view?id=Gravel029)（凑合） |
| A27 | 泥地 | 房屋之间 | 平铺 | P1 | 拍 | [Ground082L](https://ambientcg.com/view?id=Ground082L) |
| A28 | 泥地车辙 | 后巷 | 贴花 | P1 | 拍 | [muddy_tracks](https://polyhaven.com/a/muddy_tracks)（凑合） |
| A29 | 枯草地 | 民宅院子 | 平铺 | P1 | 拍 | [grass_path_3](https://polyhaven.com/a/grass_path_3)（凑合） |
| A30 | 杂草丛 | 墙根和裂缝里，透明卡片 | 卡片 | P1 | 拍 | [Foliage002](https://ambientcg.com/view?id=Foliage002) |
| A31 | 蒲公英和阔叶野草 | 人行道缝 | 卡片 | P2 | 拍 | [dandelion_01](https://polyhaven.com/a/dandelion_01) |
| A32 | 苔藓斑 | 背阴的路缘和墙根 | 贴花 | P1 | 拍 | [Ground037](https://ambientcg.com/view?id=Ground037)（凑合） |
| A33 | 湿落叶堆 | 排水沟、墙角 | 贴花 | P1 | 拍 | [ScatteredLeaves001](https://ambientcg.com/view?id=ScatteredLeaves001)（凑合） |
| A34 | 松针和小树枝 | 路肩 | 贴花 | P2 | 拍 | [PineNeedles001](https://ambientcg.com/view?id=PineNeedles001) |
| A35 | 积水遮罩 | 决定水洼位置和形状 | 遮罩 | P0 | 程 | 已做 |
| A36 | 水洼边缘泥渍 | 水退后的泥圈 | 贴花 | P1 | 拍 | 程序生成 |
| A37 | 路缘水流 | 沿路缘流向雨水口 | 着色器 | P1 | 程 | 已做 |
| A38 | 油渍彩虹斑 | 加油站、汽修店门口 | 贴花 | P1 | 拍 | 程序生成 |
| A39 | 机油滴漏斑 | 停车位中间的黑点群 | 贴花 | P0 | 拍 | 程序生成 |
| A40 | 刹车痕 | 翻车现场前 | 贴花 | P1 | 拍 | 程序生成 |
| A41 | 金属刮地痕 | 家电在柏油上拖出的白痕 | 贴花 | P0 | 拍 | [Scratches004](https://ambientcg.com/view?id=Scratches004)（凑合） |
| A42 | 碎玻璃渣 | 地面散落，带反光 | 贴花 | P0 | 拍 | 程序生成 |
| A43 | 散落砂砾 | 路面边缘 | 贴花 | P2 | 拍 | 程序生成 |
| A44 | 波形梁护栏 | 镇口弯道 | 物件 | P1 | 库 | [Metal059C](https://ambientcg.com/view?id=Metal059C)（凑合） |
| A45 | 混凝土隔离墩 | 北端封路 | 物件 | P1 | 拍 | [concrete_road_barrier](https://polyhaven.com/a/concrete_road_barrier) |
| A46 | 橡胶路锥 | 橙色带反光圈 | 物件 | P1 | 库 | 待找 |
| A47 | 条纹路障板 | 橙白条，带闪灯 | 物件 | P1 | 画 | 有初稿 #19 |
| A48 | 半挂车厢板 | 南端翻车，铝波纹板 | 物件 | P1 | 库 | [container_side](https://polyhaven.com/a/container_side)（凑合） |
| A49 | 半挂车头漆面 | 原创车队涂装 | 物件 | P1 | 画 | 有初稿 #19 |
| A50 | 原木树皮 | 散落的运木 | 平铺 | P1 | 拍 | [Bark015](https://ambientcg.com/view?id=Bark015) |
| A51 | 原木年轮截面 | 木头两端 | 物件 | P1 | 拍 | [TreeEnd002](https://ambientcg.com/view?id=TreeEnd002) |
| A52 | 捆扎链条和绑带 | 断开的 | 物件 | P2 | 库 | [Rope002](https://ambientcg.com/view?id=Rope002)（凑合） |
| A53 | 卡车轮胎和挡泥板 | 翻车现场 | 物件 | P2 | 库 | [old_tyre](https://polyhaven.com/a/old_tyre)（凑合） |
| A54 | 柴油渍和木屑 | 翻车现场地面 | 贴花 | P2 | 拍 | [wood_chips](https://polyhaven.com/a/wood_chips)（凑合） |

## B　市政设施和街道家具

很多东西同时是怪物的原型或藏身处，标了“怪”的要和 K 组的怪物版本严格对得上。

| 编号 | 名称 | 说明 | 类型 | 级 | 来源 | 素材 |
| --- | --- | --- | --- | --- | --- | --- |
| B01 | 木电线杆 | 焦油浸渍，开裂 | 物件 | P0 | 拍 | [modular_electricity_poles](https://polyhaven.com/a/modular_electricity_poles) |
| B02 | 电线杆编号牌 | 金属小牌 | 物件 | P2 | 画 | 有初稿 #28 |
| B03 | 电线杆上的订书钉和传单残片 | 一圈锈钉和纸角 | 贴花 | P1 | 拍 | 待找 |
| B04 | 变压器桶 | 灰漆，漏油痕 | 物件 | P1 | 库 | 待找 |
| B05 | 瓷绝缘子和横担 | 杆顶 | 物件 | P1 | 库 | [modular_electricity_poles](https://polyhaven.com/a/modular_electricity_poles)（凑合） |
| B06 | 架空电线和电话线 | 黑橡胶，下垂 | 物件 | P0 | 程 | 已做 |
| B07 | 路灯杆 | 镀锌钢，怪 | 物件 | P0 | 拍 | [street_lamp_01](https://polyhaven.com/a/street_lamp_01)（凑合） |
| B08 | 路灯灯头 | 钠灯和水银灯两种灯罩 | 发光 | P0 | 库 | [street_lamp_02](https://polyhaven.com/a/street_lamp_02)（凑合） |
| B09 | 路灯检修门 | 杆根小盖板 | 物件 | P2 | 库 | [rusty_metal_04](https://polyhaven.com/a/rusty_metal_04)（凑合） |
| B10 | 悬挂的黄色闪灯 | 路口上空 | 发光 | P1 | 库 | 待找 |
| B11 | 停车让行牌 | 八角红牌，弹孔 | 物件 | P1 | 画 | 有初稿 #19 |
| B12 | 限速牌 | 白底黑字 | 物件 | P1 | 画 | 有初稿 #19 |
| B13 | 街名牌 | 绿底白字，虚构街名 | 物件 | P1 | 画 | 有初稿 #19 |
| B14 | 禁止停车牌 | 路缘 | 物件 | P2 | 画 | 有初稿 #28 |
| B15 | 蓝色邮筒 | 虚构邮政标志 | 物件 | P1 | 画 | 有初稿 #19 |
| B16 | 住宅信箱 | 铝制和塑料两种，带姓氏 | 物件 | P1 | 画 | 有初稿 #19 |
| B17 | 投币报纸箱 | 里面是最后一天的报纸 | 物件 | P1 | 画 | 有初稿 #19 |
| B18 | 公用电话亭 | 铝框、玻璃、话机，生态演出用 | 物件 | P0 | 画 | 有初稿 #2 |
| B19 | 电话簿 | 挂在电话亭里 | 物件 | P2 | 画 | 有初稿 #28 |
| B20 | 木条长椅 | 铸铁腿 | 物件 | P1 | 库 | [PaintedWood008C](https://ambientcg.com/view?id=PaintedWood008C)（凑合） |
| B21 | 街边垃圾桶 | 金属网和水泥两种，怪 | 物件 | P0 | 拍 | [metal_trash_can](https://polyhaven.com/a/metal_trash_can) |
| B22 | 消防栓 | 红漆和黄漆，怪 | 物件 | P0 | 拍 | [fire_hydrant](https://polyhaven.com/a/fire_hydrant) |
| B23 | 自行车架 | 弯管 | 物件 | P2 | 库 | [rusty_metal_04](https://polyhaven.com/a/rusty_metal_04)（凑合） |
| B24 | 老式停车计时器 | 酒馆门口 | 物件 | P2 | 画 | 有初稿 #28 |
| B25 | 旗杆和湿旗 | 加油站 | 物件 | P2 | 画 | 有初稿 #28 |
| B26 | 枯死的花箱 | 店门口 | 物件 | P2 | 拍 | [planter_box_01](https://polyhaven.com/a/planter_box_01) |
| B27 | 链环铁丝网 | 后巷，透明 | 平铺 | P0 | 库 | [modular_chainlink_fence](https://polyhaven.com/a/modular_chainlink_fence) |
| B28 | 带刺铁丝顶 | 汽修厂后院 | 物件 | P2 | 库 | [rusty_metal_04](https://polyhaven.com/a/rusty_metal_04)（凑合） |
| B29 | 尖头木栅栏 | 民宅前院，漆剥落 | 物件 | P1 | 拍 | [PaintedWood008C](https://ambientcg.com/view?id=PaintedWood008C)（凑合） |
| B30 | 隐私木板栅栏 | 后院，发灰 | 平铺 | P1 | 拍 | [PaintedWood008C](https://ambientcg.com/view?id=PaintedWood008C)（凑合） |
| B31 | 砖砌矮墙和压顶 | 店铺之间 | 平铺 | P2 | 拍 | [Bricks100](https://ambientcg.com/view?id=Bricks100)（凑合） |
| B32 | 购物手推车 | 翻倒在路边 | 物件 | P1 | 库 | 待找 |
| B33 | 婴儿车 | 布面和镀铬架，生态演出用 | 物件 | P0 | 画 | 有初稿 #2 |
| B34 | 生锈自行车 | 靠在墙边 | 物件 | P2 | 库 | [rusty_metal_04](https://polyhaven.com/a/rusty_metal_04)（凑合） |
| B35 | 弃置的沙发和床垫 | 后巷 | 物件 | P2 | 拍 | [sofa_03](https://polyhaven.com/a/sofa_03)（凑合） |
| B36 | 湿烂纸箱 | 后巷堆叠 | 物件 | P1 | 拍 | [cardboard_box_01](https://polyhaven.com/a/cardboard_box_01) |
| B37 | 黑色垃圾袋 | 鼓胀、破口 | 物件 | P1 | 拍 | [trashbag](https://polyhaven.com/a/trashbag) |
| B38 | 木托盘 | 堆放 | 物件 | P1 | 拍 | [PaintedWood008C](https://ambientcg.com/view?id=PaintedWood008C)（凑合） |
| B39 | 塑料牛奶箱 | 后门垫脚 | 物件 | P1 | 库 | [plastic_crate_02](https://polyhaven.com/a/plastic_crate_02) |
| B40 | 民用车漆面 | 街边停放的车，4 种颜色 | 物件 | P1 | 库 | [PaintedMetal002](https://ambientcg.com/view?id=PaintedMetal002)（凑合） |
| B41 | 民用车玻璃和内饰剪影 | 车窗里看到的 | 物件 | P1 | 画 | 有初稿 #19 |
| B42 | 民用车轮胎和轮毂 | 通用 | 物件 | P1 | 库 | [rusted_wheel_rim_02](https://polyhaven.com/a/rusted_wheel_rim_02)（凑合） |
| B43 | 民用车灯 | 警报闪烁用 | 发光 | P1 | 库 | 待找 |
| B44 | 车牌 | 虚构的州，10 个随机号 | 图集 | P1 | 画 | 有初稿 #19 |
| B45 | 保险杠贴纸 | 原创，8 种 | 图集 | P2 | 画 | 有初稿 #28 |

## C　建筑外立面

70 年代的红砖和木结构底子。铁锈带特有的仿砖油毡、石棉瓦墙板值得做，一眼就有地域感。

| 编号 | 名称 | 说明 | 类型 | 级 | 来源 | 素材 |
| --- | --- | --- | --- | --- | --- | --- |
| C01 | 红砖墙 | 顺砌，基础款 | 平铺 | P0 | 拍 | [red_brick](https://polyhaven.com/a/red_brick) |
| C02 | 风化泛碱的红砖 | 下半墙 | 平铺 | P0 | 拍 | [brick_wall_001](https://polyhaven.com/a/brick_wall_001) |
| C03 | 煤烟熏黑的红砖 | 后巷和烟囱旁 | 平铺 | P1 | 拍 | [dark_brick_wall](https://polyhaven.com/a/dark_brick_wall) |
| C04 | 刷白漆后剥落的砖 | 联排房侧墙 | 平铺 | P1 | 拍 | [PaintedBricks005](https://ambientcg.com/view?id=PaintedBricks005) |
| C05 | 褪色的手绘墙体广告 | 侧墙上的老广告，原创 | 贴花 | P1 | 画 | 有初稿 #20 |
| C06 | 砖拱窗楣和石窗台 | 窗洞上下 | 饰条 | P1 | 拍 | [factory_brick](https://polyhaven.com/a/factory_brick)（凑合） |
| C07 | 砖檐口线脚 | 屋顶边缘 | 饰条 | P1 | 拍 | [factory_brick](https://polyhaven.com/a/factory_brick)（凑合） |
| C08 | 玻璃砖 | 酒馆的小高窗 | 平铺 | P1 | 库 | 待找 |
| C09 | 混凝土砌块墙 | 加油站后墙、车间 | 平铺 | P0 | 拍 | [concrete_block_wall_02](https://polyhaven.com/a/concrete_block_wall_02) |
| C10 | 刷漆的砌块墙 | 半截绿漆 | 平铺 | P1 | 拍 | [concrete_block_wall_02](https://polyhaven.com/a/concrete_block_wall_02)（凑合） |
| C11 | 现浇混凝土 | 带模板纹，基础和挡墙 | 平铺 | P1 | 拍 | [wood_textured_concrete](https://polyhaven.com/a/wood_textured_concrete) |
| C12 | 基础水线污渍 | 墙根一圈深色 | 贴花 | P0 | 拍 | [Leaking005](https://ambientcg.com/view?id=Leaking005) |
| C13 | 开裂的灰泥墙 | 个别店铺 | 平铺 | P2 | 拍 | [worn_cracked_plaster](https://polyhaven.com/a/worn_cracked_plaster) |
| C14 | 白漆剥落的木护墙板 | 民宅基础款 | 平铺 | P0 | 拍 | [WoodSiding009](https://ambientcg.com/view?id=WoodSiding009) |
| C15 | 褪色的彩色木护墙板 | 用染色做蓝、黄、绿变体 | 平铺 | P1 | 拍 | [WoodSiding006](https://ambientcg.com/view?id=WoodSiding006) |
| C16 | 木瓦墙板 | 山墙尖 | 平铺 | P2 | 拍 | [WoodSiding012](https://ambientcg.com/view?id=WoodSiding012)（凑合） |
| C17 | 乙烯基壁板 | 后来翻新的房子 | 平铺 | P1 | 库 | [exterior_wall_cladding](https://polyhaven.com/a/exterior_wall_cladding)（凑合） |
| C18 | 石棉水泥墙板 | 铁锈带特征，波浪下缘 | 平铺 | P1 | 拍 | [asbestos_sheet](https://polyhaven.com/a/asbestos_sheet) |
| C19 | 仿砖油毡墙面 | 铁锈带特征，接缝翘起 | 平铺 | P1 | 画 | 有初稿 #20 |
| C20 | 受潮分层的胶合板 | 封窗板 | 平铺 | P0 | 拍 | [plywood](https://polyhaven.com/a/plywood) |
| C21 | 定向刨花板 | 临时封堵 | 平铺 | P2 | 库 | [oriented_strand_board](https://polyhaven.com/a/oriented_strand_board) |
| C22 | 镀锌波纹铁皮 | 新旧两种 | 平铺 | P1 | 拍 | [corrugated_iron](https://polyhaven.com/a/corrugated_iron) |
| C23 | 粉化的压型钢板 | 车间外墙 | 平铺 | P1 | 拍 | [box_profile_metal_sheet](https://polyhaven.com/a/box_profile_metal_sheet) |
| C24 | 金属包边和泛水 | 屋檐和墙顶 | 饰条 | P1 | 库 | [PaintedMetal013](https://ambientcg.com/view?id=PaintedMetal013)（凑合） |
| C25 | 檐沟和落水管 | 铝制，凹痕 | 饰条 | P0 | 库 | [Metal059C](https://ambientcg.com/view?id=Metal059C)（凑合） |
| C26 | 腐朽的木封檐板 | 屋檐 | 饰条 | P1 | 拍 | [PaintedWood009B](https://ambientcg.com/view?id=PaintedWood009B)（凑合） |
| C27 | 屋檐底板 | 带通风孔 | 平铺 | P2 | 库 | [distressed_painted_planks](https://polyhaven.com/a/distressed_painted_planks)（凑合） |
| C28 | 沥青瓦 | 三垂片，灰和褐 | 平铺 | P0 | 库 | [grey_roof_01](https://polyhaven.com/a/grey_roof_01) |
| C29 | 缺片长苔的沥青瓦 | 局部变体 | 平铺 | P1 | 拍 | [RoofingTiles013C](https://ambientcg.com/view?id=RoofingTiles013C) |
| C30 | 平屋顶沥青卷材 | 带砾石，店铺屋顶 | 平铺 | P1 | 库 | [bitumen](https://polyhaven.com/a/bitumen) |
| C31 | 屋顶卷材接缝和补丁 | 银色修补漆 | 贴花 | P2 | 拍 | 程序生成 |
| C32 | 砖烟囱和烟囱帽 | 民宅 | 物件 | P1 | 拍 | 待找 |
| C33 | 屋顶通风帽和排气管 | 通用 | 物件 | P2 | 库 | [modular_airduct_circular_01](https://polyhaven.com/a/modular_airduct_circular_01)（凑合） |
| C34 | 电视天线和卫星锅 | 屋顶，90 年代小锅 | 物件 | P1 | 库 | 待找 |
| C35 | 窗式空调外壳 | 伸出窗外，怪 | 物件 | P1 | 拍 | [exterior_aircon_unit](https://polyhaven.com/a/exterior_aircon_unit)（凑合） |
| C36 | 屋顶空调机组 | 店铺屋顶 | 物件 | P2 | 库 | [exterior_aircon_unit](https://polyhaven.com/a/exterior_aircon_unit)（凑合） |
| C37 | 外墙电表 | 玻璃罩转盘 | 物件 | P1 | 拍 | [power_box_01](https://polyhaven.com/a/power_box_01)（凑合） |
| C38 | 外墙断路器箱 | 可拉闸，有玩法功能 | 物件 | P0 | 拍 | [utility_box_01](https://polyhaven.com/a/utility_box_01) |
| C39 | 煤气表和电话接线盒 | 后墙 | 物件 | P2 | 拍 | [utility_box_02](https://polyhaven.com/a/utility_box_02)（凑合） |
| C40 | 外墙灯 | 笼形和鹅颈两种 | 发光 | P1 | 库 | [industrial_wall_lamp](https://polyhaven.com/a/industrial_wall_lamp) |
| C41 | 老式监控摄像头 | 加油站 | 物件 | P2 | 库 | [security_camera_02](https://polyhaven.com/a/security_camera_02) |
| C42 | 门铃和投信口 | 民宅 | 物件 | P2 | 库 | 待找 |
| C43 | 门廊木地板 | 灰漆磨损 | 平铺 | P1 | 拍 | [wood_floor_deck](https://polyhaven.com/a/wood_floor_deck) |
| C44 | 门廊立柱和栏杆 | 车木件，漆剥落 | 饰条 | P1 | 拍 | [PaintedWood009C](https://ambientcg.com/view?id=PaintedWood009C)（凑合） |
| C45 | 门廊台阶 | 混凝土和木两种 | 物件 | P1 | 拍 | [stair_planks](https://polyhaven.com/a/stair_planks)（凑合） |
| C46 | 地下室窗井 | 墙根的小窗 | 物件 | P2 | 拍 | 待找 |
| C47 | 百叶护窗 | 窗两侧的装饰板 | 物件 | P2 | 库 | [wood_shutter](https://polyhaven.com/a/wood_shutter) |
| C48 | 条纹帆布遮雨棚 | 五金店、酒馆 | 平铺 | P1 | 画 | 有初稿 #20 |
| C49 | 波形铝遮雨棚 | 民宅门上 | 物件 | P2 | 库 | [CorrugatedSteel005](https://ambientcg.com/view?id=CorrugatedSteel005)（凑合） |
| C50 | 店面铝合金框 | 橱窗和门 | 饰条 | P0 | 库 | [Metal059C](https://ambientcg.com/view?id=Metal059C)（凑合） |
| C51 | 店面橱窗底墙瓷砖 | 老店面特征 | 平铺 | P1 | 拍 | [square_tiled_wall](https://polyhaven.com/a/square_tiled_wall)（凑合） |
| C52 | 橱窗玻璃上的手写促销字 | 原创 | 贴花 | P1 | 画 | 有初稿 #20 |
| C53 | 营业和打烊翻牌 | 挂在门上 | 物件 | P1 | 画 | 有初稿 #20 |
| C54 | 分节式车库门 | 白漆、凹痕，克拉克家 | 物件 | P0 | 拍 | [PaintedMetal012](https://ambientcg.com/view?id=PaintedMetal012) |
| C55 | 工业卷帘门 | 钢条，汽修车间 | 平铺 | P1 | 库 | [rusty_metal_shutter](https://polyhaven.com/a/rusty_metal_shutter) |
| C56 | 钢板后门 | 踢痕、锈 | 物件 | P0 | 拍 | [PaintedMetal007](https://ambientcg.com/view?id=PaintedMetal007)（凑合） |
| C57 | 防盗铁栅门 | 酒馆后门 | 物件 | P2 | 库 | [large_iron_gate](https://polyhaven.com/a/large_iron_gate)（凑合） |
| C58 | 纱门 | 铝框，纱网撕裂 | 物件 | P1 | 库 | 待找 |
| C59 | 实木镶板前门 | 民宅 | 物件 | P0 | 拍 | [rough_pine_door](https://polyhaven.com/a/rough_pine_door) |
| C60 | 门把手、锁具和合页 | 通用五金 | 图集 | P0 | 库 | [gate_latch_01](https://polyhaven.com/a/gate_latch_01)（凑合） |
| C61 | 木双悬窗 | 漆剥落，窗框 | 饰条 | P0 | 拍 | [PaintedWood008C](https://ambientcg.com/view?id=PaintedWood008C)（凑合） |
| C62 | 铝推拉窗 | 窗框 | 饰条 | P1 | 库 | 待找 |
| C63 | 脏玻璃 | 雨痕、手印、胶带残留 | 平铺 | P0 | 拍 | [Fingerprints007](https://ambientcg.com/view?id=Fingerprints007)（凑合） |
| C64 | 结露起雾的玻璃 | 室内有热源的窗 | 遮罩 | P1 | 程 | 已做 |
| C65 | 玻璃裂纹 | 4 种 | 遮罩 | P0 | 画 | 有初稿 #34 |
| C66 | 破碎玻璃残片边缘 | 砸窗后留在框里的 | 遮罩 | P0 | 画 | 有初稿 #34 |
| C67 | 从外面看的窗帘 | 蕾丝和发黄厚帘 | 平铺 | P1 | 拍 | [velour_velvet](https://polyhaven.com/a/velour_velvet)（凑合） |
| C68 | 弯折的百叶窗 | 从外面看 | 平铺 | P1 | 库 | 待找 |
| C69 | 钉窗木板 | 加固用，也是场景装饰 | 物件 | P1 | 拍 | [worn_planks](https://polyhaven.com/a/worn_planks) |
| C70 | 窗内假室内 | 不可进入的房子用，6 种 | 全景 | P1 | 画 | 有初稿 #20 |
| C71 | 窗内电视雪花光 | 电网惊扰的可见指示 | 发光 | P0 | 程 | 已做 |
| C72 | 空置店面的招租牌 | 背景店铺 | 物件 | P2 | 画 | 有初稿 #28 |

## D　室内通用

80 年代的发霉墙纸和 90 年代的地板革是主调。多层旧装修叠在一起（墙纸底下还有墙纸）是最有说服力的细节。

| 编号 | 名称 | 说明 | 类型 | 级 | 来源 | 素材 |
| --- | --- | --- | --- | --- | --- | --- |
| D01 | 大花碎花墙纸 | 客厅 | 平铺 | P0 | 画 | 有初稿 #3 |
| D02 | 小碎花墙纸 | 卧室 | 平铺 | P1 | 画 | 有初稿 #21 |
| D03 | 条纹墙纸 | 走廊 | 平铺 | P1 | 画 | 有初稿 #21 |
| D04 | 厨房水果图案墙纸 | 厨房 | 平铺 | P1 | 画 | 有初稿 #21 |
| D05 | 儿童房图案墙纸 | 原创图案 | 平铺 | P1 | 画 | 有初稿 #21 |
| D06 | 浴室墙纸 | 受潮起泡 | 平铺 | P2 | 画 | 有初稿 #28 |
| D07 | 墙纸翘边和撕裂 | 露出底下另一层旧墙纸 | 贴花 | P0 | 拍 | [decrepit_wallpaper](https://polyhaven.com/a/decrepit_wallpaper)（凑合） |
| D08 | 深色胡桃木饰板 | 酒馆、客厅 | 平铺 | P1 | 拍 | [WoodFloor051](https://ambientcg.com/view?id=WoodFloor051)（凑合） |
| D09 | 带节疤的松木饰板 | 阁楼、地下室风格 | 平铺 | P2 | 拍 | [Wood095](https://ambientcg.com/view?id=Wood095)（凑合） |
| D10 | 乳胶漆石膏板 | 用染色做米黄、薄荷绿、烟熏黄 | 平铺 | P0 | 拍 | [blue_plaster_wall](https://polyhaven.com/a/blue_plaster_wall)（凑合） |
| D11 | 破洞露出木板条的灰泥墙 | 老房子 | 贴花 | P1 | 拍 | [damaged_plaster](https://polyhaven.com/a/damaged_plaster)（凑合） |
| D12 | 墙洞露出保温棉和电线 | 被家电撞穿的墙 | 贴花 | P1 | 拍 | 程序生成 |
| D13 | 矿棉吸音天花板 | 便利店、洗衣房 | 平铺 | P0 | 拍 | [OfficeCeiling001](https://ambientcg.com/view?id=OfficeCeiling001) |
| D14 | 天花龙骨 | 吸音板之间的金属条 | 饰条 | P1 | 库 | [OfficeCeiling005](https://ambientcg.com/view?id=OfficeCeiling005)（凑合） |
| D15 | 颗粒喷涂天花 | 民宅 | 平铺 | P1 | 库 | [Wallpaper002C](https://ambientcg.com/view?id=Wallpaper002C)（凑合） |
| D16 | 压花锡天花板 | 酒馆、五金店 | 平铺 | P1 | 库 | 待找 |
| D17 | 天花水渍圈 | 6 种 | 贴花 | P0 | 拍 | [Leaking015C](https://ambientcg.com/view?id=Leaking015C) |
| D18 | 塌陷露出龙骨的天花 | 局部 | 贴花 | P2 | 拍 | 程序生成 |
| D19 | 木梁 | 车间、阁楼 | 平铺 | P1 | 拍 | [dark_wood](https://polyhaven.com/a/dark_wood)（凑合） |
| D20 | 黑白棋盘地板革 | 便利店、厨房 | 平铺 | P0 | 画 | 有初稿 #4 |
| D21 | 仿红砖地板革 | 厨房 | 平铺 | P1 | 画 | 有初稿 #21 |
| D22 | 几何图案黄褐地板革 | 70 年代遗留 | 平铺 | P1 | 画 | 有初稿 #21 |
| D23 | 地板革翘边和破口 | 露出胶痕和垫底的旧报纸 | 贴花 | P1 | 拍 | [old_linoleum_flooring_01](https://polyhaven.com/a/old_linoleum_flooring_01)（凑合） |
| D24 | 商用方块塑胶地板 | 米色斑点，走道磨损 | 平铺 | P0 | 拍 | [Terrazzo001](https://ambientcg.com/view?id=Terrazzo001)（凑合） |
| D25 | 长绒地毯 | 橙和绿 | 平铺 | P1 | 库 | [dirty_carpet](https://polyhaven.com/a/dirty_carpet) |
| D26 | 圈绒商用地毯 | 灰蓝，账房 | 平铺 | P1 | 库 | [Carpet001](https://ambientcg.com/view?id=Carpet001)（凑合） |
| D27 | 花纹旧地毯 | 酒馆卡座区 | 平铺 | P2 | 画 | 有初稿 #28 |
| D28 | 地毯污渍和烟洞 | 6 种 | 贴花 | P1 | 拍 | 程序生成 |
| D29 | 亮漆实木条地板 | 走道磨白 | 平铺 | P1 | 拍 | [WoodFloor051](https://ambientcg.com/view?id=WoodFloor051) |
| D30 | 未处理的宽木地板 | 阁楼、五金店 | 平铺 | P1 | 拍 | [WoodFloor051](https://ambientcg.com/view?id=WoodFloor051)（凑合） |
| D31 | 胶合板底板 | 掀开地毯的地方 | 平铺 | P2 | 库 | [plywood](https://polyhaven.com/a/plywood) |
| D32 | 六角小白地砖 | 卫生间、酒馆入口 | 平铺 | P1 | 库 | [Tiles118](https://ambientcg.com/view?id=Tiles118) |
| D33 | 四寸方墙砖 | 用染色做白、粉、薄荷绿 | 平铺 | P0 | 库 | [Tiles109](https://ambientcg.com/view?id=Tiles109) |
| D34 | 发黑的砖缝 | 叠加在墙砖上 | 贴花 | P0 | 拍 | [Tiles087](https://ambientcg.com/view?id=Tiles087)（凑合） |
| D35 | 油浸水泥地 | 车间、车库、后间 | 平铺 | P0 | 拍 | [Concrete033](https://ambientcg.com/view?id=Concrete033) |
| D36 | 木踢脚线和橡胶踢脚线 | 所有室内 | 饰条 | P0 | 库 | [WoodFloor047](https://ambientcg.com/view?id=WoodFloor047)（凑合） |
| D37 | 门框和门套 | 漆木 | 饰条 | P0 | 库 | [PaintedWood009C](https://ambientcg.com/view?id=PaintedWood009C)（凑合） |
| D38 | 仿木纹空心门 | 室内门，可被踢破 | 物件 | P0 | 库 | [oak_veneer_01](https://polyhaven.com/a/oak_veneer_01)（凑合） |
| D39 | 空心门破洞 | 露出蜂窝纸芯 | 贴花 | P1 | 画 | 有初稿 #21 |
| D40 | 室内镶板门 | 民宅 | 物件 | P1 | 库 | [rough_pine_door](https://polyhaven.com/a/rough_pine_door)（凑合） |
| D41 | 百叶折叠壁柜门 | 衣帽间 | 物件 | P1 | 库 | [wood_shutter](https://polyhaven.com/a/wood_shutter)（凑合） |
| D42 | 楼梯踏板和地毯条 | 克拉克家 | 物件 | P1 | 拍 | [stair_planks](https://polyhaven.com/a/stair_planks)（凑合） |
| D43 | 楼梯栏杆和扶手 | 车木件 | 饰条 | P1 | 库 | 待找 |
| D44 | 铸铁暖气片 | 老房子 | 物件 | P1 | 拍 | 待找 |
| D45 | 踢脚电暖器 | 翻新过的房间 | 物件 | P2 | 库 | 待找 |
| D46 | 发黄的插座和开关面板 | 通用，周围有焦痕版本 | 图集 | P0 | 拍 | [Plastic010](https://ambientcg.com/view?id=Plastic010)（凑合） |
| D47 | 圆形老式温控器 | 墙上 | 物件 | P2 | 库 | 待找 |
| D48 | 烟雾报警器 | 天花，电量低时会叫 | 物件 | P1 | 库 | 待找 |
| D49 | 吊扇 | 怪 | 物件 | P1 | 库 | [ceiling_fan](https://polyhaven.com/a/ceiling_fan) |
| D50 | 乳白玻璃吸顶灯 | 里面有死虫 | 发光 | P1 | 拍 | [modern_ceiling_lamp_01](https://polyhaven.com/a/modern_ceiling_lamp_01)（凑合） |
| D51 | 格栅日光灯盘 | 店铺 | 发光 | P0 | 库 | [mounted_fluorescent_lights](https://polyhaven.com/a/mounted_fluorescent_lights) |
| D52 | 裸灯泡和拉绳 | 储藏室、地沟 | 发光 | P1 | 库 | [pull_chain_light_socket](https://polyhaven.com/a/pull_chain_light_socket) |
| D53 | 布艺灯罩 | 落地灯和台灯 | 物件 | P1 | 库 | 待找 |
| D54 | 厚窗帘和蕾丝窗帘 | 室内侧 | 平铺 | P1 | 拍 | [velour_velvet](https://polyhaven.com/a/velour_velvet)（凑合） |
| D55 | 金属百叶帘 | 弯折、积灰 | 平铺 | P1 | 库 | 待找 |
| D56 | 发黄撕裂的卷帘 | 民宅 | 物件 | P2 | 拍 | 待找 |
| D57 | 通风口格栅 | 墙和地面 | 物件 | P1 | 库 | [modular_airduct_rectangular_01](https://polyhaven.com/a/modular_airduct_rectangular_01)（凑合） |
| D58 | 室内配电箱 | 员工室、车库 | 物件 | P1 | 拍 | [power_box_01](https://polyhaven.com/a/power_box_01) |
| D59 | 热水器 | 圆筒，怪 | 物件 | P2 | 库 | 待找 |
| D60 | 暖风炉 | 车库或杂物间，怪 | 物件 | P2 | 库 | 待找 |
| D61 | 管道 | 铜、铸铁、塑料，带保温层 | 饰条 | P1 | 库 | [modular_industrial_pipes_01](https://polyhaven.com/a/modular_industrial_pipes_01) |
| D62 | 蜘蛛网 | 墙角，透明 | 卡片 | P1 | 画 | 有初稿 #21 |
| D63 | 积灰遮罩 | 朝上的面自动积灰 | 遮罩 | P1 | 程 | 已做 |

## E　加油站和便利店（起点和安全屋）

玩家停留最久的地方，也是修车的地方，细节密度要最高。品牌全部原创。

| 编号 | 名称 | 说明 | 类型 | 级 | 来源 | 素材 |
| --- | --- | --- | --- | --- | --- | --- |
| E01 | 加油棚立柱 | 包铝，车刮的凹痕 | 物件 | P0 | 拍 | [Metal059C](https://ambientcg.com/view?id=Metal059C)（凑合） |
| E02 | 加油棚底板 | 金属条板和嵌入灯 | 平铺 | P0 | 库 | [CorrugatedSteel003](https://ambientcg.com/view?id=CorrugatedSteel003)（凑合） |
| E03 | 加油棚檐口灯箱 | 原创品牌色带，部分不亮 | 发光 | P0 | 画 | 有初稿 #5 |
| E04 | 加油岛基座和防撞柱 | 混凝土，黄漆 | 物件 | P0 | 拍 | [patterned_concrete_wall](https://polyhaven.com/a/patterned_concrete_wall)（凑合） |
| E05 | 加油站地坪 | 重油污混凝土 | 平铺 | P0 | 拍 | [Concrete033](https://ambientcg.com/view?id=Concrete033) |
| E06 | 加油机机身 | 褪色漆面，90 年代款 | 物件 | P0 | 画 | 有初稿 #5 |
| E07 | 加油机面板 | 滚轮数字、按钮、标号选择 | 物件 | P0 | 画 | 有初稿 #5 |
| E08 | 油枪、软管和护套 | 橡胶和彩色塑料 | 物件 | P1 | 库 | 待找 |
| E09 | 加油机顶灯箱 | 品牌标 | 发光 | P1 | 画 | 有初稿 #22 |
| E10 | 挤水桶和刮水器 | 加油岛上 | 物件 | P1 | 库 | 待找 |
| E11 | 擦手纸盒和岛上垃圾桶 | 加油岛上 | 物件 | P2 | 库 | [metal_trash_can](https://polyhaven.com/a/metal_trash_can)（凑合） |
| E12 | 投币充气机 | 棚外，怪 | 物件 | P2 | 画 | 有初稿 #29 |
| E13 | 地面油罐加注口盖 | 彩色铸铁盖 | 物件 | P2 | 画 | 有初稿 #29 |
| E14 | 油价立牌 | 翻页数字 | 发光 | P1 | 画 | 有初稿 #22 |
| E15 | 主立柱招牌 | 原创品牌，全街最亮的光源之一 | 发光 | P0 | 画 | 有初稿 #5 |
| E16 | 24 小时营业霓虹 | 窗内 | 发光 | P1 | 画 | 有初稿 #22 |
| E17 | 香烟和彩票广告灯箱 | 原创 | 发光 | P2 | 画 | 有初稿 #29 |
| E18 | 取款机 | 店内角落，怪 | 物件 | P2 | 画 | 有初稿 #29 |
| E19 | 冰袋柜 | 店外，怪 | 物件 | P0 | 画 | 有初稿 #6 |
| E20 | 丙烷罐笼 | 店外 | 物件 | P2 | 库 | [propane_tank](https://polyhaven.com/a/propane_tank)（凑合） |
| E21 | 自动售货机 | 店外，教学战的怪 | 物件 | P0 | 画 | 有初稿 #6 |
| E22 | 便利店铝门 | 推拉杆，门框上的身高刻度贴条 | 物件 | P0 | 画 | 有初稿 #6 |
| E23 | 门口脚垫 | 橡胶，湿脚印 | 物件 | P2 | 库 | [rubber_tiles](https://polyhaven.com/a/rubber_tiles)（凑合） |
| E24 | 金属货架 | 带洞洞背板 | 饰条 | P0 | 库 | [Shelf_01](https://polyhaven.com/a/Shelf_01) |
| E25 | 价签条和促销牌 | 货架前缘 | 图集 | P0 | 画 | 待画 |
| E26 | 零食包装 | 薯片、糖果棒、口香糖、牛肉干，12 种 | 图集 | P1 | 画 | 有初稿 #22 |
| E27 | 干货包装 | 罐头、杯面、面包、麦片，12 种 | 图集 | P1 | 画 | 有初稿 #22 |
| E28 | 饮料瓶罐 | 汽水、运动饮料、啤酒、瓶装水，10 种 | 图集 | P0 | 画 | 有初稿 #7 |
| E29 | 牛奶盒 | 侧面印着寻人启事，叙事 | 物件 | P1 | 画 | 有初稿 #22 |
| E30 | 香烟条盒和打火机架 | 收银台后 | 图集 | P1 | 画 | 有初稿 #22 |
| E31 | 汽车用品 | 机油、防冻液、玻璃水、香片 | 图集 | P1 | 画 | 有初稿 #22 |
| E32 | 药品和急救用品 | 止痛药、胶带等可拾取物 | 图集 | P0 | 画 | 有初稿 #7 |
| E33 | 杂志和报纸 | 原创封面和头版，叙事 | 图集 | P1 | 画 | 有初稿 #22 |
| E34 | 公路地图册和明信片 | 转架上 | 图集 | P2 | 画 | 有初稿 #29 |
| E35 | 电池和小五金吊卡 | 收银台旁 | 图集 | P1 | 画 | 有初稿 #22 |
| E36 | 立式玻璃门冷柜 | 白钢，玻璃结露，内灯 | 物件 | P0 | 拍 | [PaintedMetal012](https://ambientcg.com/view?id=PaintedMetal012) |
| E37 | 冷柜内层架和价签 | 透过玻璃可见 | 物件 | P1 | 库 | 待找 |
| E38 | 卧式冷柜 | 冰淇淋，怪 | 物件 | P1 | 库 | 待找 |
| E39 | 冷柜下的滴水水渍 | 地面 | 贴花 | P1 | 拍 | 程序生成 |
| E40 | 咖啡台 | 层压板和不锈钢 | 物件 | P1 | 库 | [CoffeeCart_01](https://polyhaven.com/a/CoffeeCart_01)（凑合） |
| E41 | 咖啡壶和加热板 | 还冒着热气 | 物件 | P0 | 库 | [vintage_electric_kettle](https://polyhaven.com/a/vintage_electric_kettle)（凑合） |
| E42 | 纸杯、杯盖、糖包 | 原创图案 | 图集 | P2 | 画 | 有初稿 #29 |
| E43 | 热狗滚轴机 | 还在转 | 物件 | P2 | 库 | 待找 |
| E44 | 微波炉 | 怪 | 物件 | P1 | 库 | [vintage_microwave](https://polyhaven.com/a/vintage_microwave) |
| E45 | 汽水机和冰沙机 | 冰沙机还在搅，怪 | 物件 | P2 | 画 | 有初稿 #29 |
| E46 | 收银台 | 层压板，缺角露出刨花板 | 物件 | P0 | 拍 | 待找 |
| E47 | 收银机和刷卡机 | 抽屉弹开 | 物件 | P1 | 库 | [CashRegister_01](https://polyhaven.com/a/CashRegister_01) |
| E48 | 彩票展示盒 | 原创 | 物件 | P2 | 画 | 有初稿 #29 |
| E49 | 头顶香烟架 | 收银台上方 | 物件 | P2 | 库 | [cigarette_pack](https://polyhaven.com/a/cigarette_pack)（凑合） |
| E50 | 监控显示器 | 显像管，雪花 | 发光 | P1 | 程 | 已做 |
| E51 | 防盗凸面镜 | 能看到身后的过道 | 物件 | P1 | 库 | 待找 |
| E52 | 员工储物柜 | 灰漆，贴着贴纸 | 物件 | P1 | 库 | 待找 |
| E53 | 折叠桌椅 | 员工室 | 物件 | P2 | 库 | [plastic_monobloc_chair_01](https://polyhaven.com/a/plastic_monobloc_chair_01)（凑合） |
| E54 | 排班表和劳工法海报 | 原创 | 图集 | P1 | 画 | 有初稿 #22 |
| E55 | 手绘宣传单地图 | 游戏里唯一的地图，关键道具 | 物件 | P0 | 画 | 有初稿 #8 |
| E56 | 钥匙挂板 | 后备箱钥匙在这里 | 物件 | P0 | 画 | 有初稿 #6 |
| E57 | 拖把桶和小心地滑牌 | 黄色 | 物件 | P1 | 库 | [WetFloorSign_01](https://polyhaven.com/a/WetFloorSign_01)（凑合） |
| E58 | 小保险箱 | 柜台下 | 物件 | P2 | 库 | 待找 |
| E59 | 卫生间洁具 | 马桶、小便池、洗手盆，污渍 | 物件 | P1 | 拍 | 待找 |
| E60 | 卫生间镜子 | 裂纹和起雾两种 | 物件 | P1 | 程 | 已做 |
| E61 | 卫生间隔板 | 刻字和涂鸦 | 平铺 | P2 | 画 | 有初稿 #29 |
| E62 | 干手机和纸巾盒 | 墙挂，怪 | 物件 | P2 | 库 | 待找 |
| E63 | 后巷垃圾站围墙 | 砌块和木门 | 平铺 | P1 | 拍 | [concrete_block_wall_02](https://polyhaven.com/a/concrete_block_wall_02)（凑合） |
| E64 | 大垃圾箱 | 绿漆，凹痕，污流 | 物件 | P0 | 拍 | [PaintedMetal013](https://ambientcg.com/view?id=PaintedMetal013) |
| E65 | 垃圾站油腻地面 | 混凝土 | 平铺 | P1 | 拍 | [dirty_concrete](https://polyhaven.com/a/dirty_concrete) |
| E66 | 压扁的纸箱堆 | 垃圾站 | 物件 | P1 | 拍 | [CardboardSet001](https://ambientcg.com/view?id=CardboardSet001) |
| E67 | 废油桶 | 垃圾站 | 物件 | P2 | 库 | [barrel_03](https://polyhaven.com/a/barrel_03) |

## F　警车

全游戏看得最久、贴得最近的一件东西：改装台、仓库、修车对象、安全光源。引擎舱和右前轮按每米 1024 像素做。

| 编号 | 名称 | 说明 | 类型 | 级 | 来源 | 素材 |
| --- | --- | --- | --- | --- | --- | --- |
| F01 | 车漆和涂装 | 原创县警配色 | 物件 | P0 | 画 | 有初稿 #9 |
| F02 | 车门徽章和编号 | 原创警徽 | 贴花 | P0 | 画 | 有初稿 #9 |
| F03 | 车身泥点和雨水流痕 | 叠加层 | 遮罩 | P1 | 程 | 已做 |
| F04 | 警灯灯条 | 红蓝灯罩和内部反光碗 | 发光 | P0 | 库 | 待找 |
| F05 | 推杠 | 车头黑色钢管 | 物件 | P1 | 库 | 待找 |
| F06 | A 柱探照灯 | 可转动 | 发光 | P1 | 库 | 待找 |
| F07 | 天线 | 车顶和后备箱盖 | 物件 | P2 | 库 | [Metal059C](https://ambientcg.com/view?id=Metal059C)（凑合） |
| F08 | 轮胎 | 胎纹和胎壁字样（原创） | 物件 | P0 | 画 | 有初稿 #9 |
| F09 | 被扎破的右前轮 | 瘪胎和撕裂口 | 物件 | P0 | 画 | 有初稿 #9 |
| F10 | 钢轮和轮毂盖 | 轮毂盖要做实时反光 | 物件 | P0 | 库 | [rusted_wheel_rim_02](https://polyhaven.com/a/rusted_wheel_rim_02) |
| F11 | 轮毂螺母和螺栓 | 换胎时逐颗操作 | 物件 | P0 | 库 | [rusted_wheel_rim_02](https://polyhaven.com/a/rusted_wheel_rim_02)（凑合） |
| F12 | 备胎 | 后备箱里 | 物件 | P0 | 库 | [old_tyre](https://polyhaven.com/a/old_tyre) |
| F13 | 刹车盘和卡钳 | 卸下轮子后可见，锈 | 物件 | P0 | 拍 | [rusty_metal_04](https://polyhaven.com/a/rusty_metal_04) |
| F14 | 轮拱内衬 | 泥和碎石 | 物件 | P0 | 拍 | [Plastic012B](https://ambientcg.com/view?id=Plastic012B)（凑合） |
| F15 | 底盘和悬挂 | 顶起后可见 | 物件 | P1 | 拍 | [rusty_metal_04](https://polyhaven.com/a/rusty_metal_04)（凑合） |
| F16 | 前格栅 | 镀铬和黑塑料 | 物件 | P1 | 库 | 待找 |
| F17 | 大灯 | 透镜和反光碗，接通电瓶后亮 | 发光 | P0 | 库 | 待找 |
| F18 | 尾灯和转向灯 | 红和琥珀色 | 发光 | P1 | 库 | 待找 |
| F19 | 镀铬保险杠 | 划痕 | 物件 | P1 | 库 | 待找 |
| F20 | 挡风玻璃 | 雨滴和雨刷扇形痕 | 遮罩 | P0 | 程 | 已做 |
| F21 | 车窗玻璃 | 侧窗，会被怪拍打 | 物件 | P1 | 库 | 待找 |
| F22 | 后视镜 | 实时反光 | 物件 | P1 | 程 | 已做 |
| F23 | 发动机本体 | 铸铁和铝，油泥 | 物件 | P0 | 拍 | [Metal053B](https://ambientcg.com/view?id=Metal053B)（凑合） |
| F24 | 气门室盖和空滤壳 | 引擎舱上层 | 物件 | P0 | 拍 | [Metal041B](https://ambientcg.com/view?id=Metal041B)（凑合） |
| F25 | 发电机 | 皮带的一端 | 物件 | P0 | 拍 | 待找 |
| F26 | 皮带轮组和张紧器 | 曲轴、发电机、张紧轮 | 物件 | P0 | 拍 | 待找 |
| F27 | 断裂的旧皮带 | 挂在轮上 | 物件 | P0 | 画 | 有初稿 #9 |
| F28 | 新皮带 | 可拾取物，带纸套 | 物件 | P0 | 画 | 有初稿 #9 |
| F29 | 散热器和风扇罩 | 引擎舱前部 | 物件 | P1 | 拍 | 待找 |
| F30 | 橡胶水管和卡箍 | 引擎舱 | 物件 | P1 | 拍 | [Rubber004](https://ambientcg.com/view?id=Rubber004)（凑合） |
| F31 | 电瓶托架 | 腐蚀的白色粉末 | 物件 | P0 | 拍 | [rusty_metal_05](https://polyhaven.com/a/rusty_metal_05) |
| F32 | 电瓶桩头夹 | 红和黑，接线时逐个操作 | 物件 | P0 | 库 | [Metal057C](https://ambientcg.com/view?id=Metal057C)（凑合） |
| F33 | 线束和保险丝盒 | 引擎舱侧面 | 物件 | P1 | 拍 | 待找 |
| F34 | 引擎盖内衬 | 下垂的隔音棉 | 物件 | P0 | 拍 | 待找 |
| F35 | 引擎盖支撑杆和机油尺 | 小件 | 物件 | P1 | 库 | 待找 |
| F36 | 防火墙 | 引擎舱后壁 | 物件 | P1 | 拍 | 待找 |
| F37 | 仪表盘 | 塑料裂纹 | 物件 | P0 | 拍 | [Plastic012B](https://ambientcg.com/view?id=Plastic012B)（凑合） |
| F38 | 仪表 | 指针和里程表 | 发光 | P0 | 画 | 有初稿 #9 |
| F39 | 中控缝隙 | 结尾渗出黑油的地方 | 物件 | P0 | 画 | 有初稿 #9 |
| F40 | 警用无线电 | 面板和指示灯，结尾的声源 | 发光 | P0 | 画 | 有初稿 #9 |
| F41 | 车载终端支架 | 90 年代的单色屏 | 发光 | P2 | 画 | 有初稿 #29 |
| F42 | 方向盘和转向柱 | 磨亮 | 物件 | P0 | 库 | [Leather028](https://ambientcg.com/view?id=Leather028)（凑合） |
| F43 | 点火开关和钥匙 | 打火时的特写 | 物件 | P0 | 库 | 待找 |
| F44 | 座椅 | 乙烯基，裂口用胶带补过 | 物件 | P1 | 拍 | [fabric_leather_01](https://polyhaven.com/a/fabric_leather_01)（凑合） |
| F45 | 前后排隔离网 | 钢网和亚克力 | 物件 | P1 | 库 | [SheetMetal002](https://ambientcg.com/view?id=SheetMetal002)（凑合） |
| F46 | 顶棚、遮阳板和地垫 | 地垫上有泥和咖啡杯 | 物件 | P1 | 库 | 待找 |
| F47 | 手套箱里的证件 | 原创登记证 | 物件 | P2 | 画 | 有初稿 #29 |
| F48 | 霰弹枪架 | 中控旁，空的 | 物件 | P1 | 库 | 待找 |
| F49 | 后备箱衬垫 | 毛毡，油渍 | 物件 | P1 | 库 | 待找 |
| F50 | 千斤顶存放位和扳手 | 后备箱侧壁 | 物件 | P1 | 库 | [ratchet_wrench](https://polyhaven.com/a/ratchet_wrench)（凑合） |
| F51 | 信号棒和三角牌 | 后备箱里 | 物件 | P2 | 库 | [signal_flashlight](https://polyhaven.com/a/signal_flashlight)（凑合） |

## G　米勒父子五金与汽修行

前店货架高、视线窄；后厂有 1.5 米深的修车地沟。红色工具箱里有一只是拟态怪，真假必须共用同一套贴图。

| 编号 | 名称 | 说明 | 类型 | 级 | 来源 | 素材 |
| --- | --- | --- | --- | --- | --- | --- |
| G01 | 手绘木招牌 | 店名，原创 | 物件 | P0 | 画 | 有初稿 #10 |
| G02 | 门玻璃上的金字 | 店名和营业时间 | 贴花 | P1 | 画 | 有初稿 #23 |
| G03 | 褪色的橱窗陈列 | 晒褪色的包装盒 | 物件 | P2 | 画 | 有初稿 #30 |
| G04 | 油浸宽木地板 | 前店 | 平铺 | P0 | 拍 | [dark_planks](https://polyhaven.com/a/dark_planks) |
| G05 | 原木和角钢高货架 | 积灰 | 物件 | P0 | 拍 | [steel_frame_shelves_03](https://polyhaven.com/a/steel_frame_shelves_03) |
| G06 | 洞洞板和挂钩 | 墙面 | 平铺 | P0 | 库 | [SheetMetal002](https://ambientcg.com/view?id=SheetMetal002)（凑合） |
| G07 | 百格螺丝抽屉柜 | 手写标签 | 物件 | P1 | 画 | 有初稿 #23 |
| G08 | 木柜台和玻璃展示柜 | 划痕 | 物件 | P1 | 拍 | [wooden_display_shelves_01](https://polyhaven.com/a/wooden_display_shelves_01)（凑合） |
| G09 | 黄铜老式收银机 | 柜台上 | 物件 | P2 | 库 | [CashRegister_01](https://polyhaven.com/a/CashRegister_01)（凑合） |
| G10 | 配钥匙机和钥匙坯墙 | 柜台后，怪 | 物件 | P2 | 库 | 待找 |
| G11 | 油漆罐墙和色卡架 | 原创品牌 | 图集 | P1 | 画 | 有初稿 #23 |
| G12 | 手工具吊卡包装 | 锤、扳手、螺丝刀、钳 | 图集 | P1 | 画 | 有初稿 #23 |
| G13 | 电钻和包装盒 | 小型怪的原型 | 物件 | P0 | 画 | 有初稿 #10 |
| G14 | 台扇和包装盒 | 小型怪的原型 | 物件 | P0 | 画 | 有初稿 #10 |
| G15 | 链条和绳索卷轴 | 货架端头 | 物件 | P2 | 库 | [Rope001](https://ambientcg.com/view?id=Rope001)（凑合） |
| G16 | 管件箱 | 铜、镀锌、塑料 | 图集 | P1 | 库 | 待找 |
| G17 | 钢管货架 | 近战武器的来源 | 物件 | P1 | 拍 | [steel_frame_shelves_01](https://polyhaven.com/a/steel_frame_shelves_01) |
| G18 | 钉子桶和台秤 | 柜台旁 | 物件 | P2 | 库 | [wooden_bucket_01](https://polyhaven.com/a/wooden_bucket_01)（凑合） |
| G19 | 园艺工具 | 铲、耙 | 物件 | P2 | 库 | [rusted_spade_01](https://polyhaven.com/a/rusted_spade_01) |
| G20 | 肥料和种子袋 | 原创包装 | 图集 | P2 | 画 | 有初稿 #30 |
| G21 | 割草机 | 陈列品，怪 | 物件 | P2 | 库 | 待找 |
| G22 | 铝梯和木梯 | 靠墙 | 物件 | P2 | 库 | [wooden_ladder_02](https://polyhaven.com/a/wooden_ladder_02) |
| G23 | 工业胶带货架 | 可拾取物 | 物件 | P0 | 画 | 有初稿 #7 |
| G24 | 强力胶吊卡 | 可拾取物 | 物件 | P0 | 画 | 待画 |
| G25 | 机油滤芯盒 | 可拾取物，消音器改件 | 物件 | P0 | 画 | 有初稿 #7 |
| G26 | 钢锯和锯条 | 锯短枪管用 | 物件 | P1 | 画 | 有初稿 #23 |
| G27 | 木尺和卷尺 | 夹板材料 | 物件 | P1 | 画 | 有初稿 #23 |
| G28 | 手电筒和电池陈列 | 柜台旁 | 图集 | P1 | 画 | 有初稿 #23 |
| G29 | 劳保手套和灭火器 | 小件 | 物件 | P2 | 库 | [garden_gloves_01](https://polyhaven.com/a/garden_gloves_01)（凑合） |
| G30 | 手写促销牌 | 原创 | 图集 | P2 | 画 | 有初稿 #30 |
| G31 | 赠品日历 | 原创，停在出事那天 | 物件 | P1 | 画 | 有初稿 #23 |
| G32 | 鹿头标本 | 柜台上方 | 物件 | P2 | 库 | 待找 |
| G33 | 车间混凝土地面 | 油浸、修补、排水沟 | 平铺 | P0 | 拍 | [Concrete033](https://ambientcg.com/view?id=Concrete033) |
| G34 | 地沟壁 | 油泥砖 | 平铺 | P0 | 拍 | [brick_pavement_02](https://polyhaven.com/a/brick_pavement_02)（凑合） |
| G35 | 地沟积油液面 | 黑色高光 | 着色器 | P0 | 程 | 已做 |
| G36 | 地沟防爆笼灯 | 沟壁上 | 发光 | P1 | 库 | [industrial_caged_sconce](https://polyhaven.com/a/industrial_caged_sconce) |
| G37 | 地沟边钢包角 | 黄黑警示漆 | 饰条 | P0 | 拍 | [PaintedMetal016](https://ambientcg.com/view?id=PaintedMetal016) |
| G38 | 地沟盖板 | 木板和钢格栅 | 物件 | P1 | 拍 | [DiamondPlate001](https://ambientcg.com/view?id=DiamondPlate001) |
| G39 | 举升机 | 立柱式，漆铁 | 物件 | P1 | 库 | 待找 |
| G40 | 落地千斤顶 | 红漆，关键道具 | 物件 | P0 | 画 | 有初稿 #10 |
| G41 | 马凳 | 三脚支架 | 物件 | P2 | 库 | 待找 |
| G42 | 红色工具车 | 带抽屉，拟态 | 物件 | P0 | 画 | 有初稿 #10 |
| G43 | 红色手提工具箱 | 拟态 | 物件 | P0 | 画 | 有初稿 #10 |
| G44 | 工作台和台钳 | 木和钢 | 物件 | P1 | 拍 | [bench_vice_01](https://polyhaven.com/a/bench_vice_01)（凑合） |
| G45 | 工具轮廓墙 | 洞洞板上画着工具剪影 | 平铺 | P1 | 画 | 有初稿 #23 |
| G46 | 气泵和气管卷轴 | 怪 | 物件 | P1 | 库 | [old_military_compressor](https://polyhaven.com/a/old_military_compressor) |
| G47 | 电焊机和气瓶 | 角落 | 物件 | P2 | 库 | [portable_welding_cart](https://polyhaven.com/a/portable_welding_cart) |
| G48 | 电瓶充电机 | 怪，暗示电瓶的线索 | 物件 | P1 | 画 | 有初稿 #23 |
| G49 | 轮胎架和拆胎机 | 靠墙 | 物件 | P2 | 库 | 待找 |
| G50 | 废轮胎堆 | 后院 | 物件 | P1 | 库 | [old_tyre](https://polyhaven.com/a/old_tyre) |
| G51 | 机油桶、漏斗和接油盆 | 地面 | 物件 | P1 | 库 | [small_oil_can_01](https://polyhaven.com/a/small_oil_can_01)（凑合） |
| G52 | 零件清洗槽 | 红色，黑水 | 物件 | P2 | 库 | 待找 |
| G53 | 零件货架和纸盒 | 原创零件品牌 | 图集 | P1 | 画 | 有初稿 #23 |
| G54 | 挂墙的一排皮带 | 要找对型号 | 物件 | P0 | 画 | 有初稿 #10 |
| G55 | 排气管和消音器挂架 | 天花下 | 物件 | P2 | 库 | 待找 |
| G56 | 引擎吊架和吊着的发动机 | 车间中央 | 物件 | P2 | 库 | 待找 |
| G57 | 待修的车 | 引擎盖开着 | 物件 | P1 | 库 | [covered_car](https://polyhaven.com/a/covered_car) |
| G58 | 墙上的油手印 | 开关和门边 | 贴花 | P1 | 拍 | [Fingerprints004](https://ambientcg.com/view?id=Fingerprints004) |
| G59 | 轮胎品牌海报和价目表 | 原创 | 图集 | P2 | 画 | 有初稿 #30 |
| G60 | 车间挂钟和收音机 | 收音机是怪 | 物件 | P1 | 库 | [wall_clock](https://polyhaven.com/a/wall_clock) |
| G61 | 客户等候椅 | 人造革 | 物件 | P2 | 库 | [SchoolChair_01](https://polyhaven.com/a/SchoolChair_01)（凑合） |
| G62 | 阁楼陡木梯和护栏 | 账房 | 物件 | P1 | 拍 | [wooden_ladder](https://polyhaven.com/a/wooden_ladder)（凑合） |
| G63 | 绿漆钢办公桌和转椅 | 账房 | 物件 | P1 | 库 | [metal_office_desk](https://polyhaven.com/a/metal_office_desk)（凑合） |
| G64 | 文件柜 | 账房 | 物件 | P2 | 库 | [drawer_cabinet](https://polyhaven.com/a/drawer_cabinet)（凑合） |
| G65 | 账本和发票 | 原创，叙事 | 图集 | P1 | 画 | 有初稿 #23 |
| G66 | 绿罩台灯 | 账房 | 发光 | P2 | 库 | [desk_lamp_arm_01](https://polyhaven.com/a/desk_lamp_arm_01)（凑合） |
| G67 | 加法机和打字机 | 小型怪的原型 | 物件 | P2 | 库 | 待找 |
| G68 | 遮雨棚顶面 | 可以从窗户翻上去踩 | 平铺 | P1 | 拍 | [rusty_corrugated_iron](https://polyhaven.com/a/rusty_corrugated_iron) |

## H　锈锚小酒馆

U 型吧台加卡座的环形近战场地。背吧镜子可以做反光，让玩家从镜子里看到身后。

| 编号 | 名称 | 说明 | 类型 | 级 | 来源 | 素材 |
| --- | --- | --- | --- | --- | --- | --- |
| H01 | 酒馆霓虹招牌 | 船锚图形，原创 | 发光 | P0 | 画 | 有初稿 #11 |
| H02 | 窗内啤酒霓虹 | 原创品牌，4 种 | 发光 | P1 | 画 | 待画 |
| H03 | 厚木门 | 带菱形小窗 | 物件 | P1 | 拍 | [wooden_garage_door](https://polyhaven.com/a/wooden_garage_door)（凑合） |
| H04 | 磨损的木地板 | 吧台前一道白痕 | 平铺 | P1 | 拍 | [wood_floor_worn](https://polyhaven.com/a/wood_floor_worn) |
| H05 | 吧台台面 | 深色木，烟烫痕和杯圈 | 物件 | P0 | 拍 | [wood_table_worn](https://polyhaven.com/a/wood_table_worn) |
| H06 | 吧台前缘软包和铜脚踏杆 | 磨亮 | 饰条 | P1 | 库 | [brown_leather](https://polyhaven.com/a/brown_leather)（凑合） |
| H07 | 吧凳 | 人造革和镀铬，裂口 | 物件 | P1 | 库 | [bar_chair_round_01](https://polyhaven.com/a/bar_chair_round_01) |
| H08 | 背吧木架 | 雕花，酒瓶层架 | 物件 | P1 | 拍 | [wooden_bookshelf_worn](https://polyhaven.com/a/wooden_bookshelf_worn)（凑合） |
| H09 | 背吧镜子 | 银层剥落，做实时反光 | 物件 | P1 | 程 | 已做 |
| H10 | 酒瓶和酒标 | 原创，12 种，威士忌可拾取 | 图集 | P0 | 画 | 有初稿 #11 |
| H11 | 啤酒龙头和生啤塔 | 吧台上 | 物件 | P2 | 库 | 待找 |
| H12 | 杯具 | 品脱杯、小酒杯，有的还有酒 | 物件 | P1 | 库 | 待找 |
| H13 | 杯垫、餐巾和满的烟灰缸 | 台面杂物 | 图集 | P1 | 画 | 待画 |
| H14 | 小费罐和腌蛋罐 | 吧台上 | 物件 | P2 | 画 | 有初稿 #31 |
| H15 | 挂角的显像管电视 | 怪 | 物件 | P1 | 库 | [Television_01](https://polyhaven.com/a/Television_01) |
| H16 | 点唱机 | 发光，怪 | 发光 | P1 | 画 | 待画 |
| H17 | 台球桌 | 绿呢撕裂，球和杆架 | 物件 | P2 | 库 | 待找 |
| H18 | 飞镖靶和记分黑板 | 粉笔字 | 物件 | P2 | 画 | 有初稿 #31 |
| H19 | 弹珠机 | 发光，原创主题，怪 | 发光 | P2 | 画 | 有初稿 #31 |
| H20 | 拉杆式香烟售卖机 | 怪 | 物件 | P2 | 画 | 有初稿 #31 |
| H21 | 墙挂公用电话 | 旁边墙上写满号码 | 物件 | P1 | 画 | 待画 |
| H22 | 高背木卡座 | 人造革坐垫，裂口露海绵 | 物件 | P1 | 拍 | [painted_wooden_bench](https://polyhaven.com/a/painted_wooden_bench)（凑合） |
| H23 | 卡座桌面 | 层压板，刻字 | 物件 | P1 | 画 | 待画 |
| H24 | 啤酒广告镜 | 原创 | 物件 | P2 | 画 | 有初稿 #31 |
| H25 | 照片墙 | 本地球队和常客，原创人像，叙事 | 图集 | P1 | 画 | 待画 |
| H26 | 保龄球奖杯和鱼标本 | 架子上 | 物件 | P2 | 库 | 待找 |
| H27 | 常年不摘的彩灯 | 发光 | 发光 | P2 | 库 | 待找 |
| H28 | 烟熏发黄的天花 | 渐变叠加 | 贴花 | P1 | 程 | 已做 |
| H29 | 酒馆卫生间 | 涂鸦和破镜子 | 平铺 | P2 | 画 | 有初稿 #31 |
| H30 | 后厨油炸锅和不锈钢台 | 油垢 | 物件 | P2 | 拍 | 待找 |
| H31 | 后厨防滑地砖 | 油垢 | 平铺 | P2 | 拍 | [Tiles087](https://ambientcg.com/view?id=Tiles087)（凑合） |
| H32 | 啤酒桶、酒箱和气瓶 | 储藏室 | 物件 | P2 | 画 | 有初稿 #31 |
| H33 | 库房备用钥匙和钥匙牌 | 关键道具 | 物件 | P0 | 画 | 有初稿 #11 |

## I　24 小时投币洗衣房

十几台滚筒同时空转，真怪混在其中。圆窗里的内容物是这一栋的全部戏份。

| 编号 | 名称 | 说明 | 类型 | 级 | 来源 | 素材 |
| --- | --- | --- | --- | --- | --- | --- |
| I01 | 塑料字招牌 | 缺一个字母 | 发光 | P0 | 画 | 有初稿 #11 |
| I02 | 窗贴 | 营业项目和价格，原创 | 贴花 | P1 | 画 | 待画 |
| I03 | 洗衣房方砖地面 | 白砖黑缝，朝地漏找坡 | 平铺 | P0 | 拍 | [Tiles109](https://ambientcg.com/view?id=Tiles109) |
| I04 | 半墙瓷砖 | 上半刷漆 | 平铺 | P1 | 拍 | [long_white_tiles](https://polyhaven.com/a/long_white_tiles) |
| I05 | 滚筒洗衣机机身 | 搪瓷和不锈钢 | 物件 | P0 | 拍 | [Porcelain002](https://ambientcg.com/view?id=Porcelain002) |
| I06 | 圆窗玻璃和门圈 | 厚玻璃，胶圈 | 物件 | P0 | 库 | 待找 |
| I07 | 圆窗内容：清水和泡沫 | 正常的那几台 | 着色器 | P0 | 程 | 已做 |
| I08 | 圆窗内容：翻滚的衣物 | 正常的那几台 | 序列帧 | P0 | 画 | 有初稿 #11 |
| I09 | 圆窗内容：血水 | 不正常的那几台 | 着色器 | P0 | 程 | 已做 |
| I10 | 圆窗内容：尸块和遗物 | 戒指、手表一闪而过 | 序列帧 | P0 | 画 | 有初稿 #11 |
| I11 | 滚筒内壁 | 不锈钢冲孔 | 物件 | P1 | 库 | [SheetMetal002](https://ambientcg.com/view?id=SheetMetal002) |
| I12 | 上开式洗衣机 | 靠墙一排 | 物件 | P1 | 库 | [Porcelain002](https://ambientcg.com/view?id=Porcelain002)（凑合） |
| I13 | 叠式烘干机 | 大圆窗，怪 | 物件 | P1 | 库 | [Porcelain002](https://ambientcg.com/view?id=Porcelain002)（凑合） |
| I14 | 滑槽式投币器 | 每台机器上 | 物件 | P1 | 库 | 待找 |
| I15 | 换币机 | 发光，怪 | 发光 | P1 | 画 | 待画 |
| I16 | 洗衣粉售卖机 | 小盒装，原创品牌 | 物件 | P2 | 画 | 有初稿 #30 |
| I17 | 折叠台 | 层压板 | 物件 | P1 | 库 | 待找 |
| I18 | 洗衣车 | 金属网和帆布 | 物件 | P1 | 库 | 待找 |
| I19 | 模压塑料排椅 | 橙色和黄色 | 物件 | P1 | 库 | [plastic_monobloc_chair_01](https://polyhaven.com/a/plastic_monobloc_chair_01)（凑合） |
| I20 | 旧杂志 | 原创封面，夹板材料 | 图集 | P1 | 画 | 待画 |
| I21 | 失物篮 | 单只袜子和童装 | 物件 | P2 | 画 | 有初稿 #30 |
| I22 | 软木布告板 | 寻人、寻狗、出租，叙事 | 物件 | P1 | 画 | 待画 |
| I23 | 店规牌 | 原创 | 物件 | P2 | 画 | 有初稿 #30 |
| I24 | 停走的挂钟 | 停在三点 | 物件 | P1 | 画 | 待画 |
| I25 | 毛絮堆 | 烘干机后面 | 贴花 | P2 | 拍 | 程序生成 |
| I26 | 肥皂水渍 | 地面 | 贴花 | P1 | 拍 | [SurfaceImperfections005](https://ambientcg.com/view?id=SurfaceImperfections005)（凑合） |
| I27 | 污水槽 | 角落 | 物件 | P2 | 拍 | [Porcelain002](https://ambientcg.com/view?id=Porcelain002) |
| I28 | 毛巾 | 可拾取物，包扎用 | 物件 | P0 | 画 | 有初稿 #11 |

## J　克拉克家（终局）

一楼是生活突然中断的样子，二楼是巢穴。叙事小物件最密的一栋：这一家人是谁、谁变成了什么，都靠这里的贴图讲。

| 编号 | 名称 | 说明 | 类型 | 级 | 来源 | 素材 |
| --- | --- | --- | --- | --- | --- | --- |
| J01 | 门廊秋千椅和门垫 | 入口 | 物件 | P2 | 库 | 待找 |
| J02 | 院子杂物 | 儿童三轮车、瘪的充气泳池、空狗屋和链子 | 物件 | P1 | 画 | 有初稿 #24 |
| J03 | 混凝土车道 | 裂缝和油斑 | 平铺 | P1 | 拍 | [grooved_concrete_driveway](https://polyhaven.com/a/grooved_concrete_driveway) |
| J04 | 门口垃圾桶 | 拟态：雨夜里顶盖是干的 | 物件 | P1 | 画 | 有初稿 #24 |
| J05 | 门厅鞋架和外套钩 | 入口 | 物件 | P2 | 库 | 待找 |
| J06 | 门厅镜子和钥匙碗 | 入口 | 物件 | P2 | 库 | [ornate_mirror_01](https://polyhaven.com/a/ornate_mirror_01)（凑合） |
| J07 | 客厅沙发 | 格子呢，搭着钩针毯 | 物件 | P1 | 拍 | [Sofa_01](https://polyhaven.com/a/Sofa_01)（凑合） |
| J08 | 人造革躺椅 | 扶手磨破 | 物件 | P1 | 拍 | [mid_century_lounge_chair](https://polyhaven.com/a/mid_century_lounge_chair) |
| J09 | 茶几和杂物 | 杯圈、遥控器、电视指南 | 物件 | P1 | 画 | 有初稿 #24 |
| J10 | 落地式木壳电视 | 闪着雪花，怪 | 物件 | P0 | 画 | 有初稿 #12 |
| J11 | 录像机和手写标签的录像带 | 叙事 | 物件 | P1 | 画 | 有初稿 #24 |
| J12 | 游戏机 | 原创外形 | 物件 | P2 | 画 | 有初稿 #32 |
| J13 | 音响和唱机 | 怪 | 物件 | P2 | 库 | [boombox](https://polyhaven.com/a/boombox)（凑合） |
| J14 | 书架 | 百科全书和相册 | 物件 | P1 | 画 | 有初稿 #24 |
| J15 | 布谷鸟挂钟 | 怪 | 物件 | P2 | 库 | 待找 |
| J16 | 墙上挂画 | 十字绣、风景画、结婚照 | 图集 | P1 | 画 | 有初稿 #24 |
| J17 | 餐桌椅 | 层压板桌面和镀铬腿 | 物件 | P1 | 库 | [dining_table](https://polyhaven.com/a/dining_table) |
| J18 | 桌上的剩饭 | 凝固的肉汁、半杯牛奶 | 物件 | P0 | 画 | 有初稿 #12 |
| J19 | 塑料格子桌布 | 餐桌 | 平铺 | P2 | 画 | 有初稿 #32 |
| J20 | 厨房橱柜贴面 | 牛油果绿或暗金色 | 平铺 | P1 | 画 | 有初稿 #24 |
| J21 | 回旋镖纹台面 | 厨房 | 平铺 | P1 | 画 | 有初稿 #24 |
| J22 | 不锈钢水槽和脏碗 | 厨房 | 物件 | P1 | 拍 | 待找 |
| J23 | 厨房里的冰箱 | 贴着磁贴、儿童画、日历、购物清单 | 物件 | P0 | 画 | 有初稿 #12 |
| J24 | 电炉灶台和抽油烟机 | 螺旋炉盘，怪 | 物件 | P1 | 库 | [electric_stove](https://polyhaven.com/a/electric_stove) |
| J25 | 厨房小家电 | 烤面包机、搅拌机、电动开罐器、咖啡机，怪 | 图集 | P0 | 画 | 有初稿 #12 |
| J26 | 洗碗机 | 怪 | 物件 | P2 | 库 | 待找 |
| J27 | 墙挂电话 | 很长的卷线 | 物件 | P1 | 库 | 待找 |
| J28 | 留言板和日历 | 最后一条留言，叙事 | 物件 | P1 | 画 | 有初稿 #24 |
| J29 | 麦片盒、调料架和狗碗 | 台面杂物 | 图集 | P2 | 画 | 有初稿 #32 |
| J30 | 地面的拖拽刮痕 | 从厨房通向楼梯，有引导功能 | 贴花 | P0 | 画 | 有初稿 #12 |
| J31 | 楼梯间照片墙 | 逐年的全家福，原创人像，叙事 | 图集 | P1 | 画 | 有初稿 #24 |
| J32 | 门框上的身高刻度 | 孩子每年的身高，叙事 | 贴花 | P1 | 画 | 有初稿 #24 |
| J33 | 立式吸尘器 | 怪 | 物件 | P1 | 库 | 待找 |
| J34 | 浴缸和发霉的浴帘 | 浴室 | 物件 | P1 | 拍 | 待找 |
| J35 | 洗手台和镜门药柜 | 里面有止痛药 | 物件 | P1 | 库 | 待找 |
| J36 | 处方药瓶 | 标签上有姓名，叙事 | 物件 | P1 | 画 | 有初稿 #24 |
| J37 | 浴室小电器 | 吹风机、电动剃须刀，怪 | 图集 | P2 | 画 | 有初稿 #32 |
| J38 | 主卧床品 | 被肉筋缠住 | 物件 | P1 | 画 | 有初稿 #24 |
| J39 | 闹钟收音机 | 数字停在 3:00，怪 | 发光 | P1 | 画 | 有初稿 #24 |
| J40 | 梳妆台和首饰盒 | 主卧 | 物件 | P2 | 库 | [vintage_wooden_drawer_01](https://polyhaven.com/a/vintage_wooden_drawer_01)（凑合） |
| J41 | 衣帽间挂着的衣物 | 卡片或低模 | 卡片 | P1 | 画 | 有初稿 #24 |
| J42 | 枪柜 | 木和毛毡，玻璃门 | 物件 | P0 | 画 | 有初稿 #12 |
| J43 | 弹药盒 | 原创品牌 | 图集 | P0 | 画 | 有初稿 #12 |
| J44 | 车库钥匙和挂钩 | 关键道具 | 物件 | P0 | 画 | 有初稿 #12 |
| J45 | 儿童房玩具 | 遥控车、会说话的玩偶，怪 | 图集 | P1 | 画 | 有初稿 #24 |
| J46 | 儿童床品和夜灯 | 原创卡通图案 | 物件 | P2 | 画 | 有初稿 #32 |
| J47 | 蜡笔画 | 画的是冰箱和家人，叙事，6 张 | 图集 | P1 | 画 | 有初稿 #24 |
| J48 | 车库地面 | 混凝土，油斑 | 平铺 | P1 | 拍 | [garage_floor](https://polyhaven.com/a/garage_floor) |
| J49 | 车库未刷漆的石膏板 | 接缝腻子 | 平铺 | P1 | 库 | [Plaster003](https://ambientcg.com/view?id=Plaster003)（凑合） |
| J50 | 车库工具墙和工作台 | 通用 | 物件 | P2 | 库 | [metal_tool_chest](https://polyhaven.com/a/metal_tool_chest)（凑合） |
| J51 | 车库冷冻柜和旧冰箱 | 怪 | 物件 | P1 | 库 | 待找 |
| J52 | 满电蓄电池 | 关键道具，三档受损外观 | 物件 | P0 | 画 | 有初稿 #12 |
| J53 | 车库门电机、导轨和拉绳 | 拉起时巨响 | 物件 | P1 | 库 | 待找 |
| J54 | 红色汽油桶和油漆罐 | 车库 | 物件 | P2 | 库 | [metal_jerrycan](https://polyhaven.com/a/metal_jerrycan) |
| J55 | 家用旅行车 | 木纹贴皮，原创外形 | 物件 | P2 | 画 | 有初稿 #32 |
| J56 | 节日装饰箱 | 手写标签 | 物件 | P2 | 画 | 有初稿 #32 |

## K　家电怪和设施怪的外壳

全部每米 1024 像素。每种怪要三套：完好外壳、破损外壳（带破损遮罩）、内腔。完好外壳必须和场景里的普通家电共用。

| 编号 | 名称 | 说明 | 类型 | 级 | 来源 | 素材 |
| --- | --- | --- | --- | --- | --- | --- |
| K01 | 白色搪瓷钢板 | 冰箱、洗衣机，边角生锈 | 平铺 | P0 | 拍 | [PaintedMetal012](https://ambientcg.com/view?id=PaintedMetal012) |
| K02 | 杏色和牛油果绿搪瓷 | 老款冰箱灶台，用染色 | 平铺 | P1 | 拍 | [PaintedMetal006](https://ambientcg.com/view?id=PaintedMetal006) |
| K03 | 木纹贴皮塑料 | 电视、收音机 | 平铺 | P0 | 拍 | [synthetic_wood](https://polyhaven.com/a/synthetic_wood) |
| K04 | 发黄的米色塑料 | 微波炉、风扇、电话 | 平铺 | P0 | 拍 | [Plastic010](https://ambientcg.com/view?id=Plastic010)（凑合） |
| K05 | 黑色皮纹塑料 | 录像机、音响 | 平铺 | P1 | 库 | [Plastic012B](https://ambientcg.com/view?id=Plastic012B) |
| K06 | 拉丝不锈钢和镀铬 | 把手、饰条 | 平铺 | P0 | 库 | [Metal059C](https://ambientcg.com/view?id=Metal059C)（凑合） |
| K07 | 电视屏幕 | 雪花、彩条、扭曲的人脸残影，4 段 | 序列帧 | P0 | 程 | 已做 |
| K08 | 显像管玻璃和爆裂 | 厚玻璃断面 | 物件 | P0 | 画 | 有初稿 #13 |
| K09 | 电视后盖 | 散热孔和原创警告标签 | 物件 | P1 | 画 | 有初稿 #25 |
| K10 | 电视内腔 | 电路板、显像管颈和肉长在一起 | 物件 | P0 | 画 | 有初稿 #14 |
| K11 | 旋钮、按键和兔耳天线 | 通用小件 | 图集 | P1 | 库 | 待找 |
| K12 | 冰箱门封条 | 变成嘴唇和齿 | 物件 | P0 | 画 | 有初稿 #14 |
| K13 | 冰箱内胆 | 塑料层架变成肋骨 | 物件 | P0 | 画 | 有初稿 #14 |
| K14 | 冰箱背面盘管 | 变成脊柱 | 物件 | P0 | 画 | 有初稿 #13 |
| K15 | 压缩机 | 变成心脏，搏动 | 物件 | P0 | 画 | 有初稿 #13 |
| K16 | 冰箱里腐烂的食物 | 内腔杂物 | 图集 | P1 | 画 | 有初稿 #25 |
| K17 | 洗衣机内腔 | 滚筒变成胃，排水管变成肠 | 物件 | P0 | 画 | 有初稿 #14 |
| K18 | 售货机喷漆钢板 | 机身 | 平铺 | P0 | 拍 | [PaintedMetal013](https://ambientcg.com/view?id=PaintedMetal013) |
| K19 | 售货机发光面板 | 原创饮料品牌，2 种 | 发光 | P0 | 画 | 有初稿 #6 |
| K20 | 售货机出货口 | 变成嘴 | 物件 | P0 | 画 | 有初稿 #6 |
| K21 | 售货机内腔 | 货道螺旋和肉 | 物件 | P1 | 画 | 有初稿 #25 |
| K22 | 冰柜外壳和霜 | 冰袋柜、冷冻柜 | 物件 | P1 | 拍 | [PaintedMetal012](https://ambientcg.com/view?id=PaintedMetal012)（凑合） |
| K23 | 消防栓铸铁漆面 | 红和黄，剥落 | 物件 | P0 | 拍 | [fire_hydrant](https://polyhaven.com/a/fire_hydrant) |
| K24 | 没有接缝的消防栓 | 拟态的破绽版本 | 物件 | P1 | 画 | 有初稿 #25 |
| K25 | 消防栓的地下部分 | 破土而出的管身 | 物件 | P1 | 画 | 有初稿 #25 |
| K26 | 路灯怪杆体和破土基座 | 混凝土碎块 | 物件 | P0 | 画 | 有初稿 #13 |
| K27 | 路灯怪灯头 | 趋光眼 | 发光 | P0 | 画 | 有初稿 #13 |
| K28 | 高压电线怪 | 护套、断口铜丝和肉 | 物件 | P1 | 画 | 有初稿 #25 |
| K29 | 电弧 | 发光序列 | 序列帧 | P1 | 程 | 已做 |
| K30 | 收音机外壳和喇叭网布 | 网布后面是发声腔 | 物件 | P0 | 画 | 有初稿 #13 |
| K31 | 收音机调谐盘 | 发光 | 发光 | P1 | 画 | 有初稿 #25 |
| K32 | 微波炉门网格和内腔 | 溅射的痕迹 | 物件 | P1 | 画 | 有初稿 #25 |
| K33 | 电风扇网罩和扇叶 | 小型怪 | 物件 | P0 | 库 | 待找 |
| K34 | 电钻机身和钻头 | 小型怪 | 物件 | P0 | 库 | [Drill_01](https://polyhaven.com/a/Drill_01)（凑合） |
| K35 | 小家电合集 | 烤面包机、搅拌机、电话、吹风机、熨斗、台灯 | 图集 | P1 | 画 | 有初稿 #25 |
| K36 | 铭牌和标签 | 原创厂牌、型号、序列号 | 图集 | P1 | 画 | 有初稿 #25 |
| K37 | 电源线和插头 | 变成尾巴和触须 | 物件 | P0 | 库 | [modular_electric_cables](https://polyhaven.com/a/modular_electric_cables)（凑合） |
| K38 | 铁皮撕裂边缘 | 透明和法线 | 遮罩 | P0 | 画 | 有初稿 #13 |
| K39 | 被顶出的铆钉和螺丝 | 外壳细节 | 贴花 | P1 | 画 | 有初稿 #25 |
| K40 | 外壳凹痕 | 金属、搪瓷、塑料、木壳各一组 | 贴花 | P0 | 画 | 程序生成 |
| K41 | 搪瓷崩瓷 | 露出黑铁和锈 | 贴花 | P0 | 拍 | [pot_enamel_01](https://polyhaven.com/a/pot_enamel_01)（凑合） |
| K42 | 塑料开裂和熔化 | 小家电破损 | 贴花 | P1 | 画 | 有初稿 #25 |
| K43 | 拟态破绽：微光 | 停电街区里独自亮着的面板 | 发光 | P1 | 程 | 已做 |
| K44 | 拟态破绽：干燥顶盖和白气 | 垃圾桶 | 序列帧 | P1 | 程 | 已做 |
| K45 | 拟态工具箱内腔 | 开口里的齿是套筒和扳手 | 物件 | P0 | 画 | 有初稿 #10 |

## L　血肉、异变和遗物

“家电里面的血肉就是失踪镇民”。皮肤上要有属于某个人的细节（痣、纹身残片、戒指印），遗物是尸检式叙事的载体。

| 编号 | 名称 | 说明 | 类型 | 级 | 来源 | 素材 |
| --- | --- | --- | --- | --- | --- | --- |
| L01 | 湿润的肌肉束 | 基础款 | 平铺 | P0 | 画 | 有初稿 #15 |
| L02 | 干结和坏死的肌肉 | 老巢穴、死去的怪 | 平铺 | P1 | 画 | 有初稿 #25 |
| L03 | 筋膜和肉筋 | 和电线长在一起，透明 | 平铺 | P0 | 画 | 有初稿 #15 |
| L04 | 脂肪层 | 断面上的黄色 | 平铺 | P1 | 画 | 有初稿 #25 |
| L05 | 从缝里挤出的皮肤 | 苍白，带毛孔、痣、纹身残片 | 平铺 | P0 | 画 | 待画 |
| L06 | 皮肤和金属的过渡带 | 融合边缘 | 遮罩 | P0 | 画 | 程序生成 |
| L07 | 嵌在缝里的指甲、牙齿、头发 | 细节 | 图集 | P1 | 画 | 有初稿 #25 |
| L08 | 骨、脊柱和肋 | 从铁皮缝里露出 | 物件 | P1 | 画 | 有初稿 #25 |
| L09 | 内脏 | 肠和会呼吸的囊 | 物件 | P1 | 画 | 有初稿 #25 |
| L10 | 血管和黑油管 | 发光脉动 | 遮罩 | P1 | 画 | 有初稿 #25 |
| L11 | 肉核弱点 | 次表面散射，搏动 | 物件 | P0 | 画 | 有初稿 #13 |
| L12 | 人眼 | 虹膜 3 种颜色 | 物件 | P0 | 画 | 有初稿 #13 |
| L13 | 黏液拉丝 | 透明 | 卡片 | P1 | 画 | 有初稿 #25 |
| L14 | 断面 | 破甲和拆解时露出的截面 | 图集 | P0 | 画 | 待画 |
| L15 | 黑油液面和流挂 | 着色器 | 着色器 | P0 | 程 | 已做 |
| L16 | 黑油滴落和喷溅 | 墙面、地面各 4 种 | 贴花 | P0 | 画 | 有初稿 #16 |
| L17 | 黑油拖痕 | 怪爬过的地方 | 贴花 | P0 | 画 | 程序生成 |
| L18 | 新鲜血溅 | 墙面、地面各 4 种 | 贴花 | P0 | 画 | 有初稿 #16 |
| L19 | 干涸的血 | 发褐 | 贴花 | P1 | 画 | 有初稿 #25 |
| L20 | 血的涂抹、手印和脚印 | 叙事 | 贴花 | P1 | 画 | 有初稿 #25 |
| L21 | 血滴 | 玩家流血时留下的 | 贴花 | P0 | 画 | 程序生成 |
| L22 | 拖拽血痕 | 长条 | 贴花 | P1 | 画 | 有初稿 #25 |
| L23 | 水中血雾 | 水洼和洗衣机里 | 着色器 | P1 | 程 | 已做 |
| L24 | 戴戒指的手 | 洗衣机和肉腔里 | 物件 | P0 | 画 | 有初稿 #13 |
| L25 | 戴手表的断腕 | 洗衣机和肉腔里 | 物件 | P0 | 画 | 有初稿 #13 |
| L26 | 衣物碎片 | 工装、护士服、童装，叙事 | 图集 | P1 | 画 | 有初稿 #25 |
| L27 | 刻字的结婚戒指 | 遗物 | 物件 | P0 | 画 | 有初稿 #13 |
| L28 | 手表 | 遗物，3 种 | 物件 | P1 | 画 | 有初稿 #25 |
| L29 | 钱包和驾照 | 遗物，原创人像 | 物件 | P1 | 画 | 有初稿 #25 |
| L30 | 工牌 | 遗物，对应镇上的店 | 物件 | P1 | 画 | 有初稿 #25 |
| L31 | 假牙、眼镜和助听器 | 遗物 | 图集 | P2 | 画 | 有初稿 #32 |
| L32 | 宠物名牌 | 遗物，对应空狗屋 | 物件 | P2 | 画 | 有初稿 #32 |
| L33 | 巢穴墙面增生 | 3 种 | 平铺 | P0 | 画 | 有初稿 #15 |
| L34 | 巢穴地面增生 | 肉质地毯 | 平铺 | P0 | 画 | 有初稿 #15 |
| L35 | 增生边缘过渡 | 透明 | 遮罩 | P0 | 画 | 程序生成 |
| L36 | 被顶起的墙纸 | 巢穴周围 | 贴花 | P1 | 画 | 待画 |
| L37 | 墙面渗出的黑油 | 巢穴周围 | 贴花 | P1 | 画 | 程序生成 |
| L38 | 搏动发光遮罩 | 通用 | 遮罩 | P0 | 程 | 已做 |
| L39 | 呼出的白气 | 序列帧 | 序列帧 | P1 | 程 | 已做 |

## M　通用污渍和痕迹贴花

这一组是“脏”的主要来源，也是成本最低、回报最高的一组。每种都要多个变体避免重复感。

| 编号 | 名称 | 说明 | 类型 | 级 | 来源 | 素材 |
| --- | --- | --- | --- | --- | --- | --- |
| M01 | 竖向水渍流痕 | 8 种 | 贴花 | P0 | 拍 | [Leaking005](https://ambientcg.com/view?id=Leaking005) |
| M02 | 窗台下的流痕 | 4 种 | 贴花 | P0 | 拍 | [Leaking012A](https://ambientcg.com/view?id=Leaking012A) |
| M03 | 墙根雨水溅泥 | 4 种 | 贴花 | P1 | 拍 | [Leaking007](https://ambientcg.com/view?id=Leaking007) |
| M04 | 墙角霉斑 | 6 种 | 贴花 | P0 | 拍 | [Leaking013C](https://ambientcg.com/view?id=Leaking013C)（凑合） |
| M05 | 天花角落黑霉 | 4 种 | 贴花 | P0 | 拍 | [Leaking015C](https://ambientcg.com/view?id=Leaking015C)（凑合） |
| M06 | 外墙青苔和藻 | 4 种 | 贴花 | P1 | 拍 | [Moss001](https://ambientcg.com/view?id=Moss001)（凑合） |
| M07 | 锈流痕 | 6 种 | 贴花 | P0 | 拍 | [Metal020](https://ambientcg.com/view?id=Metal020)（凑合） |
| M08 | 锈斑和锈穿孔 | 6 种 | 贴花 | P0 | 拍 | [Rust001](https://ambientcg.com/view?id=Rust001) |
| M09 | 泛碱白霜 | 4 种 | 贴花 | P1 | 拍 | [Leaking002](https://ambientcg.com/view?id=Leaking002)（凑合） |
| M10 | 多层漆面剥落 | 6 种，带高度 | 贴花 | P0 | 拍 | [PaintedPlaster006](https://ambientcg.com/view?id=PaintedPlaster006)（凑合） |
| M11 | 灰泥脱落露出砖 | 4 种 | 贴花 | P1 | 拍 | [PaintedPlaster014](https://ambientcg.com/view?id=PaintedPlaster014)（凑合） |
| M12 | 混凝土剥落露出钢筋 | 4 种 | 贴花 | P1 | 拍 | [rebar_reinforced_concrete](https://polyhaven.com/a/rebar_reinforced_concrete)（凑合） |
| M13 | 墙面裂缝 | 6 种 | 贴花 | P0 | 拍 | [damaged_plaster](https://polyhaven.com/a/damaged_plaster)（凑合） |
| M14 | 地面裂缝 | 6 种 | 贴花 | P0 | 拍 | [AsphaltDamageSet001](https://ambientcg.com/view?id=AsphaltDamageSet001) |
| M15 | 砖墙阶梯状沉降裂缝 | 3 种 | 贴花 | P1 | 拍 | 程序生成 |
| M16 | 胶带残痕和海报撕痕 | 6 种 | 贴花 | P1 | 拍 | [Tape004](https://ambientcg.com/view?id=Tape004)（凑合） |
| M17 | 钉孔和纸角 | 4 种 | 贴花 | P2 | 拍 | 程序生成 |
| M18 | 脏手印和油手印 | 6 种 | 贴花 | P1 | 拍 | [Fingerprints004](https://ambientcg.com/view?id=Fingerprints004) |
| M19 | 抓痕 | 墙、门、地面，6 种 | 贴花 | P0 | 画 | 有初稿 #16 |
| M20 | 踢痕和鞋印 | 4 种 | 贴花 | P1 | 拍 | [Footsteps004](https://ambientcg.com/view?id=Footsteps004)（凑合） |
| M21 | 泥脚印和湿脚印 | 4 种 | 贴花 | P1 | 画 | 有初稿 #26 |
| M22 | 泥地轮胎印 | 3 种 | 贴花 | P2 | 拍 | [TireTracks001](https://ambientcg.com/view?id=TireTracks001) |
| M23 | 家电拖拽痕 | 木地板、地板革、地毯、水泥各一种 | 贴花 | P0 | 画 | 有初稿 #16 |
| M24 | 电线拖痕 | 细长蛇形，3 种 | 贴花 | P0 | 画 | 程序生成 |
| M25 | 烟熏和烧焦 | 4 种 | 贴花 | P1 | 拍 | 程序生成 |
| M26 | 插座周围的电弧焦痕 | 4 种 | 贴花 | P0 | 拍 | 程序生成 |
| M27 | 机油污 | 滴点、大片、手抹，6 种 | 贴花 | P0 | 拍 | 程序生成 |
| M28 | 饮料泼洒 | 3 种 | 贴花 | P2 | 拍 | 程序生成 |
| M29 | 鸟粪 | 3 种 | 贴花 | P2 | 拍 | 程序生成 |
| M30 | 人行道口香糖斑 | 1 张 | 图集 | P2 | 拍 | [ChewingGum001](https://ambientcg.com/view?id=ChewingGum001) |
| M31 | 烟头、瓶盖和碎纸 | 1 张 | 图集 | P1 | 拍 | 程序生成 |
| M32 | 散落的落叶 | 1 张 | 图集 | P1 | 拍 | [ScatteredLeaves004](https://ambientcg.com/view?id=ScatteredLeaves004) |
| M33 | 弹孔 | 砖、混凝土、木、金属、搪瓷、玻璃、石膏板、塑料，每种 4 个 | 图集 | P0 | 画 | 有初稿 #16 |
| M34 | 霰弹散布痕 | 3 种 | 贴花 | P1 | 画 | 有初稿 #26 |
| M35 | 涂鸦 | 原创，6 种，克制使用 | 贴花 | P2 | 画 | 有初稿 #33 |
| M36 | 儿童粉笔画 | 人行道上，叙事 | 贴花 | P1 | 画 | 有初稿 #26 |
| M37 | 市政喷漆标记 | 管线位置的橙色蓝色记号 | 贴花 | P2 | 画 | 有初稿 #33 |
| M38 | 警戒带和封条 | 原创 | 物件 | P2 | 画 | 有初稿 #33 |
| M39 | 灰尘堆和棉絮 | 2 种 | 贴花 | P2 | 拍 | [SurfaceImperfections014](https://ambientcg.com/view?id=SurfaceImperfections014)（凑合） |
| M40 | 墙面下半截的湿润渐变 | 雨夜外墙 | 遮罩 | P0 | 程 | 已做 |
| M41 | 屋檐滴水线 | 地面一排小坑 | 贴花 | P1 | 拍 | [Leaking012A](https://ambientcg.com/view?id=Leaking012A) |

## N　招牌和印刷品（全部原创）

需要先定几套虚构品牌的标志和配色，再往各处贴。叙事纸品是背景故事的主要载体：家电召回通知、电力公司的电压异常通知值得优先做。

| 编号 | 名称 | 说明 | 类型 | 级 | 来源 | 素材 |
| --- | --- | --- | --- | --- | --- | --- |
| N01 | 镇名欢迎牌 | 虚构镇名和人口数 | 物件 | P0 | 画 | 有初稿 #5 |
| N02 | 加油站品牌整套 | 标志、配色、立牌、棚檐、油机贴、纸杯、小票 | 图集 | P0 | 画 | 待画 |
| N03 | 便利店灯箱 | 原创店名 | 发光 | P0 | 画 | 有初稿 #5 |
| N04 | 背景店铺招牌 | 理发店、当铺、旧货店等 6 家 | 图集 | P1 | 画 | 有初稿 #26 |
| N05 | 教堂活动字母板 | 可以拼出不对劲的句子，叙事 | 物件 | P1 | 画 | 有初稿 #26 |
| N06 | 国道和州道盾牌 | 虚构的州 | 物件 | P1 | 画 | 有初稿 #26 |
| N07 | 高速匝道指示牌 | 开局和结尾出现 | 物件 | P0 | 画 | 有初稿 #5 |
| N08 | 警示路牌 | 鹿出没、学校区域、弯道 | 图集 | P2 | 画 | 有初稿 #33 |
| N09 | 门牌号字体 | 一套数字 | 图集 | P1 | 画 | 有初稿 #26 |
| N10 | 门窗小贴纸 | 营业时间、推和拉、信用卡标（原创）、禁烟、监控提示 | 图集 | P1 | 画 | 有初稿 #26 |
| N11 | 出口灯箱 | 发光 | 发光 | P1 | 画 | 有初稿 #26 |
| N12 | 安全标识 | 高压危险、易燃、灭火器，通用符号 | 图集 | P1 | 画 | 有初稿 #26 |
| N13 | 收据和小票 | 时间戳是线索 | 图集 | P2 | 画 | 有初稿 #33 |
| N14 | 报纸头版 | 3 期，日期递进，背景故事 | 图集 | P1 | 画 | 有初稿 #26 |
| N15 | 杂志封面 | 8 种 | 图集 | P2 | 画 | 有初稿 #33 |
| N16 | 日历 | 4 种，都停在同一天 | 图集 | P1 | 画 | 有初稿 #26 |
| N17 | 寻人启事 | 6 张，原创人像 | 图集 | P1 | 画 | 有初稿 #26 |
| N18 | 寻宠启事 | 3 张 | 图集 | P2 | 画 | 有初稿 #33 |
| N19 | 社区活动传单和庭院售卖牌 | 电线杆和布告板上 | 图集 | P2 | 画 | 有初稿 #33 |
| N20 | 家电安全召回通知 | 暗线 | 物件 | P1 | 画 | 有初稿 #26 |
| N21 | 电力公司电压异常通知 | 暗线 | 物件 | P1 | 画 | 有初稿 #26 |
| N22 | 警局文件 | 巡逻日志、罚单本、交接班记录 | 图集 | P1 | 画 | 有初稿 #26 |
| N23 | 手写便条 | 10 张，叙事 | 图集 | P1 | 画 | 有初稿 #26 |
| N24 | 照片 | 20 张，家庭、店主、球队，原创人像 | 图集 | P1 | 画 | 有初稿 #26 |
| N25 | 饮料和啤酒品牌 | 汽水 6 种、啤酒 4 种、威士忌 3 种的标志 | 图集 | P0 | 画 | 待画 |
| N26 | 香烟和零食品牌 | 香烟 4 种、零食 12 种的标志 | 图集 | P1 | 画 | 有初稿 #26 |
| N27 | 家电和工具品牌 | 家电 5 种、工具 3 种、电池 2 种、洗衣粉 3 种的标志 | 图集 | P0 | 画 | 待画 |

## O　天空、天气、光和特效

雨夜的光影是性能预算的重点投入方向。灯光遮罩很便宜，但对“每盏灯长得不一样”帮助很大。

| 编号 | 名称 | 说明 | 类型 | 级 | 来源 | 素材 |
| --- | --- | --- | --- | --- | --- | --- |
| O01 | 阴云夜空 | 浓云和透月薄云两种 | 全景 | P0 | 库 | [qwantani_night_puresky](https://polyhaven.com/a/qwantani_night_puresky)（凑合） |
| O02 | 地平线光污染 | 远处城市的橙色辉光 | 全景 | P1 | 程 | 已做 |
| O03 | 闪电 | 光照曲线和天空闪光 | 序列帧 | P2 | 程 | 已做 |
| O04 | 雨丝 | 近、远两层 | 卡片 | P0 | 程 | 已做 |
| O05 | 光柱里的斜雨 | 车灯和路灯下才看得见 | 序列帧 | P0 | 程 | 已做 |
| O06 | 水面雨滴涟漪 | 法线序列 | 序列帧 | P0 | 程 | 已做 |
| O07 | 雨点溅起 | 地面、车顶、水洼 3 种 | 序列帧 | P1 | 程 | 已做 |
| O08 | 窗玻璃流水 | 流动图和法线 | 着色器 | P1 | 程 | 已做 |
| O09 | 体积雾噪声 | 三维噪声 | 遮罩 | P0 | 程 | 已做 |
| O10 | 贴地薄雾 | 卡片 | 卡片 | P1 | 程 | 已做 |
| O11 | 边界黑雾墙 | 浓得发黑、会吞光 | 着色器 | P0 | 程 | 已做 |
| O12 | 蒸汽和热气 | 井盖、咖啡壶、呼吸 3 种 | 序列帧 | P1 | 程 | 已做 |
| O13 | 镜头脏污和光晕 | 克制使用 | 遮罩 | P2 | 库 | [SurfaceImperfections004](https://ambientcg.com/view?id=SurfaceImperfections004)（凑合） |
| O14 | 光源眩光 | 钠灯橙、霓虹、警灯红蓝 | 卡片 | P1 | 程 | 已做 |
| O15 | 路灯罩光斑 | 灯光遮罩 | 灯光遮罩 | P0 | 程 | 已做 |
| O16 | 百叶窗条纹光和窗格投影 | 灯光遮罩 | 灯光遮罩 | P1 | 程 | 已做 |
| O17 | 吊扇转影 | 灯光遮罩 | 灯光遮罩 | P2 | 程 | 已做 |
| O18 | 警灯旋转光 | 灯光遮罩 | 灯光遮罩 | P0 | 程 | 已做 |
| O19 | 车大灯光型 | 近光和远光 | 灯光遮罩 | P0 | 程 | 已做 |
| O20 | 手电光斑 | 警用手电和趋光眼各一种 | 灯光遮罩 | P0 | 程 | 已做 |
| O21 | 电视闪烁光 | 颜色和亮度曲线 | 灯光遮罩 | P0 | 程 | 已做 |
| O22 | 日光灯频闪 | 亮度曲线 | 灯光遮罩 | P0 | 程 | 已做 |
| O23 | 火花 | 电瓶接线和短路 | 序列帧 | P0 | 程 | 已做 |
| O24 | 枪口焰 | 手枪、霰弹枪、冲锋枪、滤芯消音器 4 种 | 序列帧 | P0 | 程 | 已做 |
| O25 | 硝烟和短路冒烟 | 序列帧 | 序列帧 | P1 | 程 | 已做 |
| O26 | 命中粒子 | 按材质 8 类：砖、混凝土、木、金属、搪瓷、玻璃、石膏、塑料 | 图集 | P0 | 程 | 已做 |
| O27 | 血雾和黑油雾 | 序列帧 | 序列帧 | P0 | 程 | 已做 |
| O28 | 肉块和碎壳 | 破甲时飞出的 | 图集 | P1 | 画 | 待画 |

## P　第一人称：身体、装备和武器

无 HUD 之后，袖口、双手和身上的伤就是界面。这一组直接决定玩家读不读得懂自己的状态。

| 编号 | 名称 | 说明 | 类型 | 级 | 来源 | 素材 |
| --- | --- | --- | --- | --- | --- | --- |
| P01 | 警服长袖 | 深色涤棉，原创袖章 | 物件 | P0 | 画 | 有初稿 #17 |
| P02 | 袖子血污 | 每条手臂 3 档 | 遮罩 | P0 | 画 | 有初稿 #17 |
| P03 | 袖子雨湿 | 深色渐变 | 遮罩 | P1 | 程 | 已做 |
| P04 | 双手 | 皮肤细节，污垢、血、失血发白的档位 | 物件 | P0 | 画 | 有初稿 #17 |
| P05 | 割裂伤 | 身体检视时可见，3 级 | 贴花 | P0 | 画 | 有初稿 #17 |
| P06 | 挫伤淤青 | 3 级 | 贴花 | P0 | 画 | 有初稿 #17 |
| P07 | 电灼伤 | 树枝状纹路，3 级 | 贴花 | P0 | 画 | 有初稿 #17 |
| P08 | 缠在身上的胶带 | 手臂、腿、躯干 | 物件 | P0 | 画 | 有初稿 #17 |
| P09 | 强力胶封口痕 | 伤口上的亮膜 | 贴花 | P1 | 画 | 有初稿 #27 |
| P10 | 夹板 | 木尺或杂志加胶带 | 物件 | P1 | 画 | 有初稿 #27 |
| P11 | 毛巾包扎 | 渗血 | 物件 | P1 | 画 | 有初稿 #27 |
| P12 | 警裤 | 膝盖的泥、血、撕裂 | 物件 | P0 | 画 | 有初稿 #17 |
| P13 | 警靴 | 泥 | 物件 | P1 | 库 | [Leather028](https://ambientcg.com/view?id=Leather028)（凑合） |
| P14 | 躯干 | 衬衫、警徽、姓名牌（原创） | 物件 | P0 | 画 | 有初稿 #17 |
| P15 | 警用腰带 | 编织纹皮革 | 物件 | P0 | 库 | [brown_leather](https://polyhaven.com/a/brown_leather)（凑合） |
| P16 | 枪套和弹匣套 | 腰带上 | 物件 | P0 | 库 | [Leather028](https://ambientcg.com/view?id=Leather028)（凑合） |
| P17 | 电击枪套和手电套 | 腰带上 | 物件 | P1 | 库 | [Leather028](https://ambientcg.com/view?id=Leather028)（凑合） |
| P18 | 手铐和手铐套 | 腰带上 | 物件 | P2 | 库 | 待找 |
| P19 | 对讲机和肩麦 | 卷线，杂音指示用 | 物件 | P0 | 画 | 有初稿 #18 |
| P20 | 钥匙串 | 逐把增加 | 物件 | P1 | 库 | 待找 |
| P21 | 手表 | 指针夜光 | 发光 | P1 | 画 | 有初稿 #27 |
| P22 | 警用手电 | 铝合金，原创外形 | 物件 | P0 | 画 | 有初稿 #18 |
| P23 | 肩灯 | 抱大件时唯一的光 | 发光 | P1 | 画 | 有初稿 #27 |
| P24 | 勤务包 | 尼龙外层、内衬、拉链、反光条、格层 | 物件 | P0 | 画 | 有初稿 #18 |
| P25 | 手机 | 开局亮屏后黑屏，原创界面 | 发光 | P1 | 画 | 有初稿 #27 |
| P26 | 警用手枪 | 聚合物握把、磷化套筒、原创厂标 | 物件 | P0 | 画 | 有初稿 #18 |
| P27 | 手枪弹匣 | 带观察孔，验弹时看 | 物件 | P0 | 画 | 有初稿 #18 |
| P28 | 夜光准星 | 发光点 | 发光 | P1 | 画 | 有初稿 #27 |
| P29 | 泵动霰弹枪 | 烤蓝磨白的机匣、油浸木托 | 物件 | P0 | 画 | 有初稿 #18 |
| P30 | 霰弹枪背带和侧挂弹夹 | 尼龙 | 物件 | P1 | 库 | [Leather028](https://ambientcg.com/view?id=Leather028)（凑合） |
| P31 | 三种霰弹 | 鹿弹、独头弹、橡胶弹，用弹壳颜色区分 | 物件 | P0 | 画 | 有初稿 #18 |
| P32 | 冲锋枪或步枪 | 机匣、护木、弹匣、背带 | 物件 | P1 | 画 | 有初稿 #27 |
| P33 | 蓝胶带双联弹匣 | 常规改件 | 物件 | P1 | 画 | 有初稿 #27 |
| P34 | 钢管 | 锈、螺纹端、缠手胶带 | 物件 | P0 | 拍 | [Metal053B](https://ambientcg.com/view?id=Metal053B) |
| P35 | 破门锤 | 漆钢和握把 | 物件 | P1 | 画 | 有初稿 #27 |
| P36 | 电击枪 | 黄黑塑料、电极 | 物件 | P0 | 画 | 有初稿 #18 |
| P37 | 电击枪电弧 | 发光 | 序列帧 | P0 | 程 | 已做 |
| P38 | 子弹和弹壳 | 黄铜、底火，抛壳用 | 物件 | P0 | 库 | [Metal057C](https://ambientcg.com/view?id=Metal057C) |
| P39 | 机油滤芯消音器 | 完好和炸裂两种形态 | 物件 | P1 | 画 | 有初稿 #27 |
| P40 | 锯短的枪管和鸭嘴收束器 | 锯痕断口 | 物件 | P1 | 画 | 有初稿 #27 |
| P41 | 绝缘胶带缠绕 | 绑改件用 | 物件 | P0 | 画 | 有初稿 #18 |
| P42 | 趋光眼改件 | 眼球、视神经、胶带 | 物件 | P1 | 画 | 有初稿 #27 |
| P43 | 神经束改件 | 缠在护木上，搏动 | 物件 | P1 | 画 | 有初稿 #27 |
| P44 | 发声腔改件 | 膜和喇叭纸盆长在一起 | 物件 | P1 | 画 | 有初稿 #27 |
| P45 | 压缩机咬合齿改件 | 装在钢管上 | 物件 | P1 | 画 | 有初稿 #27 |
| P46 | 枪身上的肉芽和呼吸囊 | 检视动作里会拍掉 | 物件 | P1 | 画 | 有初稿 #27 |
| P47 | 枪身黑油腐蚀 | 3 档渐进 | 遮罩 | P1 | 画 | 有初稿 #27 |

## Q　界面、菜单和商店页

游戏里没有 HUD，但菜单、可选的辅助图标和 Steam 商店页仍然需要一整套图。

| 编号 | 名称 | 说明 | 类型 | 级 | 来源 | 素材 |
| --- | --- | --- | --- | --- | --- | --- |
| Q01 | 游戏标题标志 | 结尾切黑后弹出的那个 | 图集 | P0 | 画 | 有初稿 #34 |
| Q02 | 主菜单背景 | 雨夜的挡风玻璃和仪表 | 全景 | P0 | 画 | 有初稿 #34 |
| Q03 | 菜单皮肤 | 警用表格和无线电面板的风格 | 图集 | P1 | 画 | 有初稿 #27 |
| Q04 | 中英文字体 | 需要可商用授权 | 图集 | P0 | 库 | [Noto Sans SC](https://fonts.google.com/noto/specimen/Noto+Sans+SC) |
| Q05 | 辅助状态图标 | 可选开启：人形部位、流血、失血、疼痛、弹药 | 图集 | P1 | 画 | 有初稿 #27 |
| Q06 | 键位和手柄图标 | 设置菜单用 | 图集 | P1 | 库 | 待找 |
| Q07 | 加载画面 | 证物照片风格，6 张 | 图集 | P1 | 画 | 有初稿 #27 |
| Q08 | 字幕底板 | 电话和无线电的台词 | 图集 | P0 | 画 | 有初稿 #34 |
| Q09 | 极简准星 | 可选开启的一个点 | 图集 | P1 | 画 | 有初稿 #27 |
| Q10 | 商店胶囊图 | 主图、小图、竖图、页眉、库展示图 | 图集 | P0 | 画 | 有初稿 #34 |
| Q11 | 商店截图 | 至少 8 张 | 图集 | P0 | 程 | 已做 |
| Q12 | 成就图标 | 数量待定 | 图集 | P2 | 画 | 有初稿 #33 |

## R　技术贴图

不直接看得见，但所有材质都要用。全部可以程序生成。

| 编号 | 名称 | 说明 | 类型 | 级 | 来源 | 素材 |
| --- | --- | --- | --- | --- | --- | --- |
| R01 | 微表面细节法线 | 混凝土颗粒、金属拉丝、织物、塑料皮纹、木纹、橡胶，6 种 | 平铺 | P0 | 程 | 已做 |
| R02 | 通用噪声 | 柏林、细胞、蓝噪声 | 遮罩 | P0 | 程 | 已做 |
| R03 | 污垢遮罩 | 边缘磨损、凹处积灰、雨水流向，6 种 | 遮罩 | P0 | 程 | 已做 |
| R04 | 湿润遮罩 | 全局雨湿 | 遮罩 | P0 | 程 | 已做 |
| R05 | 材质混合高度图 | 砖和灰泥、柏油和泥之间的过渡 | 遮罩 | P1 | 程 | 已做 |
| R06 | 水洼法线 | 静水微波 | 平铺 | P0 | 程 | 已做 |
| R07 | 玻璃折射法线 | 老玻璃的不平整 | 平铺 | P1 | 程 | 已做 |
| R08 | 调色查找表 | 每个氛围预设一张 | 遮罩 | P1 | 程 | 已做 |
| R09 | 胶片颗粒 | 克制使用 | 遮罩 | P2 | 程 | 已做 |

## 最适合你用手机实拍的东西

下面这些不分国家、哪里都拍得到，而且自己拍的比素材库的更有“这是个真地方”的感觉。按回报从高到低：

| 拍什么 | 对应编号 |
| --- | --- |
| 旧柏油路面，以及上面的裂缝、封缝条、补丁 | A01 到 A06 |
| 水泥人行道板、掉角的路缘石 | A15、A16、A18 |
| 风化的红砖墙，最好有泛白和发黑的两种 | C01 到 C03 |
| 漆面剥落的木板、木门 | C14、C59、M10 |
| 油浸的水泥地（修车铺、停车场） | D35、E05、G33 |
| 墙角和天花的霉斑、水渍圈 | D17、M04、M05 |
| 铁器上往下流的锈痕、锈穿的孔 | M07、M08 |
| 墙上往下流的水渍、窗台下的流痕 | M01、M02 |
| 各种裂缝 | M13、M14 |
| 脏玻璃（雨痕、手印、胶带印） | C63 |
| 旧冰箱、旧洗衣机的搪瓷表面，尤其是崩瓷生锈的边角 | K01、K41 |
| 发黄的旧塑料外壳（老电器、老开关面板） | K04、D46 |
| 生锈的铁皮、波纹板 | C22 |
| 发黑的瓷砖缝 | D34 |
| 旧木地板、磨白的走道 | D29、D30 |

## 修订记录

| 日期 | 改动 |
| --- | --- |
| 2026-11-11 | 第一版，785 项 |
| 2026-11-12 | 加"现状"一段：程序生成的 62 项完成 |
| 2026-11-12 | 每张表加了“素材”一列；核对用户的三份素材表后，111 项有了素材，其余标明待找、待画或已做 |
| 2026-11-12 | "画"类的 296 项标为"有初稿"，指向分支 `xiaoyu/atlases-20261111` 里的 34 张图集 |
| 2026-11-12 | 联网补全：有素材的项从 111 增加到 286，素材全部下载进 `textures/`；剩 114 项待找 |
| 2026-11-12 | 新增 28 项程序生成的贴花（原来 21 项待找、7 项待画），在 `textures/procedural/` |
