package odyssea.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import odyssea.render.veil.AtmosphereRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sur un corps sans atmosphère, rien ne diffuse la lumière du jour : les étoiles restent visibles. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelStarsMixin {
    @Inject(method = "getStarBrightness", at = @At("RETURN"), cancellable = true)
    private void odyssea$starsInDaylight(float partialTick, CallbackInfoReturnable<Float> cir) {
        float floor = AtmosphereRenderPass.dayStarBrightness();
        if (floor > cir.getReturnValueF()) {
            cir.setReturnValue(floor);
        }
    }
}
