package odyssea.space;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/** Destination quantique choisie par chaque joueur sur la carte du système. */
public final class Navigation {
    private static final Map<UUID, String> DESTINATIONS = new ConcurrentHashMap<>();
    /** Copie locale côté client (affichage du repère de navigation). */
    private static volatile String clientDestination = "";

    private Navigation() {
    }

    public static void setDestination(ServerPlayer player, String bodyId) {
        if (bodyId.isEmpty() || SpaceSystem.body(bodyId) == null) {
            DESTINATIONS.remove(player.getUUID());
            return;
        }
        DESTINATIONS.put(player.getUUID(), bodyId);
        SpaceSystem.Body body = SpaceSystem.body(bodyId);
        player.displayClientMessage(Component.translatable("odyssea.nav.destination_set",
                Component.translatable(body.translationKey())).withStyle(ChatFormatting.AQUA), true);
    }

    public static @Nullable SpaceSystem.Body destination(Player player) {
        String id = DESTINATIONS.get(player.getUUID());
        return id == null ? null : SpaceSystem.body(id);
    }

    public static void setClientDestination(String bodyId) {
        clientDestination = bodyId;
    }

    public static @Nullable SpaceSystem.Body clientDestination() {
        return clientDestination.isEmpty() ? null : SpaceSystem.body(clientDestination);
    }
}
