"""Génère les assets des blocs planétaires d'Odyssea : textures 16×16 procédurales, blockstates, modèles,
modèles d'objets, tables de butin, tags et traductions.

    python tools/assets/generate_blocks.py

La liste BLOCKS doit rester alignée sur odyssea.registry.OdysseaBlocks. Les textures sont tirées d'une graine fixe :
le résultat est reproductible, et on ajuste une texture en modifiant sa recette ci-dessous.
"""

import json
import math
import random
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / "src" / "main" / "resources"
ASSETS = ROOT / "assets" / "odyssea"
DATA = ROOT / "data"
SIZE = 16


# ───────────────────────────── PNG et bruit ─────────────────────────────

def write_png(path, pixels):
    raw = b"".join(b"\x00" + bytes(channel for px in row for channel in px) for row in pixels)

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


class TileNoise:
    """Bruit de valeur périodique : la texture se raccorde sans couture sur ses quatre bords."""

    def __init__(self, rng, period):
        self.period = period
        self.values = [[rng.random() for _ in range(period)] for _ in range(period)]

    def at(self, x, y):
        u = x * self.period / SIZE
        v = y * self.period / SIZE
        x0, y0 = math.floor(u), math.floor(v)
        fx, fy = u - x0, v - y0
        sx, sy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)

        def g(i, j):
            return self.values[j % self.period][i % self.period]

        top = g(x0, y0) + (g(x0 + 1, y0) - g(x0, y0)) * sx
        bottom = g(x0, y0 + 1) + (g(x0 + 1, y0 + 1) - g(x0, y0 + 1)) * sx
        return top + (bottom - top) * sy


def hex_rgb(value):
    value = value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


