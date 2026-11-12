package odyssea.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import odyssea.OdysseaCore;
import odyssea.client.ship.ShipControls;
import odyssea.client.ship.ShipHud;
import odyssea.client.ship.ShipMeshCache;
import odyssea.client.ship.ShipProjectileRenderer;
import odyssea.client.ship.ShipRenderer;
import odyssea.client.ship.ShipWorldRenderer;
import odyssea.registry.OdysseaEntities;
import odyssea.render.veil.AtmosphereRenderPass;

@Mod(value = OdysseaCore.MOD_ID, dist = Dist.CLIENT)
public final class OdysseaClient {
    public OdysseaClient(IEventBus modBus) {
        modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(AtmosphereRenderPass::register));
        modBus.addListener(RegisterDimensionSpecialEffectsEvent.class, PlanetEnvironment::registerEffects);
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, OdysseaClient::registerRenderers);
        modBus.addListener(RegisterKeyMappingsEvent.class, ShipControls::registerKeys);
        modBus.addListener(RegisterGuiLayersEvent.class,
                event -> event.registerAbove(VanillaGuiLayers.CROSSHAIR, OdysseaCore.id("ship_hud"), new ShipHud()));
        NeoForge.EVENT_BUS.addListener(ViewportEvent.RenderFog.class, PlanetEnvironment::onRenderFog);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeFogColor.class, PlanetEnvironment::onComputeFogColor);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeFov.class, ShipControls::onComputeFov);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Pre.class, ShipControls::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, ShipControls::onClientTickPost);
        NeoForge.EVENT_BUS.addListener(InputEvent.InteractionKeyMappingTriggered.class, ShipControls::onInteraction);
        NeoForge.EVENT_BUS.addListener(InputEvent.MouseScrollingEvent.class, ShipControls::onScroll);
        NeoForge.EVENT_BUS.addListener(CalculateDetachedCameraDistanceEvent.class, ShipControls::onCameraDistance);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, ShipWorldRenderer::onRenderStage);
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> {
            ShipMeshCache.clear();
            HorizonClient.clear();
        });
        if (ClientSmokeTest.enabled()) {
            NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, ClientSmokeTest::onClientTick);
            NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientChatReceivedEvent.System.class,
                    event -> com.mojang.logging.LogUtils.getLogger().info("[Odyssea smoke] message : {}", event.getMessage().getString()));
        }
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(OdysseaEntities.SHIP.get(), ShipRenderer::new);
        event.registerEntityRenderer(OdysseaEntities.SHIP_PROJECTILE.get(), ShipProjectileRenderer::new);
    }
}
