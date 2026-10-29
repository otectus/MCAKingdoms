#!/usr/bin/env python3
"""Draws the Book's text-free diagrams (H10) into assets/mcakingdoms/textures/gui/guide/. Every word a diagram needs lives
in its topic's alt text and caption, so each language reads it in its own words. Run: python3 -I tools/art/guide_diagrams.py <repo>"""
import pathlib, sys
from PIL import Image, ImageDraw

repo = pathlib.Path(sys.argv[1])
out = repo / 'src/main/resources/assets/mcakingdoms/textures/gui/guide'
out.mkdir(parents=True, exist_ok=True)
W, H, S = 192, 96, 4          # drawn at 4x, then reduced for clean edges
INK, RED, BLUE, GREEN, GOLD, PAPER, FOG = (59, 42, 30, 255), (140, 59, 46, 255), (62, 92, 118, 255), (78, 107, 58, 255), (184, 137, 46, 255), (232, 222, 196, 255), (120, 104, 84, 110)

def canvas():
    img = Image.new('RGBA', (W * S, H * S), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)

def p(*xy): return [v * S for v in xy]

def arrow(d, x0, y0, x1, y1, colour=INK):
    d.line(p(x0, y0, x1, y1), fill=colour, width=2 * S)
    import math
    a = math.atan2(y1 - y0, x1 - x0)
    for side in (2.6, -2.6):
        d.line(p(x1, y1, x1 - 6 * math.cos(a + side / 6), y1 - 6 * math.sin(a + side / 6)), fill=colour, width=2 * S)

def house(d, x, y, colour=INK, fill=PAPER):
    d.polygon(p(x - 12, y, x, y - 12, x + 12, y), fill=fill, outline=colour, width=2 * S)
    d.rectangle(p(x - 9, y, x + 9, y + 12), fill=fill, outline=colour, width=2 * S)
    d.rectangle(p(x - 3, y + 5, x + 3, y + 12), fill=colour)

def banner(d, x, y, colour):
    d.line(p(x, y - 16, x, y + 18), fill=INK, width=2 * S)
    d.line(p(x - 10, y - 16, x + 10, y - 16), fill=INK, width=2 * S)
    d.polygon(p(x - 9, y - 15, x + 9, y - 15, x + 9, y + 8, x, y + 3, x - 9, y + 8), fill=colour, outline=INK, width=S)

def crown(d, x, y):
    d.polygon(p(x - 13, y + 8, x - 13, y - 6, x - 6, y + 1, x, y - 9, x + 6, y + 1, x + 13, y - 6, x + 13, y + 8), fill=GOLD, outline=INK, width=2 * S)

def person(d, x, y, colour=INK):
    d.ellipse(p(x - 4, y - 12, x + 4, y - 4), fill=colour)
    d.pieslice(p(x - 7, y - 3, x + 7, y + 11), 180, 360, fill=colour)

def scroll(d, x, y, seal=True):
    d.rectangle(p(x - 14, y - 10, x + 14, y + 10), fill=PAPER, outline=INK, width=2 * S)
    for i in range(3): d.line(p(x - 9, y - 5 + i * 5, x + 9, y - 5 + i * 5), fill=INK, width=S)
    if seal: d.ellipse(p(x + 4, y + 4, x + 14, y + 14), fill=RED, outline=INK, width=S)

def save(img, name):
    img.resize((W, H), Image.LANCZOS).save(out / f'{name}.png')
    print('wrote', name)

img, d = canvas()            # settlement -> kingdom -> government, with offices under the government
house(d, 28, 42); arrow(d, 46, 44, 70, 44); banner(d, 92, 42, BLUE); arrow(d, 108, 44, 132, 44); crown(d, 160, 40)
for i, x in enumerate((136, 160, 184)):
    person(d, x, 80); d.line(p(160, 49, x, 66), fill=INK, width=S)
save(img, 'government_chain')

img, d = canvas()            # a map: from the player's mark along a dotted path to known settlements; fog over the unknown
d.rectangle(p(6, 6, 186, 90), fill=PAPER, outline=INK, width=2 * S)
for x in range(124, 186, 6): d.line(p(x, 8, x - 30, 88), fill=FOG, width=2 * S)
d.ellipse(p(18, 64, 30, 76), fill=RED, outline=INK, width=S)
for (x0, y0), (x1, y1) in (((24, 70), (60, 48)), ((60, 48), (96, 34)), ((96, 34), (118, 58))):
    steps = 8
    for i in range(0, steps, 2):
        d.line(p(x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps, x0 + (x1 - x0) * (i + 1) / steps, y0 + (y1 - y0) * (i + 1) / steps), fill=INK, width=2 * S)
house(d, 62, 40, fill=PAPER); house(d, 118, 52, fill=PAPER)
save(img, 'discovery_map')

img, d = canvas()            # two kingdoms' banners, each signing one agreement between them
banner(d, 30, 46, BLUE); banner(d, 162, 46, GREEN); scroll(d, 96, 46)
arrow(d, 46, 46, 76, 46); arrow(d, 146, 46, 116, 46)
save(img, 'diplomacy')

img, d = canvas()            # an attacking banner's claim on a defended settlement, inside its border
d.rounded_rectangle(p(92, 14, 182, 86), radius=10 * S, outline=INK, width=2 * S)
for x in range(96, 180, 10): d.line(p(x, 14, x + 5, 14), fill=PAPER, width=3 * S)
house(d, 150, 46); d.polygon(p(116, 30, 132, 30, 132, 50, 124, 58, 116, 50), fill=BLUE, outline=INK, width=2 * S)
banner(d, 30, 48, RED); arrow(d, 46, 48, 104, 48, RED)
save(img, 'warfare_control')

img, d = canvas()            # a workshop's commission, carried out, earns a guild's standing
d.rectangle(p(14, 40, 46, 54), fill=INK); d.polygon(p(10, 40, 50, 40, 44, 32, 16, 32), fill=INK)
d.rectangle(p(24, 54, 36, 66), fill=INK)
arrow(d, 56, 46, 78, 46); scroll(d, 96, 46, seal=False); arrow(d, 114, 46, 136, 46)
import math
pts = []
for i in range(10):
    r = 14 if i % 2 == 0 else 6
    a = -math.pi / 2 + i * math.pi / 5
    pts += [160 + r * math.cos(a), 46 + r * math.sin(a)]
d.polygon(p(*pts), fill=GOLD, outline=INK, width=2 * S)
save(img, 'commissions')

img, d = canvas()            # the kingdom's arms, the seal press, the sealed charter the ledger vouches for
banner(d, 26, 46, RED); arrow(d, 42, 46, 66, 46)
d.rectangle(p(78, 40, 114, 66), fill=PAPER, outline=INK, width=2 * S); d.line(p(96, 40, 96, 22), fill=INK, width=3 * S)
d.line(p(86, 22, 106, 22), fill=INK, width=3 * S); d.ellipse(p(90, 46, 102, 58), fill=RED, outline=INK, width=S)
arrow(d, 122, 46, 142, 46); scroll(d, 164, 46)
save(img, 'heraldry_charter')
