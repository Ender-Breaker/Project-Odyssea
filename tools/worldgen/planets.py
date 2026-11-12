"""Planètes et lunes d'Odyssea : relief, biomes, règles de surface, types de dimension.

Chaque corps solide décrit son altitude H(x, z) en blocs à partir des briques Java (fractal, craters,
volcanoes, terrace) et de quelques masques régionaux. Les masques alimentent aussi les paramètres climatiques
du routeur, qui ne servent ici qu'à choisir le biome : le biome suit exactement la géologie (mers lunaires,
canyons, volcans, calottes polaires…).

Échelle : celle de Minecraft vanilla. Les reliefs culminent entre 200 et 330 blocs, les cratères géants font
quelques centaines de blocs de rayon. Le monde va de Y = −64 (bedrock) à Y = 2031 (limite de construction), mais le
bruit ne couvre que −64 → 448 : la génération reste rapide.

Uranus est une géante de glaces : sous l'atmosphère, un océan d'eau couvert d'une banquise et de crêtes de glace de
méthane et d'ammoniac. Jupiter, Saturne et Neptune n'ont pas de sol : leur dimension est vide et le « paysage » est
la mer de nuages dessinée par le shader céleste, très loin sous le joueur.
"""

from dsl import *

NS = "odyssea"
MIN_Y = -64
DIMENSION_TOP = 2032  # limite du moteur : on construit jusqu'à Y = 2031
NOISE_TOP = 448

ALL_BIOME_FEATURE_STEPS = 11
LOCAL_MODIFICATIONS = 2
SURFACE_STRUCTURES = 4
UNDERGROUND_ORES = 6
TOP_LAYER_MODIFICATION = 10
SPAWN_CATEGORIES = ["monster", "creature", "ambient", "axolotls", "underground_water_creature",
                    "water_creature", "water_ambient", "misc"]


def rgb(r, g, b):
    return (r << 16) | (g << 8) | b


def O(name, props=None):
    """Bloc Odyssea."""
    return block(f"odyssea:{name}", props)


def B(name, props=None):
    """Bloc vanilla."""
    return block(f"minecraft:{name}", props)


# ───────────────────────────── Briques communes ─────────────────────────────

class Planet:
    def __init__(self, name, noise_top=NOISE_TOP, sea_level=MIN_Y, default_fluid="minecraft:air", crag_amplitude=0.25):
        self.name = name
        self.noise_top = noise_top
        self.sea_level = sea_level
        self.default_fluid = default_fluid
        self.crag_amplitude = crag_amplitude
        self.dfs = {}
        self.biomes = {}
        self.biome_parameters = []

    def ref(self, path):
        return f"{NS}:{self.name}/{path}"

    def define(self, path, value):
        self.dfs[f"{self.name}/{path}"] = value
        return self.ref(path)

    def finish_terrain(self, height, crag_amplitude=None):
        """Pente de densité, bruit 3D et grottes à partir de l'altitude."""
        h = self.define("height", cached(clamp(height, MIN_Y + 40.0, self.noise_top - 60.0)))
        slope = self.define("slope", add(mul(h, 0.125), linear_y(MIN_Y, self.noise_top, 0.125)))
        amplitude = crag_amplitude if crag_amplitude is not None else self.crag_amplitude
        self.define("sloped_terrain", cache_once(add(slope, mul(amplitude, noise(f"{NS}:planet/crag", 1.0, 0.7)))))
        self.define("depth", add(mul(fmin(h, 200.0), 1.0 / 128.0), linear_y(MIN_Y, DIMENSION_TOP, 1.0 / 128.0)))
        # Tunnels de lave : intersection des lignes de zéro de deux bruits, entre 16 et 90 blocs sous la surface.
        tubes = add(mul(1.6, fmax(fabs(noise("minecraft:noodle_ridge_a", 0.55, 1.6)),
                                  fabs(noise("minecraft:noodle_ridge_b", 0.55, 1.6)))), -0.11)
        tubes = range_choice(slope, 2.0, 11.0, tubes, 64.0)
        # Cavernes profondes (plus de 100 blocs sous la surface).
        caverns = range_choice(slope, 13.0, 1000000.0, add(0.42, noise("minecraft:cave_cheese", 1.2, 0.8)), 64.0)
        self.define("caves", fmin(tubes, caverns))
        return h

    def router(self, temperature=0.0, vegetation=0.0, continents=0.0, erosion=0.0, ridges=0.0):
        slope = self.ref("slope")
        terrain = fmin(self.ref("sloped_terrain"), self.ref("caves"))
        terrain = range_choice(slope, -1000000.0, -4.0, -1.0, terrain)
        final = squeeze(mul(0.64, interpolated(blend_density(slide(terrain, MIN_Y, self.noise_top)))))
        return {
            "barrier": 0.0, "fluid_level_floodedness": 0.0, "fluid_level_spread": 0.0, "lava": 0.0,
            "temperature": temperature, "vegetation": vegetation, "continents": continents,
            "erosion": erosion, "depth": self.ref("depth"), "ridges": ridges,
            "initial_density_without_jaggedness": slide(clamp(slope, -64.0, 64.0), MIN_Y, self.noise_top),
            "final_density": final,
            "vein_toggle": 0.0, "vein_ridged": 0.0, "vein_gap": 0.0,
        }

    def biome(self, path, parameters, **effects):
        biome_id = f"{NS}:{self.name}_{path}"
        self.biomes[f"{self.name}_{path}"] = biome_json(**effects)
        p = {"temperature": [-1.0, 1.0], "humidity": [-1.0, 1.0], "continentalness": [-1.0, 1.0],
             "erosion": [-1.0, 1.0], "weirdness": [-1.0, 1.0], "depth": 0.0, "offset": 0.0}
        p.update(parameters)
        self.biome_parameters.append({"biome": biome_id, "parameters": p})
        return biome_id


