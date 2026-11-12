package odyssea.client.ship;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import odyssea.ship.ShipEntity;
import odyssea.ship.ShipStructure;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * Dessine un vaisseau : son maillage précalculé (une passe par couche de rendu : opaque, découpée, translucide),
 * transformé par l'orientation interpolée, puis les blocs à rendu d'entité (coffres, têtes, boîtes de Shulker…) et,
 * après un impact, la bulle du bouclier.
 */
public class ShipRenderer extends EntityRenderer<ShipEntity> {
    private static final Map<BlockState, BlockEntity> BLOCK_ENTITIES = new HashMap<>();

    public ShipRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0f;
    }

    /**
     * Le rendu vanilla des entités exige que la section de terrain sous l'entité soit compilée, ce qui échoue dans le
     * vide (espace, haute altitude) : les vaisseaux sont dessinés par {@link ShipWorldRenderer}, pas ici.
     */
    @Override
    public void render(ShipEntity ship, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
    }

    @Override
    public boolean shouldRender(ShipEntity ship, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z) {
        return false;
    }

    /** @param poseStack translation caméra → pivot du vaisseau déjà appliquée */
    static void renderShip(ShipEntity ship, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, Matrix4f view) {
        ShipStructure structure = ship.structure();
        if (structure == null) {
            return;
        }
        ShipMeshCache.Entry mesh = ShipMeshCache.get(structure);
        if (mesh == null) {
            return;
        }
        poseStack.pushPose();
        poseStack.mulPose(ship.renderOrientation(partialTick));
        Vector3d pivot = ship.pivot();
        poseStack.translate(-pivot.x, -pivot.y, -pivot.z);

        Matrix4f modelView = new Matrix4f(view).mul(poseStack.last().pose());
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        for (Map.Entry<RenderType, VertexBuffer> entry : mesh.buffers().entrySet()) {
            RenderType type = entry.getKey();
            type.setupRenderState();
            ShaderInstance shader = RenderSystem.getShader();
            if (shader != null) {
                if (shader.CHUNK_OFFSET != null) {
                    shader.CHUNK_OFFSET.set(0.0f, 0.0f, 0.0f);
                }
                VertexBuffer buffer = entry.getValue();
                buffer.bind();
                buffer.drawWithShader(modelView, projection, shader);
            }
            type.clearRenderState();
        }
        VertexBuffer.unbind();

        BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        for (BlockPos pos : mesh.animatedBlocks()) {
            BlockState state = structure.get(pos.getX(), pos.getY(), pos.getZ());
            BlockEntity blockEntity = blockEntity(state);
            if (blockEntity == null) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
            try {
                dispatcher.renderItem(blockEntity, poseStack, buffers, light, OverlayTexture.NO_OVERLAY);
            } catch (RuntimeException ignored) {
                // Un rendu d'entité de bloc qui exige un monde : on s'en passe.
            }
            poseStack.popPose();
        }

        int sinceHit = ship.ticksSinceShieldHit();
        if (sinceHit >= 0 && sinceHit < 10 && ship.shield() > 0.0f) {
            renderShield(poseStack, buffers, structure, (10 - sinceHit - partialTick) / 10.0f);
        }
        poseStack.popPose();
    }

    private static BlockEntity blockEntity(BlockState state) {
        if (state.getBlock() instanceof BedBlock || !(state.getBlock() instanceof EntityBlock entityBlock)) {
            return null;
        }
        return BLOCK_ENTITIES.computeIfAbsent(state, s -> entityBlock.newBlockEntity(BlockPos.ZERO, s));
    }

    /** Ellipsoïde translucide autour de la coque, qui s'estompe en 10 ticks. */
    private static void renderShield(PoseStack poseStack, MultiBufferSource buffers, ShipStructure structure, float strength) {
        VertexConsumer consumer = buffers.getBuffer(RenderType.lightning());
        float cx = structure.sizeX() / 2.0f;
        float cy = structure.sizeY() / 2.0f;
        float cz = structure.sizeZ() / 2.0f;
        float rx = cx * 1.25f + 1.5f;
        float ry = cy * 1.4f + 1.5f;
        float rz = cz * 1.2f + 1.5f;
        Matrix4f pose = poseStack.last().pose();
        int rings = 12;
        int segments = 24;
        float alpha = 0.35f * Mth.clamp(strength, 0.0f, 1.0f);
        for (int i = 0; i < rings; i++) {
            float t0 = (float) Math.PI * i / rings;
            float t1 = (float) Math.PI * (i + 1) / rings;
            for (int j = 0; j < segments; j++) {
                float p0 = (float) Math.PI * 2.0f * j / segments;
                float p1 = (float) Math.PI * 2.0f * (j + 1) / segments;
                vertex(consumer, pose, cx, cy, cz, rx, ry, rz, t0, p0, alpha);
                vertex(consumer, pose, cx, cy, cz, rx, ry, rz, t1, p0, alpha);
                vertex(consumer, pose, cx, cy, cz, rx, ry, rz, t1, p1, alpha);
                vertex(consumer, pose, cx, cy, cz, rx, ry, rz, t0, p1, alpha);
            }
        }
    }

    private static void vertex(VertexConsumer consumer, Matrix4f pose, float cx, float cy, float cz, float rx, float ry, float rz,
                               float theta, float phi, float alpha) {
        float s = Mth.sin(theta);
        float x = cx + rx * s * Mth.cos(phi);
        float y = cy + ry * Mth.cos(theta);
        float z = cz + rz * s * Mth.sin(phi);
        consumer.addVertex(pose, x, y, z).setColor(0.35f, 0.75f, 1.0f, alpha);
    }

    @Override
    public ResourceLocation getTextureLocation(ShipEntity ship) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
