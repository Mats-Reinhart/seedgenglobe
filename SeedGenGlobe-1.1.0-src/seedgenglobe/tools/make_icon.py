"""Draws SeedGenGlobe's icon: a round globe whose terrain gets blockier towards the rim, like LOD detail levels
getting coarser with distance, with a sprout growing on top. Original artwork, MIT like the rest of the mod.

usage: python3 make_icon.py <output.png> [size]
"""
import math
import sys

from PIL import Image, ImageDraw

SIZE = int(sys.argv[2]) if len(sys.argv) > 2 else 256
SCALE = 3                      # supersampling for smooth edges
S = SIZE * SCALE               # canvas size
U = S / 128.0                  # one "design pixel": the design was laid out on a 128 grid
CX = S / 2
CY = S * 0.56
R = S * 0.41


def lerp(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def land(x, y):
    """Smooth deterministic pseudo-noise for the continents."""
    return (math.sin(x * 2.1 + 0.7) * math.cos(y * 1.7 - 0.4)
            + 0.6 * math.sin(x * 4.3 - y * 3.1 + 1.3)
            + 0.35 * math.cos(x * 7.9 + y * 6.7))


OCEAN_DEEP, OCEAN_SHALLOW = (26, 70, 140), (52, 128, 196)
GRASS, FOREST, SAND, ROCK, SNOW = (88, 160, 72), (46, 112, 58), (214, 196, 128), (122, 112, 98), (236, 242, 246)

img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
px = img.load()
for j in range(S):
    for i in range(S):
        dx, dy = (i + 0.5 - CX) / R, (j + 0.5 - CY) / R
        d2 = dx * dx + dy * dy
        if d2 > 1.0:
            continue
        d = math.sqrt(d2)
        # cells grow towards the rim: fine detail in the middle, chunky blocks at the edge
        level = min(4, int(d * 5.0))
        cell = max(1, int(round((0.5, 1.5, 3, 5, 8)[level] * 2 * U)))
        ci = (i // cell) * cell + cell / 2
        cj = (j // cell) * cell + cell / 2
        sx, sy = (ci - CX) / R, (cj - CY) / R
        sz = math.sqrt(max(0.0, 1.0 - min(1.0, sx * sx + sy * sy)))
        u, v = sx * 1.6 + 0.3, sy * 1.6 - 0.2 + 0.3 * sz
        h = land(u, v)
        if h < -0.15:
            colour = lerp(OCEAN_DEEP, OCEAN_SHALLOW, (h + 1.6) / 1.45)
        elif h < -0.02:
            colour = SAND
        elif h < 0.55:
            colour = lerp(GRASS, FOREST, (h + 0.02) / 0.57)
        elif h < 1.35:
            colour = lerp(FOREST, ROCK, (h - 0.55) / 0.8)
        else:
            colour = SNOW
        # light from the upper left
        shade = 0.55 + 0.45 * max(0.0, -dx * 0.55 - dy * 0.65 + math.sqrt(max(0.0, 1.0 - d2)) * 0.55)
        px[i, j] = tuple(min(255, int(c * shade)) for c in colour) + (255,)

draw = ImageDraw.Draw(img)
draw.ellipse([CX - R, CY - R, CX + R, CY + R], outline=(18, 32, 54, 255), width=int(round(2.5 * U)))

# the sprout growing out of the top of the globe
stem_x = CX
stem_bottom = CY - R + 2 * U
stem_top = CY - R - 15 * U
outline = (30, 90, 34, 255)
draw.line([stem_x, stem_bottom, stem_x, stem_top + 6 * U], fill=(44, 120, 44, 255), width=int(round(3 * U)))
draw.ellipse([stem_x - 13 * U, stem_top, stem_x - 1 * U, stem_top + 8 * U], fill=(84, 178, 70, 255), outline=outline, width=int(round(U)))
draw.ellipse([stem_x + 1 * U, stem_top - 3 * U, stem_x + 14 * U, stem_top + 6 * U], fill=(104, 196, 84, 255), outline=outline, width=int(round(U)))

img = img.resize((SIZE, SIZE), Image.LANCZOS)
img.save(sys.argv[1] if len(sys.argv) > 1 else "icon.png", optimize=True)
