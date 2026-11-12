package odyssea.client.ship;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;
import odyssea.ship.ShipProjectile;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Trait laser lumineux (additif) étiré dans le sens du tir ; missile : corps sombre et flamme de propulsion. */
public class ShipProjectileRenderer extends EntityRenderer<ShipProjectile> {
    public ShipProjectileRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ShipProjectile projectile, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
    }

    @Override
    public boolean shouldRender(ShipProjectile projectile, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z) {
        return false;
    }

    /** Dessiné par {@link ShipWorldRenderer} (voir {@link ShipRenderer#render}). */
    static void renderProjectile(ShipProjectile projectile, PoseStack poseStack, MultiBufferSource buffers) {
        Vec3 velocity = projectile.getDeltaMovement();
        double speed = velocity.length();
        if (speed < 1.0e-4) {
            return;
        }
        Vector3f dir = new Vector3f((float) (velocity.x / speed), (float) (velocity.y / speed), (float) (velocity.z / speed));
        int color = projectile.color();
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        VertexConsumer consumer = buffers.getBuffer(RenderType.lightning());
        Matrix4f pose = poseStack.last().pose();
        if (projectile.kind() == ShipProjectile.BOLT) {
            float length = (float) Math.min(6.0, speed * 0.5);
            beam(consumer, pose, dir, length, 0.09f, r, g, b, 0.9f);
            beam(consumer, pose, dir, length * 1.1f, 0.22f, r, g, b, 0.25f);
        } else {
            beam(consumer, pose, dir, 1.4f, 0.16f, 0.25f, 0.25f, 0.28f, 1.0f);
            Vector3f back = new Vector3f(dir).mul(-1.4f);
            poseStack.pushPose();
            poseStack.translate(back.x, back.y, back.z);
            beam(consumer, poseStack.last().pose(), dir, 1.2f, 0.2f, r, g, b, 0.8f);
            poseStack.popPose();
        }
    }

    /** Prisme carré de {@code length} blocs derrière l'origine, aligné sur {@code dir}. */
    private static void beam(VertexConsumer consumer, Matrix4f pose, Vector3f dir, float length, float half, float r, float g, float b, float a) {
        Vector3f up = Math.abs(dir.y) > 0.9f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f side = new Vector3f(dir).cross(up).normalize().mul(half);
        Vector3f top = new Vector3f(side).cross(dir).normalize().mul(half);
        Vector3f tail = new Vector3f(dir).mul(-length);
        Vector3f[] corners = {
                new Vector3f(side).add(top), new Vector3f(side).sub(top),
                new Vector3f(side).negate().sub(top), new Vector3f(side).negate().add(top)};
        for (int i = 0; i < 4; i++) {
            Vector3f c0 = corners[i];
            Vector3f c1 = corners[(i + 1) % 4];
            consumer.addVertex(pose, c0.x, c0.y, c0.z).setColor(r, g, b, a);
            consumer.addVertex(pose, c1.x, c1.y, c1.z).setColor(r, g, b, a);
            consumer.addVertex(pose, c1.x + tail.x, c1.y + tail.y, c1.z + tail.z).setColor(r, g, b, a * 0.3f);
            consumer.addVertex(pose, c0.x + tail.x, c0.y + tail.y, c0.z + tail.z).setColor(r, g, b, a * 0.3f);
        }
    }

    @Override
    public ResourceLocation getTextureLocation(ShipProjectile projectile) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
