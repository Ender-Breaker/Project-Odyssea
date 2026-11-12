package odyssea.ship;

import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import odyssea.OdysseaCore;
import odyssea.registry.OdysseaEntities;
import org.jetbrains.annotations.Nullable;

/**
 * Tir de vaisseau : trait laser (rectiligne, très rapide) ou missile (guidé vers la cible verrouillée, explose).
 * Les collisions avec les vaisseaux suivent la coque réelle, bloc par bloc, et non leur boîte englobante.
 */
public class ShipProjectile extends Entity implements IEntityWithComplexSpawn {
    public static final ResourceKey<DamageType> LASER = ResourceKey.create(Registries.DAMAGE_TYPE, OdysseaCore.id("laser"));
    private static final EntityDataAccessor<Byte> DATA_KIND = SynchedEntityData.defineId(ShipProjectile.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_COLOR = SynchedEntityData.defineId(ShipProjectile.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_TARGET = SynchedEntityData.defineId(ShipProjectile.class, EntityDataSerializers.INT);
    public static final byte BOLT = 0;
    public static final byte MISSILE = 1;
    private static final int BOLT_LIFE = 60;
    private static final int MISSILE_LIFE = 220;
    private static final double MISSILE_SPEED = 9.0;
    private static final double MISSILE_TURN = 0.12;

    private @Nullable UUID shipId;
    private @Nullable UUID shooterId;
    private float damage;
    private float explosion;
    private int life;

    public ShipProjectile(EntityType<? extends ShipProjectile> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    private static ShipProjectile create(ServerLevel level, ShipEntity ship, @Nullable Entity shooter, Vec3 position, Vec3 velocityPerSecond,
                                         byte kind, float damage) {
        ShipProjectile projectile = new ShipProjectile(OdysseaEntities.SHIP_PROJECTILE.get(), level);
        projectile.setPos(position);
        projectile.setDeltaMovement(velocityPerSecond.scale(ShipFlight.DT));
        projectile.shipId = ship.getUUID();
        projectile.shooterId = shooter != null ? shooter.getUUID() : null;
        projectile.damage = damage;
        projectile.entityData.set(DATA_KIND, kind);
        return projectile;
    }

    public static ShipProjectile bolt(ServerLevel level, ShipEntity ship, @Nullable Entity shooter, Vec3 position, Vec3 velocity,
                                      float damage, int color) {
        ShipProjectile bolt = create(level, ship, shooter, position, velocity, BOLT, damage);
        bolt.entityData.set(DATA_COLOR, color);
        return bolt;
    }

    public static ShipProjectile missile(ServerLevel level, ShipEntity ship, @Nullable Entity shooter, Vec3 position, Vec3 velocity,
                                         float damage, float explosion, @Nullable Entity target) {
        ShipProjectile missile = create(level, ship, shooter, position, velocity, MISSILE, damage);
        missile.explosion = explosion;
        missile.entityData.set(DATA_COLOR, 0xFF8A2A);
        missile.entityData.set(DATA_TARGET, target == null ? -1 : target.getId());
        return missile;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_KIND, BOLT);
        builder.define(DATA_COLOR, 0xFF4030);
        builder.define(DATA_TARGET, -1);
    }

    public byte kind() {
        return entityData.get(DATA_KIND);
    }

    public int color() {
        return entityData.get(DATA_COLOR);
    }

    @Override
    public void tick() {
        life++;
        boolean missile = kind() == MISSILE;
        if (life > (missile ? MISSILE_LIFE : BOLT_LIFE)) {
            if (!level().isClientSide) {
                if (missile) {
                    explode(position());
                }
                discard();
            }
            return;
        }
        Vec3 velocity = getDeltaMovement();
        if (missile) {
            velocity = steer(velocity);
            setDeltaMovement(velocity);
        }
        Vec3 from = position();
        Vec3 to = from.add(velocity);
        if (!level().isClientSide && findAndApplyHit(from, to)) {
            return;
        }
        setPos(to);
        if (level().isClientSide && missile) {
            level().addParticle(ParticleTypes.SMOKE, from.x, from.y, from.z, 0, 0, 0);
            level().addParticle(ParticleTypes.FLAME, from.x, from.y, from.z, -velocity.x * 0.05, -velocity.y * 0.05, -velocity.z * 0.05);
        }
    }

    private Vec3 steer(Vec3 velocity) {
        double speed = Math.min(MISSILE_SPEED, velocity.length() + 0.35);
        Vec3 direction = velocity.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : velocity.normalize();
        int targetId = entityData.get(DATA_TARGET);
        Entity target = targetId < 0 ? null : level().getEntity(targetId);
        if (target != null && target.isAlive() && life > 6) {
            Vec3 desired = target.getBoundingBox().getCenter().subtract(position()).normalize();
            double angle = Math.acos(Math.clamp(direction.dot(desired), -1.0, 1.0));
            if (angle > 1.0e-4) {
                double t = Math.min(1.0, MISSILE_TURN / angle);
                direction = direction.scale(1.0 - t).add(desired.scale(t)).normalize();
            }
        }
        return direction.scale(speed);
    }

    private boolean findAndApplyHit(Vec3 from, Vec3 to) {
        BlockHitResult blockHit = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        Vec3 end = blockHit.getType() == HitResult.Type.MISS ? to : blockHit.getLocation();
        double best = from.distanceToSqr(end);
        Entity hitEntity = null;
        Vec3 hitPoint = null;

        for (ShipEntity ship : ShipRegistry.ships(level())) {
            if (ship.getUUID().equals(shipId) || !ship.getBoundingBox().inflate(1.0).intersects(new AABB(from, end))) {
                continue;
            }
            Vec3 point = ship.raycastHull(from, end);
            if (point != null && from.distanceToSqr(point) < best) {
                best = from.distanceToSqr(point);
                hitEntity = ship;
                hitPoint = point;
            }
        }
        for (Entity entity : level().getEntities(this, new AABB(from, end).inflate(1.0),
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator() && !isFriendly(e))) {
            var clip = entity.getBoundingBox().inflate(0.3).clip(from, end);
            if (clip.isPresent() && from.distanceToSqr(clip.get()) < best) {
                best = from.distanceToSqr(clip.get());
                hitEntity = entity;
                hitPoint = clip.get();
            }
        }

        if (hitEntity != null) {
            onHitEntity(hitEntity, hitPoint);
            return true;
        }
        if (blockHit.getType() != HitResult.Type.MISS) {
            onHitBlock(blockHit.getLocation());
            return true;
        }
        return false;
    }

    private boolean isFriendly(Entity entity) {
        if (entity.getUUID().equals(shooterId)) {
            return true;
        }
        Entity vehicle = entity.getVehicle();
        return vehicle != null && vehicle.getUUID().equals(shipId);
    }

    private @Nullable Entity shooter() {
        if (shooterId != null && level() instanceof ServerLevel level) {
            Entity shooter = level.getEntity(shooterId);
            if (shooter != null) {
                return shooter;
            }
        }
        if (shipId != null && level() instanceof ServerLevel level) {
            return level.getEntity(shipId);
        }
        return null;
    }

    private void onHitEntity(Entity entity, Vec3 point) {
        ServerLevel level = (ServerLevel) level();
        if (entity instanceof ShipEntity ship) {
            ship.damageShip(damage, shooter(), point);
        } else {
            DamageSource source = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(LASER),
                    this, shooter());
            entity.hurt(source, damage);
        }
        if (kind() == MISSILE) {
            explode(point);
        } else {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, point.x, point.y, point.z, 6, 0.1, 0.1, 0.1, 0.25);
        }
        discard();
    }

