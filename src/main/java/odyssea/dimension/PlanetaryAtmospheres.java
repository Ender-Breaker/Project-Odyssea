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

/**
 * Atmosphères des corps du système solaire et registre dimension → atmosphère.
 *
 * <p>Les grandeurs de jeu (pression, O₂, température, gravité) sont réelles. Les coefficients de diffusion sont
 * dérivés de la composition, sauf pour Vénus : avec 92 bar de CO₂, un modèle de diffusion simple donnerait un ciel
 * noir (la lumière y est diffusée des dizaines de fois), on y fixe donc une diffusion « effective » qui reproduit
 * le ciel orangé et diffus observé par les sondes Venera.
 */
public final class PlanetaryAtmospheres {
    public static final ResourceKey<Level> SPACE = key("space");
    public static final ResourceKey<Level> MERCURY = key("mercury");
    public static final ResourceKey<Level> VENUS = key("venus");
    public static final ResourceKey<Level> LUNA = key("luna");
    public static final ResourceKey<Level> MARS = key("mars");
    public static final ResourceKey<Level> JUPITER = key("jupiter");
    public static final ResourceKey<Level> SATURN = key("saturn");
    public static final ResourceKey<Level> URANUS = key("uranus");
    public static final ResourceKey<Level> NEPTUNE = key("neptune");

    public static final PlanetaryAtmosphere TERRA = PlanetaryAtmosphere.fromComposition(
            Map.of(Gas.N2, 0.7808, Gas.O2, 0.2095, Gas.AR, 0.0093, Gas.CO2, 0.0004),
            101_325.0, 288.15, 9.80665, 63.0, 400.0, 6371.0f, 100.0f,
            new Aerosols(new Vector3f(3.996e-3f), new Vector3f(4.440e-3f), 1.2f, 0.8f));

    // Poussières d'oxyde de fer (épaisseur optique ~0,5) : elles absorbent le bleu, d'où le ciel caramel, et le
    // diffusent plus vers l'avant que le rouge (g plus grand en bleu), d'où le halo bleu autour du Soleil couchant.
    public static final PlanetaryAtmosphere MARS_ATMOSPHERE = PlanetaryAtmosphere.fromComposition(
            Map.of(Gas.CO2, 0.951, Gas.N2, 0.0259, Gas.AR, 0.0194, Gas.O2, 0.0016),
            610.0, 210.0, 3.71, 63.0, 1200.0, 3389.5f, 120.0f,
            new Aerosols(new Vector3f(0.044f, 0.028f, 0.017f), new Vector3f(0.046f, 0.046f, 0.052f), 11.0f,
                    new Vector3f(0.52f, 0.64f, 0.88f)));

    /** Vénus : 92 bar, 737 K. Diffusion effective (voir la doc de la classe), absorption bleue du soufre. */
    public static final PlanetaryAtmosphere VENUS_ATMOSPHERE = PlanetaryAtmosphere.fromComposition(
            Map.of(Gas.CO2, 0.965, Gas.N2, 0.035),
            9_200_000.0, 737.0, 8.87, 40.0, 2000.0, 6051.8f, 110.0f,
            new Aerosols(new Vector3f(0.060f, 0.052f, 0.040f), new Vector3f(0.064f, 0.060f, 0.058f), 14.0f, 0.72f))
            .withScattering(new PlanetaryAtmosphere.Scattering(
                    new Vector3f(0.016f, 0.026f, 0.050f), 15.9f,
                    new Vector3f(0.060f, 0.052f, 0.040f), new Vector3f(0.064f, 0.060f, 0.058f), 14.0f, new Vector3f(0.72f),
                    new Vector3f(0.0008f, 0.0240f, 0.0650f), 6051.8f, 110.0f));

    public static final PlanetaryAtmosphere JUPITER_ATMOSPHERE = PlanetaryAtmosphere.fromComposition(
            Map.of(Gas.H2, 0.898, Gas.HE, 0.102, Gas.CH4, 0.003),
            100_000.0, 165.0, 24.79, -320.0, 900.0, 69_911.0f, 420.0f,
            new Aerosols(new Vector3f(2.6e-3f, 2.4e-3f, 1.9e-3f), new Vector3f(3.0e-3f, 2.9e-3f, 2.8e-3f), 24.0f, 0.7f));

    public static final PlanetaryAtmosphere SATURN_ATMOSPHERE = PlanetaryAtmosphere.fromComposition(
            Map.of(Gas.H2, 0.963, Gas.HE, 0.0325, Gas.CH4, 0.0045),
            100_000.0, 134.0, 10.44, -320.0, 1000.0, 58_232.0f, 600.0f,
            new Aerosols(new Vector3f(2.0e-3f, 1.8e-3f, 1.2e-3f), new Vector3f(2.4e-3f, 2.3e-3f, 2.2e-3f), 30.0f, 0.7f));

