"""Génère la schématique du chasseur pirate « raider » (Sponge v2) dans src/main/resources/data/odyssea/ships.

    python tools/ships/generate_raider.py

Aucune dépendance : petit écrivain NBT intégré. L'avant du chasseur est +Z.
"""

import gzip
import struct
from pathlib import Path

OUT = Path(__file__).resolve().parents[2] / "src" / "main" / "resources" / "data" / "odyssea" / "ships" / "raider.schem"

TAG_END, TAG_BYTE, TAG_SHORT, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND, TAG_BYTE_ARRAY, TAG_INT_ARRAY = 0, 1, 2, 3, 8, 9, 10, 7, 11


def nbt_string(value):
    data = value.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def payload(tag, value):
    if tag == TAG_BYTE:
        return struct.pack(">b", value)
    if tag == TAG_SHORT:
        return struct.pack(">h", value)
    if tag == TAG_INT:
        return struct.pack(">i", value)
    if tag == TAG_STRING:
        return nbt_string(value)
    if tag == TAG_BYTE_ARRAY:
        return struct.pack(">i", len(value)) + bytes(value)
    if tag == TAG_INT_ARRAY:
        return struct.pack(">i", len(value)) + b"".join(struct.pack(">i", v) for v in value)
    if tag == TAG_LIST:
        element_tag, items = value
        return struct.pack(">bi", element_tag, len(items)) + b"".join(payload(element_tag, item) for item in items)
    if tag == TAG_COMPOUND:
        out = b""
        for name, (child_tag, child_value) in value.items():
            out += struct.pack(">b", child_tag) + nbt_string(name) + payload(child_tag, child_value)
        return out + struct.pack(">b", TAG_END)
    raise ValueError(tag)


def varint(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return out


def build():
    blocks = {}

    def put(x, y, z, state):
        blocks[(x, y, z)] = state

    hull = "minecraft:polished_blackstone"
    accent = "minecraft:red_concrete"
    plate = "minecraft:polished_blackstone_slab[type=bottom]"
    # Fuselage (z 0 → 10), cockpit vitré à l'avant
    for z in range(0, 11):
        put(4, 1, z, hull)
        put(3, 1, z, hull if 1 <= z <= 8 else "minecraft:air")
        put(5, 1, z, hull if 1 <= z <= 8 else "minecraft:air")
        if 2 <= z <= 7:
            put(4, 2, z, accent if z <= 4 else hull)
        put(4, 0, z, hull if 2 <= z <= 8 else "minecraft:air")
    put(4, 2, 8, "minecraft:red_stained_glass")
    put(4, 2, 9, "minecraft:red_stained_glass")
    put(4, 1, 11, "minecraft:blackstone_wall")
    # Ailes en flèche
    for x in range(0, 9):
        reach = abs(x - 4)
        for z in range(1 + reach // 2, 6 - reach // 3):
            if (x, 1, z) not in blocks or blocks[(x, 1, z)] == "minecraft:air":
                put(x, 1, z, plate if reach >= 3 else "minecraft:gray_concrete")
    for x in (0, 8):
        put(x, 2, 3, accent)
        put(x, 1, 5, "minecraft:end_rod[facing=south]")  # canons en bout d’aile
    # Dérives et réacteurs
    put(3, 2, 1, "minecraft:blackstone_wall")
    put(5, 2, 1, "minecraft:blackstone_wall")
    put(3, 1, 0, "minecraft:magma_block")
    put(5, 1, 0, "minecraft:magma_block")
    put(4, 1, 0, "minecraft:shroomlight")
    return {k: v for k, v in blocks.items() if v != "minecraft:air"}


def main():
    blocks = build()
    width = max(x for x, _, _ in blocks) + 1
    height = max(y for _, y, _ in blocks) + 1
    length = max(z for _, _, z in blocks) + 1
    palette = {"minecraft:air": 0}
    data = bytearray()
    for y in range(height):
        for z in range(length):
            for x in range(width):
                state = blocks.get((x, y, z), "minecraft:air")
                if state not in palette:
                    palette[state] = len(palette)
                data += varint(palette[state])
    root = {
        "Version": (TAG_INT, 2),
        "DataVersion": (TAG_INT, 3955),
        "Width": (TAG_SHORT, width),
        "Height": (TAG_SHORT, height),
        "Length": (TAG_SHORT, length),
        "PaletteMax": (TAG_INT, len(palette)),
        "Palette": (TAG_COMPOUND, {name: (TAG_INT, index) for name, index in palette.items()}),
        "BlockData": (TAG_BYTE_ARRAY, list(data)),
        "BlockEntities": (TAG_LIST, (TAG_COMPOUND, [])),
        "Offset": (TAG_INT_ARRAY, [0, 0, 0]),
    }
    raw = struct.pack(">b", TAG_COMPOUND) + nbt_string("Schematic") + payload(TAG_COMPOUND, root)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(OUT, "wb") as f:
        f.write(raw)
    print(f"{OUT.name} : {len(blocks)} blocs, {width}x{height}x{length}")


if __name__ == "__main__":
    main()
