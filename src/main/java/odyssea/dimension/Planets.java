package odyssea.dimension;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Les corps du système solaire qui possèdent une dimension.
 *
 * <p><b>Durée du jour.</b> Toutes les dimensions partagent l'horloge de l'overworld ({@code dayTime}). Chaque
 * planète la lit à sa propre cadence : {@code solarDay} est la durée d'un jour solaire en jours de jeu terrestres.
 * Les rotations rapides gardent leur valeur réelle (Mars 1,03 ; Jupiter 0,41…). Les rotations lentes sont
 * comprimées du même facteur que le cycle lunaire vanilla, qui ramène le mois synodique de 29,5 jours à 8 jours :
 * la Lune a donc un jour de 8 jours terrestres, cohérent avec les phases vues depuis la Terre ; Mercure en a 48 et
 * Vénus 32. Une valeur négative indique une rotation rétrograde (Vénus, Uranus) : le Soleil se lève à l'ouest.
 */
public final class Planets {
    public enum Kind {
        ROCKY, ICE_GIANT, GAS_GIANT
    }

    /**
     * @param solarDay  durée du jour solaire en jours de jeu (négatif : rotation rétrograde)
     * @param distanceAu demi-grand axe de l'orbite autour du Soleil (pour un satellite, celui de sa planète)
     */
    public record Planet(String id, ResourceKey<Level> dimension, PlanetaryAtmosphere atmosphere, double gravity,
                         double solarDay, double distanceAu, Kind kind) {
        public boolean retrograde() {
            return solarDay < 0.0;
        }

        /** Nombre de jours locaux écoulés depuis l'origine de l'horloge partagée. */
        public double localDays(double dayTime) {
            return dayTime / (24_000.0 * Math.abs(solarDay));
        }

        /** Même forme que {@code DimensionType.timeOfDay} : 0 à midi, 0,25 au coucher, aube à {@code dayTime} = 0. */
        public float timeOfDay(long dayTime) {
            double d0 = frac(localDays(dayTime) - 0.25);
            double d1 = 0.5 - Math.cos(d0 * Math.PI) / 2.0;
            return (float) (d0 * 2.0 + d1) / 3.0f;
        }

        /** Éclairement relatif du Soleil, en 1/d² (1 pour la Terre). */
        public double solarFlux() {
            return 1.0 / (distanceAu * distanceAu);
        }
    }

    public static final Planet MERCURY = new Planet("mercury", PlanetaryAtmospheres.MERCURY, PlanetaryAtmosphere.VACUUM, 3.70, 48.0, 0.387, Kind.ROCKY);
    public static final Planet VENUS = new Planet("venus", PlanetaryAtmospheres.VENUS, PlanetaryAtmospheres.VENUS_ATMOSPHERE, 8.87, -32.0, 0.723, Kind.ROCKY);
    public static final Planet TERRA = new Planet("terra", Level.OVERWORLD, PlanetaryAtmospheres.TERRA, 9.81, 1.0, 1.0, Kind.ROCKY);
    public static final Planet LUNA = new Planet("luna", PlanetaryAtmospheres.LUNA, PlanetaryAtmosphere.VACUUM, 1.62, 8.0, 1.0, Kind.ROCKY);
    public static final Planet MARS = new Planet("mars", PlanetaryAtmospheres.MARS, PlanetaryAtmospheres.MARS_ATMOSPHERE, 3.71, 1.0275, 1.524, Kind.ROCKY);
    public static final Planet JUPITER = new Planet("jupiter", PlanetaryAtmospheres.JUPITER, PlanetaryAtmospheres.JUPITER_ATMOSPHERE, 24.79, 0.4135, 5.203, Kind.GAS_GIANT);
    public static final Planet SATURN = new Planet("saturn", PlanetaryAtmospheres.SATURN, PlanetaryAtmospheres.SATURN_ATMOSPHERE, 10.44, 0.444, 9.537, Kind.GAS_GIANT);
    public static final Planet URANUS = new Planet("uranus", PlanetaryAtmospheres.URANUS, PlanetaryAtmospheres.URANUS_ATMOSPHERE, 8.69, -0.718, 19.19, Kind.ICE_GIANT);
    public static final Planet NEPTUNE = new Planet("neptune", PlanetaryAtmospheres.NEPTUNE, PlanetaryAtmospheres.NEPTUNE_ATMOSPHERE, 11.15, 0.671, 30.07, Kind.GAS_GIANT);

    public static final List<Planet> ALL = List.of(MERCURY, VENUS, TERRA, LUNA, MARS, JUPITER, SATURN, URANUS, NEPTUNE);
    private static final Map<ResourceKey<Level>, Planet> BY_DIMENSION =
            ALL.stream().collect(Collectors.toUnmodifiableMap(Planet::dimension, Function.identity()));

    private Planets() {
    }

    public static @Nullable Planet get(ResourceKey<Level> dimension) {
        return BY_DIMENSION.get(dimension);
    }

    private static double frac(double value) {
        return value - Math.floor(value);
    }
}
