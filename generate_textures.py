#!/usr/bin/env python3
"""Draw mc-paint's item sprites and mod icon. Run from the mod root; rewrites the PNGs in place."""
from PIL import Image

OUT = "src/main/resources/assets/mc-paint"

CANVAS = (255, 252, 245)
CANVAS_DARK = (230, 226, 214)
WOOD = (150, 104, 58)
WOOD_LIGHT = (186, 138, 84)
WOOD_DARK = (104, 70, 38)
GOLD = (222, 177, 45)
GOLD_DARK = (160, 118, 24)
SKY = (102, 153, 216)
SUN = (229, 229, 51)
HILL = (127, 204, 25)
HILL_DARK = (102, 127, 51)
RED = (153, 51, 51)
BLUE = (51, 76, 178)
ORANGE = (216, 127, 51)
BLACK = (25, 25, 25)


def blank(size=16):
    return Image.new("RGBA", (size, size), (0, 0, 0, 0))


def put(img, x, y, color):
    img.putpixel((x, y), color + (255,) if len(color) == 3 else color)


def rect(img, x0, y0, x1, y1, color):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            put(img, x, y, color)


def framed(border, border_dark, inside):
    """A square canvas on its stretcher, 12x12, with a lit top-left edge and a shaded bottom-right."""
    img = blank()
    rect(img, 2, 2, 13, 13, border)
    rect(img, 2, 2, 13, 2, border if border != WOOD else WOOD_LIGHT)
    rect(img, 13, 3, 13, 13, border_dark)
    rect(img, 2, 13, 13, 13, border_dark)
    rect(img, 3, 3, 12, 12, CANVAS)
    inside(img)
    return img


def weave(img):
    for y in range(3, 13):
        for x in range(3, 13):
            if (x + y) % 4 == 0:
                put(img, x, y, CANVAS_DARK)


def sketch(img):
    weave(img)
    rect(img, 3, 3, 12, 6, SKY)
    rect(img, 3, 3, 7, 3, CANVAS)
    rect(img, 3, 4, 5, 4, CANVAS)
    put(img, 10, 4, SUN)
    put(img, 11, 4, SUN)
    put(img, 10, 5, SUN)
    for x in range(3, 13):
        if x < 9:
            put(img, x, 9 - (x // 4), HILL)
    put(img, 4, 11, RED)
    put(img, 5, 11, RED)


def landscape(img):
    rect(img, 3, 3, 12, 8, SKY)
    rect(img, 9, 4, 10, 5, SUN)
    rect(img, 3, 9, 12, 12, HILL)
    rect(img, 3, 8, 6, 8, HILL)
    rect(img, 4, 7, 5, 7, HILL)
    rect(img, 9, 11, 12, 12, HILL_DARK)
    put(img, 7, 11, RED)
    put(img, 7, 10, RED)


def easel():
    """A studio easel: a mast up the back, a small canvas on the tray, two legs splayed below."""
    img = blank()
    for y in range(0, 16):
        put(img, 7, y, WOOD_DARK)
        put(img, 8, y, WOOD)
    rect(img, 6, 0, 9, 0, WOOD_LIGHT)
    rect(img, 3, 2, 12, 8, CANVAS)
    rect(img, 3, 2, 12, 4, SKY)
    rect(img, 3, 7, 12, 8, HILL)
    put(img, 10, 3, SUN)
    put(img, 5, 6, HILL)
    put(img, 6, 6, HILL)
    rect(img, 2, 9, 13, 9, WOOD_LIGHT)
    rect(img, 2, 10, 13, 10, WOOD_DARK)
    for i, y in enumerate(range(11, 16)):
        put(img, 4 - i // 2, y, WOOD)
        put(img, 11 + i // 2, y, WOOD_DARK)
    rect(img, 5, 12, 10, 12, WOOD)
    return img


def icon():
    """The easel sprite at 8x, a finished painting propped on it and a palette of dye beside."""
    small = easel()
    big = small.resize((128, 128), Image.NEAREST)
    palette = [RED, ORANGE, SUN, HILL, BLUE, BLACK]
    for i, color in enumerate(palette):
        x = 98 + (i % 2) * 12
        y = 72 + (i // 2) * 12
        for dy in range(10):
            for dx in range(10):
                big.putpixel((x + dx, y + dy), color + (255,))
    return big


framed(WOOD, WOOD_DARK, weave).save(f"{OUT}/textures/item/canvas.png")
framed(WOOD, WOOD_DARK, sketch).save(f"{OUT}/textures/item/unfinished_painting.png")
framed(GOLD, GOLD_DARK, landscape).save(f"{OUT}/textures/item/painting.png")
easel().save(f"{OUT}/textures/item/easel.png")
icon().save(f"{OUT}/icon.png")
