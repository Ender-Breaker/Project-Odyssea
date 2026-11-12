package odyssea.ship;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Vol d'un vaisseau : corps rigide piloté par des consignes, partagé entre le client du pilote (autorité sur le
 * mouvement, comme les bateaux vanilla) et le serveur (vaisseaux sans pilote et pirates).
 *
 * <p>Repère local : X à gauche, Y en haut, Z vers l'avant (celui d'une entité de lacet nul). L'origine est le pivot,
 * centre de masse de la structure.
 *
 * <p><b>Assistance de vol</b> (par défaut) : les commandes donnent une vitesse de consigne, suivie avec une constante
 * de temps de 0,8 s dans la limite des accélérations du modèle, gravité compensée : manche au neutre, le vaisseau
 * s'arrête et tient le stationnaire. <b>Découplé</b> : les commandes donnent directement une poussée ; le vaisseau
 * garde son inertie (dérive spatiale) et la gravité n'est plus compensée.
 *
 * <p>L'orientation suit la direction visée par le pilote (souris), avec une vitesse et une accélération angulaires
 * limitées. En atmosphère, le roulis revient à l'horizontale quand on ne le commande pas ; dans l'espace, il reste libre.
 *
 * <p><b>Collisions</b> : quelques centaines de points sur la peau de la coque sont testés contre les blocs du monde.
 * Le déplacement est résolu axe par axe (Y, X, Z, comme les entités vanilla) avec une recherche dichotomique du
 * dernier point libre ; une rotation qui ferait pénétrer le relief est annulée.
 */
public final class ShipFlight {
    public static final double DT = 0.05;
    private static final double ASSIST_TIME_CONSTANT = 0.8;
    private static final int MAX_SAMPLES = 640;
    private static final Vector3dc WORLD_UP = new Vector3d(0.0, 1.0, 0.0);

    /** @param strafe + vers la droite ; @param roll + roulis à droite ; @param aim direction visée (monde), null : cap tenu */
    public record Input(float strafe, float vertical, float thrust, float roll, boolean boost, @Nullable Vector3dc aim) {
        public static final Input NEUTRAL = new Input(0, 0, 0, 0, false, null);
    }

    /**
     * @param gravity         accélération de la pesanteur (m/s², vers −Y)
     * @param airDensity      densité relative de l'air (1 au niveau de la mer terrestre)
     * @param atmosphere      vrai sur une planète : roulis auto-stabilisé
     * @param thrustAvailable faux sans carburant : plus aucune poussée ni gouverne
     * @param speedFactor     multiplicateur des vitesses (répartition d'énergie, atmosphère)
     * @param accelFactor     multiplicateur des accélérations
     */
    public record Environment(double gravity, double airDensity, boolean atmosphere, boolean thrustAvailable,
                              double speedFactor, double accelFactor, double floorY, double ceilingY) {
        public Environment(double gravity, double airDensity, boolean atmosphere, boolean thrustAvailable,
                           double speedFactor, double accelFactor) {
            this(gravity, airDensity, atmosphere, thrustAvailable, speedFactor, accelFactor, Double.NaN, Double.NaN);
        }
    }

    public interface Collider {
        /** Vrai si le bloc contient une forme de collision. */
        boolean solid(int x, int y, int z);
    }

    public final Vector3d position = new Vector3d();
    public final Quaterniond orientation = new Quaterniond();
    public final Vector3d velocity = new Vector3d();
    public final Vector3d angularVelocity = new Vector3d();
    public boolean flightAssist = true;

    private final List<Vector3d> samples;
    private final Vector3d[] rotatedSamples;
    private final Long2BooleanOpenHashMap solidCache = new Long2BooleanOpenHashMap();
    private double lastThrustFraction;
    private double lastImpactSpeed;
    private boolean onGround;
    private int stuckTicks;

    public ShipFlight(ShipStructure structure, Vector3dc pivot) {
        this.samples = buildSamples(structure, pivot);
        this.rotatedSamples = new Vector3d[samples.size()];
        for (int i = 0; i < rotatedSamples.length; i++) {
            rotatedSamples[i] = new Vector3d();
        }
    }

