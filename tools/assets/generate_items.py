"""Objets des vaisseaux et combinaison spatiale : sprites 16×16, couches d'armure 64×32, modèles, recettes, traductions.

    python tools/assets/generate_items.py

Sprites dessinés en « pixel art » ASCII (demi-sprite gauche, mis en miroir quand c'est indiqué), puis ombrés sur leur
moitié droite pour donner du volume. Aucune dépendance.
"""

import json
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / "src" / "main" / "resources"
ASSETS = ROOT / "assets" / "odyssea"
DATA = ROOT / "data" / "odyssea"

PALETTE = {
    ".": None,
    "k": (28, 30, 36), "d": (58, 62, 70), "g": (112, 118, 128), "G": (170, 176, 186),
    "w": (236, 239, 243), "W": (198, 205, 214), "r": (196, 52, 40), "o": (242, 140, 38),
    "y": (252, 214, 84), "b": (58, 138, 228), "c": (110, 224, 255), "p": (146, 88, 226),
    "P": (204, 168, 255), "v": (26, 44, 84), "V": (96, 146, 206), "s": (90, 64, 40),
}


def write_png(path, width, height, pixels):
    rows = []
    for y in range(height):
        row = bytearray(b"\x00")
        for x in range(width):
            px = pixels[y][x]
            row += bytes(px) if px else b"\x00\x00\x00\x00"
        rows.append(bytes(row))

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(b"".join(rows), 9))
                     + chunk(b"IEND", b""))


def sprite(rows, mirror=True, shade=True):
    full = []
    for row in rows:
        if mirror:
            assert len(row) == 8, row
            row = row + row[::-1]
        assert len(row) == 16, row
        full.append(row)
    assert len(full) == 16
    pixels = []
    for y, row in enumerate(full):
        line = []
        for x, ch in enumerate(row):
            color = PALETTE[ch]
            if color is None:
                line.append(None)
                continue
            if shade and x >= 9 and ch not in "kc":
                color = tuple(int(c * 0.86) for c in color)
            line.append((*color, 255))
        pixels.append(line)
    return pixels


SPRITES = {
    "cutlass_beacon": sprite([
        "........", ".......k", "......kc", "......kG", ".....kgG", ".....kgG", "....kdgG", "..kkdggg",
        ".kgggggg", ".kdddggg", "..kk.kgg", ".....kgd", "....kkrr", "....kroo", ".....kk.", "........"]),
    "destroyer_beacon": sprite([
        "........", ".......k", "......kG", "......kg", "......kg", ".....kgG", ".....kgg", ".....kgG",
        "....kdgg", "...kdggG", "..kgggGg", "..kgdggg", "..kddddd", "..kbcbbc", "...kk.kk", "........"]),
    "hydrogen_cell": sprite([
        "........", ".....kkk", ".....kdd", "....kkkk", "...kwwWW", "...kwwWW", "...kbbbb", "...kwwWW",
        "...kwwWW", "...kwwWW", "...kbbbb", "...kwwWW", "...kwwWW", "....kkkk", "........", "........"]),
    "quantum_cell": sprite([
        "........", ".....kkk", ".....kdd", "....kkkk", "...kpPPc", "...kpPPc", "...kkkkk", "...kpPPc",
        "...kpPcc", "...kpPPc", "...kkkkk", "...kpPPc", "...kpPPc", "....kkkk", "........", "........"]),
    "ship_missile": sprite([
        "............kk..", "...........krrk.", "..........krGGk.", ".........kGGGk..",
        "........kGGGk...", ".......kGGGk....", "......kGgGk.....", ".....kGGGk......",
        "..k.kGGGk.......", "..kkGGGk........", "...kggk.........", "..kkgkk.........",
        ".kook.k.........", "koyok...........", ".kok............", "................"], mirror=False),
    "hull_repair_kit": sprite([
        "........", "........", ".....kkk", ".....k..", "..kkkkkk", ".krrrrrr", ".krrrrww", ".krrrrww",
        ".krrwwww", ".krrrrww", ".krrrrww", ".krrrrrr", ".kdddddd", "..kkkkkk", "........", "........"]),
    "space_helmet": sprite([
        "........", ".....kkk", "...kkwww", "..kwwwww", "..kwwvvv", ".kwwvVVv", ".kwwvVvv", ".kwwvvvv",
        ".kwwwvvv", ".kWwwwww", ".kWwwooo", "..kWwwww", "..kkkkkk", "...kgggg", "...kkkkk", "........"]),
    "space_suit": sprite([
        "........", "..kkk..k", ".kwwwkkw", "kwwwwwww", "kwwwwwww", "kWwwgggg", "kWwwgccg", ".kWwgggg",
        "..kwwwww", "..kwoooo", "..kwwwww", "..kWwwww", "..kWwwww", "..kkkkkk", "........", "........"]),
    "space_leggings": sprite([
        "........", "..kkkkkk", "..kgdddd", "..kWwwww", "..kwwwwk", "..kwwwk.", "..kwwwk.", "..kooook",
        "..kwwwk.", "..kwwwk.", "..kwwwk.", "..kWwwk.", "..kWwwk.", "..kkkkk.", "........", "........"]),
    "space_boots": sprite([
        "........", "........", "........", "........", "........", "..kkkk..", "..kwwk..", "..kwwk..",
        "..kwwk..", "..kook..", ".kwwwk..", "kwwwwk..", "kwwwwwk.", "kddddkk.", ".kkkkk..", "........"]),
}


