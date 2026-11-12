"""Petite DSL Python qui produit le JSON vanilla des density functions, splines et règles de surface.

Chaque fonction renvoie un dict (ou un nombre pour une constante) directement sérialisable. Les fonctions
enregistrées sous un identifiant sont référencées par leur nom (« odyssea:terra/height ») : vanilla accepte
indifféremment une référence ou une définition en ligne partout où une density function est attendue.
"""

from functools import reduce

SEED_NOISE = "odyssea:seed"
MAX_DOUBLE = 1.7976931348623157e308


# ───────────────────────────── Density functions ─────────────────────────────

def add(a, b):
    return {"type": "minecraft:add", "argument1": a, "argument2": b}


def mul(a, b):
    return {"type": "minecraft:mul", "argument1": a, "argument2": b}


def total(*terms):
    return reduce(add, terms)


def neg(a):
    return mul(-1.0, a)


def sub(a, b):
    return add(a, neg(b))


def lerp(a, b, t):
    """a + (b − a)·t"""
    return add(a, mul(t, sub(b, a)))


def fmax(a, b):
    return {"type": "minecraft:max", "argument1": a, "argument2": b}


def fmin(a, b):
    return {"type": "minecraft:min", "argument1": a, "argument2": b}


def clamp(x, lo, hi):
    # « clamp » est le seul type vanilla dont l'entrée n'accepte pas de référence par identifiant.
    if isinstance(x, str):
        x = add(0.0, x)
    return {"type": "minecraft:clamp", "input": x, "min": lo, "max": hi}


def fabs(x):
    return {"type": "minecraft:abs", "argument": x}


def square(x):
    return {"type": "minecraft:square", "argument": x}


def squeeze(x):
    return {"type": "minecraft:squeeze", "argument": x}


def flat_cache(x):
    return {"type": "minecraft:flat_cache", "argument": x}


def cache_2d(x):
    return {"type": "minecraft:cache_2d", "argument": x}


def cached(x):
    """Fonction 2D mise en cache sur la grille du chunk (flat_cache) ET sur la dernière colonne demandée
    (cache_2d). Le second cache est indispensable : l'aquifère et la surface préliminaire interrogent des colonnes
    hors du chunk, une fois par pas de 8 blocs ; sans lui le relief entier serait recalculé à chaque pas."""
    return flat_cache(cache_2d(x))


def carve(value, floor, weight):
    """value − max(value − floor, 0)·weight, en n'évaluant value qu'une fois."""
    return {"type": "odyssea:carve", "input": value, "floor": floor, "weight": weight}


def cache_once(x):
    return {"type": "minecraft:cache_once", "argument": x}


def interpolated(x):
    return {"type": "minecraft:interpolated", "argument": x}


def blend_density(x):
    return {"type": "minecraft:blend_density", "argument": x}


def y_gradient(from_y, to_y, from_value, to_value):
    return {"type": "minecraft:y_clamped_gradient", "from_y": from_y, "to_y": to_y,
            "from_value": from_value, "to_value": to_value}


def linear_y(min_y, max_y, per_block):
    """−y·per_block, exact sur [min_y, max_y]."""
    return y_gradient(min_y, max_y, -min_y * per_block, -max_y * per_block)


def noise(name, xz=1.0, y=1.0):
    return {"type": "minecraft:noise", "noise": name, "xz_scale": xz, "y_scale": y}


def shifted_noise_2d(name, xz=0.25):
    return {"type": "minecraft:shifted_noise", "noise": name, "xz_scale": xz, "y_scale": 0.0,
            "shift_x": "minecraft:shift_x", "shift_y": 0.0, "shift_z": "minecraft:shift_z"}


def range_choice(value, lo, hi, inside, outside):
    return {"type": "minecraft:range_choice", "input": value, "min_inclusive": lo, "max_exclusive": hi,
            "when_in_range": inside, "when_out_of_range": outside}


def spline_points(coordinate, points):
    """points : (position, valeur, dérivée = 0). La valeur peut être une spline imbriquée."""
    out = []
    for p in points:
        location, value = p[0], p[1]
        derivative = p[2] if len(p) > 2 else 0.0
        out.append({"location": location, "value": value, "derivative": derivative})
    return {"coordinate": coordinate, "points": out}


def spline(coordinate, points):
    return {"type": "minecraft:spline", "spline": spline_points(coordinate, points)}


def smooth_band(coordinate, lo, hi, width):
    """≈1 sur [lo, hi], 0 au-delà de « width » de chaque côté, raccords à dérivée nulle."""
    return spline(coordinate, [(lo - width, 0.0), (lo, 1.0), (hi, 1.0), (hi + width, 0.0)])


def ramp(coordinate, x0, x1, v0=0.0, v1=1.0):
    return spline(coordinate, [(x0, v0), (x1, v1)])


# ───────────────────────────── Types Odyssea ─────────────────────────────

