package odyssea.ship;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.network.PacketDistributor;
import odyssea.dimension.PlanetaryAtmosphere;
import odyssea.dimension.PlanetaryAtmospheres;
import odyssea.dimension.Planets;
import odyssea.network.OdysseaNetwork;
import odyssea.registry.OdysseaEntities;
import odyssea.registry.OdysseaItems;
import odyssea.space.Navigation;
import odyssea.space.SpaceSystem;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Vaisseau pilotable : une structure de blocs rendue d'un seul tenant, qui vole, porte un équipage et combat.
 *
 * <p><b>Systèmes</b> (autorité serveur, synchronisés) :
 * <ul>
 *   <li>Coque : points de structure. Les chocs au-delà de 8 m/s et les tirs l'entament ; à zéro, le vaisseau explose.</li>
 *   <li>Bouclier : absorbe les dégâts en premier, se recharge après quelques secondes sans être touché.</li>
 *   <li>Condensateur : énergie des canons, rechargée en continu.</li>
 *   <li>Hydrogène : consommé en proportion de la poussée (×2,5 en postcombustion). Réservoir vide : plus de poussée,
 *       plus de gouverne, le vaisseau tombe ou dérive.</li>
 *   <li>Carburant quantique : sauts entre corps du système.</li>
 *   <li>Répartition d'énergie : équilibrée, boucliers, armes ou moteurs.</li>
 * </ul>
 *
 * <p><b>Équipage</b> : chaque passager occupe un siège dans l'ordre d'embarquement ; le premier pilote. On embarque
 * par un clic droit sur la coque et l'on sort avec la touche dédiée (s'accroupir sert à descendre).
 */