    private void onHitBlock(Vec3 point) {
        ServerLevel level = (ServerLevel) level();
        if (kind() == MISSILE) {
            explode(point);
        } else {
            level.sendParticles(ParticleTypes.SMALL_FLAME, point.x, point.y, point.z, 4, 0.05, 0.05, 0.05, 0.03);
            level.sendParticles(ParticleTypes.SMOKE, point.x, point.y, point.z, 2, 0.05, 0.05, 0.05, 0.01);
            if (random.nextFloat() < 0.3f) {
                level.playSound(null, point.x, point.y, point.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, 0.3f, 2.0f);
            }
        }
        discard();
    }

    private void explode(Vec3 point) {
        if (level() instanceof ServerLevel level) {
            level.explode(this, level.damageSources().explosion(this, shooter()), null, point.x, point.y, point.z,
                    Math.max(1.0f, explosion), false, Level.ExplosionInteraction.MOB);
        }
    }

    /** Les traits sont rectilignes : le client les extrapole lui-même, sans corrections intermédiaires. */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        if (kind() == MISSILE) {
            setPos(x, y, z);
        }
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 512.0 * 512.0;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        // Projectiles éphémères : ils ne sont pas sauvegardés.
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buf) {
        Vec3 v = getDeltaMovement();
        buf.writeDouble(v.x);
        buf.writeDouble(v.y);
        buf.writeDouble(v.z);
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buf) {
        setDeltaMovement(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }
}
