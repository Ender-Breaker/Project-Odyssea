package odyssea.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import odyssea.OdysseaCore;
import odyssea.render.veil.AtmosphereRenderPass;

@Mod(value = OdysseaCore.MOD_ID, dist = Dist.CLIENT)
public final class OdysseaClient {
    public OdysseaClient(IEventBus modBus) {
        modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(AtmosphereRenderPass::register));
    }
}