def slide(value, min_y, top):
    bottom = y_gradient(min_y, min_y + 24, 0.0, 1.0)
    upper = y_gradient(top - 80, top - 32, 1.0, 0.0)
    return add(0.1171875, mul(bottom, add(-0.1171875, add(-0.078125, mul(upper, add(0.078125, value))))))


ROCKY_ORES = [
    "minecraft:ore_iron_upper", "minecraft:ore_iron_middle", "minecraft:ore_iron_small",
    "minecraft:ore_copper", "minecraft:ore_gold", "minecraft:ore_gold_lower",
    "minecraft:ore_redstone", "minecraft:ore_redstone_lower",
    "minecraft:ore_diamond", "minecraft:ore_diamond_medium", "minecraft:ore_diamond_large",
]


def biome_json(sky, fog, temperature=0.5, downfall=0.0, ores=True, particle=None, water=rgb(63, 118, 228),
               water_fog=rgb(5, 5, 51), extra_features=None):
    features = [[] for _ in range(ALL_BIOME_FEATURE_STEPS)]
    if ores:
        features[UNDERGROUND_ORES] = list(ROCKY_ORES)
    for step, entries in (extra_features or {}).items():
        features[step].extend(entries)
    effects = {"sky_color": sky, "fog_color": fog, "water_color": water, "water_fog_color": water_fog}
    if particle:
        effects["particle"] = particle
    return {
        "has_precipitation": False,
        "temperature": temperature,
        "downfall": downfall,
        "effects": effects,
        "spawners": {category: [] for category in SPAWN_CATEGORIES},
        "spawn_costs": {},
        "carvers": {},
        "features": features,
    }


def dust_particle(r, g, b, probability, scale=1.0):
    return {"options": {"type": "minecraft:dust", "color": [r, g, b], "scale": scale}, "probability": probability}


def under(depth):
    """Vrai jusqu'à « depth » blocs sous la surface (en plus de la profondeur de surface variable)."""
    return stone_depth(0, True, depth, "floor")


def noise_settings(planet, router, surface, default_block="minecraft:stone"):
    return {
        "sea_level": planet.sea_level,
        "disable_mob_generation": True,
        "aquifers_enabled": False,
        "ore_veins_enabled": False,
        "legacy_random_source": False,
        "default_block": {"Name": default_block},
        "default_fluid": {"Name": planet.default_fluid} if planet.default_fluid == "minecraft:air"
        else {"Name": planet.default_fluid, "Properties": {"level": "0"}},
        "noise": {"min_y": MIN_Y, "height": planet.noise_top - MIN_Y, "size_horizontal": 1, "size_vertical": 2},
        "noise_router": router,
        "spawn_target": [{"temperature": [-1.0, 1.0], "humidity": [-1.0, 1.0], "continentalness": [-1.0, 1.0],
                          "erosion": [-1.0, 1.0], "weirdness": [-1.0, 1.0], "depth": 0.0, "offset": 0.0}],
        "surface_rule": surface,
    }


def dimension_type(name, natural=True, ambient_light=0.0, ultrawarm=False, infiniburn="#minecraft:infiniburn_overworld"):
    height = DIMENSION_TOP - MIN_Y
    return {
        "ultrawarm": ultrawarm, "natural": natural, "piglin_safe": False, "respawn_anchor_works": False,
        "bed_works": True, "has_raids": False, "has_skylight": True, "has_ceiling": False,
        "coordinate_scale": 1.0, "ambient_light": ambient_light, "logical_height": height,
        "effects": f"{NS}:{name}", "infiniburn": infiniburn,
        "min_y": MIN_Y, "height": height,
        "monster_spawn_light_level": 0, "monster_spawn_block_light_limit": 0,
    }


def noise_dimension(planet):
    return {
        "type": f"{NS}:{planet.name}",
        "generator": {
            "type": "minecraft:noise",
            "settings": f"{NS}:{planet.name}",
            "biome_source": {"type": "minecraft:multi_noise", "biomes": planet.biome_parameters},
        },
    }


def unit(value):
    """Ramène un masque [0, 1] dans l'intervalle climatique [−1, 1]."""
    return add(mul(value, 2.0), -1.0)


# ───────────────────────────── Luna ─────────────────────────────