def fractal(salt, scale, mode="fbm", octaves=6, lacunarity=2.0, gain=0.5, erosion=0.0, warp=0.0,
            warp_scale=1000.0, stretch=1.0, angle=0.0):
    df = {"type": "odyssea:fractal", "noise": SEED_NOISE, "salt": salt, "mode": mode, "scale": scale,
          "octaves": octaves}
    if lacunarity != 2.0:
        df["lacunarity"] = lacunarity
    if gain != 0.5:
        df["gain"] = gain
    if erosion:
        df["erosion"] = erosion
    if warp:
        df["warp"] = warp
        df["warp_scale"] = warp_scale
    if stretch != 1.0:
        df["stretch"] = stretch
        df["angle"] = angle
    return df


def craters(salt, cell_size, min_radius, max_radius, chance=0.5, size_power=2.0, depth_ratio=0.4,
            complex_radius=120.0, rim_ratio=0.22, irregularity=0.08, degradation=0.5):
    return {"type": "odyssea:craters", "noise": SEED_NOISE, "salt": salt, "cell_size": cell_size,
            "chance": chance, "min_radius": min_radius, "max_radius": max_radius, "size_power": size_power,
            "depth_ratio": depth_ratio, "complex_radius": complex_radius, "rim_ratio": rim_ratio,
            "irregularity": irregularity, "degradation": degradation}


def volcanoes(salt, cell_size, min_radius, max_radius, height_ratio, shape="shield", chance=0.3,
              size_power=1.5, scarp=0.0, caldera_ratio=0.1, caldera_depth=0.12, irregularity=0.1):
    return {"type": "odyssea:volcanoes", "noise": SEED_NOISE, "salt": salt, "cell_size": cell_size,
            "chance": chance, "min_radius": min_radius, "max_radius": max_radius, "size_power": size_power,
            "height_ratio": height_ratio, "shape": shape, "scarp": scarp, "caldera_ratio": caldera_ratio,
            "caldera_depth": caldera_depth, "irregularity": irregularity}


def terrace(value, step, riser=0.3, offset=0.0):
    return {"type": "odyssea:terrace", "input": value, "step": step, "riser": riser, "offset": offset}


# ───────────────────────────── Règles de surface ─────────────────────────────

def block(name, properties=None):
    state = {"Name": name}
    if properties:
        state["Properties"] = properties
    return {"type": "minecraft:block", "result_state": state}


def sequence(*rules):
    return {"type": "minecraft:sequence", "sequence": list(rules)}


def if_true(condition, then):
    return {"type": "minecraft:condition", "if_true": condition, "then_run": then}


def biome_is(*ids):
    return {"type": "minecraft:biome", "biome_is": list(ids)}


def stone_depth(offset, add_surface_depth, secondary_depth_range, surface_type):
    return {"type": "minecraft:stone_depth", "offset": offset, "add_surface_depth": add_surface_depth,
            "secondary_depth_range": secondary_depth_range, "surface_type": surface_type}


ON_FLOOR = stone_depth(0, False, 0, "floor")
UNDER_FLOOR = stone_depth(0, True, 0, "floor")
DEEP_UNDER_FLOOR = stone_depth(0, True, 6, "floor")
VERY_DEEP_UNDER_FLOOR = stone_depth(0, True, 30, "floor")
ON_CEILING = stone_depth(0, False, 0, "ceiling")
STEEP = {"type": "minecraft:steep"}
HOLE = {"type": "minecraft:hole"}
COLD_ENOUGH_TO_SNOW = {"type": "minecraft:temperature"}
ABOVE_PRELIMINARY_SURFACE = {"type": "minecraft:above_preliminary_surface"}
BANDLANDS = {"type": "minecraft:bandlands"}


def y_block_check(absolute, multiplier):
    return {"type": "minecraft:y_above", "anchor": {"absolute": absolute},
            "surface_depth_multiplier": multiplier, "add_stone_depth": False}


def y_start_check(absolute, multiplier):
    return {"type": "minecraft:y_above", "anchor": {"absolute": absolute},
            "surface_depth_multiplier": multiplier, "add_stone_depth": True}


def water_block_check(offset, multiplier):
    return {"type": "minecraft:water", "offset": offset, "surface_depth_multiplier": multiplier,
            "add_stone_depth": False}


def water_start_check(offset, multiplier):
    return {"type": "minecraft:water", "offset": offset, "surface_depth_multiplier": multiplier,
            "add_stone_depth": True}


def noise_threshold(name, lo, hi=MAX_DOUBLE):
    return {"type": "minecraft:noise_threshold", "noise": name, "min_threshold": lo, "max_threshold": hi}


def surface_noise_above(value):
    return noise_threshold("minecraft:surface", value / 8.25)


def negate(condition):
    return {"type": "minecraft:not", "invert": condition}


def vertical_gradient(name, true_at_and_below, false_at_and_above):
    return {"type": "minecraft:vertical_gradient", "random_name": name,
            "true_at_and_below": true_at_and_below, "false_at_and_above": false_at_and_above}


def bedrock_floor():
    return if_true(vertical_gradient("minecraft:bedrock_floor", {"above_bottom": 0}, {"above_bottom": 5}),
                   block("minecraft:bedrock"))


def deepslate_below(y_from, y_to, name="minecraft:deepslate"):
    return if_true(vertical_gradient(name, {"absolute": y_from}, {"absolute": y_to}),
                   block("minecraft:deepslate", {"axis": "y"}))
