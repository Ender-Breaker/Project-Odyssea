package odyssea.render.veil;

import odyssea.dimension.PlanetaryAtmosphere;
import odyssea.dimension.PlanetaryAtmospheres;
import odyssea.dimension.Planets;
import odyssea.render.veil.AtmosphereRenderPass.CelestialBody;
import odyssea.render.veil.AtmosphereRenderPass.RingSystem;
import odyssea.render.veil.AtmosphereRenderPass.SkyLayout;
import odyssea.render.veil.AtmosphereRenderPass.SkyTrack;
import odyssea.render.veil.AtmosphereRenderPass.SurfacePalette;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/**
 * Le ciel vu depuis chaque corps du système solaire.
 *
 * <p>Règles de cohérence :
 * <ul>
 *   <li>Le Soleil a son diamètre apparent réel ∝ 1/d (2,6 fois plus large depuis Mercure, un point depuis Neptune)
 *       et un éclairement en 1/d². L'exposition compense en partie, comme l'œil qui s'adapte : les planètes
 *       lointaines restent plus sombres sans devenir noires.</li>
 *   <li>Les lunes ont leurs distances, rayons et périodes réels, avec le même agrandissement apparent que la
 *       lune vanilla vue de la Terre ({@link #APPARENT_SCALE}). Les périodes sont synodiques, en jours locaux :
 *       Phobos se lève à l'ouest deux fois par jour martien, Triton (orbite rétrograde) file d'est en ouest.</li>
 *   <li>La Terre vue de la Lune est immobile dans le ciel (rotation synchrone de la Lune) et montre ses phases.</li>
 *   <li>Les corps sans atmosphère ont un ciel noir où les étoiles restent visibles en plein jour.</li>
 *   <li>Sur les géantes gazeuses, l'observateur flotte ~100 km au-dessus de la couche de nuages, dessinée sous lui ;
 *       les anneaux de Saturne barrent le ciel et portent l'ombre de la planète.</li>
 * </ul>
 */
final class SolarSystemSky {
    static final double VERTICAL_KM_PER_BLOCK = 0.08;
    /** Agrandissement apparent des astres, celui de la lune vanilla par rapport à la vraie Lune (≈ 7,7). */
    static final double APPARENT_SCALE = 7.7;
    private static final float SUN_ILLUMINANCE_1AU = 22.0f;
    /** Couche de nuages des géantes 320 blocs sous Y = 0, soit ~25 km : la mer de nuages couvre tout le bas du ciel. */
    private static final double GAS_GIANT_DECK_Y = -320.0;

    private SolarSystemSky() {
    }

    // ───────────────────────────── Corps ─────────────────────────────

    private static Vector3f rgb(float r, float g, float b) {
        return new Vector3f(r, g, b);
    }

    private static SurfacePalette rocky(Vector3f low, Vector3f mid, Vector3f high, float seaLevel, float seed) {
        return new SurfacePalette(low, mid, high, seaLevel, 0.0f, SurfacePalette.ROCKY, seed);
    }

    private static SurfacePalette banded(Vector3f belts, Vector3f zones, float seed) {
        return new SurfacePalette(belts, belts, zones, 0.0f, 0.0f, SurfacePalette.BANDED, seed);
    }

    private static CelestialBody body(String name, float radiusKm, float reliefKm, PlanetaryAtmosphere atmosphere,
                                      Vector3f axis, float rotationSeconds, SurfacePalette palette, RingSystem rings) {
        return new CelestialBody(name, new Vector3d(), radiusKm, reliefKm, atmosphere, axis, rotationSeconds, palette, rings);
    }

    private static CelestialBody body(String name, float radiusKm, float reliefKm, SurfacePalette palette) {
        return body(name, radiusKm, reliefKm, PlanetaryAtmosphere.VACUUM, new Vector3f(0.0f, 1.0f, 0.0f), 0.0f, palette, null);
    }

    /** Axe de rotation vu d'un observateur à la latitude donnée (axe dans le plan Y-Z, vers +Z). */
    private static Vector3f axisAtLatitude(double latitudeDegrees) {
        double phi = Math.toRadians(latitudeDegrees);
        return new Vector3f(0.0f, (float) Math.sin(phi), (float) Math.cos(phi));
    }