def luna():
    p = Planet("luna", crag_amplitude=0.2)
    maria = p.define("maria", cached(spline(fractal("luna/maria", 3400.0, octaves=4, warp=800.0, warp_scale=2800.0),
                                            [(-1.0, 0.0), (0.08, 0.0), (0.26, 1.0), (1.0, 1.0)])))
    highlands = total(108.0,
                      mul(32.0, fractal("luna/highlands", 800.0, octaves=6, erosion=0.04)),
                      mul(110.0, square(fractal("luna/montes", 2000.0, mode="ridged", octaves=5, warp=400.0, warp_scale=2000.0))))
    mare_floor = total(66.0, mul(2.0, fractal("luna/mare_swell", 260.0, octaves=3)),
                       mul(5.0, fractal("luna/wrinkle_ridges", 700.0, mode="ridged", octaves=3, stretch=3.0, angle=32.0)))
    base = lerp(highlands, mare_floor, maria)
    impacts = total(
        craters("luna/basins", 2200.0, 280.0, 620.0, chance=0.5, size_power=1.5, depth_ratio=0.3, complex_radius=60.0,
                rim_ratio=0.36, irregularity=0.12, degradation=0.5),
        craters("luna/large", 520.0, 45.0, 210.0, chance=0.62, size_power=1.7, depth_ratio=0.36, complex_radius=60.0,
                rim_ratio=0.32, irregularity=0.1, degradation=0.5),
        craters("luna/medium", 130.0, 7.0, 42.0, chance=0.66, size_power=2.0, depth_ratio=0.42, complex_radius=60.0,
                rim_ratio=0.26, degradation=0.6),
    )
    small = craters("luna/small", 28.0, 2.0, 8.0, chance=0.5, size_power=2.2, depth_ratio=0.42, rim_ratio=0.2, degradation=0.7)
    # Les mers, plus jeunes, portent moins de cratères (seuls les petits impacts récents y restent nombreux).
    impacts = add(mul(impacts, sub(1.0, mul(0.7, maria))), small)
    rilles = mul(mul(smooth_band(fractal("luna/rilles", 1800.0, octaves=2, warp=400.0, warp_scale=1300.0), -0.008, 0.008, 0.02),
                     maria), 12.0)
    p.finish_terrain(total(base, impacts, neg(rilles)))

    hl = p.biome("highlands", {"continentalness": [-1.0, 0.0]}, sky=0, fog=0, temperature=0.0)
    mare = p.biome("maria", {"continentalness": [0.0, 1.0]}, sky=0, fog=0, temperature=0.0)
    router = p.router(continents=unit(maria))

    surface = sequence(
        bedrock_floor(),
        if_true(biome_is(mare), sequence(
            if_true(ON_FLOOR, sequence(if_true(noise_threshold("minecraft:surface", 0.5), O("lunar_regolith")), O("lunar_mare_regolith"))),
            if_true(UNDER_FLOOR, O("lunar_mare_regolith")),
            if_true(under(12), O("lunar_basalt")),
        )),
        if_true(biome_is(hl), sequence(
            if_true(ON_FLOOR, sequence(if_true(STEEP, O("anorthosite")), O("lunar_regolith"))),
            if_true(UNDER_FLOOR, O("lunar_regolith")),
        )),
        if_true(ON_CEILING, O("lunar_basalt")),
        deepslate_below(-16, 0),
    )
    return p, noise_settings(p, router, surface, "odyssea:anorthosite"), dimension_type("luna")


# ───────────────────────────── Mercure ─────────────────────────────