# ───────────────────────────── Couches d'armure ─────────────────────────────

def blank(width, height):
    return [[None] * width for _ in range(height)]


def fill(pixels, x0, y0, w, h, color):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            pixels[y][x] = (*color, 255)


def suit_face(pixels, x0, y0, w, h, stripe_row=None, glove_rows=0, sole_rows=0, panel=False):
    """Face de tissu blanc avec coutures, bande orange et ganse sombre en bas."""
    white = PALETTE["w"]
    shade = PALETTE["W"]
    fill(pixels, x0, y0, w, h, white)
    for y in range(y0, y0 + h):
        pixels[y][x0] = (*shade, 255)
        pixels[y][x0 + w - 1] = (*shade, 255)
    if stripe_row is not None:
        fill(pixels, x0, y0 + stripe_row, w, 1, PALETTE["o"])
    if glove_rows:
        fill(pixels, x0, y0 + h - glove_rows, w, glove_rows, PALETTE["g"])
    if sole_rows:
        fill(pixels, x0, y0 + h - sole_rows, w, sole_rows, PALETTE["d"])
    if panel and w >= 8:
        fill(pixels, x0 + 2, y0 + 3, w - 4, 4, PALETTE["g"])
        pixels[y0 + 4][x0 + 3] = (*PALETTE["c"], 255)
        pixels[y0 + 4][x0 + 5] = (*PALETTE["r"], 255)
        pixels[y0 + 5][x0 + 4] = (*PALETTE["y"], 255)


def layer_1():
    p = blank(64, 32)
    # Casque : six faces 8×8, visière sur la face avant (8,8).
    for (x, y) in ((8, 0), (16, 0), (0, 8), (8, 8), (16, 8), (24, 8)):
        suit_face(p, x, y, 8, 8)
    fill(p, 9, 10, 6, 4, PALETTE["v"])
    fill(p, 10, 10, 2, 1, PALETTE["V"])
    p[11][10] = (*PALETTE["V"], 255)
    fill(p, 0, 12, 8, 1, PALETTE["o"])
    fill(p, 16, 12, 8, 1, PALETTE["o"])
    fill(p, 26, 10, 4, 4, PALETTE["g"])
    # Torse : dessus, dessous, côtés, avant (panneau de survie), dos (sac dorsal).
    suit_face(p, 20, 16, 8, 4)
    suit_face(p, 28, 16, 8, 4)
    suit_face(p, 16, 20, 4, 12, stripe_row=8)
    suit_face(p, 28, 20, 4, 12, stripe_row=8)
    suit_face(p, 20, 20, 8, 12, stripe_row=8, panel=True)
    suit_face(p, 32, 20, 8, 12, stripe_row=8)
    fill(p, 33, 21, 6, 7, PALETTE["G"])
    fill(p, 34, 22, 4, 1, PALETTE["d"])
    fill(p, 34, 25, 4, 1, PALETTE["d"])
    # Bras : bande orange au poignet, gants gris.
    suit_face(p, 44, 16, 4, 4)
    suit_face(p, 48, 16, 4, 4, glove_rows=4)
    for x in (40, 44, 48, 52):
        suit_face(p, x, 20, 4, 12, stripe_row=7, glove_rows=3)
    # Bottes (bas des jambes) : 5 rangées, semelle sombre.
    for x in (0, 4, 8, 12):
        suit_face(p, x, 27, 4, 5, stripe_row=0, sole_rows=1)
    fill(p, 8, 16, 4, 4, PALETTE["d"])
    return p