    static final CelestialBody TERRA = body("terra", 6371.0f, 9.0f, PlanetaryAtmospheres.TERRA,
            new Vector3f(0.0f, 0.917f, 0.398f), 1200.0f,
            new SurfacePalette(rgb(0.02f, 0.07f, 0.18f), rgb(0.13f, 0.25f, 0.08f), rgb(0.42f, 0.36f, 0.28f), 0.53f, 1.0f,
                    SurfacePalette.ROCKY, 11.0f), null);
    static final CelestialBody LUNA = body("luna", 1737.4f, 6.0f,
            rocky(rgb(0.30f, 0.30f, 0.31f), rgb(0.45f, 0.44f, 0.42f), rgb(0.62f, 0.61f, 0.58f), 0.42f, 4.7f));
    static final CelestialBody MERCURY = body("mercury", 2439.7f, 4.0f,
            rocky(rgb(0.24f, 0.22f, 0.20f), rgb(0.36f, 0.34f, 0.31f), rgb(0.48f, 0.46f, 0.42f), 0.35f, 3.1f));
    static final CelestialBody VENUS = body("venus", 6051.8f, 0.0f, PlanetaryAtmospheres.VENUS_ATMOSPHERE,
            new Vector3f(0.0f, 1.0f, 0.0f), 0.0f,
            rocky(rgb(0.24f, 0.17f, 0.11f), rgb(0.30f, 0.22f, 0.14f), rgb(0.38f, 0.28f, 0.18f), 0.4f, 8.2f), null);
    static final CelestialBody MARS = body("mars", 3389.5f, 12.0f, PlanetaryAtmospheres.MARS_ATMOSPHERE,
            new Vector3f(0.0f, 0.906f, 0.423f), 1240.0f,
            rocky(rgb(0.30f, 0.14f, 0.08f), rgb(0.55f, 0.28f, 0.14f), rgb(0.72f, 0.45f, 0.28f), 0.45f, 23.0f), null);
    static final CelestialBody PHOBOS = body("phobos", 11.3f, 2.2f,
            rocky(rgb(0.16f, 0.14f, 0.12f), rgb(0.22f, 0.20f, 0.18f), rgb(0.30f, 0.27f, 0.24f), 0.5f, 31.0f));
    static final CelestialBody DEIMOS = body("deimos", 6.2f, 0.8f,
            rocky(rgb(0.20f, 0.18f, 0.16f), rgb(0.27f, 0.25f, 0.22f), rgb(0.34f, 0.31f, 0.28f), 0.5f, 37.0f));

    static final CelestialBody JUPITER = body("jupiter", 69_911.0f, 0.0f, PlanetaryAtmospheres.JUPITER_ATMOSPHERE,
            axisAtLatitude(28.0), 0.0f, banded(rgb(0.62f, 0.47f, 0.34f), rgb(0.93f, 0.88f, 0.78f), 5.3f), null);
    static final CelestialBody IO = body("io", 1821.6f, 3.0f,
            rocky(rgb(0.50f, 0.36f, 0.12f), rgb(0.84f, 0.74f, 0.34f), rgb(0.94f, 0.90f, 0.62f), 0.32f, 12.0f));
    static final CelestialBody EUROPA = body("europa", 1560.8f, 0.6f,
            rocky(rgb(0.52f, 0.40f, 0.30f), rgb(0.80f, 0.75f, 0.67f), rgb(0.94f, 0.92f, 0.88f), 0.38f, 14.0f));
    static final CelestialBody GANYMEDE = body("ganymede", 2634.1f, 3.0f,
            rocky(rgb(0.28f, 0.25f, 0.22f), rgb(0.46f, 0.43f, 0.39f), rgb(0.70f, 0.68f, 0.64f), 0.46f, 16.0f));
    static final CelestialBody CALLISTO = body("callisto", 2410.3f, 3.0f,
            rocky(rgb(0.16f, 0.14f, 0.12f), rgb(0.28f, 0.25f, 0.22f), rgb(0.48f, 0.45f, 0.41f), 0.5f, 18.0f));

