#!/usr/bin/env python3
"""Draws the plan sketch of the hand-built block: the four places you can
enter, the key chain through them, the loops and shortcuts, what shuts the
edges. Everything is in metres (x east, y south); change the numbers here
and run it again.

  python3 art/block/draw_plan.py            writes art/block/plan_sketch.jpg

Needs Pillow and the font in textures/fonts.
"""
import math
import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FONT = os.path.join(ROOT, "textures", "fonts", "NotoSansSC-Variable.ttf")
S = 15                      # pixels per metre
W_M, H_M = 80, 120          # the block
PAD = 40
LEGEND = 900
img = Image.new("RGB", (W_M * S + PAD * 2, H_M * S + PAD * 2 + 70 + LEGEND), (16, 18, 16))
d = ImageDraw.Draw(img, "RGBA")
OY = PAD + 70


def font(size):
	return ImageFont.truetype(FONT, size)


def P(x, y):
	return (PAD + x * S, OY + y * S)


def rect(x0, y0, x1, y1, fill, outline=None, width=2):
	d.rectangle([P(x0, y0), P(x1, y1)], fill=fill, outline=outline, width=width)


def poly(pts, fill, outline=None, width=2):
	d.polygon([P(*p) for p in pts], fill=fill, outline=outline)
	if outline and width > 1:
		d.line([P(*p) for p in pts + [pts[0]]], fill=outline, width=width)


def label(x, y, text, size=22, fill=(240, 240, 235), anchor="mm", bg=None):
	f = font(size)
	px, py = P(x, y)
	if bg:
		box = d.multiline_textbbox((px, py), text, font=f, anchor=anchor, align="center")
		d.rectangle([box[0] - 4, box[1] - 2, box[2] + 4, box[3] + 3], fill=bg)
	d.multiline_text((px, py), text, font=f, fill=fill, anchor=anchor, align="center", spacing=2)


