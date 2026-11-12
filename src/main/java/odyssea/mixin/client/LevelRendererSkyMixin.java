package odyssea.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import odyssea.render.veil.AtmosphereRenderPass;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Quand Odyssea dessine le ciel d'une dimension, le ciel vanilla est retiré, sauf les étoiles :
 * le dôme coloré est remplacé par un fond noir, la lueur du lever, le soleil et la lune ne sont pas dessinés.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSkyMixin {

    // Appel n° 0 de VertexBuffer.drawWithShader dans renderSky : le dôme (1 = étoiles, 2 = disque sombre).
    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;drawWithShader(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/ShaderInstance;)V"))
    private void odyssea$replaceSkyDome(VertexBuffer buffer, Matrix4f modelView, Matrix4f projection, ShaderInstance shader,
                                        Operation<Void> original) {
        if (AtmosphereRenderPass.replacesVanillaSky()) {
            // Le fond doit être noir : la passe d'atmosphère est composée par-dessus en alpha prémultiplié.
            AtmosphereRenderPass.clearSkyBackground();
        } else {
            original.call(buffer, modelView, projection, shader);
        }
    }

    // Les trois appels de BufferUploader.drawWithShader dans renderSky : lueur du lever, soleil, lune.
    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V"))
    private void odyssea$skipSunriseSunAndMoon(MeshData mesh, Operation<Void> original) {
        if (AtmosphereRenderPass.replacesVanillaSky()) {
            // Libère le tampon du Tesselator, sinon il grossit à chaque frame.
            mesh.close();
        } else {
            original.call(mesh);
        }
    }
}