    static final CelestialBody SATURN = body("saturn", 58_232.0f, 0.0f, PlanetaryAtmospheres.SATURN_ATMOSPHERE,
            axisAtLatitude(35.0), 0.0f, banded(rgb(0.78f, 0.67f, 0.48f), rgb(0.93f, 0.86f, 0.67f), 7.3f),
            new RingSystem(74_500.0f, 140_220.0f, rgb(0.83f, 0.76f, 0.62f), 0.85f));
    static final CelestialBody TITAN = body("titan", 2574.7f, 0.0f, PlanetaryAtmospheres.TITAN_ATMOSPHERE,
            new Vector3f(0.0f, 1.0f, 0.0f), 0.0f, banded(rgb(0.72f, 0.48f, 0.20f), rgb(0.82f, 0.58f, 0.27f), 21.0f), null);
    static final CelestialBody RHEA = body("rhea", 763.8f, 1.5f,
            rocky(rgb(0.56f, 0.54f, 0.51f), rgb(0.72f, 0.70f, 0.67f), rgb(0.88f, 0.87f, 0.85f), 0.45f, 24.0f));

    // Géante de glaces : depuis sa banquise, le sol lointain est une étendue de glace cyan pâle.
    static final CelestialBody URANUS = body("uranus", 25_362.0f, 0.0f, PlanetaryAtmospheres.URANUS_ATMOSPHERE,
            axisAtLatitude(30.0), 0.0f, rocky(rgb(0.40f, 0.62f, 0.68f), rgb(0.70f, 0.86f, 0.89f), rgb(0.86f, 0.94f, 0.95f), 0.5f, 9.1f),
            new RingSystem(41_837.0f, 51_149.0f, rgb(0.24f, 0.24f, 0.25f), 0.22f));
    static final CelestialBody MIRANDA = body("miranda", 235.8f, 4.0f,
            rocky(rgb(0.42f, 0.42f, 0.42f), rgb(0.58f, 0.58f, 0.58f), rgb(0.74f, 0.74f, 0.73f), 0.5f, 41.0f));
    static final CelestialBody ARIEL = body("ariel", 578.9f, 2.0f,
            rocky(rgb(0.50f, 0.49f, 0.48f), rgb(0.66f, 0.65f, 0.64f), rgb(0.82f, 0.81f, 0.80f), 0.45f, 43.0f));
    static final CelestialBody UMBRIEL = body("umbriel", 584.7f, 2.0f,
            rocky(rgb(0.22f, 0.22f, 0.22f), rgb(0.30f, 0.30f, 0.30f), rgb(0.42f, 0.42f, 0.41f), 0.5f, 47.0f));
    static final CelestialBody TITANIA = body("titania", 788.9f, 2.5f,
            rocky(rgb(0.40f, 0.38f, 0.37f), rgb(0.56f, 0.54f, 0.52f), rgb(0.72f, 0.70f, 0.68f), 0.45f, 53.0f));
    static final CelestialBody OBERON = body("oberon", 761.4f, 2.5f,
            rocky(rgb(0.34f, 0.31f, 0.29f), rgb(0.48f, 0.45f, 0.42f), rgb(0.64f, 0.61f, 0.58f), 0.48f, 59.0f));

    static final CelestialBody NEPTUNE = body("neptune", 24_622.0f, 0.0f, PlanetaryAtmospheres.NEPTUNE_ATMOSPHERE,
            axisAtLatitude(25.0), 0.0f, banded(rgb(0.20f, 0.34f, 0.76f), rgb(0.40f, 0.55f, 0.88f), 13.7f), null);
    static final CelestialBody TRITON = body("triton", 1353.4f, 1.0f,
            rocky(rgb(0.66f, 0.56f, 0.52f), rgb(0.84f, 0.77f, 0.73f), rgb(0.95f, 0.92f, 0.90f), 0.4f, 61.0f));
    static final CelestialBody PROTEUS = body("proteus", 210.0f, 6.0f,
            rocky(rgb(0.18f, 0.17f, 0.16f), rgb(0.24f, 0.23f, 0.22f), rgb(0.32f, 0.31f, 0.30f), 0.5f, 67.0f));

    // ───────────────────────────── Placement apparent ─────────────────────────────