def dashed(pts, fill, width=4, dash=14, gap=9):
	for (ax, ay), (bx, by) in zip(pts, pts[1:]):
		a, b = P(ax, ay), P(bx, by)
		length = math.hypot(b[0] - a[0], b[1] - a[1])
		n = max(1, int(length // (dash + gap)))
		for i in range(n + 1):
			t0 = i * (dash + gap) / length
			t1 = min(1.0, t0 + dash / length)
			if t0 >= 1.0:
				break
			d.line([(a[0] + (b[0] - a[0]) * t0, a[1] + (b[1] - a[1]) * t0), (a[0] + (b[0] - a[0]) * t1, a[1] + (b[1] - a[1]) * t1)], fill=fill, width=width)


def badge(x, y, text, fill, r=15, ink=(10, 10, 10)):
	px, py = P(x, y)
	d.ellipse([px - r, py - r, px + r, py + r], fill=fill, outline=(0, 0, 0), width=2)
	d.text((px, py - 1), text, font=font(int(r * 1.25)), fill=ink, anchor="mm")


WOODS = (26, 36, 26)
ASPHALT = (58, 58, 62)
WALK = (112, 112, 108)
CONCRETE = (92, 92, 88)
YARD = (52, 64, 44)
ROOM = (150, 140, 120)
WALL = (15, 15, 15)
BACKDROP = (40, 42, 46)
ROUTE = (255, 208, 60)
SHORT = (80, 220, 230)
LOCK = (235, 70, 60)
BEAST = (190, 110, 240)
LIGHT = (255, 170, 80)

d.text((PAD, 14), "凌晨三点公路镇 · 箱庭街区平面草图（80 × 120 米，上北）", font=font(34), fill=(245, 245, 240))
d.text((PAD, 60), "只定布局、动线和钥匙链；房间内部到做每一栋时再细化", font=font(22), fill=(170, 170, 165))

rect(0, 0, W_M, H_M, WOODS)

# --- streets: a Z, so nothing runs longer than about 40 m in view ----------------------
rect(45, 0, 56, 51, WALK)
rect(47, 0, 54, 51, ASPHALT)                                    # first leg, north-south
rect(25, 51, 56, 61.5, WALK)
rect(27, 52.5, 56, 60, ASPHALT)                                 # the corner and the short leg west
poly([(25, 60), (36, 60), (36, 84), (39, 90), (39, 113), (28, 113), (28, 90), (25, 84)], WALK)
poly([(27, 60), (34, 60), (34, 84), (37, 90), (37, 113), (30, 113), (30, 90), (27, 84)], ASPHALT)   # third leg, with a kink

# --- gas station: the corner lot, two ways in ------------------------------------------
rect(6, 6, 45, 51, CONCRETE)
rect(3, 8, 8, 51, (70, 70, 66))                                 # back alley, west of the store
rect(8, 12, 21, 34, ROOM, WALL, 4)                              # store
rect(8, 28, 14, 34, (170, 150, 110), WALL, 3)                   # staff room
rect(26, 15, 41, 30, None, (230, 230, 220), 3)                  # canopy
for y in (19, 26):
	rect(29, y - 0.6, 38, y + 0.6, (200, 200, 190))
rect(31, 21.3, 36, 23.6, (40, 50, 90), (220, 220, 230), 2)      # the patrol car
label(14.5, 20, "便利店", 22, (20, 20, 20))
label(11, 32.7, "员工室", 16, (20, 20, 20))
label(33.5, 13.3, "加油棚", 20)
label(5.5, 43, "后\n巷", 18)
label(25, 42, "北线加油站\n（起点 / 安全屋）", 26, bg=(0, 0, 0, 150))
d.regular_polygon((P(43, 48.5)[0], P(43, 48.5)[1], 16), 5, fill=LIGHT, outline=(0, 0, 0))
label(43, 45.6, "高杆价格牌", 17, LIGHT, bg=(0, 0, 0, 170))

# --- Miller & Sons ----------------------------------------------------------------------
rect(56, 6, 78, 50, CONCRETE)
rect(57, 10, 71, 27, ROOM, WALL, 4)                             # front shop
rect(57, 27, 65, 33, (170, 150, 110), WALL, 3)                  # storeroom
rect(57, 33, 77, 49, (128, 124, 116), WALL, 4)                  # workshop
rect(66, 37, 68.4, 46, (20, 20, 20))                            # pit
rect(71, 10, 77, 33, YARD)                                      # yard
label(64, 18.5, "五金店\n（高货架）", 21, (20, 20, 20))
label(61, 30, "库房", 17, (20, 20, 20))
label(62, 41, "汽修车间", 21, (20, 20, 20))
label(71.6, 41.5, "地沟", 16)
label(74, 21, "废\n车\n场", 17)
label(67, 7.6, "米勒父子五金与汽修行", 22, bg=(0, 0, 0, 150))

# --- service court and the lane behind the brick row -------------------------------------
rect(56, 50, 78, 61.5, (70, 70, 66))
rect(70, 61.5, 78, 88, (70, 70, 66))
rect(37, 82, 78, 88, (70, 70, 66))
label(67, 55.6, "卸货院", 20)
label(52, 86.5, "后巷", 18)

# --- the brick row: tavern on the corner, laundromat ------------------------------------
rect(37, 62, 53, 82, (150, 96, 80), WALL, 4)
rect(53, 62, 70, 82, (160, 160, 150), WALL, 4)
label(45, 72, "锈锚酒馆\n（U 型吧台）", 22, (20, 20, 20))
label(61.5, 72, "投币洗衣房", 22, (20, 20, 20))

# --- houses ---------------------------------------------------------------------------------
rect(6, 62, 25, 78, YARD)
rect(10, 64.5, 22, 75, BACKDROP, (90, 90, 96), 2)
label(16, 69.7, "邻居家（背景）\n窗里电视闪雪花", 16, (190, 190, 195))
rect(4, 80, 28, 111, YARD)
rect(8, 84, 21, 97, ROOM, WALL, 4)
rect(12, 99, 21, 107, (128, 124, 116), WALL, 4)
rect(21, 100.5, 28, 105.5, CONCRETE)
label(14.5, 88.4, "克拉克家\n（两层，二楼巢穴）", 20, (20, 20, 20))
label(16.5, 105.2, "车库", 18, (20, 20, 20))
for x0, x1 in ((41, 55), (58, 74)):
	rect(x0 - 2, 91, x1 + 2, 112, YARD)
	rect(x0, 94, x1, 106, BACKDROP, (90, 90, 96), 2)
label(48, 100, "民宅（背景）", 16, (190, 190, 195))
label(66, 100, "民宅（背景）", 16, (190, 190, 195))
rect(1.5, 51, 5.5, 80, (60, 52, 40))                             # the footpath behind the neighbour's
label(4.4, 66, "林\n间\n小\n路", 16, (220, 210, 190))

# --- what shuts the edges ---------------------------------------------------------------------
for i in range(12):
	d.rectangle([P(44, -1 + i * 0.5), P(57, -0.5 + i * 0.5)], fill=(200, 205, 210, 20 + i * 14))
rect(44, 0, 57, 2.2, (200, 205, 210, 170))
label(50.5, 3.4, "北：匝道，被浓雾吞掉", 20, (20, 20, 20), bg=(215, 220, 225, 230))
poly([(24.5, 108.5), (40.5, 111.5), (40, 115), (24, 112)], (120, 90, 60), (0, 0, 0))
for i in range(5):
	d.line([P(23 + i * 3.6, 116.5 - i * 0.2), P(30 + i * 3.2, 118.2 - i * 0.5)], fill=(150, 110, 70), width=7)
label(33, 117.6, "南：运木半挂车翻倒", 20, bg=(0, 0, 0, 170))
dashed([(79, 5), (79, 115)], (160, 160, 160), 3)
label(77.4, 70, "东\n铁\n丝\n网\n+\n深\n沟", 16, (190, 190, 190))
label(1.3, 25, "西\n密\n林", 16, (150, 180, 150))
d.line([P(56, 51), P(56, 61.5)], fill=LOCK, width=7)
label(63.5, 59.6, "铁门（里面上锁）", 16, LOCK, bg=(0, 0, 0, 190))

# --- things in the street that stop the eye --------------------------------------------------
poly([(48.5, 31), (52.8, 33), (51.8, 36.5), (47.5, 34.5)], (90, 60, 50), (0, 0, 0))
label(50.6, 38.6, "抛锚的皮卡", 15, bg=(0, 0, 0, 170))
poly([(27.5, 78.5), (35.5, 76), (36.3, 79), (28.3, 81.5)], (175, 175, 170), (0, 0, 0))
label(31.5, 74.2, "横在路上的\n送货车", 15, bg=(0, 0, 0, 170))

# --- lamps and what lives here ----------------------------------------------------------------
for x, y in ((46, 9), (55, 26), (46, 43), (40, 51.6), (26, 66), (35.2, 95), (29, 104)):
	px, py = P(x, y)
	d.ellipse([px - 7, py - 7, px + 7, py + 7], fill=LIGHT, outline=(0, 0, 0))
for x, y, t in ((5.5, 21, "售"), (60, 14, "钻"), (67.2, 44.5, "箱"), (62, 78, "洗"), (41, 66, "点"), (9.6, 85.5, "巢"), (61, 86.4, "栓"),
		(55.2, 49.6, "话"), (25.9, 92, "婴")):
	badge(x, y, t, BEAST, 13, (255, 255, 255))

# --- the way through: numbered stops, one stroke per errand ---------------------------------------
for leg in (
		[(33.5, 24.8), (22, 24.8), (12, 30), (8.2, 30), (5.5, 30), (5.5, 23)],                    # car, staff room, alley
		[(36.2, 24.8), (45, 24.8), (50.5, 22), (57.2, 19), (64, 22.6)],                          # across to Miller's
		[(50.5, 22), (50.5, 56), (41, 58), (39.4, 63), (45, 67)],                                # down to the tavern
		[(57.2, 19), (61, 27), (61, 33), (67.2, 40.5)],                                          # storeroom, pit
		[(67.2, 40.5), (62, 49), (62, 56), (56.6, 56), (41, 58), (30.5, 61), (30.5, 87), (24.5, 91.5), (21, 91.5), (11, 94.6),
			(16.5, 98.2), (16.5, 101.2)]):                                                    # out by the shortcut, to the Clarks'
	d.line([P(*q) for q in leg], fill=ROUTE + (170,), width=4, joint="curve")
for (x, y), n in (((33.5, 24.8), "1"), ((12.6, 29.7), "2"), ((30, 22.4), "3"), ((5.5, 26), "4"), ((64, 23.2), "5"), ((45, 66.4), "6"),
		((67.2, 39.6), "7"), ((10.8, 94.6), "8"), ((16.5, 101.2), "9")):
	badge(x, y, n, ROUTE, 15)

# --- shortcuts opened from inside ---------------------------------------------------------------
for pts, name, at in (
		([(62, 49), (62, 52)], "A", (60, 51.2)),
		([(56, 54), (56, 58)], "B", (57.8, 58.6)),
		([(45, 82), (45, 84), (70, 84), (74, 70), (74, 58)], "C", (43, 84.6)),
		([(8, 31), (5.5, 31)], "D", (7, 33.6)),
		([(6, 80.5), (2.5, 78), (2.5, 52), (5.5, 49)], "E", (6.6, 81.6))):
	dashed(pts, SHORT, 5)
	badge(at[0], at[1], name, SHORT, 14)

# --- legend ----------------------------------------------------------------------------------------
LY = OY + H_M * S + 26
x0 = PAD
f, fb = font(23), font(26)
d.text((x0, LY), "钥匙链（黄线和数字）", font=fb, fill=ROUTE)
steps = ["1  警车抛锚在加油棚下，这里是起点，也是全程的家",
		"2  员工室：纸质地图 + 后备箱钥匙",
		"3  回车上开后备箱：霰弹枪",
		"4  后巷：捡水管，第一战（走掉的售货机）",
		"5  米勒五金：前店能进，库房锁着",
		"6  锈锚酒馆：Lou 身上的库房备用钥匙",
		"7  回五金店开库房 → 车间地沟：皮带 + 千斤顶（红色工具箱是拟态怪）",
		"8  克拉克家二楼衣帽间：冲锋枪 + 车库钥匙",
		"9  车库：满电蓄电池。拉卷帘门的巨响唤醒巢穴和全街路灯",
		"→  抱着电瓶回加油站（走大街，或走捷径 E）→ 修车 → 结尾"]
for i, t in enumerate(steps):
	d.text((x0, LY + 40 + i * 33), t, font=f, fill=(235, 235, 230))
y1 = LY + 40 + len(steps) * 33 + 18
d.text((x0, y1), "捷径（青色虚线，都是从里面打开，开了之后跑图越来越短）", font=fb, fill=SHORT)
cuts = ["A  汽修车间卷帘门 → 卸货院", "B  卸货院铁门 → 街角（红线，一开始从街上进不去）",
		"C  酒馆后门 → 后巷 → 卸货院（洗衣房后门也通后巷）", "D  员工室后门 → 加油站后巷",
		"E  克拉克家后院门 → 林间小路 → 加油站后巷（终局的另一条路：近，但又黑又窄）"]
for i, t in enumerate(cuts):
	d.text((x0, y1 + 40 + i * 33), t, font=f, fill=(235, 235, 230))
y2 = y1 + 40 + len(cuts) * 33 + 18
d.text((x0, y2), "其他", font=fb, fill=(235, 235, 230))
px, py = x0 + 12, y2 + 56
d.ellipse([px - 9, py - 9, px + 9, py + 9], fill=LIGHT)
d.text((x0 + 32, y2 + 40), "路灯（终局全部变成路灯怪）；五角星是加油站的高杆牌，在街区大部分地方越过屋顶都看得见", font=f, fill=(235, 235, 230))
d.ellipse([px - 11, py + 22, px + 11, py + 44], fill=BEAST)
d.text((x0 + 32, y2 + 73), "怪：售货机、钻（货架顶的小电钻）、箱（拟态工具箱）、洗（混在洗衣机里的真怪）、点（点唱机）、", font=f, fill=(235, 235, 230))
d.text((x0 + 32, y2 + 106), "巢（二楼冰箱巢穴）、栓（暗巷里吃小怪的消防栓）、话（自己摘听筒的电话亭）、婴（自己走的婴儿车）", font=f, fill=(235, 235, 230))
d.text((x0, y2 + 148), "街道是 Z 字形，三段各四五十米，加上抛锚的车和浓雾，哪里都望不到头。深灰色的两栋民宅和邻居家只做外观，进不去。", font=font(21), fill=(170, 170, 165))

out = os.path.join(ROOT, "art", "block", "plan_sketch.jpg")
img.save(out, quality=88)
print(out, img.size)
