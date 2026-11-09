package odyssea;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import odyssea.atmosphere.Airtightness;
import odyssea.atmosphere.AtmosphereManager;
import odyssea.pipenetwork.GasFluidNetwork;

@Mod(OdysseaCore.MOD_ID)
public final class OdysseaCore {
    public static final String MOD_ID = "odyssea";

    public OdysseaCore(IEventBus modBus, ModContainer container) {
        NeoForge.EVENT_BUS.addListener(OdysseaCore::onLevelTick);
        NeoForge.EVENT_BUS.addListener(OdysseaCore::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(OdysseaCore::onServerStopped);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            // Le réseau d'abord : les diffuseurs injectent l'air avant l'intégration des fuites.
            GasFluidNetwork.get(level).tick(level);
            AtmosphereManager.get(level).tick(level);
        }
    }

    private static void onTagsUpdated(TagsUpdatedEvent event) {
        Airtightness.invalidateCache();
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        AtmosphereManager.shutdownWorkers();
    }
}
