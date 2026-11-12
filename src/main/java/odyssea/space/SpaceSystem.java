package odyssea.space;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import odyssea.dimension.PlanetaryAtmospheres;
import odyssea.dimension.Planets;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Le système solaire « pilotable » de la dimension {@code odyssea:space}.
 *
 * <p>Un bloc y vaut un kilomètre visuel. Le Soleil est à l'origine, dans le plan de l'écliptique ({@link #ECLIPTIC_Y}) ;
 * les distances sont comprimées (la Terre à 100 000 blocs, Neptune à 820 000) et les rayons divisés par deux, ce qui
 * garde les proportions entre corps voisins tout en rendant chaque planète atteignable en vol normal (quelques minutes
 * pour la Lune) et en saut quantique (de 5 à 45 s).
 *
 * <p>Les corps qui ont une dimension s'abordent physiquement : franchir le sommet de leur atmosphère fait entrer dans
 * la dimension, au point de la surface survolé (longitude → X, latitude → Z). Monter au-dessus de {@link #EXIT_Y}
 * dans une dimension planétaire ramène dans l'espace, au-dessus du même point. Les autres lunes sont visibles, servent
 * de destinations quantiques, mais leur surface n'est pas accessible.
 */
public final class SpaceSystem {
    public static final ResourceKey<Level> SPACE = PlanetaryAtmospheres.SPACE;
    /** Altitude du plan de l'écliptique dans la dimension spatiale : marge de 1 000 blocs de part et d'autre. */
    public static final double ECLIPTIC_Y = 1000.0;
    /** Distance Terre-Soleil, en blocs : sert d'unité astronomique pour l'éclairement et le diamètre du Soleil. */
    public static final double AU = 100_000.0;
    public static final double SUN_RADIUS = 465.0;
    /** En deçà, la coque chauffe ; au contact de la photosphère visuelle, elle fond. */
    public static final double SUN_DANGER_RADIUS = 6_000.0;
    public static final double RADIUS_SCALE = 0.5;
    /** Altitude d'arrivée dans une dimension planétaire et seuil de sortie vers l'espace. */
    public static final double ENTRY_Y = 1850.0;
    public static final double EXIT_Y = 1960.0;
    public static final double MIN_SPACE_Y = -40.0;
    public static final double MAX_SPACE_Y = 2040.0;
    /** Vitesse de croisière quantique, en blocs par seconde. */
    public static final double QUANTUM_SPEED = 22_000.0;
    /** Unités de carburant quantique par tranche de 10 000 blocs. */
    public static final double QUANTUM_FUEL_PER_10K = 1.0;

    public enum Kind {
        STAR, ROCKY, GAS_GIANT, ICE_GIANT, MOON, BELT
    }

    /**
     * @param orbitRadius   distance au parent, en blocs
     * @param angleDegrees  position sur l'orbite (0 = +X, 90 = +Z)
     * @param radius        rayon visuel, en blocs
     * @param atmosphere    épaisseur de l'atmosphère (seuil d'entrée au-dessus du rayon)
     * @param color         couleur sur la carte (RGB)
     */
    public record Body(String id, @Nullable String parent, Kind kind, double orbitRadius, double angleDegrees,
                       double radius, double atmosphere, @Nullable ResourceKey<Level> dimension, int color) {
        public boolean landable() {
            return dimension != null;
        }

        public double entryRadius() {
            return radius + atmosphere;
        }

        /** Rayon de la zone où le saut quantique ne peut pas démarrer (puits de gravité). */
        public double quantumExclusion() {
            return kind == Kind.BELT ? 0.0 : entryRadius() + 800.0;
        }

        public String translationKey() {
            return "odyssea.body." + id;
        }
    }

    private static final Map<String, Body> BODIES = new LinkedHashMap<>();
    private static final Map<String, Vec3> POSITIONS = new LinkedHashMap<>();

    static {
        body("sun", null, Kind.STAR, 0, 0, SUN_RADIUS, 0, null, 0xFFE9A8);
        body("mercury", "sun", Kind.ROCKY, 40_000, 30, 1_220, 60, PlanetaryAtmospheres.MERCURY, 0x9C948A);
        body("venus", "sun", Kind.ROCKY, 72_000, 140, 3_026, 110, PlanetaryAtmospheres.VENUS, 0xE3C58A);
        body("terra", "sun", Kind.ROCKY, AU, 260, 3_186, 100, Level.OVERWORLD, 0x4C8BD6);
        body("luna", "terra", Kind.MOON, 12_000, 20, 869, 60, PlanetaryAtmospheres.LUNA, 0xBDBAB4);
        body("mars", "sun", Kind.ROCKY, 152_000, 10, 1_695, 120, PlanetaryAtmospheres.MARS, 0xC8643C);
        body("phobos", "mars", Kind.MOON, 4_500, 70, 40, 0, null, 0x6E6258);
        body("deimos", "mars", Kind.MOON, 8_000, 200, 25, 0, null, 0x857868);
        body("belt", "sun", Kind.BELT, 225_000, 300, 25_000, 0, null, 0x8A7D6C);
        body("jupiter", "sun", Kind.GAS_GIANT, 360_000, 100, 34_955, 420, PlanetaryAtmospheres.JUPITER, 0xD8B48C);
        body("io", "jupiter", Kind.MOON, 60_000, 40, 911, 0, null, 0xE0CF6A);
        body("europa", "jupiter", Kind.MOON, 75_000, 160, 780, 0, null, 0xD9CFBF);
        body("ganymede", "jupiter", Kind.MOON, 95_000, 250, 1_317, 0, null, 0x8F877C);
        body("callisto", "jupiter", Kind.MOON, 120_000, 320, 1_205, 0, null, 0x5E554B);
        body("saturn", "sun", Kind.GAS_GIANT, 520_000, 210, 29_116, 600, PlanetaryAtmospheres.SATURN, 0xE6D2A0);
        body("titan", "saturn", Kind.MOON, 100_000, 300, 1_287, 0, null, 0xD08A3A);
        body("rhea", "saturn", Kind.MOON, 80_000, 60, 382, 0, null, 0xC9C6C0);
        body("uranus", "sun", Kind.ICE_GIANT, 680_000, 320, 12_681, 350, PlanetaryAtmospheres.URANUS, 0x9ADCE4);
        body("miranda", "uranus", Kind.MOON, 25_000, 10, 118, 0, null, 0x9A9A9A);
        body("ariel", "uranus", Kind.MOON, 32_000, 100, 289, 0, null, 0xB0AEAB);
        body("umbriel", "uranus", Kind.MOON, 40_000, 190, 292, 0, null, 0x555555);
        body("titania", "uranus", Kind.MOON, 50_000, 260, 394, 0, null, 0x97918C);
        body("oberon", "uranus", Kind.MOON, 60_000, 330, 381, 0, null, 0x7E7570);
        body("neptune", "sun", Kind.GAS_GIANT, 820_000, 60, 12_311, 300, PlanetaryAtmospheres.NEPTUNE, 0x3F68D8);
        body("triton", "neptune", Kind.MOON, 35_000, 150, 677, 0, null, 0xD9C6BE);
        body("proteus", "neptune", Kind.MOON, 20_000, 280, 105, 0, null, 0x3E3B38);
        for (Body body : BODIES.values()) {
            POSITIONS.put(body.id(), computePosition(body));
        }
    }

    private SpaceSystem() {
    }

    private static void body(String id, @Nullable String parent, Kind kind, double orbit, double angle, double radius,
                             double atmosphere, @Nullable ResourceKey<Level> dimension, int color) {
        BODIES.put(id, new Body(id, parent, kind, orbit, angle, radius, atmosphere, dimension, color));
    }

    private static Vec3 computePosition(Body body) {
        Vec3 parent = body.parent() == null ? new Vec3(0.0, ECLIPTIC_Y, 0.0) : position(body.parent());
        double angle = Math.toRadians(body.angleDegrees());
        return parent.add(Math.cos(angle) * body.orbitRadius(), 0.0, Math.sin(angle) * body.orbitRadius());
    }

    public static java.util.Collection<Body> bodies() {
        return Collections.unmodifiableCollection(BODIES.values());
    }

    public static @Nullable Body body(String id) {
        return BODIES.get(id);
    }

    public static Vec3 position(String id) {
        Vec3 position = POSITIONS.get(id);
        if (position == null) {
            Body body = BODIES.get(id);
            return body == null ? Vec3.ZERO : computePosition(body);
        }
        return position;
    }

    public static Vec3 sunPosition() {
        return new Vec3(0.0, ECLIPTIC_Y, 0.0);
    }

    public static @Nullable Body bodyForDimension(ResourceKey<Level> dimension) {
        for (Body body : BODIES.values()) {
            if (dimension.equals(body.dimension())) {
                return body;
            }
        }
        return null;
    }

    public static boolean isSpace(Level level) {
        return level.dimension().equals(SPACE);
    }

    /** Corps dont l'atmosphère contient le point (entrée dans sa dimension), s'il y en a un. */
    public static @Nullable Body entryBodyAt(Vec3 point) {
        for (Body body : BODIES.values()) {
            if (body.landable() && point.distanceToSqr(position(body.id())) < body.entryRadius() * body.entryRadius()) {
                return body;
            }
        }
        return null;
    }

    /** Corps solide sans dimension dont la surface est franchie (lunes inaccessibles). */
    public static @Nullable Body solidBodyAt(Vec3 point) {
        for (Body body : BODIES.values()) {
            if (body.landable() || body.kind() == Kind.BELT) {
                continue;
            }
            double r = body.kind() == Kind.STAR ? SUN_RADIUS * 2.0 : body.radius();
            if (point.distanceToSqr(position(body.id())) < r * r) {
                return body;
            }
        }
        return null;
    }

    /** Corps dont le puits de gravité empêche un saut quantique. */
    public static @Nullable Body quantumBlocker(Vec3 point) {
        for (Body body : BODIES.values()) {
            double r = body.kind() == Kind.STAR ? SUN_DANGER_RADIUS : body.quantumExclusion();
            if (r > 0.0 && point.distanceToSqr(position(body.id())) < r * r) {
                return body;
            }
        }
        return null;
    }

    /** Point d'arrivée d'un saut vers {@code body}, juste hors de son atmosphère, du côté d'où l'on vient. */
    public static Vec3 quantumArrival(Body body, Vec3 from) {
        Vec3 center = position(body.id());
        if (body.kind() == Kind.BELT) {
            // Ceinture : au bord intérieur, dans la direction du vaisseau.
            Vec3 flat = new Vec3(from.x, 0.0, from.z);
            Vec3 dir = flat.lengthSqr() < 1.0 ? new Vec3(1.0, 0.0, 0.0) : flat.normalize();
            return new Vec3(dir.x * 205_000.0, ECLIPTIC_Y, dir.z * 205_000.0);
        }
        Vec3 dir = from.subtract(center);
        dir = dir.lengthSqr() < 1.0 ? new Vec3(1.0, 0.0, 0.0) : dir.normalize();
        double distance = (body.kind() == Kind.STAR ? SUN_DANGER_RADIUS * 1.6 : body.entryRadius()) + 1_400.0;
        Vec3 arrival = center.add(dir.scale(distance));
        return new Vec3(arrival.x, Math.clamp(arrival.y, MIN_SPACE_Y + 50.0, MAX_SPACE_Y - 50.0), arrival.z);
    }

    public static double quantumFuelCost(double distance) {
        return Math.max(1.0, distance / 10_000.0 * QUANTUM_FUEL_PER_10K);
    }

    public static int quantumTravelTicks(double distance) {
        return (int) Math.round(Math.clamp(distance / QUANTUM_SPEED, 5.0, 45.0) * 20.0);
    }

    /** Éclairement relatif du Soleil (1 à une unité astronomique). */
    public static double solarFlux(Vec3 point) {
        double d = Math.max(SUN_RADIUS * 2.0, point.distanceTo(sunPosition()));
        return (AU / d) * (AU / d);
    }

    // ───────────────────────────── Espace ↔ surface ─────────────────────────────

    /** Repère local au point de la sphère : lignes est, haut, nord (base directe, comme X, Y, Z de la dimension). */
    private static Matrix3d tangentFrame(double longitude, double latitude) {
        double cl = Math.cos(latitude);
        double sl = Math.sin(latitude);
        double co = Math.cos(longitude);
        double so = Math.sin(longitude);
        Vector3d up = new Vector3d(cl * co, sl, cl * so);
        Vector3d east = new Vector3d(-so, 0.0, co);
        Vector3d north = new Vector3d(-sl * co, cl, -sl * so);
        // Matrice dont les lignes sont east, up, north : v_planète = M · v_espace.
        return new Matrix3d(east.x, up.x, north.x, east.y, up.y, north.y, east.z, up.z, north.z);
    }

    /** Résultat d'une transition : position, vitesse et orientation dans le monde d'arrivée. */
    public record Transfer(ResourceKey<Level> dimension, Vec3 position, Vec3 velocity, Quaterniond rotation) {
    }

    /** Entrée dans la dimension de {@code body} depuis le point {@code point} de l'espace. */
    public static Transfer enterPlanet(Body body, Vec3 point, Vec3 velocity) {
        Vec3 rel = point.subtract(position(body.id()));
        double r = Math.max(1.0, rel.length());
        double longitude = Math.atan2(rel.z, rel.x);
        double latitude = Math.asin(Math.clamp(rel.y / r, -1.0, 1.0));
        Matrix3d frame = tangentFrame(longitude, latitude);
        Vector3d v = frame.transform(new Vector3d(velocity.x, velocity.y, velocity.z));
        Quaterniond rotation = new Quaterniond().setFromNormalized(frame);
        double x = longitude * body.radius();
        double z = latitude * body.radius();
        return new Transfer(body.dimension(), new Vec3(x, ENTRY_Y, z), new Vec3(v.x, Math.min(v.y, -5.0), v.z), rotation);
    }

    /** Sortie de la dimension de {@code body} vers l'espace, au-dessus du point (x, z) de la surface. */
    public static Transfer leavePlanet(Body body, Vec3 point, Vec3 velocity) {
        double longitude = point.x / body.radius();
        double exitRadius = body.entryRadius() + 160.0;
        // Sortie à moins de 650 blocs du plan de l'écliptique : loin des limites verticales de l'espace.
        double maxLatitude = Math.asin(Math.min(1.0, 650.0 / exitRadius));
        double latitude = Math.clamp(point.z / body.radius(), -maxLatitude, maxLatitude);
        Matrix3d frame = tangentFrame(longitude, latitude);
        Matrix3d inverse = new Matrix3d(frame).transpose();
        Vector3d v = inverse.transform(new Vector3d(velocity.x, Math.max(velocity.y, 5.0), velocity.z));
        Quaterniond rotation = new Quaterniond().setFromNormalized(inverse);
        Vec3 dir = new Vec3(Math.cos(latitude) * Math.cos(longitude), Math.sin(latitude), Math.cos(latitude) * Math.sin(longitude));
        Vec3 position = position(body.id()).add(dir.scale(exitRadius));
        return new Transfer(SPACE, position, new Vec3(v.x, v.y, v.z), rotation);
    }

    /** Destinations quantiques, dans l'ordre de la carte. */
    public static List<Body> destinations() {
        List<Body> list = new ArrayList<>();
        for (Body body : BODIES.values()) {
            list.add(body);
        }
        return list;
    }

    /** Planète « porteuse » d'une dimension Odyssea (pour l'éclairement, la gravité…). */
    public static @Nullable Planets.Planet planet(ResourceKey<Level> dimension) {
        return Planets.get(dimension);
    }
}
