package odyssea.physics;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import odyssea.dimension.PlanetaryAtmosphere;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Corps rigide d'un vaisseau assemblé bloc par bloc, indépendant du backend (sub-level maison, Sable…).
 *
 * <p>Repères : les positions de blocs, propulseurs et panneaux sont exprimées dans le repère <em>local</em> du
 * vaisseau (origine = ancre d'assemblage, coordonnées petites pour préserver la précision des moments).
 * {@link #position()} est la position monde de cette origine ; la vitesse linéaire est celle du centre de masse.
 * Une unité de longueur vaut un bloc, soit 1 m.
 *
 * <p>Synchronisation réseau : l'état fait autorité côté serveur ; {@link #snapshot()} est envoyé chaque tick
 * aux joueurs qui suivent le vaisseau, et le client interpole (Hermite sur position/vitesse, slerp sur l'orientation).
 */
public final class OdysseaShipPhysics {
    public static final double STANDARD_GRAVITY = 9.80665;

    private static final int SUBSTEPS = 4;
    private static final int ALLOCATION_SWEEPS = 12;
    private static final double ALLOCATION_REGULARIZATION = 1.0e-3;
    private static final double DRAG_COEFFICIENT = 1.05;
    private static final double ASSIST_LINEAR_TIME_CONSTANT = 0.8;
    private static final double ASSIST_ANGULAR_TIME_CONSTANT = 0.35;
    private static final double MIN_MASS_KG = 1.0;
    private static final double MASSLESS_BLOCK_FILL = 0.1;
    private static final Map<BlockState, Double> MASS_CACHE = new ConcurrentHashMap<>();
    private static final Map<Block, Double> MASS_OVERRIDES = new ConcurrentHashMap<>();

    public enum Propellant {
        CHEMICAL,
        PLASMA,
        XENON
    }

    /** Isp en secondes ; rendement électrique = puissance du jet / puissance électrique (0 : pas d'électricité). */
    public enum PropulsionType {
        CHEMICAL(Propellant.CHEMICAL, 330.0, 0.0),
        MONOPROP_RCS(Propellant.CHEMICAL, 220.0, 0.0),
        PLASMA(Propellant.PLASMA, 3_000.0, 0.65),
        ION(Propellant.XENON, 4_200.0, 0.70);

        public final Propellant propellant;
        public final double specificImpulseS;
        public final double electricalEfficiency;

        PropulsionType(Propellant propellant, double specificImpulseS, double electricalEfficiency) {
            this.propellant = propellant;
            this.specificImpulseS = specificImpulseS;
            this.electricalEfficiency = electricalEfficiency;
        }

        public double exhaustVelocity() {
            return specificImpulseS * STANDARD_GRAVITY;
        }

        public boolean isElectric() {
            return electricalEfficiency > 0.0;
        }
    }

    /** Ressources du vaisseau, implémentées par l'adaptateur du réseau de tuyaux embarqué. Valeurs retournées = quantités réellement obtenues. */
    public interface ResourceProvider {
        double drawPropellant(Propellant propellant, double kg);

        double drawEnergy(double joules);

        double depositEnergy(double joules);

        double storedPropellantMassKg();
    }

    public record Thruster(long key, Vector3dc position, Vector3dc direction, double maxThrustN, PropulsionType type) {
        public Thruster {
            if (!(maxThrustN > 0.0)) {
                throw new IllegalArgumentException("Thrust must be positive");
            }
            position = new Vector3d(position);
            direction = new Vector3d(direction).normalize();
        }
    }

    public record SolarPanel(long key, Vector3dc normal, double areaM2, double efficiency) {
        public SolarPanel {
            normal = new Vector3d(normal).normalize();
            areaM2 = Math.max(0.0, areaM2);
            efficiency = Math.clamp(efficiency, 0.0, 1.0);
        }
    }

    /** Commandes normalisées dans [−1, 1] (repère local) ; pitch/yaw/roll autour des axes X/Y/Z locaux. */
    public record ControlInput(double x, double y, double z, double pitch, double yaw, double roll, double throttleLimit) {
        public static final ControlInput NEUTRAL = new ControlInput(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0);

        public ControlInput {
            x = Math.clamp(x, -1.0, 1.0);
            y = Math.clamp(y, -1.0, 1.0);
            z = Math.clamp(z, -1.0, 1.0);
            pitch = Math.clamp(pitch, -1.0, 1.0);
            yaw = Math.clamp(yaw, -1.0, 1.0);
            roll = Math.clamp(roll, -1.0, 1.0);
            throttleLimit = Math.clamp(throttleLimit, 0.0, 1.0);
        }
    }

    public record Environment(Vector3dc gravity, double airDensity, Vector3dc starDirection, double irradianceWm2) {
        public Environment {
            gravity = new Vector3d(gravity);
            starDirection = new Vector3d(starDirection).normalize();
            airDensity = Math.max(0.0, airDensity);
            irradianceWm2 = Math.max(0.0, irradianceWm2);
        }

        public static Environment deepSpace(Vector3dc starDirection, double irradianceWm2) {
            return new Environment(new Vector3d(), 0.0, starDirection, irradianceWm2);
        }

        public static Environment planetary(PlanetaryAtmosphere atmosphere, double worldY, Vector3dc starDirection, double irradianceWm2) {
            return new Environment(new Vector3d(0.0, -atmosphere.gravity(), 0.0), atmosphere.densityAt(worldY), starDirection, irradianceWm2);
        }
    }

    public record ImpactResult(double dissipatedEnergyJ, double impulseNs, Vector3dc worldPoint, Vector3dc localPoint, Vector3dc normal) {
        public static final ImpactResult NONE = new ImpactResult(0.0, 0.0, new Vector3d(), new Vector3d(), new Vector3d());

        public boolean isImpact() {
            return dissipatedEnergyJ > 0.0;
        }
    }

    public record Snapshot(long tick, Vector3dc position, Quaterniondc orientation, Vector3dc velocity, Vector3dc angularVelocity) {
    }

    // Moments de masse cumulés incrémentalement dans le repère local (blocs centrés en p + 0,5).
    private double dryMass;
    private double sumMx;
    private double sumMy;
    private double sumMz;
    private double sumMxx;
    private double sumMyy;
    private double sumMzz;
    private double sumMxy;
    private double sumMxz;
    private double sumMyz;
    private int blockCount;
    private final Int2IntOpenHashMap[] axisHistograms = {new Int2IntOpenHashMap(), new Int2IntOpenHashMap(), new Int2IntOpenHashMap()};
    private final Vector3d boundsMin = new Vector3d();
    private final Vector3d boundsMax = new Vector3d();
    private boolean massDirty = true;
    private boolean boundsDirty = true;

    private final Vector3d centerOfMass = new Vector3d();
    private final Matrix3d dryInertia = new Matrix3d();
    private final Matrix3d dryInverseInertia = new Matrix3d();
    private final Matrix3d inertia = new Matrix3d();
    private final Matrix3d inverseInertia = new Matrix3d();
    private double totalMass = MIN_MASS_KG;
    private double characteristicLength = 1.0;

    private final Long2ObjectLinkedOpenHashMap<Thruster> thrusters = new Long2ObjectLinkedOpenHashMap<>();
    private final Long2ObjectLinkedOpenHashMap<SolarPanel> solarPanels = new Long2ObjectLinkedOpenHashMap<>();
    private Thruster[] thrusterArray = new Thruster[0];
    private double[] throttles = new double[0];
    private double[][] columns = new double[0][];
    private double[] columnNormsSq = new double[0];
    private final double[] forceCapacity = new double[6];
    private final double[] torqueCapacity = new double[6];
    private boolean allocationDirty = true;

    private final Vector3d position = new Vector3d();
    private final Quaterniond orientation = new Quaterniond();
    private final Vector3d velocity = new Vector3d();
    private final Vector3d angularVelocity = new Vector3d();
    private boolean flightAssist = true;
    private double maxAssistSpeed = 150.0;
    private double maxAssistTurnRate = 0.8;
    private long tick;

    private final Vector3d lastLocalForce = new Vector3d();
    private final Vector3d lastLocalTorque = new Vector3d();
    private final double[] lastPropellantFlow = new double[Propellant.values().length];
    private double lastPowerDrawW;
    private double lastSolarPowerW;

    // ───────────────────────────── Masse ─────────────────────────────

    public static double blockMassKg(BlockState state) {
        if (state.isAir()) {
            return 0.0;
        }
        return MASS_CACHE.computeIfAbsent(state, OdysseaShipPhysics::estimateMass);
    }

    public static void overrideBlockMass(Block block, double kgPerFullBlock) {
        MASS_OVERRIDES.put(block, Math.max(0.0, kgPerFullBlock));
        MASS_CACHE.clear();
    }

    private static double estimateMass(BlockState state) {
        Double override = MASS_OVERRIDES.get(state.getBlock());
        double density = override != null ? override : densityFor(state);
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        double fill = 0.0;
        for (AABB box : shape.toAabbs()) {
            fill += box.getXsize() * box.getYsize() * box.getZsize();
        }
        return density * (fill > 0.0 ? Math.min(fill, 1.0) : MASSLESS_BLOCK_FILL);
    }

    /** Masse volumique (kg/m³) d'un bloc plein, estimée depuis sa famille de matériau puis sa dureté. */
    private static double densityFor(BlockState state) {
        SoundType sound = state.getSoundType();
        if (sound == SoundType.METAL || sound == SoundType.ANVIL || sound == SoundType.NETHERITE_BLOCK
                || sound == SoundType.COPPER || sound == SoundType.CHAIN) {
            return 7_850.0;
        }
        if (sound == SoundType.GLASS) {
            return 2_500.0;
        }
        if (sound == SoundType.WOOD || sound == SoundType.NETHER_WOOD || sound == SoundType.BAMBOO_WOOD || sound == SoundType.CHERRY_WOOD) {
            return 650.0;
        }
        if (sound == SoundType.WOOL) {
            return 120.0;
        }
        if (sound == SoundType.SAND || sound == SoundType.GRAVEL) {
            return 1_600.0;
        }
        float hardness = state.getBlock().defaultDestroyTime();
        return hardness < 0.0f ? 8_000.0 : Math.clamp(1_200.0 + hardness * 450.0, 300.0, 5_000.0);
    }

    public void addBlock(BlockPos localPos, BlockState state) {
        accumulate(localPos, blockMassKg(state), 1);
    }

    public void removeBlock(BlockPos localPos, BlockState state) {
        accumulate(localPos, blockMassKg(state), -1);
    }

    private void accumulate(BlockPos pos, double mass, int sign) {
        if (mass <= 0.0) {
            return;
        }
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;
        double m = sign * mass;
        dryMass += m;
        sumMx += m * x;
        sumMy += m * y;
        sumMz += m * z;
        sumMxx += m * x * x;
        sumMyy += m * y * y;
        sumMzz += m * z * z;
        sumMxy += m * x * y;
        sumMxz += m * x * z;
        sumMyz += m * y * z;
        blockCount += sign;
        histogram(axisHistograms[0], pos.getX(), sign);
        histogram(axisHistograms[1], pos.getY(), sign);
        histogram(axisHistograms[2], pos.getZ(), sign);
        massDirty = true;
        boundsDirty = true;
        allocationDirty = true;
    }

    private static void histogram(Int2IntOpenHashMap map, int coordinate, int sign) {
        int count = map.addTo(coordinate, sign) + sign;
        if (count <= 0) {
            map.remove(coordinate);
        }
    }

    private void updateDryMassProperties() {
        if (!massDirty) {
            return;
        }
        massDirty = false;
        Vector3d previousCom = new Vector3d(centerOfMass);
        if (dryMass < 1.0e-9 || blockCount <= 0) {
            centerOfMass.zero();
            dryInertia.identity().scale(MIN_MASS_KG);
            dryInverseInertia.identity().scale(1.0 / MIN_MASS_KG);
            characteristicLength = 1.0;
            return;
        }
        double m = dryMass;
        centerOfMass.set(sumMx / m, sumMy / m, sumMz / m);
        // Tenseur à l'origine locale : Σm(r²E − r rᵀ), plus m/6 par axe pour l'inertie propre d'un cube unité.
        double ixx = sumMyy + sumMzz + m / 6.0;
        double iyy = sumMxx + sumMzz + m / 6.0;
        double izz = sumMxx + sumMyy + m / 6.0;
        double ixy = -sumMxy;
        double ixz = -sumMxz;
        double iyz = -sumMyz;
        // Huygens-Steiner : I_G = I_O − M(|c|²E − c cᵀ)
        double cx = centerOfMass.x;
        double cy = centerOfMass.y;
        double cz = centerOfMass.z;
        ixx -= m * (cy * cy + cz * cz);
        iyy -= m * (cx * cx + cz * cz);
        izz -= m * (cx * cx + cy * cy);
        ixy += m * cx * cy;
        ixz += m * cx * cz;
        iyz += m * cy * cz;
        dryInertia.set(ixx, ixy, ixz, ixy, iyy, iyz, ixz, iyz, izz);
        double determinant = dryInertia.determinant();
        if (!(determinant > 1.0e-12) || !Double.isFinite(determinant)) {
            dryInertia.set(Math.max(ixx, m / 6.0), 0.0, 0.0, 0.0, Math.max(iyy, m / 6.0), 0.0, 0.0, 0.0, Math.max(izz, m / 6.0));
        }
        dryInertia.invert(dryInverseInertia);
        characteristicLength = Math.max(0.5, Math.sqrt((dryInertia.m00 + dryInertia.m11 + dryInertia.m22) / (2.0 * m)));

        // Un déplacement du centre de masse à vitesse angulaire non nulle modifie sa vitesse : v_G' = v_G + ω × Δc.
        Vector3d shiftWorld = orientation.transform(new Vector3d(centerOfMass).sub(previousCom));
        velocity.add(new Vector3d(angularVelocity).cross(shiftWorld));
    }

    /** Le propergol est supposé réparti comme la structure : l'inertie est mise à l'échelle par M_totale / M_sèche. */
    private void updateTotalMass(ResourceProvider resources) {
        updateDryMassProperties();
        double dry = Math.max(dryMass, MIN_MASS_KG);
        double propellant = Math.max(0.0, resources.storedPropellantMassKg());
        totalMass = dry + propellant;
        double scale = totalMass / dry;
        inertia.set(dryInertia).scale(scale);
        inverseInertia.set(dryInverseInertia).scale(1.0 / scale);
    }

    private void updateBounds() {
        if (!boundsDirty) {
            return;
        }
        boundsDirty = false;
        double[] min = new double[3];
        double[] max = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            for (Int2IntMap.Entry entry : axisHistograms[axis].int2IntEntrySet()) {
                lo = Math.min(lo, entry.getIntKey());
                hi = Math.max(hi, entry.getIntKey());
            }
            min[axis] = lo == Integer.MAX_VALUE ? 0.0 : lo;
            max[axis] = hi == Integer.MIN_VALUE ? 1.0 : hi + 1.0;
        }
        boundsMin.set(min[0], min[1], min[2]);
        boundsMax.set(max[0], max[1], max[2]);
    }

    // ───────────────────────────── Propulseurs et panneaux ─────────────────────────────

    /** @param exhaustDirection face d'éjection des gaz ; la poussée s'exerce dans la direction opposée. */
    public void addThruster(BlockPos localPos, Direction exhaustDirection, double maxThrustN, PropulsionType type) {
        Vector3d direction = new Vector3d(-exhaustDirection.getStepX(), -exhaustDirection.getStepY(), -exhaustDirection.getStepZ());
        Vector3d center = new Vector3d(localPos.getX() + 0.5, localPos.getY() + 0.5, localPos.getZ() + 0.5);
        thrusters.put(localPos.asLong(), new Thruster(localPos.asLong(), center, direction, maxThrustN, type));
        allocationDirty = true;
    }

    public void removeThruster(BlockPos localPos) {
        if (thrusters.remove(localPos.asLong()) != null) {
            allocationDirty = true;
        }
    }

    public void addSolarPanel(BlockPos localPos, Direction facing, double areaM2, double efficiency) {
        Vector3d normal = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        solarPanels.put(localPos.asLong(), new SolarPanel(localPos.asLong(), normal, areaM2, efficiency));
    }

    public void removeSolarPanel(BlockPos localPos) {
        solarPanels.remove(localPos.asLong());
    }

    /**
     * Matrice d'allocation A (6×N) stockée par colonnes : a_i = [d_i·F_i ; (r_i × d_i)·F_i / L].
     * Le couple est normalisé par la longueur caractéristique L pour rendre forces et couples commensurables.
     */
    private void rebuildAllocation() {
        if (!allocationDirty) {
            return;
        }
        allocationDirty = false;
        updateDryMassProperties();
        Long2DoubleOpenHashMap previous = new Long2DoubleOpenHashMap();
        for (int i = 0; i < thrusterArray.length; i++) {
            previous.put(thrusterArray[i].key(), throttles[i]);
        }
        thrusterArray = thrusters.values().toArray(new Thruster[0]);
        int count = thrusterArray.length;
        throttles = new double[count];
        columns = new double[count][6];
        columnNormsSq = new double[count];
        Arrays.fill(forceCapacity, 0.0);
        Arrays.fill(torqueCapacity, 0.0);
        double inverseLength = 1.0 / characteristicLength;
        Vector3d torque = new Vector3d();
        for (int i = 0; i < count; i++) {
            Thruster thruster = thrusterArray[i];
            throttles[i] = previous.get(thruster.key());
            Vector3dc d = thruster.direction();
            double f = thruster.maxThrustN();
            new Vector3d(thruster.position()).sub(centerOfMass).cross(d, torque);
            double[] column = columns[i];
            column[0] = d.x() * f;
            column[1] = d.y() * f;
            column[2] = d.z() * f;
            column[3] = torque.x * f * inverseLength;
            column[4] = torque.y * f * inverseLength;
            column[5] = torque.z * f * inverseLength;
            double normSq = 0.0;
            for (double value : column) {
                normSq += value * value;
            }
            columnNormsSq[i] = normSq;
            accumulateCapacity(forceCapacity, d.x() * f, d.y() * f, d.z() * f);
            accumulateCapacity(torqueCapacity, torque.x * f, torque.y * f, torque.z * f);
        }
    }

    private static void accumulateCapacity(double[] capacity, double x, double y, double z) {
        capacity[0] += Math.max(0.0, x);
        capacity[1] += Math.max(0.0, -x);
        capacity[2] += Math.max(0.0, y);
        capacity[3] += Math.max(0.0, -y);
        capacity[4] += Math.max(0.0, z);
        capacity[5] += Math.max(0.0, -z);
    }

    /**
     * Moindres carrés bornés min ½‖A·t − w‖² + ½λ‖t‖², 0 ≤ t_i ≤ t_max, par descente par coordonnées projetée
     * (Gauss-Seidel borné) avec démarrage à chaud. Mise à jour exacte par coordonnée :
     * t_i ← clamp(t_i + (a_i·r − λ t_i) / (‖a_i‖² + λ)), où r = w − A·t est maintenu incrémentalement.
     * La régularisation λ départage les propulseurs redondants en faveur de la sobriété.
     */
    private void allocate(double[] wrench, double throttleLimit) {
        int count = thrusterArray.length;
        if (count == 0) {
            return;
        }
        double[] residual = wrench.clone();
        double meanNorm = 0.0;
        for (int i = 0; i < count; i++) {
            throttles[i] = Math.clamp(throttles[i], 0.0, throttleLimit);
            double[] column = columns[i];
            for (int k = 0; k < 6; k++) {
                residual[k] -= column[k] * throttles[i];
            }
            meanNorm += columnNormsSq[i];
        }
        double lambda = ALLOCATION_REGULARIZATION * meanNorm / count;
        for (int sweep = 0; sweep < ALLOCATION_SWEEPS; sweep++) {
            for (int i = 0; i < count; i++) {
                double[] column = columns[i];
                double gradient = -lambda * throttles[i];
                for (int k = 0; k < 6; k++) {
                    gradient += column[k] * residual[k];
                }
                double next = Math.clamp(throttles[i] + gradient / (columnNormsSq[i] + lambda), 0.0, throttleLimit);
                double delta = next - throttles[i];
                if (delta != 0.0) {
                    for (int k = 0; k < 6; k++) {
                        residual[k] -= column[k] * delta;
                    }
                    throttles[i] = next;
                }
            }
        }
    }

    // ───────────────────────────── Tick ─────────────────────────────

    public void tick(double dt, ControlInput input, Environment environment, ResourceProvider resources) {
        tick++;
        updateTotalMass(resources);
        rebuildAllocation();
        updateBounds();

        lastSolarPowerW = solarPowerW(environment);
        if (lastSolarPowerW > 0.0) {
            resources.depositEnergy(lastSolarPowerW * dt);
        }

        double[] wrench = new double[6];
        desiredWrench(input, environment, wrench);
        allocate(wrench, input.throttleLimit());
        double[] typeRatio = resolveConsumption(dt, resources);
        computeThrust(typeRatio);
        integrate(dt, environment);
    }

    /**
     * Flight Assist : la commande devient une consigne de vitesse (linéaire et angulaire), suivie par un
     * régulateur proportionnel de constante de temps τ. Le « frottement virtuel » qui freine le vaisseau
     * manche au neutre est donc produit physiquement par les RCS et consomme du propergol. La gravité locale
     * est compensée pour tenir un vol stationnaire.
     */
    private void desiredWrench(ControlInput input, Environment environment, double[] out) {
        double fx;
        double fy;
        double fz;
        Vector3d torque = new Vector3d();
        if (flightAssist) {
            Vector3d localVelocity = orientation.transformInverse(new Vector3d(velocity));
            Vector3d localGravity = orientation.transformInverse(new Vector3d(environment.gravity()));
            Vector3d localOmega = orientation.transformInverse(new Vector3d(angularVelocity));
            double speed = maxAssistSpeed * input.throttleLimit();
            double tau = ASSIST_LINEAR_TIME_CONSTANT;
            fx = totalMass * ((input.x() * speed - localVelocity.x) / tau - localGravity.x);
            fy = totalMass * ((input.y() * speed - localVelocity.y) / tau - localGravity.y);
            fz = totalMass * ((input.z() * speed - localVelocity.z) / tau - localGravity.z);
            double rate = maxAssistTurnRate;
            Vector3d omegaError = new Vector3d(
                    input.pitch() * rate - localOmega.x,
                    input.yaw() * rate - localOmega.y,
                    input.roll() * rate - localOmega.z).div(ASSIST_ANGULAR_TIME_CONSTANT);
            // τ = I·(ω_c − ω)/τ_a + ω × (I·ω) : la compensation gyroscopique évite la dérive en rotation combinée.
            inertia.transform(omegaError, torque);
            Vector3d angularMomentum = inertia.transform(new Vector3d(localOmega));
            torque.add(localOmega.cross(angularMomentum));
        } else {
            double limit = input.throttleLimit();
            fx = axisCommand(input.x(), forceCapacity, 0) * limit;
            fy = axisCommand(input.y(), forceCapacity, 2) * limit;
            fz = axisCommand(input.z(), forceCapacity, 4) * limit;
            torque.set(axisCommand(input.pitch(), torqueCapacity, 0) * limit,
                    axisCommand(input.yaw(), torqueCapacity, 2) * limit,
                    axisCommand(input.roll(), torqueCapacity, 4) * limit);
        }
        double inverseLength = 1.0 / characteristicLength;
        out[0] = clampToCapacity(fx, forceCapacity, 0);
        out[1] = clampToCapacity(fy, forceCapacity, 2);
        out[2] = clampToCapacity(fz, forceCapacity, 4);
        out[3] = clampToCapacity(torque.x, torqueCapacity, 0) * inverseLength;
        out[4] = clampToCapacity(torque.y, torqueCapacity, 2) * inverseLength;
        out[5] = clampToCapacity(torque.z, torqueCapacity, 4) * inverseLength;
    }

    private static double axisCommand(double command, double[] capacity, int positiveIndex) {
        return command >= 0.0 ? command * capacity[positiveIndex] : command * capacity[positiveIndex + 1];
    }

    private static double clampToCapacity(double value, double[] capacity, int positiveIndex) {
        return Math.clamp(value, -capacity[positiveIndex + 1], capacity[positiveIndex]);
    }

    /**
     * Consommation hybride. Débit de propergol ṁ = F / v_e (v_e = Isp·g₀) ; puissance électrique d'un propulseur
     * électrique P = F·v_e / (2η), puissance cinétique du jet divisée par le rendement. L'énergie est tirée en premier ;
     * la demande de propergol des propulseurs électriques est ensuite réduite au ratio d'énergie obtenu.
     *
     * @return ratio de poussée effectivement disponible par type de propulsion (pénurie → poussée réduite)
     */
    private double[] resolveConsumption(double dt, ResourceProvider resources) {
        int propellants = Propellant.values().length;
        double[] chemicalNeed = new double[propellants];
        double[] electricNeed = new double[propellants];
        double energyNeed = 0.0;
        for (int i = 0; i < thrusterArray.length; i++) {
            Thruster thruster = thrusterArray[i];
            double thrust = throttles[i] * thruster.maxThrustN();
            if (thrust <= 0.0) {
                continue;
            }
            PropulsionType type = thruster.type();
            double exhaust = type.exhaustVelocity();
            double mass = thrust / exhaust * dt;
            if (type.isElectric()) {
                electricNeed[type.propellant.ordinal()] += mass;
                energyNeed += thrust * exhaust / (2.0 * type.electricalEfficiency) * dt;
            } else {
                chemicalNeed[type.propellant.ordinal()] += mass;
            }
        }

        double energyRatio = 1.0;
        lastPowerDrawW = 0.0;
        if (energyNeed > 0.0) {
            double drawn = Math.max(0.0, resources.drawEnergy(energyNeed));
            energyRatio = Math.min(1.0, drawn / energyNeed);
            lastPowerDrawW = drawn / dt;
        }
        double[] propellantRatio = new double[propellants];
        for (Propellant propellant : Propellant.values()) {
            int index = propellant.ordinal();
            double need = chemicalNeed[index] + electricNeed[index] * energyRatio;
            if (need <= 0.0) {
                propellantRatio[index] = 1.0;
                lastPropellantFlow[index] = 0.0;
                continue;
            }
            double drawn = Math.max(0.0, resources.drawPropellant(propellant, need));
            propellantRatio[index] = Math.min(1.0, drawn / need);
            lastPropellantFlow[index] = drawn / dt;
        }

        PropulsionType[] types = PropulsionType.values();
        double[] typeRatio = new double[types.length];
        for (PropulsionType type : types) {
            double ratio = propellantRatio[type.propellant.ordinal()];
            typeRatio[type.ordinal()] = type.isElectric() ? Math.min(ratio, energyRatio) : ratio;
        }
        return typeRatio;
    }

    private void computeThrust(double[] typeRatio) {
        lastLocalForce.zero();
        lastLocalTorque.zero();
        Vector3d force = new Vector3d();
        Vector3d arm = new Vector3d();
        for (int i = 0; i < thrusterArray.length; i++) {
            Thruster thruster = thrusterArray[i];
            double thrust = throttles[i] * thruster.maxThrustN() * typeRatio[thruster.type().ordinal()];
            if (thrust <= 0.0) {
                continue;
            }
            thruster.direction().mul(thrust, force);
            lastLocalForce.add(force);
            thruster.position().sub(centerOfMass, arm);
            lastLocalTorque.add(arm.cross(force));
        }
    }

    public double solarPowerW(Environment environment) {
        if (environment.irradianceWm2() <= 0.0 || solarPanels.isEmpty()) {
            return 0.0;
        }
        double power = 0.0;
        Vector3d normal = new Vector3d();
        for (SolarPanel panel : solarPanels.values()) {
            orientation.transform(panel.normal(), normal);
            double incidence = normal.dot(environment.starDirection());
            if (incidence > 0.0) {
                power += incidence * panel.areaM2() * panel.efficiency() * environment.irradianceWm2();
            }
        }
        return power;
    }

    /**
     * Euler semi-implicite à pas fixe subdivisé. Rotation intégrée dans le repère du corps via les équations
     * d'Euler I·ω̇ = τ − ω × (I·ω), ce qui évite de recalculer R·I·Rᵀ à chaque sous-pas.
     */
    private void integrate(double dt, Environment environment) {
        double h = dt / SUBSTEPS;
        Vector3d com = centerOfMassWorld(new Vector3d());
        Vector3d force = new Vector3d();
        Vector3d localOmega = new Vector3d();
        Vector3d angularMomentum = new Vector3d();
        Vector3d angularAcceleration = new Vector3d();
        Quaterniond rotationStep = new Quaterniond();
        for (int step = 0; step < SUBSTEPS; step++) {
            orientation.transform(lastLocalForce, force);
            force.fma(totalMass, environment.gravity());
            applyDrag(force, environment.airDensity());
            velocity.fma(h / totalMass, force);
            com.fma(h, velocity);

            orientation.transformInverse(angularVelocity, localOmega);
            inertia.transform(localOmega, angularMomentum);
            angularAcceleration.set(lastLocalTorque).sub(new Vector3d(localOmega).cross(angularMomentum));
            inverseInertia.transform(angularAcceleration);
            localOmega.fma(h, angularAcceleration);
            orientation.transform(localOmega, angularVelocity);

            double omega = angularVelocity.length();
            if (omega > 1.0e-12) {
                rotationStep.fromAxisAngleRad(angularVelocity.x / omega, angularVelocity.y / omega, angularVelocity.z / omega, omega * h);
                rotationStep.mul(orientation, orientation);
                orientation.normalize();
            }
        }
        orientation.transform(centerOfMass, position).negate().add(com);
    }

    /** Traînée F = −½·ρ·C_d·A(v̂)·|v|·v, avec A la projection de la boîte englobante sur la direction de vol. */
    private void applyDrag(Vector3d force, double airDensity) {
        double speed = velocity.length();
        if (airDensity <= 0.0 || speed < 1.0e-6) {
            return;
        }
        Vector3d direction = orientation.transformInverse(new Vector3d(velocity).div(speed));
        double sx = boundsMax.x - boundsMin.x;
        double sy = boundsMax.y - boundsMin.y;
        double sz = boundsMax.z - boundsMin.z;
        double area = Math.abs(direction.x) * sy * sz + Math.abs(direction.y) * sx * sz + Math.abs(direction.z) * sx * sy;
        force.fma(-0.5 * airDensity * DRAG_COEFFICIENT * area * speed, velocity);
    }

    // ───────────────────────────── Contacts ─────────────────────────────

    /**
     * Impulsion de contact au point {@code worldPoint}, de normale {@code normal} orientée de l'obstacle vers le vaisseau.
     * j = −(1 + e)·v_n / k, avec k = 1/m_a + 1/m_b + n·((I⁻¹(r × n)) × r).
     * Énergie dissipée ΔE = ½·μ·v_n²·(1 − e²), μ = 1/k (masse effective). Contre un obstacle fixe frappé dans
     * l'axe du centre de masse, μ = m et l'on retrouve E_k = ½·m·v² pour un choc mou (e = 0).
     *
     * @param otherMassKg {@link Double#POSITIVE_INFINITY} pour le terrain
     */
    public ImpactResult resolveContact(Vector3dc worldPoint, Vector3dc normal, double otherMassKg, Vector3dc otherVelocity, double restitution) {
        updateDryMassProperties();
        Vector3d n = new Vector3d(normal).normalize();
        Vector3d r = new Vector3d(worldPoint).sub(centerOfMassWorld(new Vector3d()));
        Vector3d relativeVelocity = new Vector3d(angularVelocity).cross(r).add(velocity).sub(otherVelocity);
        double normalSpeed = relativeVelocity.dot(n);
        if (normalSpeed >= 0.0) {
            return ImpactResult.NONE;
        }
        Vector3d rCrossN = new Vector3d(r).cross(n);
        Vector3d angularResponse = worldInverseInertia(new Vector3d(rCrossN));
        double inverseOther = Double.isInfinite(otherMassKg) || otherMassKg <= 0.0 ? 0.0 : 1.0 / otherMassKg;
        double k = 1.0 / totalMass + inverseOther + n.dot(new Vector3d(angularResponse).cross(r));
        double e = Math.clamp(restitution, 0.0, 1.0);
        double impulse = -(1.0 + e) * normalSpeed / k;
        velocity.fma(impulse / totalMass, n);
        angularVelocity.fma(impulse, angularResponse);
        double dissipated = 0.5 * normalSpeed * normalSpeed * (1.0 - e * e) / k;
        Vector3d local = orientation.transformInverse(new Vector3d(worldPoint).sub(position));
        return new ImpactResult(dissipated, impulse, new Vector3d(worldPoint), local, n);
    }

    private Vector3d worldInverseInertia(Vector3d vector) {
        orientation.transformInverse(vector);
        inverseInertia.transform(vector);
        return orientation.transform(vector);
    }

    // ───────────────────────────── Accès ─────────────────────────────

    public double thrustToWeight(Vector3dc gravity) {
        double g = gravity.length();
        if (g < 1.0e-9) {
            return Double.POSITIVE_INFINITY;
        }
        Vector3d upLocal = orientation.transformInverse(new Vector3d(gravity).div(-g));
        double capacity = 0.0;
        for (Thruster thruster : thrusters.values()) {
            capacity += Math.max(0.0, thruster.direction().dot(upLocal)) * thruster.maxThrustN();
        }
        return capacity / (totalMass * g);
    }

    public Vector3d centerOfMassWorld(Vector3d dest) {
        return orientation.transform(centerOfMass, dest).add(position);
    }

    public Snapshot snapshot() {
        return new Snapshot(tick, new Vector3d(position), new Quaterniond(orientation), new Vector3d(velocity), new Vector3d(angularVelocity));
    }

    public void setTransform(Vector3dc worldPosition, Quaterniondc worldOrientation) {
        position.set(worldPosition);
        orientation.set(worldOrientation).normalize();
    }

    public void setVelocity(Vector3dc linear, Vector3dc angular) {
        velocity.set(linear);
        angularVelocity.set(angular);
    }

    public void setFlightAssist(boolean enabled) {
        flightAssist = enabled;
    }

    public boolean flightAssist() {
        return flightAssist;
    }

    public void setAssistLimits(double maxSpeed, double maxTurnRate) {
        maxAssistSpeed = Math.max(0.0, maxSpeed);
        maxAssistTurnRate = Math.max(0.0, maxTurnRate);
    }

    public Vector3dc position() {
        return position;
    }

    public Quaterniondc orientation() {
        return orientation;
    }

    public Vector3dc velocity() {
        return velocity;
    }

    public Vector3dc angularVelocity() {
        return angularVelocity;
    }

    public Vector3dc localCenterOfMass() {
        updateDryMassProperties();
        return centerOfMass;
    }

    public double totalMassKg() {
        return totalMass;
    }

    public double dryMassKg() {
        return Math.max(0.0, dryMass);
    }

    public int blockCount() {
        return blockCount;
    }

    public double throttle(BlockPos thrusterPos) {
        long key = thrusterPos.asLong();
        for (int i = 0; i < thrusterArray.length; i++) {
            if (thrusterArray[i].key() == key) {
                return throttles[i];
            }
        }
        return 0.0;
    }

    public Vector3dc lastLocalThrust() {
        return lastLocalForce;
    }

    public double lastPropellantFlowKgPerS(Propellant propellant) {
        return lastPropellantFlow[propellant.ordinal()];
    }

    public double lastPowerDrawW() {
        return lastPowerDrawW;
    }

    public double lastSolarPowerW() {
        return lastSolarPowerW;
    }
}
