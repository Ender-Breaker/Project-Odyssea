"""Terra : l'overworld d'Odyssea.

Relief décrit en altitude H(x, z) (blocs), à partir des paramètres climatiques vanilla (continentalité C,
érosion E, étrangeté W et son repli « pics et vallées » PV) pour que le placement des biomes vanilla reste
cohérent avec le terrain :

    H = socle(C)                              fonds océaniques → plateau continental → côte → intérieur
      + enveloppe(C, E, PV) · crêtes érodées  chaînes de montagnes aux dimensions vanilla (sommets ~200-280)
      + collines(E) + plateaux à escarpements + détail
      − vallées fluviales (le long de W = 0)  creusées jusqu'au niveau de la mer près des côtes (fjords)

La densité 3D vaut (H − y)/8 + bruit 3D d'amplitude croissante en montagne (falaises, surplombs), puis les
grottes vanilla (fromage, spaghetti, nouilles, piliers). Inspiré de l'organisation de Terralith (forme de base
+ ajouts + soustractions), mais avec un relief calculé par des fonctions Java dédiées.
"""

from dsl import *

NS = "odyssea"
MIN_Y = -64           # fond du monde (bedrock), comme vanilla
NOISE_TOP = 448       # plus haut bloc possible du relief (le bruit ne couvre que cette plage : génération rapide)
DIMENSION_TOP = 2032  # limite absolue du moteur (BlockPos sur 12 bits) : on construit jusqu'à Y = 2031
SEA_LEVEL = 63

C = f"{NS}:terra/continents"
E = f"{NS}:terra/erosion"
W = f"{NS}:terra/ridges"
PV = f"{NS}:terra/ridges_folded"
H = f"{NS}:terra/height"


def noises():
    # Échelles horizontales vanilla (mêmes octaves) ; seules les octaves les plus fines de la continentalité sont
    # atténuées pour des côtes moins dentelées.
    return {
        "terra/continentalness": {"firstOctave": -9, "amplitudes": [1.0, 1.0, 2.0, 2.0, 2.0, 1.0, 0.7, 0.45, 0.3]},
        "terra/erosion": {"firstOctave": -9, "amplitudes": [1.0, 1.0, 0.0, 1.0, 1.0]},
        "terra/ridge": {"firstOctave": -7, "amplitudes": [1.0, 2.0, 1.0, 0.0, 0.0, 0.0]},
        "terra/temperature": {"firstOctave": -10, "amplitudes": [1.5, 0.0, 1.0, 0.0, 0.0, 0.0]},
        "terra/vegetation": {"firstOctave": -8, "amplitudes": [1.0, 1.0, 0.0, 0.0, 0.0, 0.0]},
        # Bruit 3D des falaises : étiré verticalement (y_scale < 1) pour des parois franches plutôt que des bulles.
        "terra/crag": {"firstOctave": -6, "amplitudes": [1.0, 1.0, 0.5, 0.25]},
    }


