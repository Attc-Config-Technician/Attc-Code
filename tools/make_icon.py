#!/usr/bin/env python3
"""Renders the ATTC OPENCODE launcher icons with no imaging library.

The mark is the same one the start screen draws: a green outline around a dark
rounded square with a chevron and a cursor bar inside. Pure stdlib: zlib +
struct, 4x supersampling.
"""
import math
import os
import struct
import zlib

GREEN = (0.0, 0.898, 0.627)      # #00E5A0
DARK = (0.043, 0.059, 0.078)     # #0B0F14
CLEAR = (0.0, 0.0, 0.0, 0.0)

# Geometry matches CliLogo.java (fractions of the icon size).
CORNER = 0.26
STROKE = 0.075
# Outline frame: thin and slightly inside the edge, like the start-screen mark.
RING_INSET = 0.03
RING = 0.055
APEX = (0.46, 0.50)
ARM_UP = (0.30, 0.34)
ARM_DOWN = (0.30, 0.66)
BAR_X0, BAR_X1 = 0.54, 0.74
BAR_Y0, BAR_Y1 = 0.62, 0.70

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def dist_to_segment(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    length2 = dx * dx + dy * dy
    if length2 == 0:
        return math.hypot(px - ax, py - ay)
    t = ((px - ax) * dx + (py - ay) * dy) / length2
    t = max(0.0, min(1.0, t))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def in_rrect(x, y, inset, radius):
    """Inside a rounded rectangle inset by `inset` with the given corner radius."""
    if x < inset or x > 1.0 - inset or y < inset or y > 1.0 - inset:
        return False
    inner = 1.0 - inset - radius
    cx = min(max(x, inset + radius), inner)
    cy = min(max(y, inset + radius), inner)
    return (x - cx) ** 2 + (y - cy) ** 2 <= radius * radius


def in_circle(x, y, radius):
    return (x - 0.5) ** 2 + (y - 0.5) ** 2 <= radius * radius


def glyph_hit(x, y):
    h = STROKE / 2.0
    if dist_to_segment(x, y, ARM_UP[0], ARM_UP[1], APEX[0], APEX[1]) <= h:
        return True
    if dist_to_segment(x, y, ARM_DOWN[0], ARM_DOWN[1], APEX[0], APEX[1]) <= h:
        return True
    if BAR_X0 <= x <= BAR_X1 and BAR_Y0 <= y <= BAR_Y1:
        return True
    return False


def ring_hit(x, y, mode):
    """True on the green outline, i.e. inside the shape but not inset."""
    if mode == "round":
        return (in_circle(x, y, 0.5 - RING_INSET)
                and not in_circle(x, y, 0.5 - RING_INSET - RING))
    return (in_rrect(x, y, RING_INSET, CORNER - RING_INSET)
            and not in_rrect(x, y, RING_INSET + RING, CORNER - RING_INSET - RING))


def visible(x, y, mode):
    if mode == "round":
        return in_circle(x, y, 0.5)
    return in_rrect(x, y, 0.0, CORNER)


def sample(x, y, mode):
    if not visible(x, y, mode):
        return CLEAR
    if ring_hit(x, y, mode) or glyph_hit(x, y):
        return GREEN + (1.0,)
    return DARK + (1.0,)


def render(size, mode, ss=4):
    raw = bytearray()
    for py in range(size):
        raw.append(0)
        for px in range(size):
            r = g = b = a = 0.0
            for sy in range(ss):
                for sx in range(ss):
                    cr, cg, cb, ca = sample(
                        (px + (sx + 0.5) / ss) / size,
                        (py + (sy + 0.5) / ss) / size, mode)
                    r += cr * ca
                    g += cg * ca
                    b += cb * ca
                    a += ca
            n = ss * ss
            if a > 0:
                raw.extend((int(r / a * 255 + 0.5), int(g / a * 255 + 0.5),
                            int(b / a * 255 + 0.5), int(a / n * 255 + 0.5)))
            else:
                raw.extend((0, 0, 0, 0))
    return bytes(raw)


def chunk(tag, data):
    return (struct.pack(">I", len(data)) + tag + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff))


def write_png(path, width, height, raw):
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)
    print(f"wrote {path} ({width}x{height}, {len(png)} bytes)")


def main():
    base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "res")
    for name, size in DENSITIES.items():
        write_png(os.path.join(base, f"mipmap-{name}", "ic_launcher.png"),
                  size, size, render(size, "rounded"))
        write_png(os.path.join(base, f"mipmap-{name}", "ic_launcher_round.png"),
                  size, size, render(size, "round"))


if __name__ == "__main__":
    main()