public class ShipEntity extends Entity implements IEntityWithComplexSpawn {
    private static final EntityDataAccessor<Quaternionf> DATA_ORIENTATION = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.QUATERNION);
    private static final EntityDataAccessor<Float> DATA_HULL = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SHIELD = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_ENERGY = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_FUEL = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_QUANTUM_FUEL = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_THRUST = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_MISSILES = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Byte> DATA_FLAGS = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_POWER = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_SPOOL = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Vector3f> DATA_QUANTUM_FROM = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3f> DATA_QUANTUM_TO = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Integer> DATA_QUANTUM_START = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_QUANTUM_TICKS = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SHIELD_HIT = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_TARGET = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);

    public static final int FLAG_ASSIST = 1;
    public static final int FLAG_BOOST = 2;
    public static final int FLAG_FIRING = 4;
    public static final int FLAG_SPOOLING = 8;
    public static final int FLAG_QUANTUM = 16;
    public static final int FLAG_NO_FUEL = 32;
    public static final int FLAG_HOSTILE = 64;

    public static final int SPOOL_TICKS = 60;
    private static final double IMPACT_THRESHOLD = 8.0;

    public enum PowerMode {
        BALANCED(1.0, 1.0, 1.0), SHIELDS(1.9, 0.6, 0.85), WEAPONS(0.5, 1.8, 0.9), ENGINES(0.6, 0.7, 1.2);

        public final double shield;
        public final double weapons;
        public final double engines;

        PowerMode(double shield, double weapons, double engines) {
            this.shield = shield;
            this.weapons = weapons;
            this.engines = engines;
        }

        public String translationKey() {
            return "odyssea.ship.power." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Commandes du pilote local, écrites par le client à chaque tick (lues seulement si ce client pilote). */
    public static volatile ShipFlight.Input localPilotInput = ShipFlight.Input.NEUTRAL;
    public static volatile byte localPilotFlags;

    private @Nullable ShipDesign design;
    private @Nullable ShipStructure structure;
    private final Vector3d pivot = new Vector3d();
    private @Nullable ShipFlight flight;

    // ── Serveur ──
    private int gunCooldown;
    private int missileCooldown;
    private int gunIndex;
    private int shieldDelay;
    private @Nullable UUID owner;
    private int teleportId;
    private int lastPilotPacketTick = -1000;
    private boolean allowDismount;
    private boolean transitioning;
    private int travelTicks;
    private @Nullable Vec3 quantumTo;
    private @Nullable String quantumBody;
    private @Nullable PirateBrain brain;
    private int heatWarningCooldown;
    /** Soute : 54 emplacements (vide pour les pirates), sauvegardée avec le vaisseau. */
    private final net.minecraft.world.SimpleContainer cargo = new net.minecraft.world.SimpleContainer(54);

    // ── Client ──
    private final Quaternionf renderOrientation = new Quaternionf();
    private final Quaternionf prevRenderOrientation = new Quaternionf();
    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private boolean clientControlInit;
    private int clientTeleportId;
    private int soundTimer;

    public ShipEntity(EntityType<? extends ShipEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public static ShipEntity create(ServerLevel level, ShipDesigns.Loaded loaded, Vec3 position, float yaw) {
        ShipEntity ship = new ShipEntity(OdysseaEntities.SHIP.get(), level);
        ship.setDesign(loaded.design(), loaded.structure());
        ship.setOrientation(new Quaterniond().rotationY(Math.toRadians(-yaw)));
        ship.setPos(position);
        ship.fillTanks();
        if (loaded.design().hostile()) {
            ship.brain = new PirateBrain();
            ship.setFlag(FLAG_HOSTILE, true);
        }
        return ship;
    }

    private void setDesign(ShipDesign design, ShipStructure structure) {
        this.design = design;
        this.structure = structure;
        computePivot(structure, pivot);
        this.flight = new ShipFlight(structure, pivot);
        flight.position.set(getX(), getY(), getZ());
        setBoundingBox(makeBoundingBox());
    }

    private static void computePivot(ShipStructure structure, Vector3d out) {
        double[] sum = new double[3];
        long[] count = {0};
        structure.forEachBlock((x, y, z, state) -> {
            sum[0] += x + 0.5;
            sum[1] += y + 0.5;
            sum[2] += z + 0.5;
            count[0]++;
        });
        if (count[0] == 0) {
            out.set(structure.sizeX() / 2.0, structure.sizeY() / 2.0, structure.sizeZ() / 2.0);
        } else {
            out.set(sum[0] / count[0], sum[1] / count[0], sum[2] / count[0]);
        }
    }

    private void fillTanks() {
        if (design == null) {
            return;
        }
        ShipDesign.Stats s = design.stats();
        entityData.set(DATA_HULL, s.hull());
        entityData.set(DATA_SHIELD, s.shield());
        entityData.set(DATA_ENERGY, s.energy());
        entityData.set(DATA_FUEL, s.fuel());
        entityData.set(DATA_QUANTUM_FUEL, s.quantumFuel());
        entityData.set(DATA_MISSILES, s.missileCapacity());
        setFlag(FLAG_ASSIST, true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ORIENTATION, new Quaternionf());
        builder.define(DATA_HULL, 1.0f);
        builder.define(DATA_SHIELD, 0.0f);
        builder.define(DATA_ENERGY, 0.0f);
        builder.define(DATA_FUEL, 0.0f);
        builder.define(DATA_QUANTUM_FUEL, 0.0f);
        builder.define(DATA_THRUST, 0.0f);
        builder.define(DATA_MISSILES, 0);
        builder.define(DATA_FLAGS, (byte) FLAG_ASSIST);
        builder.define(DATA_POWER, (byte) 0);
        builder.define(DATA_SPOOL, 0);
        builder.define(DATA_QUANTUM_FROM, new Vector3f());
        builder.define(DATA_QUANTUM_TO, new Vector3f());
        builder.define(DATA_QUANTUM_START, 0);
        builder.define(DATA_QUANTUM_TICKS, 0);
        builder.define(DATA_SHIELD_HIT, Integer.MIN_VALUE / 2);
        builder.define(DATA_TARGET, -1);
    }

    // ───────────────────────────── Accès ─────────────────────────────

    public @Nullable ShipDesign design() {
        return design;
    }

    public @Nullable ShipStructure structure() {
        return structure;
    }

    public Vector3d pivot() {
        return pivot;
    }

    public float hull() {
        return entityData.get(DATA_HULL);
    }

    public float shield() {
        return entityData.get(DATA_SHIELD);
    }

    public float energy() {
        return entityData.get(DATA_ENERGY);
    }

    public float fuel() {
        return entityData.get(DATA_FUEL);
    }

    public float quantumFuel() {
        return entityData.get(DATA_QUANTUM_FUEL);
    }

    public float thrustLevel() {
        return entityData.get(DATA_THRUST);
    }

    public int missiles() {
        return entityData.get(DATA_MISSILES);
    }

    public int spoolTicks() {
        return entityData.get(DATA_SPOOL);
    }

    /** Ticks écoulés depuis le dernier impact sur le bouclier (horloge du monde, commune au client et au serveur). */
    public int ticksSinceShieldHit() {
        return (int) level().getGameTime() - entityData.get(DATA_SHIELD_HIT);
    }

    public PowerMode powerMode() {
        PowerMode[] modes = PowerMode.values();
        return modes[Math.floorMod(entityData.get(DATA_POWER), modes.length)];
    }

    public boolean hasFlag(int flag) {
        return (entityData.get(DATA_FLAGS) & flag) != 0;
    }

    private void setFlag(int flag, boolean value) {
        byte flags = entityData.get(DATA_FLAGS);
        entityData.set(DATA_FLAGS, (byte) (value ? flags | flag : flags & ~flag));
    }

    public boolean isHostile() {
        return hasFlag(FLAG_HOSTILE);
    }

    public boolean isQuantum() {
        return hasFlag(FLAG_QUANTUM);
    }

    public @Nullable Entity lockedTarget() {
        int id = entityData.get(DATA_TARGET);
        return id < 0 ? null : level().getEntity(id);
    }

    /** Avancement du saut en cours, entre 0 et 1 (lissé : accélération puis décélération). */
    public double quantumProgress(float partialTick) {
        int total = entityData.get(DATA_QUANTUM_TICKS);
        if (!isQuantum() || total <= 0) {
            return 0.0;
        }
        double t = Math.clamp(((int) level().getGameTime() - entityData.get(DATA_QUANTUM_START) + partialTick) / total, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    public Vec3 quantumFrom() {
        Vector3f v = entityData.get(DATA_QUANTUM_FROM);
        return new Vec3(v.x, v.y, v.z);
    }

    public Vec3 quantumTo() {
        Vector3f v = entityData.get(DATA_QUANTUM_TO);
        return new Vec3(v.x, v.y, v.z);
    }

    public Vec3 velocity() {
        return flight == null ? Vec3.ZERO : new Vec3(flight.velocity.x, flight.velocity.y, flight.velocity.z);
    }

    public double speed() {
        if (flight == null) {
            return 0.0;
        }
        return flight.velocity.length();
    }

    /**
     * Joueur local dont la caméra à la troisième personne est ancrée au centre du vaisseau (client) : la caméra tourne
     * autour du vaisseau selon le regard du joueur, au lieu de partir de sa tête (qui, dans un grand vaisseau, est
     * enfouie dans la coque).
     */
    public static volatile @Nullable Entity cameraViewer;

    @Override
    public float getViewYRot(float partialTick) {
        Entity viewer = cameraViewer;
        if (level().isClientSide && viewer != null && viewer.getVehicle() == this) {
            return viewer.getViewYRot(partialTick);
        }
        return super.getViewYRot(partialTick);
    }

    @Override
    public float getViewXRot(float partialTick) {
        Entity viewer = cameraViewer;
        if (level().isClientSide && viewer != null && viewer.getVehicle() == this) {
            return viewer.getViewXRot(partialTick);
        }
        return super.getViewXRot(partialTick);
    }

    /** Diagnostic de vol (test automatique). */
    public String flightDebug() {
        if (flight == null) {
            return "pas de vol";
        }
        String hit = flight.firstCollision(collider());
        return String.format("v=(%.1f %.1f %.1f) avant=%s coincé=%d sol=%s collision=%s", flight.velocity.x, flight.velocity.y,
                flight.velocity.z, forward(), flight.stuckTicks(), flight.onGround(), hit);
    }

    public boolean flightAssist() {
        return hasFlag(FLAG_ASSIST);
    }

    public @Nullable Player pilot() {
        Entity first = getFirstPassenger();
        return first instanceof Player player ? player : null;
    }

    /** Vrai côté client si le joueur local pilote ce vaisseau. */
    public boolean isLocallyPiloted() {
        Player pilot = pilot();
        return level().isClientSide && pilot != null && pilot.isLocalPlayer();
    }

    // ───────────────────────────── Orientation et repères ─────────────────────────────

    public Quaterniond orientation() {
        if (flight != null && (!level().isClientSide || isLocallyPiloted())) {
            return new Quaterniond(flight.orientation);
        }
        Quaternionf q = level().isClientSide ? renderOrientation : entityData.get(DATA_ORIENTATION);
        return new Quaterniond(q.x, q.y, q.z, q.w);
    }

    public Quaternionf renderOrientation(float partialTick) {
        return new Quaternionf(prevRenderOrientation).slerp(renderOrientation, partialTick);
    }

    private void setOrientation(Quaterniond q) {
        if (flight != null) {
            flight.orientation.set(q).normalize();
        }
        Quaternionf f = new Quaternionf((float) q.x, (float) q.y, (float) q.z, (float) q.w);
        entityData.set(DATA_ORIENTATION, f);
        renderOrientation.set(f);
        prevRenderOrientation.set(f);
    }

    public Vec3 forward() {
        Vector3d f = orientation().transform(new Vector3d(0.0, 0.0, 1.0));
        return new Vec3(f.x, f.y, f.z);
    }

    /** Point de la grille de la structure → monde. */
    public Vec3 toWorld(Vec3 grid) {
        Vector3d p = orientation().transform(new Vector3d(grid.x - pivot.x, grid.y - pivot.y, grid.z - pivot.z));
        return new Vec3(getX() + p.x, getY() + p.y, getZ() + p.z);
    }

    /** Monde → grille de la structure. */
    public Vec3 toGrid(Vec3 world) {
        Vector3d p = orientation().transformInverse(new Vector3d(world.x - getX(), world.y - getY(), world.z - getZ()));
        return new Vec3(p.x + pivot.x, p.y + pivot.y, p.z + pivot.z);
    }

    public Vec3 closestHullPoint(Vec3 world) {
        if (structure == null) {
            return position();
        }
        Vec3 g = toGrid(world);
        return toWorld(new Vec3(Math.clamp(g.x, 0, structure.sizeX()), Math.clamp(g.y, 0, structure.sizeY()),
                Math.clamp(g.z, 0, structure.sizeZ())));
    }

    /** Premier point de la coque traversé par le segment, ou null : collisions précises des tirs et du clic droit. */
    public @Nullable Vec3 raycastHull(Vec3 from, Vec3 to) {
        if (structure == null) {
            return null;
        }
        Vec3 a = toGrid(from);
        Vec3 b = toGrid(to);
        double length = a.distanceTo(b);
        int steps = Math.max(1, (int) Math.ceil(length / 0.3));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double x = Mth.lerp(t, a.x, b.x);
            double y = Mth.lerp(t, a.y, b.y);
            double z = Mth.lerp(t, a.z, b.z);
            if (structure.isSolidAt(Mth.floor(x), Mth.floor(y), Mth.floor(z))) {
                return toWorld(new Vec3(x, y, z));
            }
        }
        return null;
    }

    @Override
    protected AABB makeBoundingBox() {
        if (structure == null) {
            return super.makeBoundingBox();
        }
        Quaterniond q = orientation();
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        Vector3d corner = new Vector3d();
        for (int i = 0; i < 8; i++) {
            corner.set((i & 1) == 0 ? 0 : structure.sizeX(), (i & 2) == 0 ? 0 : structure.sizeY(),
                    (i & 4) == 0 ? 0 : structure.sizeZ()).sub(pivot);
            q.transform(corner);
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
            minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x);
            maxY = Math.max(maxY, corner.y);
            maxZ = Math.max(maxZ, corner.z);
        }
        return new AABB(getX() + minX, getY() + minY, getZ() + minZ, getX() + maxX, getY() + maxY, getZ() + maxZ);
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        return getBoundingBox().inflate(4.0);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        double size = getBoundingBox().getSize();
        double range = 160.0 + size * 8.0;
        return distanceSq < range * range;
    }

    // ───────────────────────────── Environnement ─────────────────────────────

    public ShipFlight.Environment environment() {
        Level level = level();
        boolean space = SpaceSystem.isSpace(level);
        Planets.Planet planet = Planets.get(level.dimension());
        double gravity = space ? 0.0 : planet != null ? Math.min(planet.gravity(), 14.0) : 9.81;
        PlanetaryAtmosphere atmosphere = PlanetaryAtmospheres.get(level.dimension());
        double density = space ? 0.0 : Math.min(3.0, atmosphere.densityAt(getY()) / 1.225);
        PowerMode mode = powerMode();
        boolean thrust = fuel() > 0.0f;
        return new ShipFlight.Environment(gravity, density, !space, thrust,
                mode.engines * (density > 0.2 ? 0.75 : 1.0), mode.engines,
                space ? SpaceSystem.MIN_SPACE_Y : Double.NaN, space ? SpaceSystem.MAX_SPACE_Y : Double.NaN);
    }

    /** Relief du monde, bords verticaux de l'espace et lunes inaccessibles. */
    private ShipFlight.Collider collider() {
        Level level = level();
        boolean space = SpaceSystem.isSpace(level);
        List<double[]> spheres = new ArrayList<>();
        if (space) {
            Vec3 here = position();
            double reach = getBoundingBox().getSize() + 400.0;
            for (SpaceSystem.Body body : SpaceSystem.bodies()) {
                if (body.landable() || body.kind() == SpaceSystem.Kind.BELT) {
                    continue;
                }
                double r = body.kind() == SpaceSystem.Kind.STAR ? SpaceSystem.SUN_RADIUS * 2.0 : body.radius();
                Vec3 c = SpaceSystem.position(body.id());
                if (c.distanceTo(here) < r + reach) {
                    spheres.add(new double[]{c.x, c.y, c.z, r * r});
                }
            }
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        return (x, y, z) -> {
            if (space) {
                for (double[] s : spheres) {
                    double dx = x + 0.5 - s[0];
                    double dy = y + 0.5 - s[1];
                    double dz = z + 0.5 - s[2];
                    if (dx * dx + dy * dy + dz * dz < s[3]) {
                        return true;
                    }
                }
            }
            cursor.set(x, y, z);
            if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight() || !level.isLoaded(cursor)) {
                return false;
            }
            BlockState state = level.getBlockState(cursor);
            return !state.isAir() && !state.getCollisionShape(level, cursor).isEmpty();
        };
    }

    // ───────────────────────────── Tick ─────────────────────────────

    @Override
    public void tick() {
        if (design == null || structure == null || flight == null) {
            if (!level().isClientSide && tickCount > 40) {
                discard();
            }
            return;
        }
        if (level().isClientSide) {
            clientTick();
        } else {
            serverTick((ServerLevel) level());
        }
    }

    private void serverTick(ServerLevel level) {
        Player pilot = pilot();
        tickResources();
        tickQuantum(pilot);
        if (!isQuantum()) {
            boolean piloted = pilot != null && tickCount - lastPilotPacketTick < 40;
            if (!piloted) {
                ShipFlight.Input input = brain != null ? brain.think(this, level) : autopilotInput();
                flight.flightAssist = true;
                flight.position.set(getX(), getY(), getZ());
                flight.step(design.stats(), input, environment(), collider());
                consumeFuel(flight.lastThrustFraction(), input.boost());
                handleImpact(flight.lastImpactSpeed());
                setFlag(FLAG_BOOST, input.boost());
                applyFlightState();
            }
            tickWeapons(level, pilot);
            tickHazards(level);
            if (!isRemoved() && spoolTicks() == 0) {
                tickTransitions(level);
            }
        }
        if (!isRemoved()) {
            for (Entity passenger : getPassengers()) {
                if (passenger instanceof ServerPlayer player) {
                    level.getChunkSource().move(player);
                }
            }
        }
    }

    private ShipFlight.Input autopilotInput() {
        if (SpaceSystem.isSpace(level()) || flight.onGround() || fuel() <= 0.0f) {
            return ShipFlight.Input.NEUTRAL;
        }
        // Sans pilote sur une planète : descente lente jusqu'au sol, assiette horizontale.
        return new ShipFlight.Input(0, -0.05f, 0, 0, false, null);
    }

    private void applyFlightState() {
        Quaternionf q = new Quaternionf((float) flight.orientation.x, (float) flight.orientation.y,
                (float) flight.orientation.z, (float) flight.orientation.w);
        entityData.set(DATA_ORIENTATION, q);
        entityData.set(DATA_THRUST, (float) flight.lastThrustFraction());
        setPos(flight.position.x, flight.position.y, flight.position.z);
        setDeltaMovement(flight.velocity.x * ShipFlight.DT, flight.velocity.y * ShipFlight.DT, flight.velocity.z * ShipFlight.DT);
    }

    private void tickResources() {
        ShipDesign.Stats s = design.stats();
        PowerMode mode = powerMode();
        entityData.set(DATA_ENERGY, (float) Math.min(s.energy(), energy() + s.energyRegen() * mode.weapons * ShipFlight.DT));
        if (shieldDelay > 0) {
            shieldDelay--;
        } else if (shield() < s.shield()) {
            entityData.set(DATA_SHIELD, (float) Math.min(s.shield(), shield() + s.shieldRegen() * mode.shield * ShipFlight.DT));
        }
        setFlag(FLAG_NO_FUEL, fuel() <= 0.0f);
        if (heatWarningCooldown > 0) {
            heatWarningCooldown--;
        }
        Entity target = lockedTarget();
        if (target != null && (!target.isAlive() || target.distanceToSqr(this) > 900.0 * 900.0)) {
            entityData.set(DATA_TARGET, -1);
        }
    }

    private void consumeFuel(double thrust, boolean boost) {
        double burn = design.stats().fuelBurn() * thrust * (boost ? 2.5 : 1.0) * ShipFlight.DT;
        if (burn > 0.0) {
            entityData.set(DATA_FUEL, (float) Math.max(0.0, fuel() - burn));
        }
    }

    private void handleImpact(double speed) {
        if (speed <= IMPACT_THRESHOLD) {
            return;
        }
        double excess = speed - IMPACT_THRESHOLD;
        float damage = (float) (excess * excess * 0.4 * Math.sqrt(design.stats().hull() / 2000.0));
        level().playSound(null, getX(), getY(), getZ(), excess > 15 ? SoundEvents.GENERIC_EXPLODE.value() : SoundEvents.ANVIL_LAND,
                SoundSource.NEUTRAL, (float) Math.min(2.0, 0.4 + excess / 20.0), 0.6f);
        damageHull(damage, null);
    }

    // ───────────────────────────── Pilote (paquets) ─────────────────────────────

    public void acceptPilotState(ServerPlayer player, OdysseaNetwork.PilotState state) {
        if (pilot() != player || design == null || flight == null || isQuantum() || state.teleportId() != teleportId) {
            return;
        }
        Vec3 next = new Vec3(state.x(), state.y(), state.z());
        int elapsed = Math.max(1, Math.min(20, tickCount - lastPilotPacketTick));
        double maxStep = (design.stats().boostSpeed() * 1.6 + 40.0) * ShipFlight.DT * elapsed;
        if (next.distanceToSqr(position()) > maxStep * maxStep && lastPilotPacketTick > 0) {
            resyncPilot();
            return;
        }
        lastPilotPacketTick = tickCount;
        flight.position.set(state.x(), state.y(), state.z());
        flight.orientation.set(state.qx(), state.qy(), state.qz(), state.qw()).normalize();
        flight.velocity.set(state.vx(), state.vy(), state.vz());
        boolean boost = (state.flags() & OdysseaNetwork.PilotState.BOOST) != 0;
        setFlag(FLAG_FIRING, (state.flags() & OdysseaNetwork.PilotState.FIRING) != 0);
        setFlag(FLAG_BOOST, boost);
        consumeFuel(Math.clamp(state.thrust(), 0.0f, 1.0f), boost);
        handleImpact(Math.clamp(state.impact(), 0.0f, 400.0f));
        applyFlightState();
        entityData.set(DATA_THRUST, Math.clamp(state.thrust(), 0.0f, 1.0f));
        // L'équipage suit immédiatement, et avec lui la zone de tronçons chargés et actifs : sinon le vaisseau sort de
        // la zone active du joueur resté en arrière et cesse d'être simulé (des deux côtés).
        for (Entity passenger : getPassengers()) {
            positionRider(passenger);
            if (passenger instanceof ServerPlayer rider) {
                rider.serverLevel().getChunkSource().move(rider);
            }
        }
    }

    private void resyncPilot() {
        teleportId++;
        if (pilot() instanceof ServerPlayer player) {
            Quaterniond q = flight.orientation;
            PacketDistributor.sendToPlayer(player, new OdysseaNetwork.ShipTeleport(getId(), teleportId, getX(), getY(), getZ(),
                    (float) q.x, (float) q.y, (float) q.z, (float) q.w,
                    (float) flight.velocity.x, (float) flight.velocity.y, (float) flight.velocity.z));
        }
    }

    public void handleAction(ServerPlayer player, OdysseaNetwork.Action action) {
        if (design == null || !hasPassenger(player)) {
            return;
        }
        if (action == OdysseaNetwork.Action.EXIT) {
            exitPassenger(player);
            return;
        }
        if (action == OdysseaNetwork.Action.CARGO) {
            openCargo(player);
            return;
        }
        if (pilot() != player) {
            return;
        }
        switch (action) {
            case TOGGLE_ASSIST -> {
                setFlag(FLAG_ASSIST, !flightAssist());
                player.displayClientMessage(Component.translatable(flightAssist() ? "odyssea.ship.assist.on" : "odyssea.ship.assist.off")
                        .withStyle(ChatFormatting.AQUA), true);
            }
            case CYCLE_POWER -> {
                entityData.set(DATA_POWER, (byte) ((powerMode().ordinal() + 1) % PowerMode.values().length));
                player.displayClientMessage(Component.translatable("odyssea.ship.power",
                        Component.translatable(powerMode().translationKey())).withStyle(ChatFormatting.AQUA), true);
            }
            case QUANTUM -> toggleQuantum(player);
            case FIRE_MISSILE -> fireMissile(player);
            case LOCK_TARGET -> lockTarget(player);
            default -> {
            }
        }
    }

    // ───────────────────────────── Équipage ─────────────────────────────

    public int seatCount() {
        return design == null ? 1 : Math.max(1, design.seats().size());
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return design != null && !isHostile() && getPassengers().size() < seatCount();
    }

    @Override
    public @Nullable LivingEntity getControllingPassenger() {
        return pilot();
    }

    /** Le mouvement est piloté par nos propres paquets : aucun paquet de véhicule vanilla. */
    @Override
    public boolean isControlledByLocalInstance() {
        return !level().isClientSide && pilot() == null;
    }

    public Vec3 seatPosition(int index) {
        if (design == null || design.seats().isEmpty()) {
            return position();
        }
        Vec3 seat = design.seats().get(Math.min(index, design.seats().size() - 1));
        return toWorld(seat);
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction callback) {
        if (!hasPassenger(passenger)) {
            return;
        }
        int index = getPassengers().indexOf(passenger);
        Vec3 seat = seatPosition(index);
        Vec3 attachment = passenger.getVehicleAttachmentPoint(this);
        callback.accept(passenger, seat.x - attachment.x, seat.y - attachment.y, seat.z - attachment.z);
    }

    public boolean allowsDismount() {
        return allowDismount || transitioning || isRemoved();
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        if (design == null || structure == null) {
            return super.getDismountLocationForPassenger(passenger);
        }
        int index = Math.max(0, getPassengers().indexOf(passenger));
        Vec3 seat = design.seats().isEmpty() ? new Vec3(pivot.x, pivot.y, pivot.z) : design.seats().get(Math.min(index, design.seats().size() - 1));
        // Vers la droite du vaisseau (−X local) : juste après le dernier bloc de coque de la rangée du siège.
        int row = Mth.floor(seat.y);
        int column = Mth.floor(seat.z);
        int edge = Mth.floor(seat.x);
        for (int x = Mth.floor(seat.x); x >= 0; x--) {
            if (structure.isSolidAt(x, row, column) || structure.isSolidAt(x, row + 1, column)) {
                edge = x;
            }
        }
        Vec3 out = toWorld(new Vec3(edge - 1.5, seat.y, seat.z));
        if (flight != null && flight.onGround() && !SpaceSystem.isSpace(level())) {
            // Posé : on descend jusqu'au sol plutôt que de sauter du cockpit.
            BlockPos.MutableBlockPos cursor = BlockPos.containing(out).mutable();
            for (int i = 0; i < 96 && cursor.getY() > level().getMinBuildHeight(); i++) {
                BlockState below = level().getBlockState(cursor.below());
                if (!below.getCollisionShape(level(), cursor.below()).isEmpty()) {
                    return new Vec3(out.x, cursor.getY(), out.z);
                }
                cursor.move(0, -1, 0);
            }
        }
        return out;
    }

    public void exitPassenger(ServerPlayer player) {
        allowDismount = true;
        try {
            player.stopRiding();
        } finally {
            allowDismount = false;
        }
    }

    private void board(Player player) {
        if (player.isPassenger() || isHostile()) {
            return;
        }
        if (getPassengers().size() >= seatCount()) {
            player.displayClientMessage(Component.translatable("odyssea.ship.full").withStyle(ChatFormatting.RED), true);
            return;
        }
        boolean pilot = getPassengers().isEmpty();
        if (player.startRiding(this)) {
            if (owner == null) {
                owner = player.getUUID();
            }
            player.displayClientMessage(Component.translatable(pilot ? "odyssea.ship.boarded.pilot" : "odyssea.ship.boarded.crew")
                    .withStyle(ChatFormatting.AQUA), true);
            if (pilot) {
                lastPilotPacketTick = tickCount;
            }
        }
    }

    public void interactFrom(ServerPlayer player, InteractionHand hand) {
        if (design == null || isHostile() || isRemoved()) {
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        ShipDesign.Stats s = design.stats();
        if (stack.is(OdysseaItems.HYDROGEN_CELL.get())) {
            refill(player, stack, DATA_FUEL, s.fuel(), s.fuel() * 0.25f, "odyssea.ship.refuel");
        } else if (stack.is(OdysseaItems.QUANTUM_CELL.get())) {
            refill(player, stack, DATA_QUANTUM_FUEL, s.quantumFuel(), s.quantumFuel() * 0.25f, "odyssea.ship.refuel_quantum");
        } else if (stack.is(OdysseaItems.REPAIR_KIT.get())) {
            refill(player, stack, DATA_HULL, s.hull(), s.hull() * 0.25f, "odyssea.ship.repaired");
        } else if (stack.is(OdysseaItems.SHIP_MISSILE.get())) {
            int capacity = s.missileCapacity();
            if (missiles() < capacity) {
                entityData.set(DATA_MISSILES, Math.min(capacity, missiles() + 2));
                stack.consume(1, player);
                level().playSound(null, getX(), getY(), getZ(), SoundEvents.CROSSBOW_LOADING_END.value(), SoundSource.PLAYERS, 1.0f, 0.8f);
                player.displayClientMessage(Component.translatable("odyssea.ship.missiles_loaded", missiles(), capacity), true);
            }
        } else if (stack.isEmpty() && player.isShiftKeyDown() && hand == InteractionHand.MAIN_HAND) {
            packUp(player);
        } else if (player.isShiftKeyDown() && hand == InteractionHand.MAIN_HAND) {
            openCargo(player);
        } else if (hand == InteractionHand.MAIN_HAND) {
            board(player);
        }
    }

    private void refill(ServerPlayer player, ItemStack stack, EntityDataAccessor<Float> data, float capacity, float amount, String message) {
        float current = entityData.get(data);
        if (current >= capacity - 0.01f) {
            player.displayClientMessage(Component.translatable("odyssea.ship.already_full"), true);
            return;
        }
        float next = Math.min(capacity, current + amount);
        entityData.set(data, next);
        stack.consume(1, player);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.BUCKET_EMPTY, SoundSource.PLAYERS, 1.0f, 1.2f);
        player.displayClientMessage(Component.translatable(message, Math.round(next * 100.0f / capacity)), true);
    }

    /** Ouvre la soute (tout membre d'équipage, ou à quai par clic droit accroupi avec un objet en main). */
    public void openCargo(ServerPlayer player) {
        if (isHostile()) {
            return;
        }
        player.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inventory, p) ->
                net.minecraft.world.inventory.ChestMenu.sixRows(id, inventory, cargo),
                Component.translatable("odyssea.ship.cargo", getDisplayName())));
    }

    /** Remballe le vaisseau posé dans sa balise, avec ses réservoirs. */
    private void packUp(ServerPlayer player) {
        if (!getPassengers().isEmpty() || (owner != null && !owner.equals(player.getUUID()) && !player.isCreative())) {
            return;
        }
        if (!cargo.isEmpty()) {
            player.displayClientMessage(Component.translatable("odyssea.ship.cargo_not_empty").withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!(flight.onGround() || SpaceSystem.isSpace(level())) || speed() > 2.0) {
            player.displayClientMessage(Component.translatable("odyssea.ship.pack_landed").withStyle(ChatFormatting.RED), true);
            return;
        }
        ItemStack beacon = OdysseaItems.beaconFor(design.id());
        if (beacon.isEmpty()) {
            return;
        }
        CompoundTag state = new CompoundTag();
        saveResources(state);
        beacon.set(DataComponents.CUSTOM_DATA, CustomData.of(state));
        if (!player.getInventory().add(beacon)) {
            player.drop(beacon, false);
        }
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.0f, 1.2f);
        discard();
    }

    /** Applique l'état d'une balise remballée (réservoirs, coque…). */
    public void loadPackedState(CompoundTag tag) {
        if (tag.contains("Hull")) {
            loadResources(tag);
        }
    }

    // ───────────────────────────── Armes ─────────────────────────────

    private Vec3 aimPoint(@Nullable Player pilot) {
        if (brain != null && brain.aimPoint() != null) {
            return brain.aimPoint();
        }
        if (pilot != null) {
            return pilot.getEyePosition().add(pilot.getLookAngle().scale(320.0));
        }
        return position().add(forward().scale(320.0));
    }

    private void tickWeapons(ServerLevel level, @Nullable Player pilot) {
        if (gunCooldown > 0) {
            gunCooldown--;
        }
        if (missileCooldown > 0) {
            missileCooldown--;
        }
        boolean firing = brain != null ? brain.wantsFire() : pilot != null && hasFlag(FLAG_FIRING);
        if (!firing || gunCooldown > 0 || design.guns().isEmpty()) {
            return;
        }
        ShipDesign.Stats s = design.stats();
        if (energy() < s.gunEnergy()) {
            return;
        }
        List<Vec3> guns = design.guns();
        Vec3 muzzle = toWorld(guns.get(gunIndex++ % guns.size()));
        Vec3 direction = aimPoint(pilot).subtract(muzzle).normalize();
        // Les canons ne pivotent que dans un cône de 25° autour de l'axe du vaisseau.
        Vec3 axis = forward();
        if (direction.dot(axis) < Math.cos(Math.toRadians(25.0))) {
            direction = axis;
        }
        ShipProjectile bolt = ShipProjectile.bolt(level, this, pilot, muzzle, direction.scale(s.boltSpeed()).add(velocity()),
                s.gunDamage(), s.boltColor());
        level.addFreshEntity(bolt);
        entityData.set(DATA_ENERGY, energy() - s.gunEnergy());
        gunCooldown = Math.max(1, Math.round(s.gunCooldown() / (float) Math.max(1, guns.size() / 2)));
        level.playSound(null, muzzle.x, muzzle.y, muzzle.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.NEUTRAL, 0.7f, 1.7f + random.nextFloat() * 0.2f);
    }

    public void fireMissile(@Nullable Player pilot) {
        if (design == null || missileCooldown > 0 || missiles() <= 0) {
            return;
        }
        List<Vec3> pods = design.missiles().isEmpty() ? design.guns() : design.missiles();
        if (pods.isEmpty()) {
            return;
        }
        ShipDesign.Stats s = design.stats();
        Vec3 pod = toWorld(pods.get(random.nextInt(pods.size())));
        Entity target = lockedTarget();
        Vec3 direction = forward();
        ShipProjectile missile = ShipProjectile.missile((ServerLevel) level(), this, pilot, pod,
                direction.scale(40.0).add(velocity()), s.missileDamage(), s.missileExplosion(), target);
        level().addFreshEntity(missile);
        entityData.set(DATA_MISSILES, missiles() - 1);
        missileCooldown = s.missileCooldown();
        level().playSound(null, pod.x, pod.y, pod.z, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.NEUTRAL, 1.5f, 0.6f);
    }

    public void lockTarget(@Nullable Player pilot) {
        Vec3 eye = pilot != null ? pilot.getEyePosition() : position();
        Vec3 look = pilot != null ? pilot.getLookAngle() : forward();
        Entity best = null;
        double bestAngle = Math.toRadians(30.0);
        for (Entity candidate : level().getEntities(this, getBoundingBox().inflate(700.0),
                e -> e.isAlive() && !hasPassenger(e) && (e instanceof ShipEntity || e instanceof Enemy))) {
            Vec3 to = candidate.getBoundingBox().getCenter().subtract(eye);
            double angle = Math.acos(Math.clamp(to.normalize().dot(look), -1.0, 1.0));
            if (angle < bestAngle) {
                bestAngle = angle;
                best = candidate;
            }
        }
        entityData.set(DATA_TARGET, best == null ? -1 : best.getId());
        if (pilot != null) {
            pilot.displayClientMessage(best == null ? Component.translatable("odyssea.ship.no_target")
                    : Component.translatable("odyssea.ship.target_locked", best.getDisplayName()).withStyle(ChatFormatting.RED), true);
        }
    }

    public void setTargetFromAi(@Nullable Entity target) {
        entityData.set(DATA_TARGET, target == null ? -1 : target.getId());
    }

    // ───────────────────────────── Dégâts ─────────────────────────────

    /** Dégâts d'arme : le bouclier encaisse d'abord. */
    public void damageShip(float amount, @Nullable Entity attacker, Vec3 at) {
        if (design == null || isQuantum() || isRemoved() || level().isClientSide) {
            return;
        }
        shieldDelay = Math.round(design.stats().shieldDelay() * 20.0f);
        float shield = shield();
        if (shield > 0.0f) {
            float absorbed = Math.min(shield, amount);
            entityData.set(DATA_SHIELD, shield - absorbed);
            entityData.set(DATA_SHIELD_HIT, (int) level().getGameTime());
            amount -= absorbed;
            ((ServerLevel) level()).sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 8, 0.4, 0.4, 0.4, 0.2);
            level().playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1.2f, 1.6f);
        }
        if (amount > 0.0f) {
            ((ServerLevel) level()).sendParticles(ParticleTypes.LAVA, at.x, at.y, at.z, 4, 0.3, 0.3, 0.3, 0.1);
            damageHull(amount, attacker);
        }
        if (brain != null && attacker != null) {
            brain.onAttacked(attacker);
        }
    }

    private void damageHull(float amount, @Nullable Entity attacker) {
        float hull = hull() - amount;
        entityData.set(DATA_HULL, Math.max(0.0f, hull));
        if (hull <= 0.0f) {
            destroy(attacker);
        }
    }

    private void destroy(@Nullable Entity attacker) {
        if (!(level() instanceof ServerLevel level) || isRemoved()) {
            return;
        }
        AABB box = getBoundingBox();
        double size = box.getSize();
        float power = (float) Math.clamp(size / 8.0, 3.0, 9.0);
        Vec3 center = box.getCenter();
        for (int i = 0; i < 6; i++) {
            Vec3 p = toWorld(new Vec3(random.nextDouble() * structure.sizeX(), random.nextDouble() * structure.sizeY(),
                    random.nextDouble() * structure.sizeZ()));
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        level.sendParticles(ParticleTypes.LARGE_SMOKE, center.x, center.y, center.z, 200, size / 6, size / 8, size / 6, 0.15);
        allowDismount = true;
        ejectPassengers();
        allowDismount = false;
        level.explode(this, center.x, center.y, center.z, power, Level.ExplosionInteraction.NONE);
        net.minecraft.world.Containers.dropContents(level, BlockPos.containing(center), cargo);
        if (isHostile()) {
            dropLoot(level, center);
        }
        discard();
    }

    private void dropLoot(ServerLevel level, Vec3 at) {
        List<ItemStack> loot = new ArrayList<>();
        loot.add(new ItemStack(OdysseaItems.HYDROGEN_CELL.get(), 1 + random.nextInt(2)));
        if (random.nextFloat() < 0.5f) {
            loot.add(new ItemStack(OdysseaItems.QUANTUM_CELL.get()));
        }
        loot.add(new ItemStack(Items.IRON_INGOT, 2 + random.nextInt(5)));
        if (random.nextFloat() < 0.4f) {
            loot.add(new ItemStack(Items.GOLD_INGOT, 1 + random.nextInt(3)));
        }
        if (random.nextFloat() < 0.12f) {
            loot.add(new ItemStack(Items.DIAMOND));
        }
        if (random.nextFloat() < 0.4f) {
            loot.add(new ItemStack(OdysseaItems.SHIP_MISSILE.get(), 1 + random.nextInt(2)));
        }
        for (ItemStack stack : loot) {
            ItemEntity item = new ItemEntity(level, at.x, at.y, at.z, stack);
            item.setDeltaMovement(random.nextGaussian() * 0.1, random.nextGaussian() * 0.1, random.nextGaussian() * 0.1);
            item.setNoGravity(SpaceSystem.isSpace(level));
            level.addFreshEntity(item);
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || isRemoved() || isInvulnerableTo(source)) {
            return false;
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            if (source.getDirectEntity() == this) {
                return false;
            }
            damageShip(amount * 4.0f, source.getEntity(), getBoundingBox().getCenter());
            return true;
        }
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    // ───────────────────────────── Saut quantique ─────────────────────────────

    private void toggleQuantum(ServerPlayer pilot) {
        if (isQuantum()) {
            return;
        }
        if (spoolTicks() > 0) {
            cancelSpool(pilot, "odyssea.quantum.cancelled");
            return;
        }
        if (!SpaceSystem.isSpace(level())) {
            pilot.displayClientMessage(Component.translatable("odyssea.quantum.need_space").withStyle(ChatFormatting.RED), true);
            return;
        }
        SpaceSystem.Body destination = Navigation.destination(pilot);
        if (destination == null) {
            pilot.displayClientMessage(Component.translatable("odyssea.quantum.no_destination").withStyle(ChatFormatting.RED), true);
            return;
        }
        SpaceSystem.Body blocker = SpaceSystem.quantumBlocker(position());
        if (blocker != null) {
            pilot.displayClientMessage(Component.translatable("odyssea.quantum.gravity_well",
                    Component.translatable(blocker.translationKey())).withStyle(ChatFormatting.RED), true);
            return;
        }
        Vec3 arrival = SpaceSystem.quantumArrival(destination, position());
        double distance = arrival.distanceTo(position());
        if (distance < 3_000.0) {
            pilot.displayClientMessage(Component.translatable("odyssea.quantum.too_close").withStyle(ChatFormatting.RED), true);
            return;
        }
        double cost = SpaceSystem.quantumFuelCost(distance);
        if (quantumFuel() < cost) {
            pilot.displayClientMessage(Component.translatable("odyssea.quantum.no_fuel", Math.round(quantumFuel()),
                    Math.round(cost)).withStyle(ChatFormatting.RED), true);
            return;
        }
        if (alignmentAngle(arrival) > Math.toRadians(10.0)) {
            pilot.displayClientMessage(Component.translatable("odyssea.quantum.align").withStyle(ChatFormatting.GOLD), true);
            return;
        }
        quantumTo = arrival;
        quantumBody = destination.id();
        entityData.set(DATA_SPOOL, 1);
        setFlag(FLAG_SPOOLING, true);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.NEUTRAL, 2.0f, 0.6f);
        pilot.displayClientMessage(Component.translatable("odyssea.quantum.spooling",
                Component.translatable(destination.translationKey())).withStyle(ChatFormatting.AQUA), true);
    }

    private double alignmentAngle(Vec3 target) {
        Vec3 to = target.subtract(position()).normalize();
        return Math.acos(Math.clamp(to.dot(forward()), -1.0, 1.0));
    }

    private void cancelSpool(@Nullable Player pilot, String message) {
        entityData.set(DATA_SPOOL, 0);
        setFlag(FLAG_SPOOLING, false);
        quantumTo = null;
        if (pilot != null) {
            pilot.displayClientMessage(Component.translatable(message).withStyle(ChatFormatting.GOLD), true);
        }
    }

    private void tickQuantum(@Nullable Player pilot) {
        int spool = spoolTicks();
        if (spool > 0 && !isQuantum()) {
            if (pilot == null || quantumTo == null) {
                cancelSpool(pilot, "odyssea.quantum.cancelled");
                return;
            }
            if (alignmentAngle(quantumTo) > Math.toRadians(15.0)) {
                cancelSpool(pilot, "odyssea.quantum.alignment_lost");
                return;
            }
            entityData.set(DATA_SPOOL, spool + 1);
            if (spool + 1 >= SPOOL_TICKS) {
                beginTravel(pilot);
            }
        } else if (isQuantum()) {
            travelTicks++;
            flight.velocity.zero();
            flight.angularVelocity.zero();
            if (travelTicks >= entityData.get(DATA_QUANTUM_TICKS)) {
                arrive(pilot);
            }
        }
    }

    private void beginTravel(Player pilot) {
        double distance = quantumTo.distanceTo(position());
        entityData.set(DATA_QUANTUM_FUEL, (float) Math.max(0.0, quantumFuel() - SpaceSystem.quantumFuelCost(distance)));
        entityData.set(DATA_QUANTUM_FROM, new Vector3f((float) getX(), (float) getY(), (float) getZ()));
        entityData.set(DATA_QUANTUM_TO, new Vector3f((float) quantumTo.x, (float) quantumTo.y, (float) quantumTo.z));
        entityData.set(DATA_QUANTUM_START, (int) level().getGameTime());
        entityData.set(DATA_QUANTUM_TICKS, SpaceSystem.quantumTravelTicks(distance));
        entityData.set(DATA_SPOOL, 0);
        setFlag(FLAG_SPOOLING, false);
        setFlag(FLAG_QUANTUM, true);
        travelTicks = 0;
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.PORTAL_TRAVEL, SoundSource.NEUTRAL, 0.6f, 1.6f);
    }

    private void arrive(@Nullable Player pilot) {
        Vec3 target = quantumTo != null ? quantumTo : quantumTo();
        SpaceSystem.Body body = quantumBody == null ? null : SpaceSystem.body(quantumBody);
        Vec3 look = body != null ? SpaceSystem.position(body.id()).subtract(target).normalize() : forward();
        Quaterniond q = new Quaterniond().rotationY(Math.atan2(look.x, look.z)).rotateX(-Math.asin(Math.clamp(look.y, -1.0, 1.0)));
        setFlag(FLAG_QUANTUM, false);
        quantumTo = null;
        teleportShip(target, q, look.scale(30.0));
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.NEUTRAL, 2.0f, 0.7f);
        if (pilot != null && body != null) {
            pilot.displayClientMessage(Component.translatable("odyssea.quantum.arrived",
                    Component.translatable(body.translationKey())).withStyle(ChatFormatting.AQUA), true);
        }
    }

    /** Téléportation dans la même dimension, passagers compris ; le client du pilote se recale. */
    public void teleportShip(Vec3 position, Quaterniond orientation, Vec3 velocity) {
        teleportId++;
        setOrientation(orientation);
        flight.position.set(position.x, position.y, position.z);
        flight.velocity.set(velocity.x, velocity.y, velocity.z);
        flight.angularVelocity.zero();
        teleportTo(position.x, position.y, position.z);
        setBoundingBox(makeBoundingBox());
        for (Entity passenger : getPassengers()) {
            if (passenger instanceof ServerPlayer player) {
                PacketDistributor.sendToPlayer(player, new OdysseaNetwork.ShipTeleport(getId(), teleportId, position.x, position.y, position.z,
                        (float) orientation.x, (float) orientation.y, (float) orientation.z, (float) orientation.w,
                        (float) velocity.x, (float) velocity.y, (float) velocity.z));
                player.serverLevel().getChunkSource().move(player);
            }
        }
    }

    // ───────────────────────────── Espace ↔ planètes ─────────────────────────────

    private void tickHazards(ServerLevel level) {
        if (!SpaceSystem.isSpace(level)) {
            return;
        }
        double sunDistance = position().distanceTo(SpaceSystem.sunPosition());
        if (sunDistance < SpaceSystem.SUN_DANGER_RADIUS) {
            double heat = 1.0 - sunDistance / SpaceSystem.SUN_DANGER_RADIUS;
            damageShip((float) (heat * heat * design.stats().hull() * 0.01), null, position());
            Player pilot = pilot();
            if (pilot != null && heatWarningCooldown <= 0) {
                pilot.displayClientMessage(Component.translatable("odyssea.ship.heat").withStyle(ChatFormatting.RED), true);
                heatWarningCooldown = 40;
            }
        }
    }

    private void tickTransitions(ServerLevel level) {
        if (SpaceSystem.isSpace(level)) {
            Vec3 here = position();
            SpaceSystem.Body entry = SpaceSystem.entryBodyAt(here);
            if (entry != null && velocity().dot(here.subtract(SpaceSystem.position(entry.id()))) < 0.0) {
                transfer(SpaceSystem.enterPlanet(entry, here, velocity()));
            }
        } else {
            SpaceSystem.Body body = SpaceSystem.bodyForDimension(level.dimension());
            if (body != null && getY() > SpaceSystem.EXIT_Y && flight.velocity.y > 0.0) {
                transfer(SpaceSystem.leavePlanet(body, position(), velocity()));
            }
        }
    }

    private void transfer(SpaceSystem.Transfer transfer) {
        if (!(level() instanceof ServerLevel current)) {
            return;
        }
        ServerLevel target = current.getServer().getLevel(transfer.dimension());
        if (target == null) {
            return;
        }
        Quaterniond q = new Quaterniond(transfer.rotation()).mul(flight.orientation).normalize();
        setOrientation(q);
        flight.velocity.set(transfer.velocity().x, transfer.velocity().y, transfer.velocity().z);
        Player pilot = pilot();
        transitioning = true;
        try {
            changeDimension(new DimensionTransition(target, transfer.position(), transfer.velocity().scale(ShipFlight.DT),
                    getYRot(), getXRot(), DimensionTransition.DO_NOTHING));
        } finally {
            transitioning = false;
        }
        if (pilot != null) {
            SpaceSystem.Body body = SpaceSystem.bodyForDimension(transfer.dimension());
            pilot.displayClientMessage(body != null
                    ? Component.translatable("odyssea.space.entering", Component.translatable(body.translationKey())).withStyle(ChatFormatting.AQUA)
                    : Component.translatable("odyssea.space.leaving").withStyle(ChatFormatting.AQUA), true);
        }
    }

    // ───────────────────────────── Client ─────────────────────────────

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        if (isLocallyPiloted()) {
            return;
        }
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpSteps = Math.max(1, steps);
    }

    /** Heure du monde au dernier tick client (le client peut devoir faire tiquer lui-même le vaisseau piloté). */
    private long lastClientTickTime = Long.MIN_VALUE;

    public boolean tickedThisClientTick() {
        return lastClientTickTime == level().getGameTime();
    }

    private void clientTick() {
        lastClientTickTime = level().getGameTime();
        prevRenderOrientation.set(renderOrientation);
        if (isLocallyPiloted()) {
            if (!clientControlInit) {
                Quaternionf q = entityData.get(DATA_ORIENTATION);
                flight.position.set(getX(), getY(), getZ());
                flight.orientation.set(q.x, q.y, q.z, q.w).normalize();
                flight.angularVelocity.zero();
                clientControlInit = true;
            }
            if (!isQuantum()) {
                ShipFlight.Input input = localPilotInput;
                flight.flightAssist = flightAssist();
                flight.step(design.stats(), input, environment(), collider());
                setPos(flight.position.x, flight.position.y, flight.position.z);
                PacketDistributor.sendToServer(new OdysseaNetwork.PilotState(getId(), clientTeleportId,
                        flight.position.x, flight.position.y, flight.position.z,
                        (float) flight.orientation.x, (float) flight.orientation.y, (float) flight.orientation.z, (float) flight.orientation.w,
                        (float) flight.velocity.x, (float) flight.velocity.y, (float) flight.velocity.z,
                        (float) flight.lastThrustFraction(), (float) flight.lastImpactSpeed(), localPilotFlags));
            }
            Quaterniond q = flight.orientation;
            renderOrientation.set((float) q.x, (float) q.y, (float) q.z, (float) q.w);
        } else {
            clientControlInit = false;
            if (lerpSteps > 0) {
                double t = 1.0 / lerpSteps;
                setPos(getX() + (lerpX - getX()) * t, getY() + (lerpY - getY()) * t, getZ() + (lerpZ - getZ()) * t);
                lerpSteps--;
            }
            renderOrientation.slerp(entityData.get(DATA_ORIENTATION), 0.5f);
            if (flight != null) {
                Vec3 motion = getDeltaMovement();
                flight.velocity.set(motion.x / ShipFlight.DT, motion.y / ShipFlight.DT, motion.z / ShipFlight.DT);
            }
        }
        setBoundingBox(makeBoundingBox());
        clientEffects();
    }

    /** Recalage imposé par le serveur (paquet {@link OdysseaNetwork.ShipTeleport}). */
    public void applyServerTeleport(OdysseaNetwork.ShipTeleport teleport) {
        clientTeleportId = teleport.teleportId();
        Quaternionf q = new Quaternionf(teleport.qx(), teleport.qy(), teleport.qz(), teleport.qw());
        renderOrientation.set(q);
        prevRenderOrientation.set(q);
        if (flight != null) {
            flight.position.set(teleport.x(), teleport.y(), teleport.z());
            flight.orientation.set(q.x, q.y, q.z, q.w);
            flight.velocity.set(teleport.vx(), teleport.vy(), teleport.vz());
            flight.angularVelocity.zero();
        }
        setPos(teleport.x(), teleport.y(), teleport.z());
        xo = xOld = teleport.x();
        yo = yOld = teleport.y();
        zo = zOld = teleport.z();
        clientControlInit = true;
    }

    private void clientEffects() {
        float thrust = thrustLevel();
        if (design == null || isQuantum()) {
            return;
        }
        boolean boost = hasFlag(FLAG_BOOST);
        if (thrust > 0.04f && !hasFlag(FLAG_NO_FUEL)) {
            Vec3 back = forward().scale(-1.0);
            Vec3 motion = velocity().scale(ShipFlight.DT);
            for (Vec3 engine : design.engines()) {
                Vec3 p = toWorld(engine);
                int count = boost ? 3 : 1 + (thrust > 0.5f ? 1 : 0);
                for (int i = 0; i < count; i++) {
                    double spread = 0.15;
                    double speed = 0.4 + thrust * (boost ? 1.4 : 0.8);
                    level().addParticle(boost ? ParticleTypes.FLAME : ParticleTypes.SOUL_FIRE_FLAME,
                            p.x + random.nextGaussian() * spread, p.y + random.nextGaussian() * spread, p.z + random.nextGaussian() * spread,
                            motion.x + back.x * speed, motion.y + back.y * speed, motion.z + back.z * speed);
                }
                if (random.nextFloat() < 0.25f && !SpaceSystem.isSpace(level())) {
                    level().addParticle(ParticleTypes.CLOUD, p.x, p.y, p.z, motion.x + back.x * 0.2, motion.y + back.y * 0.2, motion.z + back.z * 0.2);
                }
            }
        }
        if (--soundTimer <= 0 && (thrust > 0.04f || getPassengers().size() > 0)) {
            soundTimer = 30;
            level().playLocalSound(getX(), getY(), getZ(), SoundEvents.BEACON_AMBIENT, SoundSource.NEUTRAL,
                    0.4f + thrust * 0.8f, 0.5f + thrust * 0.5f + (boost ? 0.3f : 0.0f), false);
        }
    }

    // ───────────────────────────── Sauvegarde et réseau ─────────────────────────────

    private void saveResources(CompoundTag tag) {
        tag.putFloat("Hull", hull());
        tag.putFloat("Shield", shield());
        tag.putFloat("Energy", energy());
        tag.putFloat("Fuel", fuel());
        tag.putFloat("QuantumFuel", quantumFuel());
        tag.putInt("Missiles", missiles());
        tag.putByte("Flags", (byte) (entityData.get(DATA_FLAGS) & (FLAG_ASSIST | FLAG_HOSTILE)));
        tag.putByte("Power", entityData.get(DATA_POWER));
    }

    private void loadResources(CompoundTag tag) {
        entityData.set(DATA_HULL, tag.getFloat("Hull"));
        entityData.set(DATA_SHIELD, tag.getFloat("Shield"));
        entityData.set(DATA_ENERGY, tag.getFloat("Energy"));
        entityData.set(DATA_FUEL, tag.getFloat("Fuel"));
        entityData.set(DATA_QUANTUM_FUEL, tag.getFloat("QuantumFuel"));
        entityData.set(DATA_MISSILES, tag.getInt("Missiles"));
        entityData.set(DATA_FLAGS, tag.getByte("Flags"));
        entityData.set(DATA_POWER, tag.getByte("Power"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (design == null) {
            return;
        }
        tag.putString("Design", design.id().toString());
        saveResources(tag);
        Quaterniond q = orientation();
        tag.putDouble("QX", q.x);
        tag.putDouble("QY", q.y);
        tag.putDouble("QZ", q.z);
        tag.putDouble("QW", q.w);
        if (flight != null) {
            tag.putDouble("VX", flight.velocity.x);
            tag.putDouble("VY", flight.velocity.y);
            tag.putDouble("VZ", flight.velocity.z);
        }
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        if (!cargo.isEmpty()) {
            net.minecraft.core.NonNullList<ItemStack> items = net.minecraft.core.NonNullList.withSize(cargo.getContainerSize(), ItemStack.EMPTY);
            for (int i = 0; i < items.size(); i++) {
                items.set(i, cargo.getItem(i));
            }
            tag.put("Cargo", net.minecraft.world.ContainerHelper.saveAllItems(new CompoundTag(), items, registryAccess()));
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        ShipDesigns.Loaded loaded = ShipDesigns.get(ResourceLocation.parse(tag.getString("Design")));
        if (loaded == null) {
            return;
        }
        setDesign(loaded.design(), loaded.structure());
        loadResources(tag);
        setOrientation(new Quaterniond(tag.getDouble("QX"), tag.getDouble("QY"), tag.getDouble("QZ"), tag.getDouble("QW")).normalize());
        flight.velocity.set(tag.getDouble("VX"), tag.getDouble("VY"), tag.getDouble("VZ"));
        if (tag.hasUUID("Owner")) {
            owner = tag.getUUID("Owner");
        }
        if (tag.contains("Cargo")) {
            net.minecraft.core.NonNullList<ItemStack> items = net.minecraft.core.NonNullList.withSize(cargo.getContainerSize(), ItemStack.EMPTY);
            net.minecraft.world.ContainerHelper.loadAllItems(tag.getCompound("Cargo"), items, registryAccess());
            for (int i = 0; i < items.size(); i++) {
                cargo.setItem(i, items.get(i));
            }
        }
        if (loaded.design().hostile()) {
            brain = new PirateBrain();
            setFlag(FLAG_HOSTILE, true);
        }
    }

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buf) {
        design.write(buf);
        structure.write(buf);
        Quaterniond q = orientation();
        buf.writeDouble(q.x);
        buf.writeDouble(q.y);
        buf.writeDouble(q.z);
        buf.writeDouble(q.w);
        buf.writeFloat((float) flight.velocity.x);
        buf.writeFloat((float) flight.velocity.y);
        buf.writeFloat((float) flight.velocity.z);
        buf.writeVarInt(teleportId);
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buf) {
        ShipDesign design = ShipDesign.read(buf);
        ShipStructure structure = ShipStructure.read(buf);
        setDesign(design, structure);
        setOrientation(new Quaterniond(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble()).normalize());
        flight.velocity.set(buf.readFloat(), buf.readFloat(), buf.readFloat());
        clientTeleportId = buf.readVarInt();
        setBoundingBox(makeBoundingBox());
    }

    @Override
    public void restoreFrom(Entity entity) {
        super.restoreFrom(entity);
        if (entity instanceof ShipEntity other) {
            this.teleportId = other.teleportId;
            this.owner = other.owner;
        }
    }

    @Override
    public boolean shouldBeSaved() {
        return super.shouldBeSaved() && design != null;
    }

    @Override
    public Component getDisplayName() {
        return design == null ? super.getDisplayName() : Component.translatable(design.translationKey());
    }
}