def density_functions():
    df = {}

    # Décalage de +0,06 : un peu moins d'océan que vanilla. Le biome lit la même valeur, il reste cohérent.
    df["terra/continents"] = cached(add(0.06, shifted_noise_2d(f"{NS}:terra/continentalness")))
    # Ceintures orogéniques : lignes de crête d'un bruit à très grande échelle, longues chaînes sinueuses
    # (Andes, Alpes). Elles abaissent l'érosion « vue » par les biomes : les biomes de montagne vanilla y apparaissent.
    df["terra/orogeny"] = cached(spline(fractal("terra/orogeny", 5200.0, mode="ridged", octaves=3, warp=1300.0,
                                                warp_scale=3500.0), [(0.0, 0.0), (0.5, 0.0), (0.72, 1.0), (1.0, 1.0)]))
    df["terra/erosion"] = cached(clamp(sub(shifted_noise_2d(f"{NS}:terra/erosion"), mul(0.7, f"{NS}:terra/orogeny")), -1.0, 1.0))
    df["terra/ridges"] = cached(shifted_noise_2d(f"{NS}:terra/ridge"))
    df["terra/ridges_folded"] = cached(mul(add(fabs(add(fabs(W), -2.0 / 3.0)), -1.0 / 3.0), -3.0))
    df["terra/vegetation"] = cached(shifted_noise_2d(f"{NS}:terra/vegetation"))
    df["terra/temperature_base"] = cached(shifted_noise_2d(f"{NS}:terra/temperature"))

    # Socle continental (blocs). Les champignons (C < −1,05) restent des îles, comme en vanilla.
    df["terra/base"] = cached(spline(C, [
        (-1.20, 72.0), (-1.10, 67.0), (-1.05, 38.0), (-0.98, 24.0), (-0.75, 18.0), (-0.55, 24.0),
        (-0.455, 32.0), (-0.32, 42.0), (-0.22, 50.0), (-0.19, 54.0), (-0.15, 60.0), (-0.11, 64.5),
        (-0.05, 69.5), (0.03, 74.0), (0.30, 85.0), (0.60, 97.0), (1.00, 110.0),
    ]))

    # Facteur « intérieur des terres » : nul en mer, faible sur la côte (falaises côtières), plein à l'intérieur.
    df["terra/inland"] = cached(spline(C, [(-0.21, 0.0), (-0.15, 0.22), (-0.11, 0.45), (0.03, 0.82), (0.25, 1.0)]))

    # Enveloppe des massifs : faible érosion = montagnes ; PV haut = cœur de chaîne, PV bas = vallée.
    # Facteurs généreux : leur produit doit rester élevé sur de vastes surfaces pour former de vraies chaînes.
    erosion_factor = spline(E, [(-1.0, 1.0), (-0.6, 0.92), (-0.375, 0.7), (-0.2225, 0.42), (0.05, 0.12), (0.3, 0.0)])
    pv_factor = spline(PV, [(-1.0, 0.08), (-0.85, 0.2), (-0.6, 0.45), (0.0, 0.8), (0.4, 1.0), (1.0, 1.0)])
    df["terra/mountain_envelope"] = cached(mul(f"{NS}:terra/inland", mul(erosion_factor, pv_factor)))

    # Crêtes alpines : multifractale à crêtes, domaine déformé (chaînes sinueuses), légère érosion par la pente.
    df["terra/peaks"] = cached(fractal("terra/peaks", 900.0, mode="ridged", octaves=8, erosion=0.03,
                                       warp=190.0, warp_scale=1500.0))
    # Variation d'altitude le long des chaînes (massifs culminants et cols).
    df["terra/massif"] = cached(fractal("terra/massif", 2600.0, octaves=3))
    # Profil calé sur la distribution mesurée des crêtes (médiane ~0,11, p95 ~0,3, p99 ~0,4).
    peak_profile = spline(f"{NS}:terra/peaks", [(0.0, 0.0), (0.05, 0.22), (0.12, 0.5), (0.22, 0.78), (0.35, 1.0),
                                               (0.5, 1.15), (0.8, 1.3)])
    # Socle de massif (hauts plateaux entre les crêtes) + crêtes, modulés le long de la chaîne.
    mountains = mul(f"{NS}:terra/mountain_envelope",
                    add(mul(f"{NS}:terra/mountain_envelope", 40.0),
                        mul(mul(peak_profile, 135.0), add(0.85, mul(0.22, f"{NS}:terra/massif")))))
    df["terra/mountains"] = cached(mountains)

    # Collines : amplitude réglée par l'érosion, atténuée sur la côte et dans les vallées.
    hill_amplitude = mul(spline(E, [(-0.6, 30.0), (-0.375, 34.0), (-0.2225, 28.0), (0.05, 18.0), (0.3, 10.0),
                                    (0.45, 5.0), (0.55, 2.5), (1.0, 2.0)]),
                         spline(C, [(-0.19, 0.1), (-0.11, 0.45), (0.0, 1.0)]))
    hill_noise = fractal("terra/hills", 300.0, octaves=5, erosion=0.05, warp=70.0, warp_scale=400.0)
    pv_hills = spline(PV, [(-1.0, 0.35), (0.0, 0.8), (1.0, 1.0)])
    df["terra/hills"] = cached(mul(mul(hill_amplitude, pv_hills), add(0.55, mul(0.45, hill_noise))))

    # Plateaux à escarpements (type Drakensberg) : érosion moyenne, intérieur des terres, PV positif.
    plateau_mask = mul(mul(spline(C, [(0.0, 0.0), (0.15, 1.0)]),
                           spline(E, [(-0.5, 0.0), (-0.375, 1.0), (0.05, 1.0), (0.2, 0.0)])),
                       spline(PV, [(-0.2, 0.0), (0.3, 1.0)]))
    plateau_step = spline(fractal("terra/plateaus", 1400.0, octaves=4, warp=280.0, warp_scale=1200.0),
                          [(-1.0, 0.0), (0.02, 0.0), (0.14, 1.0), (1.0, 1.0)])
    df["terra/plateaus"] = cached(mul(mul(plateau_mask, plateau_step), 50.0))

    # Fonds océaniques : dorsales et canyons sous-marins.
    ocean_mask = spline(C, [(-0.32, 1.0), (-0.2, 0.0)])
    seafloor = sub(mul(fractal("terra/seafloor", 800.0, mode="ridged", octaves=5, warp=120.0, warp_scale=1000.0), 18.0), 5.0)
    df["terra/seafloor"] = cached(mul(ocean_mask, seafloor))

    detail = mul(fractal("terra/detail", 48.0, octaves=3), add(1.5, mul(4.0, f"{NS}:terra/mountain_envelope")))

    land = total(f"{NS}:terra/base", f"{NS}:terra/mountains", f"{NS}:terra/hills", f"{NS}:terra/plateaus",
                 f"{NS}:terra/seafloor", detail)
    df["terra/land_raw"] = cached(land)
    # Compression douce au-dessus de 240 : les sommets d'exception plafonnent vers 300, comme les pics vanilla.
    df["terra/land"] = cached(spline(f"{NS}:terra/land_raw", [
        (-100.0, -100.0, 1.0), (240.0, 240.0, 1.0), (300.0, 275.0, 0.4), (400.0, 298.0, 0.08), (600.0, 305.0, 0.0)]))

    # Vallées fluviales le long de W = 0. Le fond descend au niveau de la mer près des côtes (fjords, rivières)
    # et reste perché au cœur des massifs lointains (vallées glaciaires sèches).
    valley = spline(W, [(-0.14, 0.0), (-0.07, 0.25), (-0.035, 0.7), (-0.012, 0.96), (0.0, 1.0),
                        (0.012, 0.96), (0.035, 0.7), (0.07, 0.25), (0.14, 0.0)])
    valley_floor = add(SEA_LEVEL - 5.0, mul(mul(f"{NS}:terra/mountain_envelope", spline(C, [(0.02, 0.0), (0.45, 1.0)])), 60.0))
    river_strength = spline(C, [(-0.21, 0.0), (-0.17, 1.0)])
    df["terra/height"] = cached(clamp(carve(f"{NS}:terra/land", valley_floor, mul(valley, river_strength)),
                                      MIN_Y + 40.0, NOISE_TOP - 60.0))

    # Gradient thermique : −3,5 par 1000 blocs au-dessus de Y = 90. Les sommets basculent vers les biomes
    # froids (neiges éternelles), les plateaux vers les forêts de conifères. L'altitude utilisée est l'enveloppe
    # lissée des massifs (sans le détail des crêtes) : frontières de biomes plus douces et calcul bien moins cher.
    smooth_altitude = add(f"{NS}:terra/base", mul(f"{NS}:terra/mountain_envelope", 150.0))
    df["terra/temperature"] = cached(add(f"{NS}:terra/temperature_base",
                                         mul(-0.0035, fmax(add(smooth_altitude, -90.0), 0.0))))

    # Profondeur pour le choix des biomes : 0 en surface (relief plafonné à 180 pour que les biomes de grotte
    # gardent la répartition vanilla même sous les hautes montagnes).
    df["terra/depth"] = add(mul(fmin(H, 180.0), 1.0 / 128.0), linear_y(MIN_Y, DIMENSION_TOP, 1.0 / 128.0))

    # Pente de densité : 1 unité pour 8 blocs, comme le « sloped_cheese » vanilla près de la surface.
    df["terra/slope"] = add(mul(H, 0.125), linear_y(MIN_Y, NOISE_TOP, 0.125))

    crag_amplitude = cached(total(0.12, mul(1.3, f"{NS}:terra/mountain_envelope"),
                                  mul(0.012, f"{NS}:terra/hills"), mul(0.006, f"{NS}:terra/plateaus")))
    df["terra/crag_amplitude"] = crag_amplitude
    df["terra/sloped_terrain"] = cache_once(add(f"{NS}:terra/slope",
                                                mul(f"{NS}:terra/crag_amplitude", noise(f"{NS}:terra/crag", 1.0, 0.55))))
    return df


