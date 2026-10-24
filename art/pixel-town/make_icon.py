"""Draws the 32x32 pixel-art app icon (a pink radish on a sunny field) and
scales it up with nearest-neighbour. Run: python3 art/pixel-town/make_icon.py
Writes pixel-town/icon.png (192x192). Needs Pillow."""
import os

from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "pixel-town", "icon.png")
img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rounded_rectangle((0, 0, 31, 31), radius=6, fill="#9fd4f5")       # sky
d.ellipse((-8, 20, 40, 44), fill="#8fcf6f")                        # hill
d.ellipse((22, 3, 28, 9), fill="#ffe27a")                          # sun
d.rectangle((3, 6, 10, 8), fill="#ffffff")                         # cloud
d.rectangle((5, 5, 8, 5), fill="#ffffff")
for x0, y0, x1, y1, c in [(14, 5, 16, 14, "#5aa845"), (11, 7, 13, 14, "#6fbf55"),
                          (17, 7, 19, 14, "#6fbf55"), (9, 10, 11, 14, "#5aa845"),
                          (19, 10, 21, 14, "#5aa845")]:
    d.rectangle((x0, y0, x1, y1), fill=c)                          # leaves
d.ellipse((9, 13, 21, 25), fill="#f585a0")                         # radish
d.polygon([(12, 23), (18, 23), (15, 29)], fill="#f585a0")
d.rectangle((11, 15, 12, 17), fill="#ffc2d0")                      # shine
d.rectangle((13, 25, 16, 25), fill="#d9667f")
img.resize((192, 192), Image.NEAREST).save(OUT)
print("Icon saved:", os.path.abspath(OUT))