def mercury():
    p = Planet("mercury", crag_amplitude=0.25)
    plains = p.define("smooth_plains", cached(spline(fractal("mercury/plains", 4000.0, octaves=3, warp=900.0, warp_scale=3000.0),
                                                     [(-1.0, 0.0), (0.12, 0.0), (0.3, 1.0), (1.0, 1.0)])))
    rugged = total(100.0, mul(28.0, fractal("mercury/terrain", 900.0, octaves=6, erosion=0.05)),
                   mul(55.0, square(fractal("mercury/massifs", 1900.0, mode="ridged", octaves=5))))
    smooth = add(82.0, mul(3.0, fractal("mercury/plains_swell", 300.0, octaves=3)))
    base = lerp(rugged, smooth, plains)
    # Escarpements lobés : failles de chevauchement nées de la contraction de la planète. Front abrupt d'une
    # cinquantaine de blocs, dos en pente douce, sur plusieurs kilomètres.
    scarp_noise = fractal("mercury/scarps", 2800.0, octaves=4, warp=700.0, warp_scale=2200.0)
    scarps = mul(spline(scarp_noise, [(-1.0, 0.0), (-0.02, 0.0), (0.02, 1.0), (0.3, 0.25), (0.6, 0.0), (1.0, 0.0)]), 45.0)
    impacts = total(
        craters("mercury/basin", 10000.0, 900.0, 1500.0, chance=0.3, size_power=1.2, depth_ratio=0.26, complex_radius=60.0,
                rim_ratio=0.35, irregularity=0.08, degradation=0.4),
        craters("mercury/large", 520.0, 45.0, 230.0, chance=0.62, size_power=1.7, depth_ratio=0.36, complex_radius=60.0,
                rim_ratio=0.32, irregularity=0.1, degradation=0.5),
        craters("mercury/medium", 120.0, 7.0, 42.0, chance=0.66, size_power=2.0, depth_ratio=0.42, complex_radius=60.0,
                rim_ratio=0.26, degradation=0.6),
        craters("mercury/small", 28.0, 2.0, 8.0, chance=0.55, size_power=2.2, depth_ratio=0.42, rim_ratio=0.2, degradation=0.8),
    )
    impacts = mul(impacts, sub(1.0, mul(0.45, plains)))
    p.finish_terrain(total(base, scarps, impacts))
    # Creux brillants (« hollows ») : dépressions claires, signalées au biome par l'érosion.
    hollows = p.define("hollows", cached(fractal("mercury/hollows", 450.0, octaves=3)))

    p.biome("smooth_plains", {"continentalness": [0.0, 1.0], "erosion": [-1.0, 0.55]}, sky=0, fog=0, temperature=2.0)
    p.biome("cratered_terrain", {"continentalness": [-1.0, 0.0], "erosion": [-1.0, 0.55]}, sky=0, fog=0, temperature=2.0)
    hollow_biome = p.biome("hollows", {"erosion": [0.55, 1.0]}, sky=0, fog=0, temperature=2.0)
    router = p.router(continents=unit(plains), erosion=hollows)

    surface = sequence(
        bedrock_floor(),
        if_true(biome_is(hollow_biome), sequence(
            if_true(ON_FLOOR, O("hollow_crust")),
            if_true(under(4), O("hollow_crust")),
        )),
        if_true(ON_FLOOR, sequence(if_true(STEEP, O("mercurian_rock")), O("mercurian_regolith"))),
        if_true(UNDER_FLOOR, O("mercurian_regolith")),
        if_true(under(12), sequence(if_true(noise_threshold("minecraft:calcite", -0.2, 0.2), B("smooth_basalt")), O("mercurian_rock"))),
        deepslate_below(-16, 0),
    )
    return p, noise_settings(p, router, surface, "odyssea:mercurian_rock"), dimension_type("mercury")


# ───────────────────────────── Vénus ─────────────────────────────

def venus():
    # Les dépressions sous Y = 52 se remplissent de lave : lacs de lave dans les caldeiras et les cratères.
    p = Planet("venus", sea_level=52, default_fluid="minecraft:lava", crag_amplitude=0.35)
    tess = p.define("tesserae_mask", cached(spline(fractal("venus/tesserae", 6000.0, octaves=4, warp=1400.0, warp_scale=3600.0),
                                                   [(-1.0, 0.0), (0.28, 0.0), (0.48, 1.0), (1.0, 1.0)])))
    flows = terrace(mul(8.0, fractal("venus/flows", 420.0, octaves=4, warp=140.0, warp_scale=400.0)), 3.0, 0.22)
    plains = total(72.0, mul(10.0, fractal("venus/plains", 1800.0, octaves=5)), flows)
    tesserae = total(140.0, mul(30.0, fractal("venus/tesserae_relief", 1300.0, octaves=6, erosion=0.04)),
                     mul(36.0, fmax(fractal("venus/tess_a", 200.0, mode="ridged", octaves=5, stretch=4.0, angle=25.0),
                                    fractal("venus/tess_b", 180.0, mode="ridged", octaves=5, stretch=4.0, angle=-55.0))))
    base = lerp(plains, tesserae, tess)
    big_shields = volcanoes("venus/shields", 7000.0, 700.0, 1400.0, 0.15, shape="shield", chance=0.42,
                            caldera_ratio=0.08, caldera_depth=0.12)
    small_shields = volcanoes("venus/small_shields", 350.0, 25.0, 90.0, 0.17, shape="shield", chance=0.22,
                              caldera_ratio=0.25, caldera_depth=0.22)
    domes = volcanoes("venus/pancake_domes", 1100.0, 45.0, 120.0, 0.24, shape="dome", chance=0.35,
                      caldera_ratio=0.12, caldera_depth=0.12, irregularity=0.05)
    volcanic = p.define("volcanic", cached(total(big_shields, small_shields, domes)))
    coronae = craters("venus/coronae", 4000.0, 350.0, 800.0, chance=0.3, size_power=1.3, depth_ratio=0.03,
                      complex_radius=100000.0, rim_ratio=3.0, irregularity=0.22, degradation=0.3)
    # Cratères rares et intacts : l'atmosphère détruit les petits impacteurs, la surface est jeune.
    impacts = craters("venus/impacts", 2800.0, 50.0, 220.0, chance=0.22, size_power=1.6, depth_ratio=0.24,
                      complex_radius=60.0, rim_ratio=0.32, degradation=0.1)
    p.finish_terrain(total(base, volcanic, coronae, impacts), crag_amplitude=add(0.3, mul(0.9, tess)))

    sky, fog = rgb(196, 140, 70), rgb(176, 122, 58)
    ash = {"options": {"type": "minecraft:white_ash"}, "probability": 0.004}
    p.biome("volcanic_plains", {"continentalness": [-1.0, 0.0], "weirdness": [-1.0, 0.0]},
            sky=sky, fog=fog, temperature=2.0, particle=ash)
    tess_biome = p.biome("tesserae", {"continentalness": [0.0, 1.0], "weirdness": [-1.0, 0.0]},
                         sky=sky, fog=fog, temperature=2.0, particle=ash)
    volcano_biome = p.biome("volcanoes", {"weirdness": [0.0, 1.0]}, sky=rgb(190, 120, 60), fog=rgb(166, 100, 48),
                            temperature=2.0, particle={"options": {"type": "minecraft:ash"}, "probability": 0.02})
    router = p.router(continents=unit(tess), ridges=clamp(add(mul(volcanic, 1.0 / 25.0), -1.0), -1.0, 1.0))

    surface = sequence(
        bedrock_floor(),
        if_true(biome_is(volcano_biome), sequence(
            if_true(ON_FLOOR, sequence(
                if_true(noise_threshold("minecraft:surface", 0.62), B("magma_block")),
                if_true(noise_threshold("minecraft:surface", 0.3), O("sulfur_crust")),
                if_true(noise_threshold("minecraft:surface", -0.2), O("venusian_basalt")),
                B("basalt", {"axis": "y"}))),
            if_true(under(14), O("venusian_basalt")),
        )),
        if_true(biome_is(tess_biome), sequence(
            if_true(ON_FLOOR, sequence(if_true(STEEP, O("venusian_basalt")),
                                       if_true(noise_threshold("minecraft:surface", 0.2), B("tuff")), O("venusian_regolith"))),
            if_true(under(10), B("tuff")),
        )),
        if_true(ON_FLOOR, sequence(
            if_true(noise_threshold("minecraft:surface", 0.45), O("venusian_basalt")),
            if_true(noise_threshold("minecraft:surface", 0.25), B("blackstone")),
            O("venusian_regolith"))),
        if_true(UNDER_FLOOR, O("venusian_regolith")),
        deepslate_below(-16, 0),
    )
    return p, noise_settings(p, router, surface, "odyssea:venusian_basalt"), dimension_type("venus", ultrawarm=True)