def lerp_rgb(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def palette_at(stops, t):
    t = min(1.0, max(0.0, t))
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        if t <= p1:
            return lerp_rgb(c0, c1, (t - p0) / (p1 - p0) if p1 > p0 else 0.0)
    return stops[-1][1]


def clamp_rgb(color):
    return tuple(max(0, min(255, int(round(c)))) for c in color)


# ───────────────────────────── Recettes ─────────────────────────────

class Texture:
    def __init__(self, seed, palette, octaves=((2, 0.25), (4, 0.35), (8, 0.25), (16, 0.15)), grain=0.06, contrast=1.0):
        self.rng = random.Random(seed)
        stops = [(p, hex_rgb(c)) for p, c in palette]
        noises = [(TileNoise(self.rng, period), weight) for period, weight in octaves]
        self.pixels = []
        for y in range(SIZE):
            row = []
            for x in range(SIZE):
                n = sum(noise.at(x, y) * w for noise, w in noises)
                n = 0.5 + (n - 0.5) * contrast
                color = palette_at(stops, n)
                jitter = 1.0 + grain * (self.rng.random() - 0.5) * 2.0
                row.append([c * jitter for c in color])
            self.pixels.append(row)

    def set(self, x, y, color, mix=1.0):
        px = self.pixels[y % SIZE][x % SIZE]
        target = hex_rgb(color) if isinstance(color, str) else color
        for i in range(3):
            px[i] = px[i] + (target[i] - px[i]) * mix

    def specks(self, count, color, mix=0.85):
        for _ in range(count):
            self.set(self.rng.randrange(SIZE), self.rng.randrange(SIZE), color, mix)
        return self

    def clusters(self, count, color, mix=0.8):
        """Petits amas de 2 à 4 pixels (graviers, vésicules, cristaux trapus)."""
        for _ in range(count):
            x, y = self.rng.randrange(SIZE), self.rng.randrange(SIZE)
            for dx, dy in self.rng.sample([(0, 0), (1, 0), (0, 1), (1, 1), (-1, 0), (0, -1)], self.rng.randint(2, 4)):
                self.set(x + dx, y + dy, color, mix)
        return self

    def laths(self, count, color, mix=0.85, length=(2, 3)):
        """Lattes allongées (cristaux de plagioclase de l'anorthosite)."""
        for _ in range(count):
            x, y = self.rng.randrange(SIZE), self.rng.randrange(SIZE)
            horizontal = self.rng.random() < 0.5
            for i in range(self.rng.randint(*length)):
                self.set(x + (i if horizontal else 0), y + (0 if horizontal else i), color, mix)
        return self

    def cracks(self, count, color, mix=0.7, steps=(5, 10)):
        """Fissures : marches aléatoires qui se raccordent d'un bord à l'autre."""
        for _ in range(count):
            x, y = self.rng.randrange(SIZE), self.rng.randrange(SIZE)
            dx, dy = self.rng.choice([(1, 0), (0, 1), (1, 1), (1, -1)])
            for _ in range(self.rng.randint(*steps)):
                self.set(x, y, color, mix)
                if self.rng.random() < 0.35:
                    dx, dy = self.rng.choice([(1, 0), (0, 1), (1, 1), (1, -1), (-1, 1)])
                x, y = x + dx, y + dy
        return self

    def streaks(self, count, color, mix=0.45, length=(4, 8)):
        """Traînées diagonales (plans de clivage de la glace)."""
        for _ in range(count):
            x, y = self.rng.randrange(SIZE), self.rng.randrange(SIZE)
            for i in range(self.rng.randint(*length)):
                self.set(x + i, y + i, color, mix)
        return self

    def save(self, name):
        write_png(ASSETS / "textures" / "block" / f"{name}.png", [[clamp_rgb(px) for px in row] for row in self.pixels])


def regolith(seed, low, mid, high, dark, light):
    return (Texture(seed, [(0.0, low), (0.5, mid), (1.0, high)], octaves=((4, 0.3), (8, 0.35), (16, 0.35)), grain=0.07)
            .specks(10, dark).specks(9, light).clusters(3, dark, 0.5))


TEXTURES = {
    "lunar_regolith": lambda: regolith(11, "#8d8a85", "#a8a6a1", "#c3c1bc", "#6c6965", "#d9d7d1"),
    "lunar_mare_regolith": lambda: regolith(12, "#3e3d3b", "#5c5b59", "#75736e", "#2b2a29", "#8a8883"),
    "anorthosite": lambda: Texture(13, [(0.0, "#aeaca5"), (0.5, "#c9c7bf"), (1.0, "#e2e0d8")], grain=0.05, contrast=1.2)
        .laths(14, "#f3f1eb").specks(7, "#76736c").cracks(1, "#9d9b94", 0.4),
    "lunar_basalt": lambda: Texture(14, [(0.0, "#2c2c2e"), (0.5, "#434346"), (1.0, "#58575a")], grain=0.06)
        .clusters(6, "#1b1b1d", 0.75).specks(8, "#6b6a6d", 0.6),
    "mercurian_regolith": lambda: regolith(15, "#3c3834", "#534e48", "#686259", "#2a2724", "#7d766b"),
    "mercurian_rock": lambda: Texture(16, [(0.0, "#2b2928"), (0.5, "#3e3b38"), (1.0, "#544f4a")], grain=0.06, contrast=1.2)
        .cracks(3, "#1d1b1a", 0.7).specks(6, "#6d665e", 0.6),
    "hollow_crust": lambda: Texture(17, [(0.0, "#c3d0d6"), (0.5, "#dde8ec"), (1.0, "#f4fafb")], grain=0.05, contrast=1.3)
        .clusters(5, "#ffffff", 0.7).cracks(2, "#97a8b0", 0.55),
    "venusian_regolith": lambda: regolith(18, "#5b3a25", "#785035", "#926847", "#432a1a", "#a8805c"),
    "venusian_basalt": lambda: Texture(19, [(0.0, "#1d1816"), (0.5, "#2e2622"), (1.0, "#42362e")], grain=0.06, contrast=1.1)
        .cracks(4, "#130f0d", 0.75).specks(6, "#6a4a33", 0.55),
    "sulfur_crust": lambda: Texture(20, [(0.0, "#b3941c"), (0.45, "#d4b82a"), (1.0, "#efd958")], grain=0.07, contrast=1.3)
        .clusters(4, "#c27718", 0.55).specks(10, "#fff3a6", 0.8),
    "martian_regolith": lambda: regolith(21, "#8a4226", "#b25d38", "#cc784c", "#6b321e", "#df9868"),
    "martian_rock": lambda: Texture(22, [(0.0, "#6a3222"), (0.5, "#87422e"), (1.0, "#a1553b")], grain=0.06, contrast=1.15)
        .cracks(3, "#4c2217", 0.7).streaks(2, "#b87259", 0.35).specks(5, "#c98a6c", 0.5),
    "martian_basalt": lambda: Texture(23, [(0.0, "#29221f"), (0.5, "#3b302d"), (1.0, "#4d403b")], grain=0.06)
        .clusters(5, "#1a1513", 0.7).specks(9, "#7a3d29", 0.65),
    "dry_ice": lambda: Texture(24, [(0.0, "#d2dadd"), (0.5, "#e6ecee"), (1.0, "#f9fbfc")], grain=0.03, contrast=1.2)
        .specks(14, "#ffffff", 0.9).streaks(2, "#c3cfd5", 0.5),
    "uranian_ice": lambda: Texture(25, [(0.0, "#8cc3ce"), (0.5, "#a7d6df"), (1.0, "#c5e9ee")], grain=0.03, contrast=1.1)
        .streaks(3, "#e3f7f9", 0.55).clusters(3, "#7ab2bf", 0.55),
    "methane_ice": lambda: Texture(26, [(0.0, "#5bb5ac"), (0.5, "#79cdc3"), (1.0, "#9de2d8")], grain=0.03, contrast=1.1)
        .streaks(3, "#c9f3ec", 0.5).specks(6, "#4a9c94", 0.5),
    "ammonia_ice": lambda: Texture(27, [(0.0, "#c6ccce"), (0.5, "#dadfe1"), (1.0, "#eceff0")], grain=0.04, contrast=1.15)
        .clusters(4, "#e7e2c9", 0.55).streaks(2, "#f7f9fa", 0.5),
    "ice_slush": lambda: Texture(28, [(0.0, "#2a3947"), (0.5, "#3b4f61"), (1.0, "#52697b")], grain=0.06, contrast=1.1)
        .specks(9, "#7a96ab", 0.6).clusters(3, "#1e2a35", 0.6),
}

# (identifiant, outil, roche hôte de minerais, rotation aléatoire, nom anglais, nom français)
BLOCKS = [
    ("lunar_regolith", "shovel", False, True, "Lunar Regolith", "Régolithe lunaire"),
    ("lunar_mare_regolith", "shovel", False, True, "Lunar Mare Regolith", "Régolithe des mers lunaires"),
    ("anorthosite", "pickaxe", True, False, "Anorthosite", "Anorthosite"),
    ("lunar_basalt", "pickaxe", True, False, "Lunar Basalt", "Basalte lunaire"),
    ("mercurian_regolith", "shovel", False, True, "Mercurian Regolith", "Régolithe mercurien"),
    ("mercurian_rock", "pickaxe", True, False, "Mercurian Rock", "Roche mercurienne"),
    ("hollow_crust", "pickaxe", False, False, "Hollow Crust", "Croûte des creux"),
    ("venusian_regolith", "shovel", False, True, "Venusian Regolith", "Régolithe vénusien"),
    ("venusian_basalt", "pickaxe", True, False, "Venusian Basalt", "Basalte vénusien"),
    ("sulfur_crust", "pickaxe", False, False, "Sulfur Crust", "Croûte de soufre"),
    ("martian_regolith", "shovel", False, True, "Martian Regolith", "Régolithe martien"),
    ("martian_rock", "pickaxe", True, False, "Martian Rock", "Roche martienne"),
    ("martian_basalt", "pickaxe", True, False, "Martian Basalt", "Basalte martien"),
    ("dry_ice", "pickaxe", False, False, "Dry Ice", "Glace carbonique"),
    ("uranian_ice", "pickaxe", False, False, "Uranian Ice", "Glace uranienne"),
    ("methane_ice", "pickaxe", False, False, "Methane Ice", "Glace de méthane"),
    ("ammonia_ice", "pickaxe", False, False, "Ammonia Ice", "Glace d'ammoniac"),
    ("ice_slush", "shovel", False, True, "Ice Slush", "Boue glacée"),
]


def write_json(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(content, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main():
    for name, tool, ore_host, rotated, english, french in BLOCKS:
        TEXTURES[name]().save(name)
        model = f"odyssea:block/{name}"
        write_json(ASSETS / "models" / "block" / f"{name}.json",
                   {"parent": "minecraft:block/cube_all", "textures": {"all": model}})
        write_json(ASSETS / "models" / "item" / f"{name}.json", {"parent": model})
        variants = ([{"model": model}, {"model": model, "y": 90}, {"model": model, "y": 180}, {"model": model, "y": 270}]
                    if rotated else {"model": model})
        write_json(ASSETS / "blockstates" / f"{name}.json", {"variants": {"": variants}})
        write_json(DATA / "odyssea" / "loot_table" / "blocks" / f"{name}.json", {
            "type": "minecraft:block",
            "random_sequence": f"odyssea:blocks/{name}",
            "pools": [{"rolls": 1.0, "bonus_rolls": 0.0,
                       "entries": [{"type": "minecraft:item", "name": f"odyssea:{name}"}],
                       "conditions": [{"condition": "minecraft:survives_explosion"}]}],
        })

    def tag(path, names):
        write_json(DATA / "minecraft" / "tags" / "block" / f"{path}.json",
                   {"replace": False, "values": [f"odyssea:{n}" for n in names]})

    tag("mineable/pickaxe", [b[0] for b in BLOCKS if b[1] == "pickaxe"])
    tag("mineable/shovel", [b[0] for b in BLOCKS if b[1] == "shovel"])
    # Roches hôtes : les minerais vanilla (fer, cuivre, or…) se forment aussi dans la croûte des planètes.
    tag("stone_ore_replaceables", [b[0] for b in BLOCKS if b[2]])
    tag("base_stone_overworld", [b[0] for b in BLOCKS if b[2]])

    for lang, index in (("en_us", 4), ("fr_fr", 5)):
        path = ASSETS / "lang" / f"{lang}.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        for block in BLOCKS:
            data[f"block.odyssea.{block[0]}"] = block[index]
        data["itemGroup.odyssea.planets"] = "Odyssea – Planets" if lang == "en_us" else "Odyssea – Planètes"
        write_json(path, data)

    print(f"{len(BLOCKS)} blocs générés")


if __name__ == "__main__":
    main()
