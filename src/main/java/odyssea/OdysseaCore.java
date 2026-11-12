package odyssea;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import odyssea.atmosphere.Airtightness;
import odyssea.atmosphere.AtmosphereManager;
import odyssea.horizon.HorizonSampler;
import odyssea.network.OdysseaNetwork;
import odyssea.pipenetwork.GasFluidNetwork;
import odyssea.registry.OdysseaBlocks;
import odyssea.registry.OdysseaEntities;
import odyssea.registry.OdysseaItems;
import odyssea.ship.ShipDesigns;
import odyssea.ship.ShipEvents;
import odyssea.ship.ShipRegistry;
import odyssea.space.SpaceEncounters;
import odyssea.suit.SuitHandler;
import odyssea.worldgen.OdysseaWorldgen;
import odyssea.worldgen.preview.PreviewCommand;
import odyssea.worldgen.preview.PreviewOnStartup;

@Mod(OdysseaCore.MOD_ID)
public final class OdysseaCore {
    public static final String MOD_ID = "odyssea";

    public OdysseaCore(IEventBus modBus, ModContainer container) {
        OdysseaWorldgen.register(modBus);
        OdysseaBlocks.register(modBus);
        OdysseaItems.register(modBus);
        OdysseaEntities.register(modBus);
        SpaceEncounters.bootstrap();
        modBus.addListener(OdysseaNetwork::register);
        NeoForge.EVENT_BUS.addListener(OdysseaCore::onLevelTick);
        NeoForge.EVENT_BUS.addListener(OdysseaCore::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(OdysseaCore::onServerStopped);
        NeoForge.EVENT_BUS.addListener(OdysseaCore::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(OdysseaCore::onLogout);
        NeoForge.EVENT_BUS.addListener(PreviewOnStartup::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ShipDesigns::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(ShipEvents::onMount);
        NeoForge.EVENT_BUS.addListener(ShipRegistry::onJoin);
        NeoForge.EVENT_BUS.addListener(ShipRegistry::onLeave);
        NeoForge.EVENT_BUS.addListener(SuitHandler::onPlayerTick);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            // Le réseau d'abord : les diffuseurs injectent l'air avant l'intégration des fuites.
            GasFluidNetwork.get(level).tick(level);
            AtmosphereManager.get(level).tick(level);
            SpaceEncounters.tick(level);
            HorizonSampler.tick(level);
        }
    }

    private static void onTagsUpdated(TagsUpdatedEvent event) {
        Airtightness.invalidateCache();
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        AtmosphereManager.shutdownWorkers();
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        HorizonSampler.forget(event.getEntity().getUUID());
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        PreviewCommand.register(event.getDispatcher());
    }
}