# ───────────────────────────── Mars ─────────────────────────────

def mars():
    p = Planet("mars", crag_amplitude=0.3)
    # Dichotomie crustale : basses plaines du nord, hauts plateaux cratérisés du sud.
    dichotomy = p.define("dichotomy", cached(spline(fractal("mars/dichotomy", 10000.0, octaves=3, warp=2400.0, warp_scale=6400.0),
                                                    [(-1.0, 0.0), (-0.12, 0.0), (0.16, 1.0), (1.0, 1.0)])))
    lowlands = add(66.0, mul(6.0, fractal("mars/lowlands", 1400.0, octaves=5)))
    highlands = total(112.0, mul(24.0, fractal("mars/highlands", 1100.0, octaves=6, erosion=0.04)),
                      mul(45.0, square(fractal("mars/massifs", 2400.0, mode="ridged", octaves=5, warp=500.0, warp_scale=2400.0))))
    base = lerp(lowlands, highlands, dichotomy)
    impacts = total(
        craters("mars/large", 750.0, 55.0, 240.0, chance=0.6, size_power=1.7, depth_ratio=0.34, complex_radius=60.0,
                rim_ratio=0.32, irregularity=0.12, degradation=0.6),
        craters("mars/medium", 140.0, 8.0, 50.0, chance=0.6, size_power=2.0, depth_ratio=0.42, complex_radius=60.0,
                rim_ratio=0.26, degradation=0.6),
        craters("mars/small", 32.0, 2.0, 9.0, chance=0.45, size_power=2.2, depth_ratio=0.4, rim_ratio=0.2, degradation=0.6),
    )
    impacts = mul(impacts, add(0.3, mul(0.7, dichotomy)))
    # Province de Tharsis : boucliers géants à escarpement basal (Olympus Mons) et paterae plus modestes.
    giants = volcanoes("mars/tharsis", 9000.0, 1100.0, 1800.0, 0.1, shape="shield", chance=0.5, size_power=1.2,
                       scarp=0.22, caldera_ratio=0.09, caldera_depth=0.12)
    paterae = volcanoes("mars/paterae", 2200.0, 220.0, 500.0, 0.12, shape="shield", chance=0.35,
                        caldera_ratio=0.15, caldera_depth=0.15)
    volcanic = p.define("volcanic", cached(add(giants, paterae)))
    # Valles Marineris : tranchée sinueuse aux parois en gradins, dans certaines régions.
    valles_region = spline(fractal("mars/valles_region", 12000.0, octaves=2), [(0.1, 0.0), (0.32, 1.0)])
    valles_line = smooth_band(fractal("mars/valles", 6000.0, octaves=3, warp=1000.0, warp_scale=4000.0), -0.011, 0.011, 0.02)
    canyon_depth = p.define("canyon_depth", cached(terrace(mul(mul(valles_line, valles_region), 85.0), 12.0, 0.38)))
    # Chenaux de débâcle : seulement dans quelques régions, comme les grands chenaux d'écoulement martiens.
    channel_region = spline(fractal("mars/channel_region", 4500.0, octaves=2), [(0.2, 0.0), (0.4, 1.0)])
    channels = mul(mul(smooth_band(fractal("mars/channels", 1800.0, octaves=3, warp=380.0, warp_scale=1500.0), -0.007, 0.007, 0.016),
                       channel_region),
                   mul(18.0, sub(1.0, mul(0.5, dichotomy))))
    # Champs de dunes transverses dans les plaines du nord.
    dune_mask = p.define("dune_mask", cached(mul(spline(fractal("mars/dune_fields", 2800.0, octaves=3), [(0.3, 0.0), (0.55, 1.0)]),
                                                 sub(1.0, mul(0.8, dichotomy)))))
    dunes = mul(dune_mask, mul(6.0, fractal("mars/dunes", 60.0, mode="billow", octaves=3, stretch=3.5, angle=35.0)))
    # Calottes polaires : dépôts stratifiés de glace d'eau et de glace carbonique, dans les régions les plus froides.
    ice_mask = p.define("ice_mask", cached(spline(fractal("mars/polar", 16000.0, octaves=3, warp=3200.0, warp_scale=8000.0),
                                                  [(-1.0, 1.0), (-0.55, 1.0), (-0.42, 0.0), (1.0, 0.0)])))
    ice_cap = mul(ice_mask, add(22.0, terrace(mul(18.0, fractal("mars/polar_layers", 900.0, octaves=4, warp=200.0, warp_scale=800.0)), 4.0, 0.3)))
    p.finish_terrain(total(base, impacts, volcanic, neg(canyon_depth), neg(channels), dunes, ice_cap),
                     crag_amplitude=add(0.25, mul(0.015, canyon_depth)))

    sky, fog = rgb(214, 160, 110), rgb(196, 140, 96)
    dust = dust_particle(0.72, 0.42, 0.24, 0.003, 0.8)
    polar = p.biome("polar_cap", {"temperature": [-1.0, -0.2]}, sky=rgb(200, 168, 140), fog=rgb(190, 160, 136), temperature=-0.8)
    volcano_biome = p.biome("volcanic_plains", {"temperature": [-0.2, 1.0], "weirdness": [0.0, 1.0]}, sky=sky, fog=fog, temperature=0.4)
    canyon_biome = p.biome("canyon", {"temperature": [-0.2, 1.0], "weirdness": [-1.0, 0.0], "erosion": [0.0, 1.0]},
                           sky=sky, fog=fog, temperature=0.4, particle=dust)
    dune_biome = p.biome("dune_sea", {"temperature": [-0.2, 1.0], "weirdness": [-1.0, 0.0], "erosion": [-1.0, 0.0],
                                      "humidity": [0.0, 1.0]}, sky=sky, fog=fog, temperature=0.4, particle=dust)
    p.biome("southern_highlands", {"temperature": [-0.2, 1.0], "weirdness": [-1.0, 0.0], "erosion": [-1.0, 0.0],
                                   "humidity": [-1.0, 0.0], "continentalness": [0.0, 1.0]}, sky=sky, fog=fog, temperature=0.4, particle=dust)
    p.biome("northern_plains", {"temperature": [-0.2, 1.0], "weirdness": [-1.0, 0.0], "erosion": [-1.0, 0.0],
                                "humidity": [-1.0, 0.0], "continentalness": [-1.0, 0.0]}, sky=sky, fog=fog, temperature=0.4, particle=dust)
    router = p.router(
        temperature=sub(0.6, mul(1.6, ice_mask)),
        vegetation=unit(dune_mask),
        continents=unit(dichotomy),
        erosion=clamp(add(mul(canyon_depth, 1.0 / 12.0), -1.0), -1.0, 1.0),
        ridges=clamp(add(mul(volcanic, 1.0 / 30.0), -1.0), -1.0, 1.0),
    )

    regolith, rock = O("martian_regolith"), O("martian_rock")
    surface = sequence(
        bedrock_floor(),
        if_true(biome_is(polar), sequence(
            if_true(ON_FLOOR, sequence(if_true(STEEP, B("packed_ice")), if_true(noise_threshold("minecraft:surface", 0.3), O("dry_ice")),
                                       B("snow_block"))),
            if_true(under(18), sequence(if_true(noise_threshold("minecraft:packed_ice", -0.3, 0.3), O("dry_ice")), B("packed_ice"))),
        )),
        # Parois stratifiées : toute la colonne du canyon reçoit les strates de terre cuite.
        if_true(biome_is(canyon_biome), sequence(
            if_true(ON_FLOOR, if_true(negate(STEEP), regolith)),
            if_true(y_block_check(-40, 0), BANDLANDS),
        )),
        if_true(biome_is(volcano_biome), sequence(
            if_true(ON_FLOOR, sequence(if_true(noise_threshold("minecraft:surface", 0.25), regolith),
                                       if_true(STEEP, B("blackstone")), O("martian_basalt"))),
            if_true(under(16), O("martian_basalt")),
        )),
        if_true(biome_is(dune_biome), sequence(
            if_true(under(8), regolith),
            if_true(under(14), B("red_sandstone")),
        )),
        if_true(ON_FLOOR, sequence(
            if_true(STEEP, sequence(if_true(noise_threshold("minecraft:surface", 0.0), B("terracotta")), rock)),
            if_true(noise_threshold("minecraft:surface", 0.62), B("brown_terracotta")),
            if_true(noise_threshold("minecraft:gravel", 0.42), rock),
            regolith)),
        if_true(UNDER_FLOOR, regolith),
        if_true(under(12), sequence(if_true(noise_threshold("minecraft:calcite", -0.25, 0.25), B("orange_terracotta")), rock)),
        deepslate_below(-16, 0),
    )
    return p, noise_settings(p, router, surface, "odyssea:martian_rock"), dimension_type("mars")


