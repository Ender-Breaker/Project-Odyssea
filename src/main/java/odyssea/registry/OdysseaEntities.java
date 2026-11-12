package odyssea.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import odyssea.OdysseaCore;
import odyssea.ship.ShipEntity;
import odyssea.ship.ShipProjectile;

public final class OdysseaEntities {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, OdysseaCore.MOD_ID);

    /** Suivi à 24 tronçons (384 blocs) et mis à jour à chaque tick : les vaisseaux vont vite et se voient de loin. */
    public static final DeferredHolder<EntityType<?>, EntityType<ShipEntity>> SHIP = ENTITIES.register("ship",
            () -> EntityType.Builder.<ShipEntity>of(ShipEntity::new, MobCategory.MISC)
                    .sized(2.0f, 2.0f).clientTrackingRange(24).updateInterval(1).fireImmune()
                    .build(OdysseaCore.id("ship").toString()));

    public static final DeferredHolder<EntityType<?>, EntityType<ShipProjectile>> SHIP_PROJECTILE = ENTITIES.register("ship_projectile",
            () -> EntityType.Builder.<ShipProjectile>of(ShipProjectile::new, MobCategory.MISC)
                    .sized(0.3f, 0.3f).clientTrackingRange(16).updateInterval(2).fireImmune().noSave()
                    .build(OdysseaCore.id("ship_projectile").toString()));

    private OdysseaEntities() {
    }

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
    }
}