def underground(sloped):
    """Grottes vanilla (fonction « underground » de NoiseRouterData), branchées sur notre densité."""
    cave_layer = mul(4.0, square(noise("minecraft:cave_layer", 1.0, 8.0)))
    cave_cheese = add(clamp(add(0.27, noise("minecraft:cave_cheese", 1.0, 0.6666666666666666)), -1.0, 1.0),
                      clamp(add(1.5, mul(-0.64, sloped)), 0.0, 0.5))
    caverns = add(cave_layer, cave_cheese)
    tunnels = fmin(fmin(caverns, "minecraft:overworld/caves/entrances"),
                   add("minecraft:overworld/caves/spaghetti_2d", "minecraft:overworld/caves/spaghetti_roughness_function"))
    pillars = range_choice("minecraft:overworld/caves/pillars", -1000000.0, 0.03, -1000000.0,
                           "minecraft:overworld/caves/pillars")
    return fmax(tunnels, pillars)


def slide(value, min_y, top):
    """Transition vers du solide au fond et de l'air au sommet du bruit (même forme que slideOverworld)."""
    bottom = y_gradient(min_y, min_y + 24, 0.0, 1.0)
    upper = y_gradient(top - 80, top - 32, 1.0, 0.0)
    return add(0.1171875, mul(bottom, add(-0.1171875, add(-0.078125, mul(upper, add(0.078125, value))))))