def layer_2():
    p = blank(64, 32)
    # Jambières : jambes complètes, genouillère orange ; ceinture sur le bas du torse.
    suit_face(p, 4, 16, 4, 4)
    for x in (0, 4, 8, 12):
        suit_face(p, x, 20, 4, 12, stripe_row=5)
    for (x, w) in ((16, 4), (20, 8), (28, 4), (32, 8)):
        fill(p, x, 28, w, 4, PALETTE["W"])
        fill(p, x, 28, w, 1, PALETTE["g"])
    p[29][23] = (*PALETTE["y"], 255)
    return p


# ───────────────────────────── Recettes ─────────────────────────────

def shaped(pattern, keys, result, count=1):
    return {
        "type": "minecraft:crafting_shaped",
        "category": "equipment",
        "pattern": pattern,
        "key": {k: {"item": v} for k, v in keys.items()},
        "result": {"id": f"odyssea:{result}", "count": count},
    }


RECIPES = {
    "hydrogen_cell": shaped([" I ", "IWI", " R "], {"I": "minecraft:iron_ingot", "W": "minecraft:water_bucket",
                                                   "R": "minecraft:redstone"}, "hydrogen_cell", 2),
    "quantum_cell": shaped([" A ", "EHE", " A "], {"A": "minecraft:amethyst_shard", "E": "minecraft:ender_pearl",
                                                  "H": "odyssea:hydrogen_cell"}, "quantum_cell"),
    "ship_missile": shaped([" I ", "ITI", "IFI"], {"I": "minecraft:iron_ingot", "T": "minecraft:tnt",
                                                  "F": "minecraft:firework_rocket"}, "ship_missile", 2),
    "hull_repair_kit": shaped(["IRI", "IBI", "III"], {"I": "minecraft:iron_ingot", "R": "minecraft:redstone",
                                                     "B": "minecraft:iron_block"}, "hull_repair_kit"),
    "cutlass_beacon": shaped(["DED", "BRB", "IHI"], {"D": "minecraft:diamond", "E": "minecraft:ender_eye",
                                                    "B": "minecraft:iron_block", "R": "minecraft:redstone_block",
                                                    "I": "minecraft:iron_ingot", "H": "odyssea:hydrogen_cell"},
                             "cutlass_beacon"),
    "destroyer_beacon": shaped(["NSN", "BCB", "NQN"], {"N": "minecraft:netherite_ingot", "S": "minecraft:nether_star",
                                                      "B": "minecraft:diamond_block", "C": "odyssea:cutlass_beacon",
                                                      "Q": "odyssea:quantum_cell"}, "destroyer_beacon"),
    "space_helmet": shaped(["WIW", "WGW"], {"W": "minecraft:white_wool", "I": "minecraft:iron_ingot",
                                           "G": "minecraft:glass_pane"}, "space_helmet"),
    "space_suit": shaped(["W W", "ICI", "WWW"], {"W": "minecraft:white_wool", "I": "minecraft:iron_ingot",
                                                "C": "minecraft:copper_block"}, "space_suit"),
    "space_leggings": shaped(["WIW", "W W", "W W"], {"W": "minecraft:white_wool", "I": "minecraft:iron_ingot"},
                             "space_leggings"),
    "space_boots": shaped(["W W", "I I"], {"W": "minecraft:white_wool", "I": "minecraft:iron_ingot"}, "space_boots"),
}


def write_json(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(content, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main():
    for name, pixels in SPRITES.items():
        write_png(ASSETS / "textures" / "item" / f"{name}.png", 16, 16, pixels)
        write_json(ASSETS / "models" / "item" / f"{name}.json",
                   {"parent": "minecraft:item/generated", "textures": {"layer0": f"odyssea:item/{name}"}})
    write_png(ASSETS / "textures" / "models" / "armor" / "space_suit_layer_1.png", 64, 32, layer_1())
    write_png(ASSETS / "textures" / "models" / "armor" / "space_suit_layer_2.png", 64, 32, layer_2())
    for name, recipe in RECIPES.items():
        write_json(DATA / "recipe" / f"{name}.json", recipe)
    print(f"{len(SPRITES)} sprites, 2 couches d'armure, {len(RECIPES)} recettes")


if __name__ == "__main__":
    main()
