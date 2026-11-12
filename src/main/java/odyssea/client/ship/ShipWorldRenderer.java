package odyssea.client.ship;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import odyssea.ship.ShipEntity;
import odyssea.ship.ShipProjectile;
import odyssea.ship.ShipRegistry;

/**
 * Passe de rendu des vaisseaux et de leurs tirs, juste après les entités vanilla. Le rendu vanilla ignore toute
 * entité dont la section de terrain n'est pas compilée : dans le vide de l'espace ou très haut dans le ciel, un
 * vaisseau disparaissait. On les dessine donc ici, avec notre propre test de distance et de champ de vision, ce qui
 * permet aussi de voir un Destroyer de 300 blocs bien au-delà de la distance d'affichage des entités.
 */
public final class ShipWorldRenderer {
    private ShipWorldRenderer() {
    }

    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        boolean drew = false;
        for (ShipEntity ship : ShipRegistry.ships(level)) {
            double x = Mth.lerp(partialTick, ship.xo, ship.getX());
            double y = Mth.lerp(partialTick, ship.yo, ship.getY());
            double z = Mth.lerp(partialTick, ship.zo, ship.getZ());
            double distanceSq = (x - cam.x) * (x - cam.x) + (y - cam.y) * (y - cam.y) + (z - cam.z) * (z - cam.z);
            if (!ship.shouldRenderAtSqrDistance(distanceSq) || !event.getFrustum().isVisible(ship.getBoundingBoxForCulling())) {
                continue;
            }
            PoseStack poseStack = new PoseStack();
            poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(x, y, z));
            ShipRenderer.renderShip(ship, partialTick, poseStack, buffers, light, event.getModelViewMatrix());
            drew = true;
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ShipProjectile projectile)) {
                continue;
            }
            double x = Mth.lerp(partialTick, projectile.xo, projectile.getX());
            double y = Mth.lerp(partialTick, projectile.yo, projectile.getY());
            double z = Mth.lerp(partialTick, projectile.zo, projectile.getZ());
            if ((x - cam.x) * (x - cam.x) + (y - cam.y) * (y - cam.y) + (z - cam.z) * (z - cam.z) > 512.0 * 512.0) {
                continue;
            }
            PoseStack poseStack = new PoseStack();
            poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
            ShipProjectileRenderer.renderProjectile(projectile, poseStack, buffers);
            drew = true;
        }
        if (drew) {
            buffers.endBatch();
        }
    }
}
