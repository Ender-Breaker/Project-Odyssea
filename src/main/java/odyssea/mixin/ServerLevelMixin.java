package odyssea.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import odyssea.atmosphere.AtmosphereManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    // Point d'entrée unique pour tous les setBlock serveur : casse, explosions, pistons, portes, dégâts de coque.
    @Inject(method = "onBlockStateChange", at = @At("TAIL"))
    private void odyssea$trackAtmosphere(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        AtmosphereManager.onBlockStateChange((ServerLevel) (Object) this, pos, oldState, newState);
    }
}