    /**
     * Lune qui suit la course du ciel.
     *
     * @param distanceFromSurfaceKm distance réelle entre la surface de l'observateur et le centre de la lune
     * @param synodicLocalDays      période synodique en jours locaux (négative si l'orbite est rétrograde)
     */
    private static SkyTrack moon(CelestialBody body, double distanceFromSurfaceKm, double synodicLocalDays, double inclinationDegrees) {
        double angularRadius = Math.atan(body.radiusKm() / distanceFromSurfaceKm) * APPARENT_SCALE;
        double visualDistance = body.radiusKm() / Math.tan(angularRadius);
        return new SkyTrack(body, visualDistance, synodicLocalDays, (float) Math.toRadians(inclinationDegrees));
    }

    /** Astre fixe dans le ciel de l'observateur (azimut 0 = +Z, 90 = +X), au diamètre apparent agrandi. */
    private static CelestialBody fixed(CelestialBody body, Vector3dc observerOrigin, double azimuthDegrees, double elevationDegrees,
                                       double distanceFromSurfaceKm) {
        double angularRadius = Math.atan(body.radiusKm() / distanceFromSurfaceKm) * APPARENT_SCALE;
        double visualDistance = body.radiusKm() / Math.tan(angularRadius);
        double azimuth = Math.toRadians(azimuthDegrees);
        double elevation = Math.toRadians(elevationDegrees);
        Vector3d direction = new Vector3d(Math.sin(azimuth) * Math.cos(elevation), Math.sin(elevation), Math.cos(azimuth) * Math.cos(elevation));
        return body.at(new Vector3d(observerOrigin).add(direction.mul(visualDistance)));
    }

    private static float sunRadius(Planets.Planet planet) {
        return (float) (AtmosphereRenderPass.SUN_ANGULAR_RADIUS / planet.distanceAu());
    }

    private static float sunIlluminance(Planets.Planet planet) {
        return (float) (SUN_ILLUMINANCE_1AU * planet.solarFlux());
    }

    /** Adaptation partielle de l'œil : l'exposition croît comme (E₀/E)^0,65. */
    private static float adaptedExposure(Planets.Planet planet) {
        return (float) (0.9 * Math.pow(1.0 / planet.solarFlux(), 0.65));
    }

    private static AtmosphereRenderPass.Builder surface(CelestialBody home, Planets.Planet planet, double seaLevelY) {
        return SkyLayout.onSurface(home, planet, seaLevelY)
                .sun(sunIlluminance(planet), sunRadius(planet))
                .exposure(adaptedExposure(planet));
    }

    // ───────────────────────────── Ciels ─────────────────────────────