    public double lastThrustFraction() {
        return lastThrustFraction;
    }

    /** Vitesse perdue au dernier choc (m/s), remise à zéro au tick suivant. */
    public double lastImpactSpeed() {
        return lastImpactSpeed;
    }

    public boolean onGround() {
        return onGround;
    }

    /** Nombre de ticks passés coincé dans le relief (diagnostic). */
    public int stuckTicks() {
        return stuckTicks;
    }

    /** Premier échantillon de coque en collision à la pose courante, ou null (diagnostic). */
    public @Nullable String firstCollision(Collider collider) {
        rotateSamples(orientation);
        for (Vector3d p : rotatedSamples) {
            int x = Mth.floor(position.x + p.x);
            int y = Mth.floor(position.y + p.y);
            int z = Mth.floor(position.z + p.z);
            if (collider.solid(x, y, z)) {
                return x + " " + y + " " + z + " (échantillon " + p + ")";
            }
        }
        return null;
    }

    // ───────────────────────────── Échantillons de coque ─────────────────────────────

    private static List<Vector3d> buildSamples(ShipStructure s, Vector3dc pivot) {
        List<Vector3d> bottom = new ArrayList<>();
        List<Vector3d> sides = new ArrayList<>();
        int sx = s.sizeX();
        int sy = s.sizeY();
        int sz = s.sizeZ();
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                for (int y = 0; y < sy; y++) {
                    if (s.isSolidAt(x, y, z)) {
                        bottom.add(new Vector3d(x + 0.5, y + 0.02, z + 0.5));
                        break;
                    }
                }
                for (int y = sy - 1; y >= 0; y--) {
                    if (s.isSolidAt(x, y, z)) {
                        sides.add(new Vector3d(x + 0.5, y + 0.98, z + 0.5));
                        break;
                    }
                }
            }
        }
        for (int y = 0; y < sy; y++) {
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (s.isSolidAt(x, y, z)) {
                        sides.add(new Vector3d(x + 0.5, y + 0.5, z + 0.02));
                        break;
                    }
                }
                for (int z = sz - 1; z >= 0; z--) {
                    if (s.isSolidAt(x, y, z)) {
                        sides.add(new Vector3d(x + 0.5, y + 0.5, z + 0.98));
                        break;
                    }
                }
            }
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    if (s.isSolidAt(x, y, z)) {
                        sides.add(new Vector3d(x + 0.02, y + 0.5, z + 0.5));
                        break;
                    }
                }
                for (int x = sx - 1; x >= 0; x--) {
                    if (s.isSolidAt(x, y, z)) {
                        sides.add(new Vector3d(x + 0.98, y + 0.5, z + 0.5));
                        break;
                    }
                }
            }
        }
        // Le dessous porte le vaisseau posé : il garde la moitié du budget.
        List<Vector3d> result = new ArrayList<>(MAX_SAMPLES);
        subsample(bottom, MAX_SAMPLES / 2, result);
        subsample(sides, MAX_SAMPLES - Math.min(bottom.size(), MAX_SAMPLES / 2), result);
        for (Vector3d p : result) {
            p.sub(pivot);
        }
        return result;
    }

    private static void subsample(List<Vector3d> source, int budget, List<Vector3d> out) {
        if (source.size() <= budget) {
            out.addAll(source);
            return;
        }
        double step = source.size() / (double) budget;
        for (int i = 0; i < budget; i++) {
            out.add(source.get((int) (i * step)));
        }
    }

    // ───────────────────────────── Pas de simulation ─────────────────────────────

    public void step(ShipDesign.Stats stats, Input input, Environment env, Collider collider) {
        solidCache.clear();
        lastImpactSpeed = 0.0;
        double dt = DT;

        // ── Rotation ──
        Vector3d forward = orientation.transform(new Vector3d(0.0, 0.0, 1.0));
        Vector3d up = orientation.transform(new Vector3d(0.0, 1.0, 0.0));
        Vector3d desired = new Vector3d();
        if (env.thrustAvailable()) {
            Vector3dc aim = input.aim();
            if (aim != null) {
                Vector3d axis = new Vector3d(forward).cross(aim);
                double sin = axis.length();
                double cos = forward.dot(aim);
                double angle = Math.atan2(sin, cos);
                if (sin > 1.0e-6) {
                    axis.div(sin);
                } else if (cos < 0.0) {
                    axis.set(up);
                } else {
                    axis.zero();
                }
                desired.fma(Math.min(angle * 2.5, stats.turnRate() * env.accelFactor()), axis);
            }
            if (env.atmosphere() && Math.abs(input.roll()) < 0.05f) {
                Vector3d levelUp = new Vector3d(WORLD_UP).fma(-forward.y, forward);
                if (levelUp.lengthSquared() > 0.04) {
                    levelUp.normalize();
                    double rollError = Math.atan2(new Vector3d(up).cross(levelUp).dot(forward), up.dot(levelUp));
                    desired.fma(Math.clamp(rollError * 2.0, -stats.rollRate(), stats.rollRate()), forward);
                }
            } else {
                desired.fma(input.roll() * stats.rollRate(), forward);
            }
            Vector3d delta = desired.sub(angularVelocity, new Vector3d());
            double maxDelta = stats.angularAccel() * env.accelFactor() * dt;
            if (delta.length() > maxDelta) {
                delta.normalize(maxDelta);
            }
            angularVelocity.add(delta);
        } else {
            angularVelocity.mul(env.atmosphere() ? 0.97 : 0.999);
        }

        Quaterniond nextOrientation = new Quaterniond(orientation);
        double omega = angularVelocity.length();
        if (omega > 1.0e-7) {
            Quaterniond spin = new Quaterniond().fromAxisAngleRad(angularVelocity.x / omega, angularVelocity.y / omega,
                    angularVelocity.z / omega, omega * dt);
            spin.mul(nextOrientation, nextOrientation).normalize();
        }

        // ── Translation ──
        Vector3d accelLocal;
        double capX = stats.accelStrafe() * env.accelFactor();
        double capY = stats.accelVertical() * env.accelFactor();
        double capZ = stats.accelForward() * env.accelFactor() * (input.boost() ? 1.6 : 1.0);
        double capBack = stats.accelBack() * env.accelFactor();
        if (!env.thrustAvailable()) {
            accelLocal = new Vector3d();
        } else if (flightAssist) {
            double speed = (input.boost() ? stats.boostSpeed() : stats.maxSpeed()) * env.speedFactor();
            Vector3d target = new Vector3d(-input.strafe() * speed * 0.6, input.vertical() * speed * 0.6, input.thrust() * speed);
            Vector3d localVelocity = orientation.transformInverse(new Vector3d(velocity));
            Vector3d localGravity = orientation.transformInverse(new Vector3d(0.0, -env.gravity(), 0.0));
            accelLocal = target.sub(localVelocity).div(ASSIST_TIME_CONSTANT).sub(localGravity);
        } else {
            accelLocal = new Vector3d(-input.strafe() * capX, input.vertical() * capY,
                    input.thrust() >= 0 ? input.thrust() * capZ : input.thrust() * capBack);
        }
        accelLocal.x = Math.clamp(accelLocal.x, -capX, capX);
        accelLocal.y = Math.clamp(accelLocal.y, -capY, capY);
        accelLocal.z = Math.clamp(accelLocal.z, -capBack, capZ);
        lastThrustFraction = Math.min(1.0, (Math.abs(accelLocal.x) / Math.max(capX, 1e-3)
                + Math.abs(accelLocal.y) / Math.max(capY, 1e-3)
                + Math.abs(accelLocal.z) / Math.max(accelLocal.z >= 0 ? capZ : capBack, 1e-3)) / 2.0);

        Vector3d accel = orientation.transform(accelLocal).add(0.0, -env.gravity(), 0.0);
        velocity.fma(dt, accel);
        // Limites verticales souples (espace) : un rappel progressif, jamais un mur, donc jamais de choc.
        if (!Double.isNaN(env.ceilingY()) && position.y > env.ceilingY() - 150.0) {
            double over = position.y - (env.ceilingY() - 150.0);
            velocity.y -= over * 0.6 * dt;
            if (velocity.y > 0.0) {
                velocity.y *= 0.9;
            }
        }
        if (!Double.isNaN(env.floorY()) && position.y < env.floorY() + 150.0) {
            double under = env.floorY() + 150.0 - position.y;
            velocity.y += under * 0.6 * dt;
            if (velocity.y < 0.0) {
                velocity.y *= 0.9;
            }
        }
        if (env.airDensity() > 0.0) {
            velocity.mul(1.0 - Math.min(0.5, 0.12 * env.airDensity() * dt));
        }

        // ── Collisions ──
        if (collides(position, orientation, collider)) {
            // Déjà dans le relief (bloc posé, apparition) : on remonte doucement.
            stuckTicks++;
            position.y += 0.5;
            velocity.y = Math.max(velocity.y, 0.0);
            onGround = true;
            return;
        }
        if (!nextOrientation.equals(orientation, 1.0e-9) && collides(position, nextOrientation, collider)) {
            nextOrientation.set(orientation);
            angularVelocity.mul(-0.2);
        }
        orientation.set(nextOrientation);
        rotateSamples(orientation);

        onGround = false;
        Vector3d move = new Vector3d(velocity).mul(dt);
        for (int axis : new int[]{1, 0, 2}) {
            double wanted = move.get(axis);
            double allowed = sweepAxis(axis, wanted, collider);
            if (Math.abs(allowed - wanted) > 1.0e-6) {
                double lost = Math.abs(velocity.get(axis));
                lastImpactSpeed = Math.max(lastImpactSpeed, lost);
                if (axis == 1 && wanted < 0.0) {
                    onGround = true;
                }
                velocity.setComponent(axis, -velocity.get(axis) * 0.1);
                // Frottement au sol : le vaisseau posé ne glisse pas.
                if (axis == 1 && wanted < 0.0) {
                    velocity.x *= 0.6;
                    velocity.z *= 0.6;
                    angularVelocity.mul(0.6);
                }
            }
            position.setComponent(axis, position.get(axis) + allowed);
        }
    }

    private double sweepAxis(int axis, double delta, Collider collider) {
        if (Math.abs(delta) < 1.0e-9) {
            return 0.0;
        }
        Vector3d trial = new Vector3d(position);
        trial.setComponent(axis, position.get(axis) + delta);
        if (!collidesRotated(trial, collider)) {
            return delta;
        }
        double free = 0.0;
        double blocked = delta;
        for (int i = 0; i < 6; i++) {
            double mid = (free + blocked) * 0.5;
            trial.setComponent(axis, position.get(axis) + mid);
            if (collidesRotated(trial, collider)) {
                blocked = mid;
            } else {
                free = mid;
            }
        }
        return free;
    }

    private void rotateSamples(Quaterniond q) {
        for (int i = 0; i < rotatedSamples.length; i++) {
            q.transform(samples.get(i), rotatedSamples[i]);
        }
    }

    private boolean collides(Vector3dc at, Quaterniond q, Collider collider) {
        rotateSamples(q);
        return collidesRotated(at, collider);
    }

    private boolean collidesRotated(Vector3dc at, Collider collider) {
        for (Vector3d p : rotatedSamples) {
            int x = Mth.floor(at.x() + p.x);
            int y = Mth.floor(at.y() + p.y);
            int z = Mth.floor(at.z() + p.z);
            long key = BlockPos.asLong(x, y, z);
            boolean solid;
            if (solidCache.containsKey(key)) {
                solid = solidCache.get(key);
            } else {
                solid = collider.solid(x, y, z);
                solidCache.put(key, solid);
            }
            if (solid) {
                return true;
            }
        }
        return false;
    }

    /** Vrai si la coque, à cette pose, touche le relief (sert au contrôle de l'atterrissage et de l'apparition). */
    public boolean intersects(Vector3dc at, Quaterniond q, Collider collider) {
        solidCache.clear();
        return collides(at, q, collider);
    }
}
