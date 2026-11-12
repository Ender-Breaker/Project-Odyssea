package odyssea.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import odyssea.OdysseaCore;
import odyssea.worldgen.density.CarveFunction;
import odyssea.worldgen.density.CraterFieldFunction;
import odyssea.worldgen.density.FractalNoiseFunction;
import odyssea.worldgen.density.TerraceFunction;
import odyssea.worldgen.density.VolcanoFieldFunction;
import odyssea.worldgen.feature.AsteroidFeature;

/**
 * Types de density functions d'Odyssea. Le relief des planètes est décrit en JSON
 * ({@code data/odyssea/worldgen/density_function}), généré par {@code tools/worldgen/generate_worldgen.py} ;
 * ces types fournissent les briques que les fonctions vanilla ne savent pas exprimer.
 */
public final class OdysseaWorldgen {
    private static final DeferredRegister<MapCodec<? extends DensityFunction>> DENSITY_FUNCTION_TYPES =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, OdysseaCore.MOD_ID);
    private static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, OdysseaCore.MOD_ID);

    /** Astéroïdes de la dimension spatiale (denses dans la ceinture principale). */
    public static final net.neoforged.neoforge.registries.DeferredHolder<Feature<?>, AsteroidFeature> ASTEROID =
            FEATURES.register("asteroid", () -> new AsteroidFeature(NoneFeatureConfiguration.CODEC));

    static {
        DENSITY_FUNCTION_TYPES.register("fractal", () -> FractalNoiseFunction.MAP_CODEC);
        DENSITY_FUNCTION_TYPES.register("craters", () -> CraterFieldFunction.MAP_CODEC);
        DENSITY_FUNCTION_TYPES.register("volcanoes", () -> VolcanoFieldFunction.MAP_CODEC);
        DENSITY_FUNCTION_TYPES.register("terrace", () -> TerraceFunction.MAP_CODEC);
        DENSITY_FUNCTION_TYPES.register("carve", () -> CarveFunction.MAP_CODEC);
    }

    private OdysseaWorldgen() {
    }

    public static void register(IEventBus modBus) {
        DENSITY_FUNCTION_TYPES.register(modBus);
        FEATURES.register(modBus);
    }
}