def router():
    sloped = f"{NS}:terra/sloped_terrain"
    terrain = range_choice(sloped, -1000000.0, 1.5625,
                           fmin(sloped, mul(5.0, "minecraft:overworld/caves/entrances")),
                           underground(sloped))
    # Bien au-dessus du relief (32 blocs et plus), la densité est forcément négative : on court-circuite les
    # grottes et le bruit 3D, ce qui rend gratuits les ~1000 blocs d'air au-dessus des plaines.
    terrain = range_choice(f"{NS}:terra/slope", -1000000.0, -4.0, -1.0, terrain)
    final = fmin(squeeze(mul(0.64, interpolated(blend_density(slide(terrain, MIN_Y, NOISE_TOP))))),
                 "minecraft:overworld/caves/noodle")

    ore_y = "minecraft:y"
    vein_toggle = interpolated(range_choice(ore_y, -60.0, 51.0, noise("minecraft:ore_veininess", 1.5, 1.5), 0.0))
    vein_a = interpolated(range_choice(ore_y, -60.0, 51.0, noise("minecraft:ore_vein_a", 4.0, 4.0), 0.0))
    vein_b = interpolated(range_choice(ore_y, -60.0, 51.0, noise("minecraft:ore_vein_b", 4.0, 4.0), 0.0))
    return {
        "barrier": noise("minecraft:aquifer_barrier", 1.0, 0.5),
        "fluid_level_floodedness": noise("minecraft:aquifer_fluid_level_floodedness", 1.0, 0.67),
        "fluid_level_spread": noise("minecraft:aquifer_fluid_level_spread", 1.0, 0.7142857142857143),
        "lava": noise("minecraft:aquifer_lava", 1.0, 1.0),
        "temperature": f"{NS}:terra/temperature",
        "vegetation": f"{NS}:terra/vegetation",
        "continents": C,
        "erosion": E,
        "depth": f"{NS}:terra/depth",
        "ridges": W,
        "initial_density_without_jaggedness": slide(clamp(f"{NS}:terra/slope", -64.0, 64.0), MIN_Y, NOISE_TOP),
        "final_density": final,
        "vein_toggle": vein_toggle,
        "vein_ridged": add(-0.07999999821186066, fmax(fabs(vein_a), fabs(vein_b))),
        "vein_gap": noise("minecraft:ore_gap", 1.0, 1.0),
    }


# ───────────────────────────── Règles de surface ─────────────────────────────

def B(name, props=None):
    return block(f"minecraft:{name}", props)


