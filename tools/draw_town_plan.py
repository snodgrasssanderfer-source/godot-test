#!/usr/bin/env python3
"""Draws floor plans of the greybox town from the engine's own data.

The town is built in code (fps/world/town/town_layout.gd). The scene can write
every box and marker it placed to a JSON file; this script turns that file into
labelled plans, so the layout can be judged without opening the engine:

    godot --headless --path fps res://world/town/town.tscn -- --dump=/tmp/town.json
    python3 tools/draw_town_plan.py /tmp/town.json art/town

Output: an overview of the whole map (north up) and one plan per building and
storey, drawn square to the building with the street at the bottom. A plan is a
horizontal cut 1.25 m above the floor: what the cut passes through is drawn
solid (walls, glass), what is lower is drawn as furniture or floor, what is
higher (roofs, ceilings) is left out. Markers are numbered, with a legend.

Needs Pillow. Chinese labels use textures/fonts/NotoSansSC-Variable.ttf.
"""
import json
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT_PATH = os.path.join(ROOT, "textures", "fonts", "NotoSansSC-Variable.ttf")
CUT = 1.25

# tag -> (fill when cut through, fill when below the cut)
BACKGROUND = (38, 44, 36)
CUT_COLOR = {
    "glass": (120, 190, 220), "door": (150, 95, 50), "fence": (150, 150, 150), "shutter": (170, 170, 175),
    "pole": (90, 60, 35), "bark": (30, 28, 24), "log": (110, 70, 40), "car": (40, 50, 80),
    "car_white": (200, 200, 205), "appliance": (215, 212, 200), "shelf": (150, 135, 110), "metal": (95, 100, 105),
    "wood": (120, 90, 60), "furniture": (120, 90, 68), "counter": (160, 95, 60),
}
WALL = (22, 22, 24)
FLOOR_COLOR = {
    "asphalt": (62, 62, 66), "sidewalk": (128, 128, 124), "concrete": (104, 104, 100), "grass": (58, 78, 50),
    "floor": (150, 122, 88), "floor_tile": (168, 168, 160), "oil": (16, 16, 18), "wood": (132, 98, 62),
    "road_paint": (190, 180, 120),
}
LOW_COLOR = {
    "appliance": (228, 226, 214), "shelf": (176, 158, 126), "counter": (176, 104, 66), "furniture": (132, 98, 72),
    "metal": (120, 126, 132), "car": (52, 62, 98), "car_white": (206, 206, 210), "concrete": (140, 140, 136),
    "wood": (150, 112, 72), "oil": (30, 30, 34), "log": (120, 78, 44), "sign": (170, 50, 44),
    "wall_in": (70, 66, 60), "fence": (150, 150, 150), "brick": (120, 52, 40),
}


def font(size):
    f = ImageFont.truetype(FONT_PATH, size)
    try:
        f.set_variation_by_axes([500])
    except Exception:
        pass
    return f


class Frame:
    """Maps world XZ to image pixels. `yaw`/`origin` given: draw in that lot's
    own frame with the street at the bottom; otherwise north up."""

    def __init__(self, x0, z0, x1, z1, scale, origin=None, yaw=0.0, margin=(0, 0)):
        self.x0, self.z0, self.x1, self.z1, self.scale = x0, z0, x1, z1, scale
        self.origin, self.yaw, self.margin = origin, yaw, margin
        self.size = (int((x1 - x0) * scale) + margin[0], int((z1 - z0) * scale) + margin[1])

    def local(self, x, z):
        if self.origin is None:
            return x, z
        dx, dz = x - self.origin[0], z - self.origin[2]
        c, s = math.cos(self.yaw), math.sin(self.yaw)
        return dx * c - dz * s, dx * s + dz * c

    def px(self, x, z):
        lx, lz = self.local(x, z)
        if self.origin is None:
            return (lx - self.x0) * self.scale, (lz - self.z0) * self.scale
        # facing the lot from the street its +X runs to the left, +Z away from you
        return (self.x1 - lx) * self.scale, (self.z1 - lz) * self.scale


