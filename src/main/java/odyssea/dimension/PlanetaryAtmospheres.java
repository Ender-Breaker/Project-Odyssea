package odyssea.dimension;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import odyssea.OdysseaCore;
import odyssea.dimension.PlanetaryAtmosphere.Aerosols;
import odyssea.dimension.PlanetaryAtmosphere.Gas;
import org.joml.Vector3f;

public final class PlanetaryAtmospheres {
    public static final ResourceKey<Level> ORBIT = ResourceKey.create(Registries.DIMENSION, OdysseaCore.id("orbit"));
    public static final ResourceKey<Level> LUNA = ResourceKey.create(Registries.DIMENSION, OdysseaCore.id("luna"));
    public static final ResourceKey<Level> ARES = ResourceKey.create(Registries.DIMENSION, OdysseaCore.id("ares"));

    public static final PlanetaryAtmosphere TERRA = PlanetaryAtmosphere.fromComposition(
            Map.of(Gas.N2, 0.7808, Gas.O2, 0.2095, Gas.AR, 0.0093, Gas.CO2, 0.0004),
            101_325.0, 288.15, 9.80665, 63.0, 400.0, 6371.0f, 100.0f,
            new Aerosols(new Vector3f(3.996e-3f), new Vector3f(4.440e-3f), 1.2f, 0.8f));

    // Poussières d'oxyde de fer : extinction bleue supérieure à la diffusion, d'où le ciel ocre.
    public static final PlanetaryAtmosphere ARES_ATMOSPHERE = PlanetaryAtmosphere.fromComposition(
            Map.of(Gas.CO2, 0.951, Gas.N2, 0.0259, Gas.AR, 0.0194, Gas.O2, 0.0016),
            610.0, 210.0, 3.71, 63.0, 1200.0, 3389.5f, 120.0f,
            new Aerosols(new Vector3f(0.0215f, 0.0175f, 0.0120f), new Vector3f(0.0250f, 0.0240f, 0.0265f), 11.0f, 0.76f));

    public static final PlanetaryAtmosphere NETHER = PlanetaryAtmosphere.fromComposition(
            Map.of(Gas.N2, 0.70, Gas.CO2, 0.12, Gas.O2, 0.17, Gas.AR, 0.01),
            120_000.0, 340.0, 9.80665, 32.0, 600.0, 6371.0f, 60.0f,
            new Aerosols(new Vector3f(0.030f, 0.012f, 0.006f), new Vector3f(0.034f, 0.020f, 0.016f), 3.0f, 0.65f));

    private static final Map<ResourceKey<Level>, PlanetaryAtmosphere> REGISTRY = new ConcurrentHashMap<>(Map.of(
            Level.OVERWORLD, TERRA,
            Level.NETHER, NETHER,
            Level.END, TERRA,
            ORBIT, PlanetaryAtmosphere.VACUUM,
            LUNA, PlanetaryAtmosphere.VACUUM,
            ARES, ARES_ATMOSPHERE));

    private PlanetaryAtmospheres() {
    }

    public static PlanetaryAtmosphere get(ResourceKey<Level> dimension) {
        return REGISTRY.getOrDefault(dimension, TERRA);
    }

    public static void register(ResourceKey<Level> dimension, PlanetaryAtmosphere atmosphere) {
        REGISTRY.put(dimension, atmosphere);
    }
}