    // Le méthane absorbe le rouge : ciel cyan. Pression de référence au niveau de l'océan de glace (Y = 63).
    // Rendu : Rayleigh atténué et brume plus dense, sinon un Soleil bas ne laisse passer que le vert (ciel olive).
    public static final PlanetaryAtmosphere URANUS_ATMOSPHERE = tinted(PlanetaryAtmosphere.fromComposition(
            Map.of(Gas.H2, 0.83, Gas.HE, 0.15, Gas.CH4, 0.023),
            100_000.0, 76.0, 8.69, 63.0, 900.0, 25_362.0f, 350.0f,
            new Aerosols(new Vector3f(1.2e-3f), new Vector3f(1.4e-3f), 20.0f, 0.65f)),
            0.3f, 2.5e-3f, new Vector3f(4.0e-3f, 0.0f, 0.0f));

    // Bleu plus profond qu'Uranus : brume plus fine et absorbant rouge supplémentaire non identifié.
    public static final PlanetaryAtmosphere NEPTUNE_ATMOSPHERE = tinted(PlanetaryAtmosphere.fromComposition(
            Map.of(Gas.H2, 0.80, Gas.HE, 0.19, Gas.CH4, 0.015),
            100_000.0, 72.0, 11.15, -320.0, 900.0, 24_622.0f, 300.0f,
            new Aerosols(new Vector3f(0.6e-3f), new Vector3f(0.8e-3f), 16.0f, 0.65f)),
            0.45f, 0.8e-3f, new Vector3f(4.0e-3f, 1.2e-3f, 0.0f));

    /** Titan, vu depuis Saturne : brume organique orange (tholins) dans 1,5 bar d'azote. */
    public static final PlanetaryAtmosphere TITAN_ATMOSPHERE = PlanetaryAtmosphere.fromComposition(
            Map.of(Gas.N2, 0.95, Gas.CH4, 0.05),
            146_700.0, 94.0, 1.35, 63.0, 3000.0, 2574.7f, 600.0f,
            new Aerosols(new Vector3f(4.0e-3f, 2.5e-3f, 1.0e-3f), new Vector3f(5.0e-3f, 5.5e-3f, 7.0e-3f), 60.0f, 0.6f));

    public static final PlanetaryAtmosphere NETHER = PlanetaryAtmosphere.fromComposition(
            Map.of(Gas.N2, 0.70, Gas.CO2, 0.12, Gas.O2, 0.17, Gas.AR, 0.01),
            120_000.0, 340.0, 9.80665, 32.0, 600.0, 6371.0f, 60.0f,
            new Aerosols(new Vector3f(0.030f, 0.012f, 0.006f), new Vector3f(0.034f, 0.020f, 0.016f), 3.0f, 0.65f));

    private static final Map<ResourceKey<Level>, PlanetaryAtmosphere> REGISTRY = new ConcurrentHashMap<>(Map.ofEntries(
            Map.entry(Level.OVERWORLD, TERRA),
            Map.entry(Level.NETHER, NETHER),
            Map.entry(Level.END, TERRA),
            Map.entry(SPACE, PlanetaryAtmosphere.VACUUM),
            Map.entry(MERCURY, PlanetaryAtmosphere.VACUUM),
            Map.entry(VENUS, VENUS_ATMOSPHERE),
            Map.entry(LUNA, PlanetaryAtmosphere.VACUUM),
            Map.entry(MARS, MARS_ATMOSPHERE),
            Map.entry(JUPITER, JUPITER_ATMOSPHERE),
            Map.entry(SATURN, SATURN_ATMOSPHERE),
            Map.entry(URANUS, URANUS_ATMOSPHERE),
            Map.entry(NEPTUNE, NEPTUNE_ATMOSPHERE)));

    private PlanetaryAtmospheres() {
    }

    /**
     * Ajustement visuel d'une atmosphère d'hydrogène : Rayleigh mis à l'échelle, brume neutre, absorption ajoutée.
     * Les grandeurs de jeu (pression, composition) ne changent pas.
     */
    private static PlanetaryAtmosphere tinted(PlanetaryAtmosphere base, float rayleighScale, float haze, Vector3f extraAbsorption) {
        PlanetaryAtmosphere.Scattering s = base.scattering();
        return base.withScattering(new PlanetaryAtmosphere.Scattering(
                new Vector3f(s.rayleighPerKm()).mul(rayleighScale), s.rayleighScaleHeightKm(),
                new Vector3f(haze), new Vector3f(haze * 1.15f), s.mieScaleHeightKm(), s.mieAnisotropy(),
                new Vector3f(s.absorptionPerKm()).add(extraAbsorption), s.planetRadiusKm(), s.thicknessKm()));
    }

    private static ResourceKey<Level> key(String path) {
        return ResourceKey.create(Registries.DIMENSION, OdysseaCore.id(path));
    }

    public static PlanetaryAtmosphere get(ResourceKey<Level> dimension) {
        return REGISTRY.getOrDefault(dimension, TERRA);
    }

    public static void register(ResourceKey<Level> dimension, PlanetaryAtmosphere atmosphere) {
        REGISTRY.put(dimension, atmosphere);
    }
}