# ───────────────────────────── Uranus ─────────────────────────────

def uranus():
    """Géante de glaces : un océan d'eau sous une banquise, des plateaux et des crêtes de glace de méthane et
    d'ammoniac, des icebergs ; un noyau rocheux en profondeur."""
    p = Planet("uranus", noise_top=384, sea_level=63, default_fluid="minecraft:water", crag_amplitude=0.3)
    shelf = p.define("ice_shelf", cached(spline(fractal("uranus/shelves", 2400.0, octaves=4, warp=550.0, warp_scale=1800.0),
                                                [(-1.0, 0.0), (-0.08, 0.0), (0.12, 1.0), (1.0, 1.0)])))
    ridge_fields = p.define("ice_ridges", cached(spline(fractal("uranus/ridge_fields", 3200.0, octaves=3),
                                                        [(0.2, 0.0), (0.45, 1.0)])))
    seafloor = total(26.0, mul(12.0, fractal("uranus/seafloor", 900.0, octaves=5)),
                     neg(mul(16.0, fractal("uranus/trenches", 1400.0, mode="ridged", octaves=3, warp=250.0, warp_scale=1000.0))))
    plateau = total(68.0, mul(4.0, fractal("uranus/shelf_relief", 500.0, octaves=4)),
                    terrace(mul(10.0, fractal("uranus/shelf_steps", 700.0, octaves=3)), 3.0, 0.25))
    base = lerp(seafloor, plateau, shelf)
    # Crêtes de glace (compression de la banquise, glaciers de méthane) jusqu'à ~110 blocs au-dessus de la mer.
    peaks = mul(mul(ridge_fields, shelf),
                mul(115.0, square(fractal("uranus/ice_peaks", 650.0, mode="ridged", octaves=6, warp=140.0, warp_scale=550.0))))
    # Crevasses : entailles étroites dans la banquise.
    crevasses = mul(mul(smooth_band(fractal("uranus/crevasses", 380.0, octaves=3, warp=70.0, warp_scale=280.0), -0.012, 0.012, 0.02),
                        shelf), 14.0)
    p.finish_terrain(total(base, peaks, neg(crevasses)), crag_amplitude=add(0.25, mul(0.8, ridge_fields)))

    sky, fog = rgb(150, 210, 214), rgb(170, 218, 220)
    water, water_fog = rgb(52, 140, 168), rgb(6, 38, 52)
    # Océan gelé en surface (freeze_top_layer) et icebergs de glace tassée et de glace bleue, comme les océans gelés.
    ocean_features = {LOCAL_MODIFICATIONS: ["minecraft:iceberg_packed", "minecraft:iceberg_blue"],
                      SURFACE_STRUCTURES: ["minecraft:blue_ice"],
                      TOP_LAYER_MODIFICATION: ["minecraft:freeze_top_layer"]}
    frozen = {TOP_LAYER_MODIFICATION: ["minecraft:freeze_top_layer"]}
    ocean = p.biome("frozen_ocean", {"continentalness": [-1.0, 0.0]}, sky=sky, fog=fog, temperature=-1.0,
                    water=water, water_fog=water_fog, ores=False, extra_features=ocean_features)
    shelf_biome = p.biome("ice_shelf", {"continentalness": [0.0, 1.0], "erosion": [-1.0, 0.0]}, sky=sky, fog=fog,
                          temperature=-1.0, water=water, water_fog=water_fog, ores=False, extra_features=frozen)
    ridges_biome = p.biome("ice_ridges", {"continentalness": [0.0, 1.0], "erosion": [0.0, 1.0]}, sky=sky, fog=fog,
                           temperature=-1.0, water=water, water_fog=water_fog, ores=False, extra_features=frozen)
    router = p.router(continents=unit(shelf), erosion=unit(ridge_fields))

    seabed = negate(y_block_check(60, 0))
    surface = sequence(
        bedrock_floor(),
        # Fond de l'océan : boue glacée sur la glace.
        if_true(seabed, sequence(
            if_true(ON_FLOOR, O("ice_slush")),
            if_true(under(4), O("ice_slush")),
        )),
        if_true(biome_is(ridges_biome), sequence(
            if_true(ON_FLOOR, sequence(if_true(STEEP, O("methane_ice")),
                                       if_true(noise_threshold("minecraft:surface", 0.3), O("methane_ice")),
                                       if_true(noise_threshold("minecraft:surface", -0.3), O("ammonia_ice")),
                                       O("uranian_ice"))),
            if_true(under(10), sequence(if_true(noise_threshold("minecraft:packed_ice", -0.25, 0.25), B("blue_ice")), O("methane_ice"))),
        )),
        if_true(biome_is(shelf_biome), sequence(
            if_true(ON_FLOOR, sequence(if_true(noise_threshold("minecraft:surface", 0.55), O("methane_ice")),
                                       if_true(noise_threshold("minecraft:surface", 0.15), B("snow_block")),
                                       if_true(noise_threshold("minecraft:surface", -0.45, -0.2), O("ammonia_ice")),
                                       O("uranian_ice"))),
            if_true(under(6), B("packed_ice")),
        )),
        if_true(biome_is(ocean), if_true(ON_FLOOR, B("packed_ice"))),
        # Noyau rocheux sous le manteau de glace.
        deepslate_below(-24, -8),
    )
    return p, noise_settings(p, router, surface, "odyssea:uranian_ice"), dimension_type("uranus")


