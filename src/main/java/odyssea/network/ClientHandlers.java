package odyssea.network;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import odyssea.client.HorizonClient;
import odyssea.ship.ShipEntity;

/** Traitement des paquets côté client. Cette classe n'est chargée que sur le client, au premier paquet reçu. */
final class ClientHandlers {
    private ClientHandlers() {
    }

    static void teleport(OdysseaNetwork.ShipTeleport payload) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Entity entity = level.getEntity(payload.ship());
        if (entity instanceof ShipEntity ship) {
            ship.applyServerTeleport(payload);
        }
    }

    static void horizon(OdysseaNetwork.Horizon payload) {
        HorizonClient.accept(payload);
    }
}