def surface_rule():
    """Port de SurfaceRuleData.overworldLike(true, false, true), précédé de règles d'altitude :
    neiges éternelles au-dessus de la limite climatique, roche nue sur les versants raides, éboulis en altitude."""
    GRASS_BLOCK = B("grass_block", {"snowy": "false"})
    DIRT, STONE, SAND, GRAVEL, SANDSTONE = B("dirt"), B("stone"), B("sand"), B("gravel"), B("sandstone")
    WATER = B("water", {"level": "0"})
    ICE, PACKED_ICE, SNOW_BLOCK, POWDER_SNOW = B("ice"), B("packed_ice"), B("snow_block"), B("powder_snow")
    CALCITE, COARSE_DIRT, PODZOL, MUD, MYCELIUM = B("calcite"), B("coarse_dirt"), B("podzol", {"snowy": "false"}), B("mud"), B("mycelium", {"snowy": "false"})
    TERRACOTTA, ORANGE_TERRACOTTA, WHITE_TERRACOTTA = B("terracotta"), B("orange_terracotta"), B("white_terracotta")
    RED_SAND, RED_SANDSTONE, AIR = B("red_sand"), B("red_sandstone"), B("air")
    ANDESITE, TUFF = B("andesite"), B("tuff")

    above_97 = y_block_check(97, 2)
    above_256 = y_block_check(256, 0)
    start_63 = y_start_check(63, -1)
    start_74 = y_start_check(74, 1)
    above_60 = y_block_check(60, 0)
    above_62 = y_block_check(62, 0)
    above_63 = y_block_check(63, 0)
    water_m1 = water_block_check(-1, 0)
    water_0 = water_block_check(0, 0)
    water_start = water_start_check(-6, -1)
    frozen_ocean = biome_is("minecraft:frozen_ocean", "minecraft:deep_frozen_ocean")

    grass_or_dirt = sequence(if_true(water_0, GRASS_BLOCK), DIRT)
    sand_rule = sequence(if_true(ON_CEILING, SANDSTONE), SAND)
    gravel_rule = sequence(if_true(ON_CEILING, STONE), GRAVEL)
    beachy = biome_is("minecraft:warm_ocean", "minecraft:beach", "minecraft:snowy_beach")
    desert = biome_is("minecraft:desert")

    stony = sequence(
        if_true(biome_is("minecraft:stony_peaks"),
                sequence(if_true(noise_threshold("minecraft:calcite", -0.0125, 0.0125), CALCITE), STONE)),
        if_true(biome_is("minecraft:stony_shore"),
                sequence(if_true(noise_threshold("minecraft:gravel", -0.05, 0.05), gravel_rule), STONE)),
        if_true(biome_is("minecraft:windswept_hills"), if_true(surface_noise_above(1.0), STONE)),
        if_true(beachy, sand_rule),
        if_true(desert, sand_rule),
        if_true(biome_is("minecraft:dripstone_caves"), STONE),
    )
    powder_snow_a = if_true(noise_threshold("minecraft:powder_snow", 0.45, 0.58), if_true(water_0, POWDER_SNOW))
    powder_snow_b = if_true(noise_threshold("minecraft:powder_snow", 0.35, 0.6), if_true(water_0, POWDER_SNOW))

    under_floor = sequence(
        if_true(biome_is("minecraft:frozen_peaks"), sequence(
            if_true(STEEP, PACKED_ICE),
            if_true(noise_threshold("minecraft:packed_ice", -0.5, 0.2), PACKED_ICE),
            if_true(noise_threshold("minecraft:ice", -0.0625, 0.025), ICE),
            if_true(water_0, SNOW_BLOCK))),
        if_true(biome_is("minecraft:snowy_slopes"), sequence(if_true(STEEP, STONE), powder_snow_a, if_true(water_0, SNOW_BLOCK))),
        if_true(biome_is("minecraft:jagged_peaks"), STONE),
        if_true(biome_is("minecraft:grove"), sequence(powder_snow_a, DIRT)),
        stony,
        if_true(biome_is("minecraft:windswept_savanna"), if_true(surface_noise_above(1.75), STONE)),
        if_true(biome_is("minecraft:windswept_gravelly_hills"), sequence(
            if_true(surface_noise_above(2.0), gravel_rule),
            if_true(surface_noise_above(1.0), STONE),
            if_true(surface_noise_above(-1.0), DIRT),
            gravel_rule)),
        if_true(biome_is("minecraft:mangrove_swamp"), MUD),
        DIRT,
    )
    on_floor = sequence(
        if_true(biome_is("minecraft:frozen_peaks"), sequence(
            if_true(STEEP, PACKED_ICE),
            if_true(noise_threshold("minecraft:packed_ice", 0.0, 0.2), PACKED_ICE),
            if_true(noise_threshold("minecraft:ice", 0.0, 0.025), ICE),
            if_true(water_0, SNOW_BLOCK))),
        if_true(biome_is("minecraft:snowy_slopes"), sequence(if_true(STEEP, STONE), powder_snow_b, if_true(water_0, SNOW_BLOCK))),
        if_true(biome_is("minecraft:jagged_peaks"), sequence(if_true(STEEP, STONE), if_true(water_0, SNOW_BLOCK))),
        if_true(biome_is("minecraft:grove"), sequence(powder_snow_b, if_true(water_0, SNOW_BLOCK))),
        stony,
        if_true(biome_is("minecraft:windswept_savanna"), sequence(
            if_true(surface_noise_above(1.75), STONE), if_true(surface_noise_above(-0.5), COARSE_DIRT))),
        if_true(biome_is("minecraft:windswept_gravelly_hills"), sequence(
            if_true(surface_noise_above(2.0), gravel_rule),
            if_true(surface_noise_above(1.0), STONE),
            if_true(surface_noise_above(-1.0), grass_or_dirt),
            gravel_rule)),
        if_true(biome_is("minecraft:old_growth_pine_taiga", "minecraft:old_growth_spruce_taiga"), sequence(
            if_true(surface_noise_above(1.75), COARSE_DIRT), if_true(surface_noise_above(-0.95), PODZOL))),
        if_true(biome_is("minecraft:ice_spikes"), if_true(water_0, SNOW_BLOCK)),
        if_true(biome_is("minecraft:mangrove_swamp"), MUD),
        if_true(biome_is("minecraft:mushroom_fields"), MYCELIUM),
        grass_or_dirt,
    )

    band_a = noise_threshold("minecraft:surface", -0.909, -0.5454)
    band_b = noise_threshold("minecraft:surface", -0.1818, 0.1818)
    band_c = noise_threshold("minecraft:surface", 0.5454, 0.909)

    # ── Ajouts d'altitude (Odyssea), appliqués avant les règles par biome ──
    sandy = biome_is("minecraft:desert", "minecraft:badlands", "minecraft:eroded_badlands",
                     "minecraft:wooded_badlands", "minecraft:beach", "minecraft:snowy_beach")
    alpine = if_true(ON_FLOOR, sequence(
        # Neiges éternelles : biome assez froid à cette altitude (gradient vanilla −0,00125/bloc au-dessus de 80).
        if_true(y_block_check(165, 2), if_true(COLD_ENOUGH_TO_SNOW, sequence(
            if_true(STEEP, sequence(if_true(noise_threshold("minecraft:packed_ice", 0.1, 0.35), PACKED_ICE), STONE)),
            if_true(water_0, SNOW_BLOCK)))),
        # Roche nue sur les versants raides au-dessus de la zone des collines.
        if_true(y_block_check(100, 1), if_true(STEEP, if_true(negate(sandy), sequence(
            if_true(noise_threshold("minecraft:gravel", -0.08, 0.08), ANDESITE), STONE)))),
        # Éboulis et affleurements au-dessus de la limite des arbres.
        if_true(y_block_check(195, 3), if_true(negate(sandy), sequence(
            if_true(noise_threshold("minecraft:gravel", -0.06, 0.06), GRAVEL),
            if_true(noise_threshold("minecraft:calcite", -0.04, 0.04), TUFF)))),
    ))

    rules = sequence(
        if_true(ON_FLOOR, sequence(
            if_true(biome_is("minecraft:wooded_badlands"), if_true(above_97, sequence(
                if_true(band_a, COARSE_DIRT), if_true(band_b, COARSE_DIRT), if_true(band_c, COARSE_DIRT), grass_or_dirt))),
            if_true(biome_is("minecraft:swamp"), if_true(above_62, if_true(negate(above_63),
                                                                         if_true(noise_threshold("minecraft:surface_swamp", 0.0), WATER)))),
            if_true(biome_is("minecraft:mangrove_swamp"), if_true(above_60, if_true(negate(above_63),
                                                                                  if_true(noise_threshold("minecraft:surface_swamp", 0.0), WATER)))),
        )),
        if_true(biome_is("minecraft:badlands", "minecraft:eroded_badlands", "minecraft:wooded_badlands"), sequence(
            if_true(ON_FLOOR, sequence(
                if_true(above_256, ORANGE_TERRACOTTA),
                if_true(start_74, sequence(if_true(band_a, TERRACOTTA), if_true(band_b, TERRACOTTA), if_true(band_c, TERRACOTTA), BANDLANDS)),
                if_true(water_m1, sequence(if_true(ON_CEILING, RED_SANDSTONE), RED_SAND)),
                if_true(negate(HOLE), ORANGE_TERRACOTTA),
                if_true(water_start, WHITE_TERRACOTTA),
                gravel_rule)),
            if_true(start_63, sequence(if_true(above_63, if_true(negate(start_74), ORANGE_TERRACOTTA)), BANDLANDS)),
            if_true(UNDER_FLOOR, if_true(water_start, WHITE_TERRACOTTA)),
        )),
        if_true(ON_FLOOR, if_true(water_m1, sequence(
            if_true(frozen_ocean, if_true(HOLE, sequence(if_true(water_0, AIR), if_true(COLD_ENOUGH_TO_SNOW, ICE), WATER))),
            on_floor))),
        if_true(water_start, sequence(
            if_true(ON_FLOOR, if_true(frozen_ocean, if_true(HOLE, WATER))),
            if_true(UNDER_FLOOR, under_floor),
            if_true(beachy, if_true(DEEP_UNDER_FLOOR, SANDSTONE)),
            if_true(desert, if_true(VERY_DEEP_UNDER_FLOOR, SANDSTONE)))),
        if_true(ON_FLOOR, sequence(
            if_true(biome_is("minecraft:frozen_peaks", "minecraft:jagged_peaks"), STONE),
            if_true(biome_is("minecraft:warm_ocean", "minecraft:lukewarm_ocean", "minecraft:deep_lukewarm_ocean"), sand_rule),
            gravel_rule)),
    )

    return sequence(
        bedrock_floor(),
        if_true(ABOVE_PRELIMINARY_SURFACE, sequence(alpine, rules)),
        deepslate_below(0, 8),
    )