# ───────────────────────────── Géantes gazeuses ─────────────────────────────

def gas_giant(name, sky, fog, temperature):
    biome_id = f"{NS}:{name}_cloud_deck"
    biome = biome_json(sky=sky, fog=fog, temperature=temperature, ores=False)
    dimension = {
        "type": f"{NS}:{name}",
        "generator": {
            "type": "minecraft:flat",
            "settings": {
                "biome": biome_id,
                "layers": [{"block": "minecraft:air", "height": 1}],
                "lakes": False,
                "features": False,
                "structure_overrides": [],
            },
        },
    }
    return {f"{name}_cloud_deck": biome}, dimension, dimension_type(name, natural=False)


def gas_giants():
    return [
        ("jupiter",) + gas_giant("jupiter", rgb(120, 150, 196), rgb(196, 176, 150), -0.9),
        ("saturn",) + gas_giant("saturn", rgb(170, 160, 130), rgb(214, 196, 150), -0.95),
        ("neptune",) + gas_giant("neptune", rgb(70, 110, 200), rgb(90, 130, 210), -1.0),
    ]


# ───────────────────────────── Espace ─────────────────────────────

# Plan de l'écliptique de la dimension spatiale (voir odyssea.space.SpaceSystem.ECLIPTIC_Y).
ECLIPTIC_Y = 1000


