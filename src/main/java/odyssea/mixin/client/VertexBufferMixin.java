package odyssea.mixin.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import odyssea.client.render.LazySectionBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Création différée des objets OpenGL, uniquement pour les tampons construits par {@link LazySectionBuffers}
 * (sections de terrain). Tous les autres {@link VertexBuffer} gardent le comportement vanilla.
 *
 * <p>Un tampon en attente a des identifiants à {@link #PENDING} : ni −1 (fermé, {@code isInvalid()}), ni un
 * identifiant OpenGL valide (≥ 0). Il est matérialisé au premier {@code bind()}, {@code upload()} ou
 * {@code uploadIndexBuffer()}, toujours appelés sur le thread de rendu.
 */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferMixin {
    @Unique
    private static final int PENDING = -2;

    @Shadow
    private int vertexBufferId;

    @Shadow
    private int indexBufferId;

    @Shadow
    private int arrayObjectId;

    @Unique
    private boolean odyssea$pending;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;assertOnRenderThread()V"))
    private void odyssea$allowWorkerThread() {
        if (!LazySectionBuffers.isCreatingLazy()) {
            RenderSystem.assertOnRenderThread();
        }
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenBuffers()I"))
    private int odyssea$deferBuffer() {
        if (LazySectionBuffers.isCreatingLazy()) {
            odyssea$pending = true;
            return PENDING;
        }
        return GlStateManager._glGenBuffers();
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenVertexArrays()I"))
    private int odyssea$deferVertexArray() {
        if (LazySectionBuffers.isCreatingLazy()) {
            odyssea$pending = true;
            return PENDING;
        }
        return GlStateManager._glGenVertexArrays();
    }

    @Inject(method = {"bind", "upload", "uploadIndexBuffer"}, at = @At("HEAD"))
    private void odyssea$materialize(CallbackInfo ci) {
        if (odyssea$pending) {
            RenderSystem.assertOnRenderThread();
            odyssea$pending = false;
            vertexBufferId = GlStateManager._glGenBuffers();
            indexBufferId = GlStateManager._glGenBuffers();
            arrayObjectId = GlStateManager._glGenVertexArrays();
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void odyssea$closePending(CallbackInfo ci) {
        if (odyssea$pending) {
            // Rien à libérer côté OpenGL ; −1 rend le tampon invalide comme un tampon vanilla fermé.
            odyssea$pending = false;
            vertexBufferId = -1;
            indexBufferId = -1;
            arrayObjectId = -1;
        }
    }
}
