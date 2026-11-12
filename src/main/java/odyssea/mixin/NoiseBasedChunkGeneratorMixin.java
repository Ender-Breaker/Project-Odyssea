package odyssea.mixin;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla fixe la nappe de lave globale à Y = −54, soit le fond du monde vanilla (−64) + 10. Dans un monde
 * plus profond, tout océan descendant sous −54 se remplirait de lave. On garde la même règle relative au fond
 * du bruit (min_y + 10), uniquement pour les réglages qui descendent sous −64 : le vanilla reste identique.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    private static final int VANILLA_MIN_Y = -64;
    private static final int LAVA_ABOVE_BOTTOM = 10;

    @Inject(method = "createFluidPicker", at = @At("HEAD"), cancellable = true)
    private static void odyssea$deepWorldFluidPicker(NoiseGeneratorSettings settings, CallbackInfoReturnable<Aquifer.FluidPicker> cir) {
        int minY = settings.noiseSettings().minY();
        if (minY >= VANILLA_MIN_Y) {
            return;
        }
        int lavaLevel = minY + LAVA_ABOVE_BOTTOM;
        int seaLevel = settings.seaLevel();
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(lavaLevel, Blocks.LAVA.defaultBlockState());
        Aquifer.FluidStatus sea = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        int threshold = Math.min(lavaLevel, seaLevel);
        cir.setReturnValue((x, y, z) -> y < threshold ? lava : sea);
    }
}