    static void registerLayouts() {
        AtmosphereRenderPass.registerLayout(Planets.TERRA.dimension(), surface(TERRA, Planets.TERRA, 63.0)
                .track(moon(LUNA, 384_400.0 - 6371.0, 8.0, 6.0))
                .build());

        AtmosphereRenderPass.Builder luna = surface(LUNA, Planets.LUNA, 63.0).dayStars(0.45f);
        AtmosphereRenderPass.registerLayout(Planets.LUNA.dimension(),
                luna.visible(fixed(TERRA, luna.origin(), 160.0, 52.0, 384_400.0 - 1737.4)).build());

        AtmosphereRenderPass.registerLayout(Planets.MERCURY.dimension(), surface(MERCURY, Planets.MERCURY, 63.0)
                .dayStars(0.4f)
                .build());

        AtmosphereRenderPass.registerLayout(Planets.VENUS.dimension(), surface(VENUS, Planets.VENUS, 40.0)
                .exposure(0.75f)
                .build());

        AtmosphereRenderPass.registerLayout(Planets.MARS.dimension(), surface(MARS, Planets.MARS, 63.0)
                .track(moon(PHOBOS, 9376.0 - 3389.5, 0.3106, 1.1))
                .track(moon(DEIMOS, 23_463.0 - 3389.5, 1.2307, 1.8))
                .build());

        AtmosphereRenderPass.registerLayout(Planets.JUPITER.dimension(), surface(JUPITER, Planets.JUPITER, GAS_GIANT_DECK_Y)
                .path(0.3f, 0.05f)
                .track(moon(IO, 421_700.0 - 69_911.0, 4.278, 0.6))
                .track(moon(EUROPA, 671_034.0 - 69_911.0, 8.588, 1.5))
                .track(moon(GANYMEDE, 1_070_412.0 - 69_911.0, 17.30, 1.0))
                .track(moon(CALLISTO, 1_882_709.0 - 69_911.0, 40.36, 2.0))
                .build());

        // Latitude 35° N, Soleil 17° au-dessus du plan des anneaux : leur face éclairée est tournée vers l'observateur.
        AtmosphereRenderPass.registerLayout(Planets.SATURN.dimension(), surface(SATURN, Planets.SATURN, GAS_GIANT_DECK_Y)
                .path((float) Math.toRadians(-35.0), (float) Math.toRadians(17.0))
                .track(moon(TITAN, 1_221_870.0 - 58_232.0, 35.9, 0.4))
                .track(moon(RHEA, 527_108.0 - 58_232.0, 10.17, 0.6))
                .build());

        // Uranus roule sur son orbite (axe incliné de 98°) : Soleil très décalé vers le pôle selon la saison.
        AtmosphereRenderPass.registerLayout(Planets.URANUS.dimension(), surface(URANUS, Planets.URANUS, 63.0)
                .exposure(adaptedExposure(Planets.URANUS) * 1.6f)
                .path((float) Math.toRadians(-30.0), (float) Math.toRadians(26.0))
                .track(moon(MIRANDA, 129_390.0 - 25_362.0, 1.967, 4.2))
                .track(moon(ARIEL, 191_020.0 - 25_362.0, 3.508, 0.3))
                .track(moon(UMBRIEL, 266_000.0 - 25_362.0, 5.77, 0.4))
                .track(moon(TITANIA, 435_910.0 - 25_362.0, 12.12, 0.3))
                .track(moon(OBERON, 583_520.0 - 25_362.0, 18.74, 0.6))
                .build());

        AtmosphereRenderPass.registerLayout(Planets.NEPTUNE.dimension(), surface(NEPTUNE, Planets.NEPTUNE, GAS_GIANT_DECK_Y)
                .exposure(adaptedExposure(Planets.NEPTUNE) * 1.5f)
                .path(0.4f, 0.1f)
                .track(moon(TRITON, 354_759.0 - 24_622.0, -8.755, 23.0))
                .track(moon(PROTEUS, 117_647.0 - 24_622.0, 1.671, 0.5))
                .build());

        AtmosphereRenderPass.registerLayout(PlanetaryAtmospheres.SPACE, spaceLayout());
    }

    // ───────────────────────────── Espace ─────────────────────────────

    private static final java.util.List<CelestialBody> ALL_BODIES = java.util.List.of(TERRA, LUNA, MERCURY, VENUS, MARS, PHOBOS,
            DEIMOS, JUPITER, IO, EUROPA, GANYMEDE, CALLISTO, SATURN, TITAN, RHEA, URANUS, MIRANDA, ARIEL, UMBRIEL, TITANIA, OBERON,
            NEPTUNE, TRITON, PROTEUS);

    /**
     * Dimension spatiale : chaque astre à sa position de {@link odyssea.space.SpaceSystem} (un bloc = un km visuel),
     * à l'échelle des rayons du système pilotable ; le relief et les anneaux suivent la même échelle.
     */
    private static SkyLayout spaceLayout() {
        java.util.List<CelestialBody> bodies = new java.util.ArrayList<>();
        for (CelestialBody body : ALL_BODIES) {
            odyssea.space.SpaceSystem.Body system = odyssea.space.SpaceSystem.body(body.name());
            if (system == null) {
                continue;
            }
            float scale = (float) (system.radius() / body.radiusKm());
            RingSystem rings = body.rings() == null ? null : new RingSystem(body.rings().innerRadiusKm() * scale,
                    body.rings().outerRadiusKm() * scale, body.rings().color(), body.rings().opacity());
            net.minecraft.world.phys.Vec3 p = odyssea.space.SpaceSystem.position(body.name());
            // Axe incliné de façon fixe et rotation lente : les planètes tournent sous les yeux du pilote.
            float rotation = body.rotationPeriodSeconds() > 0.0f ? body.rotationPeriodSeconds() * 4.0f : 2400.0f;
            bodies.add(new CelestialBody(body.name(), new Vector3d(p.x, p.y, p.z), (float) system.radius(), body.reliefKm() * scale,
                    body.atmosphere(), body.axis(), rotation, body.palette(), rings));
        }
        net.minecraft.world.phys.Vec3 sun = odyssea.space.SpaceSystem.sunPosition();
        return SkyLayout.heliocentric(bodies, new Vector3d(sun.x, sun.y, sun.z));
    }
}