def space():
    """Dimension « odyssea:space » : le vide, des astéroïdes (denses dans la ceinture) et un éclairage solaire fixe.

    Le générateur de bruit ne calcule qu'une tranche de 16 blocs d'air ; les astéroïdes sont des features Java
    (odyssea:asteroid) qui décident elles-mêmes de leur densité selon la distance au Soleil.
    """
    biome = biome_json(sky=rgb(0, 0, 0), fog=rgb(0, 0, 0), temperature=0.0, ores=False,
                       extra_features={0: [f"{NS}:asteroid_field"]})
    settings = {
        "sea_level": MIN_Y,
        "disable_mob_generation": True,
        "aquifers_enabled": False,
        "ore_veins_enabled": False,
        "legacy_random_source": False,
        "default_block": {"Name": "minecraft:air"},
        "default_fluid": {"Name": "minecraft:air"},
        "noise": {"min_y": MIN_Y, "height": 16, "size_horizontal": 1, "size_vertical": 2},
        "noise_router": {key: 0.0 for key in (
            "barrier", "fluid_level_floodedness", "fluid_level_spread", "lava", "temperature", "vegetation",
            "continents", "erosion", "depth", "ridges", "initial_density_without_jaggedness", "vein_toggle",
            "vein_ridged", "vein_gap")} | {"final_density": -1.0},
        "spawn_target": [],
        "surface_rule": {"type": "minecraft:sequence", "sequence": []},
    }
    dimension = {
        "type": f"{NS}:space",
        "generator": {
            "type": "minecraft:noise",
            "settings": f"{NS}:space",
            "biome_source": {"type": "minecraft:fixed", "biome": f"{NS}:space"},
        },
    }
    dim_type = dimension_type("space", natural=False, ambient_light=0.08)
    dim_type["fixed_time"] = 6000  # plein soleil permanent : aucune planète ne fait d'ombre
    configured = {"type": f"{NS}:asteroid", "config": {}}
    placed = {
        "feature": f"{NS}:asteroid_field",
        "placement": [
            {"type": "minecraft:count", "count": 2},
            {"type": "minecraft:in_square"},
            {"type": "minecraft:height_range", "height": {
                "type": "minecraft:uniform",
                "min_inclusive": {"absolute": ECLIPTIC_Y - 140},
                "max_inclusive": {"absolute": ECLIPTIC_Y + 140}}},
            {"type": "minecraft:biome"},
        ],
    }
    return biome, settings, dimension, dim_type, configured, placed


def shared_noises():
    return {
        "seed": {"firstOctave": -4, "amplitudes": [1.0, 1.0]},
        "planet/crag": {"firstOctave": -5, "amplitudes": [1.0, 0.8, 0.4]},
    }
