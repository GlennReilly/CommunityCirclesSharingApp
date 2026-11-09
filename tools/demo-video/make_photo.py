"""Draws a small JPEG of an aluminium extension ladder leaning on a brick wall (demo item photo)."""
from PIL import Image, ImageDraw, ImageFilter
import random, sys
W, H = 1200, 900
im = Image.new('RGB', (W, H), (176, 98, 72)); d = ImageDraw.Draw(im); random.seed(3)
for row in range(0, 640, 40):
    off = 0 if (row // 40) % 2 == 0 else 45
    for x in range(-90 + off, W, 90):
        c = random.randint(-14, 14)
        d.rectangle([x + 3, row + 3, x + 87, row + 37], fill=(168 + c, 88 + c, 64 + c))
d.rectangle([0, 640, W, H], fill=(112, 128, 92))  # lawn
for _ in range(1500):
    x, y = random.randint(0, W), random.randint(645, H); d.line([x, y, x + random.randint(-3, 3), y - 9], fill=(84, 110, 70))
d.rectangle([0, 630, W, 645], fill=(150, 150, 140))  # path edge
# ladder: two rails from the lawn up to the wall top, slight lean
def lerp(a, b, t): return a + (b - a) * t
L0, L1 = (430, 860), (560, 40); R0, R1 = (650, 860), (760, 40)
shadow = Image.new('L', (W, H), 0); sd = ImageDraw.Draw(shadow)
sd.polygon([(L0[0] + 40, 860), (R0[0] + 60, 860), (R1[0] + 70, 60), (L1[0] + 50, 60)], fill=90)
im.paste((60, 30, 20), mask=shadow.filter(ImageFilter.GaussianBlur(18)))
for i in range(1, 15):
    t = i / 15
    a = (lerp(L0[0], L1[0], t), lerp(L0[1], L1[1], t)); b = (lerp(R0[0], R1[0], t), lerp(R0[1], R1[1], t))
    d.line([a, b], fill=(120, 124, 130), width=16); d.line([a[0], a[1] - 4, b[0], b[1] - 4], fill=(205, 208, 214), width=6)
for p0, p1 in ((L0, L1), (R0, R1)):
    d.line([p0, p1], fill=(150, 154, 160), width=30); d.line([(p0[0] - 6, p0[1]), (p1[0] - 6, p1[1])], fill=(222, 225, 230), width=10)
for p in (L0, R0): d.rectangle([p[0] - 22, p[1] - 10, p[0] + 22, p[1] + 14], fill=(30, 30, 30))  # rubber feet
im = im.filter(ImageFilter.GaussianBlur(0.6))
im.save(sys.argv[1], 'JPEG', quality=82)