def noise_settings():
    return {
        "sea_level": SEA_LEVEL,
        "disable_mob_generation": False,
        "aquifers_enabled": True,
        "ore_veins_enabled": True,
        "legacy_random_source": False,
        "default_block": {"Name": "minecraft:stone"},
        "default_fluid": {"Name": "minecraft:water", "Properties": {"level": "0"}},
        "noise": {"min_y": MIN_Y, "height": NOISE_TOP - MIN_Y, "size_horizontal": 1, "size_vertical": 2},
        "noise_router": router(),
        "spawn_target": [
            {"temperature": [-1.0, 1.0], "humidity": [-1.0, 1.0], "continentalness": [-0.11, 1.0],
             "erosion": [-1.0, 1.0], "weirdness": [-1.0, -0.16], "depth": 0.0, "offset": 0.0},
            {"temperature": [-1.0, 1.0], "humidity": [-1.0, 1.0], "continentalness": [-0.11, 1.0],
             "erosion": [-1.0, 1.0], "weirdness": [0.16, 1.0], "depth": 0.0, "offset": 0.0},
        ],
        "surface_rule": surface_rule(),
    }


def dimension_type():
    height = DIMENSION_TOP - MIN_Y
    return {
        "ultrawarm": False, "natural": True, "piglin_safe": False, "respawn_anchor_works": False,
        "bed_works": True, "has_raids": True, "has_skylight": True, "has_ceiling": False,
        "coordinate_scale": 1.0, "ambient_light": 0.0, "logical_height": height,
        "effects": "minecraft:overworld", "infiniburn": "#minecraft:infiniburn_overworld",
        "min_y": MIN_Y, "height": height,
        "monster_spawn_light_level": {"type": "minecraft:uniform", "min_inclusive": 0, "max_inclusive": 7},
        "monster_spawn_block_light_limit": 0,
    }


def dimension():
    return {
        "type": "minecraft:overworld",
        "generator": {
            "type": "minecraft:noise",
            "settings": f"{NS}:terra",
            "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:overworld"},
        },
    }
