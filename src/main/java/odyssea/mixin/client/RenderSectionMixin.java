package odyssea.mixin.client;

import com.mojang.blaze3d.vertex.VertexBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collector;
import java.util.stream.Stream;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import odyssea.client.render.LazySectionBuffers;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Une section de rendu ne crée plus ses 5 tampons à la construction : chaque couche obtient le sien au premier
 * besoin (voir {@link LazySectionBuffers}). La table est concurrente : {@code getBuffer} est appelé à la fois par les
 * threads de compilation des sections et par le thread de rendu.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMixin {
    @Shadow
    @Final
    private Map<RenderType, VertexBuffer> buffers;

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Ljava/util/stream/Stream;collect(Ljava/util/stream/Collector;)Ljava/lang/Object;"))
    private Object odyssea$noEagerBuffers(Stream<?> layers, Collector<?, ?, ?> collector) {
        return new ConcurrentHashMap<RenderType, VertexBuffer>(4);
    }

    @Inject(method = "getBuffer", at = @At("HEAD"), cancellable = true)
    private void odyssea$lazyBuffer(RenderType layer, CallbackInfoReturnable<VertexBuffer> cir) {
        cir.setReturnValue(buffers.computeIfAbsent(layer, LazySectionBuffers::create));
    }
}
