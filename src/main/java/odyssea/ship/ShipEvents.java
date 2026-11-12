package odyssea.ship;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.EntityMountEvent;

/** Événements communs liés aux vaisseaux. */
public final class ShipEvents {
    private ShipEvents() {
    }

    /**
     * S'accroupir sert à descendre en vol : la sortie vanilla par la touche « s'accroupir » est donc annulée, on
     * quitte son siège avec la touche dédiée. Les autres causes (mort, déconnexion, changement de dimension) passent.
     */
    public static void onMount(EntityMountEvent event) {
        if (!event.isDismounting() || !(event.getEntityBeingMounted() instanceof ShipEntity ship)) {
            return;
        }
        Entity rider = event.getEntityMounting();
        if (rider instanceof Player player && player.isAlive() && player.isShiftKeyDown() && !ship.allowsDismount()) {
            event.setCanceled(true);
        }
    }
}