def classify(box, floor):
    """'cut', 'low', 'floor' or None for a box against a cut above `floor`."""
    cut = floor + CUT
    if box["y0"] > cut:
        return None
    if box["y1"] >= cut:
        return "cut"
    if box["y1"] <= floor + 0.3:
        return "floor"
    return "low"


def draw_plan(data, frame, level_of, title, path, only_lot=None, marker_lots=None, compass_yaw=None):
    """level_of(lot) -> (level index, floor height) to draw for that lot, or None to skip it."""
    img = Image.new("RGB", frame.size, BACKGROUND)
    overlay = Image.new("RGBA", frame.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    od = ImageDraw.Draw(overlay)
    layers = {"floor": [], "low": [], "cut": []}
    for lot in data["lots"]:
        chosen = level_of(lot)
        if chosen is None:
            continue
        level, floor = chosen
        for box in lot["boxes"]:
            if not box["upright"] or not box["visible"] or box["tag"] == "terrain":
                continue
            if box["level"] != level and not (level == 0 and box["level"] == 0):
                continue
            kind = classify(box, floor)
            if kind is None:
                if box["tag"] == "canopy":
                    layers["cut"].append((box, "canopy"))
                continue
            layers[kind].append((box, kind))
    for kind in ("floor", "low", "cut"):
        for box, how in sorted(layers[kind], key=lambda b: b[0]["y1"]):
            poly = [frame.px(x, z) for x, z in box["foot"]]
            tag = box["tag"]
            if how == "floor":
                d.polygon(poly, fill=FLOOR_COLOR.get(tag, (110, 110, 106)))
            elif how == "low":
                od.polygon(poly, fill=LOW_COLOR.get(tag, (150, 150, 150)) + (215,), outline=(20, 20, 20, 255))
            elif how == "canopy":
                od.line(poly + [poly[0]], fill=(235, 235, 220, 200), width=max(int(frame.scale / 12), 1))
            else:
                od.polygon(poly, fill=CUT_COLOR.get(tag, WALL) + (255,))
    img = Image.alpha_composite(img.convert("RGBA"), overlay).convert("RGB")
    d = ImageDraw.Draw(img)

    # markers: numbered dots, legend underneath
    legend = []
    small = font(max(int(frame.scale * 0.42), 15))
    for lot in data["lots"]:
        if marker_lots is not None and lot["name"] not in marker_lots:
            continue
        chosen = level_of(lot)
        if chosen is None:
            continue
        for m in lot["markers"]:
            if m["level"] != chosen[0]:
                continue
            x, y = frame.px(m["at"][0], m["at"][2])
            if not (0 <= x < frame.size[0] - frame.margin[0] and 0 <= y < frame.size[1] - frame.margin[1]):
                continue
            legend.append(m["label_zh"])
            n = str(len(legend))
            r = max(int(frame.scale * 0.34), 11)
            d.ellipse((x - r, y - r, x + r, y + r), fill=(250, 214, 60), outline=(0, 0, 0))
            box = d.textbbox((0, 0), n, font=small)
            d.text((x - (box[2] - box[0]) / 2 - box[0], y - (box[3] - box[1]) / 2 - box[1]), n, font=small, fill=(0, 0, 0))

    # title, scale bar, north arrow
    big = font(max(int(frame.size[0] / 42), 22))
    d.rectangle((0, 0, frame.size[0], big.size * 1.7), fill=(0, 0, 0))
    d.text((14, big.size * 0.25), title, font=big, fill=(255, 255, 255))
    bar_m = 10 if frame.scale < 20 else 5
    bx, by = 20, frame.size[1] - frame.margin[1] - 34
    d.rectangle((bx - 8, by - 26, bx + bar_m * frame.scale + 8, by + 14), fill=(0, 0, 0))
    d.line((bx, by, bx + bar_m * frame.scale, by), fill=(255, 255, 255), width=4)
    d.text((bx, by - 26), "%d 米" % bar_m, font=font(18), fill=(255, 255, 255))
    # north: world -Z; in a lot frame it is turned by the lot's yaw
    ax, ay = frame.size[0] - 70, big.size * 1.7 + 70
    o = frame.px(0, 0)
    n = frame.px(0, -1)
    vx, vy = n[0] - o[0], n[1] - o[1]
    length = math.hypot(vx, vy) or 1.0
    vx, vy = vx / length * 40, vy / length * 40
    d.ellipse((ax - 52, ay - 52, ax + 52, ay + 52), fill=(0, 0, 0))
    d.line((ax - vx, ay - vy, ax + vx, ay + vy), fill=(255, 255, 255), width=4)
    d.polygon([(ax + vx, ay + vy), (ax + vx * 0.5 - vy * 0.3, ay + vy * 0.5 + vx * 0.3),
               (ax + vx * 0.5 + vy * 0.3, ay + vy * 0.5 - vx * 0.3)], fill=(255, 90, 80))
    d.text((ax + vx * 1.05 - 8, ay + vy * 1.05 - 12), "北", font=font(20), fill=(255, 255, 255))

    if legend:
        f = font(max(int(frame.size[0] / 58), 20))
        line = int(f.size * 1.45)
        cols = 2 if frame.size[0] >= 1100 else 1
        rows = math.ceil(len(legend) / cols)
        out = Image.new("RGB", (frame.size[0], frame.size[1] + rows * line + 24), (0, 0, 0))
        out.paste(img, (0, 0))
        ld = ImageDraw.Draw(out)
        for i, text in enumerate(legend):
            cx = 16 + (i // rows) * (frame.size[0] // cols)
            cy = frame.size[1] + 12 + (i % rows) * line
            ld.text((cx, cy), "%d  %s" % (i + 1, text), font=f, fill=(250, 214, 60))
        img = out
    img.save(path, quality=90)
    print("PLAN", path, img.size)


def main():
    src, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    data = json.load(open(src))
    lots = {lot["name"]: lot for lot in data["lots"]}

    def ground(lot):
        return 0, lot["floors"][0]

    # Whole map, north up. Only the main markers, or the numbers pile up.
    main_lots = ["gas_station", "miller", "brick_row", "clark", "edges"]
    overview = Frame(-78.0, -120.0, 96.0, 122.0, 9.0)
    draw_plan(data, overview, ground, "凌晨三点公路镇 · 灰盒总平面（250 × 250 米，上北）",
              os.path.join(out_dir, "plan_overview.jpg"), marker_lots=["edges", "street"])

    def lot_plan(name, x0, z0, x1, z1, scale, level, title, filename):
        lot = lots[name]

        def choose(other):
            if other["name"] == name:
                if level >= len(lot["floors"]):
                    return None
                return level, lot["floors"][level]
            return (0, other["floors"][0]) if level == 0 else None

        frame = Frame(x0, z0, x1, z1, scale, origin=lot["origin"], yaw=lot["yaw"])
        draw_plan(data, frame, choose, title, os.path.join(out_dir, filename), marker_lots=[name])

    lot_plan("gas_station", -1.0, -10.0, 47.0, 41.0, 34.0, 0, "① 北线加油站 & 夜班便利店（起点 / 安全屋）· 街道在下方", "plan_1_gas_station.jpg")
    lot_plan("miller", -1.0, -10.0, 31.0, 35.0, 44.0, 0, "② 米勒父子五金与汽修行 · 一层 · 街道在下方", "plan_2_miller_ground.jpg")
    lot_plan("miller", 0.0, 13.5, 16.0, 32.0, 70.0, 1, "② 米勒汽修行 · 账房阁楼（离地 2.7 米）", "plan_2_miller_loft.jpg")
    lot_plan("brick_row", 18.5, -10.0, 46.5, 21.0, 50.0, 0, "③ 洗衣房（右）& 锈锚小酒馆（左，街角）· 街道在下方", "plan_3_tavern_laundromat.jpg")
    lot_plan("clark", -1.0, -10.0, 29.0, 24.0, 46.0, 0, "④ 克拉克家 · 一层和车库 · 街道在下方", "plan_4_clark_ground.jpg")
    lot_plan("clark", 3.0, 6.0, 15.0, 17.0, 110.0, 1, "④ 克拉克家 · 二层（巢穴）", "plan_4_clark_upper.jpg")


if __name__ == "__main__":
    main()
