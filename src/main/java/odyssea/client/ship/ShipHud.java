package odyssea.client.ship;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;
import odyssea.ship.ShipDesign;
import odyssea.ship.ShipEntity;
import odyssea.ship.ShipRegistry;
import odyssea.space.Navigation;
import odyssea.space.SpaceSystem;
import odyssea.suit.SpaceSuitItem;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Affichage tête haute du vaisseau et jauge d'oxygène de la combinaison. */
public final class ShipHud implements LayeredDraw.Layer {
    private static final int PANEL = 0x90101418;
    private static final int TEXT = 0xFFD8F4FF;
    private static final int DIM = 0xFF7FA6B8;
    private static final int ACCENT = 0xFF4FD8FF;
    private static final int WARN = 0xFFFF5040;

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.hideGui) {
            return;
        }
        float partial = delta.getGameTimeDeltaPartialTick(false);
        if (player.getVehicle() instanceof ShipEntity ship && ship.design() != null) {
            renderShip(graphics, minecraft, ship, ship.pilot() == player, partial);
        } else if (SpaceSuitItem.isFullSuit(player)) {
            renderOxygen(graphics, minecraft, SpaceSuitItem.oxygen(player.getItemBySlot(EquipmentSlot.CHEST)));
        }
    }

    // ───────────────────────────── Vaisseau ─────────────────────────────

    private void renderShip(GuiGraphics g, Minecraft minecraft, ShipEntity ship, boolean pilot, float partial) {
        Font font = minecraft.font;
        int w = g.guiWidth();
        int h = g.guiHeight();
        ShipDesign.Stats stats = ship.design().stats();

        if (ship.isQuantum()) {
            renderQuantumTravel(g, minecraft, ship, partial);
        }

        // ── Jauges (bas gauche) ──
        int x0 = 8;
        int y0 = h - 92;
        g.fill(x0 - 4, y0 - 14, x0 + 132, y0 + 70, PANEL);
        g.drawString(font, Component.translatable(ship.design().translationKey()), x0, y0 - 10, ACCENT, false);
        bar(g, font, x0, y0 + 2, "odyssea.hud.hull", ship.hull() / stats.hull(), 0xFF5ADB6A, 0xFFE04030);
        bar(g, font, x0, y0 + 15, "odyssea.hud.shield", ship.shield() / Math.max(1, stats.shield()), ACCENT, ACCENT);
        bar(g, font, x0, y0 + 28, "odyssea.hud.energy", ship.energy() / Math.max(1, stats.energy()), 0xFFFFD84F, 0xFFFFD84F);
        bar(g, font, x0, y0 + 41, "odyssea.hud.fuel", ship.fuel() / Math.max(1, stats.fuel()), 0xFFFF9C3A, WARN);
        if (stats.quantumFuel() > 0) {
            bar(g, font, x0, y0 + 54, "odyssea.hud.quantum", ship.quantumFuel() / stats.quantumFuel(), 0xFFB487FF, 0xFFB487FF);
        }

        // ── Vol (haut centre) ──
        int speed = (int) Math.round(ship.speed());
        String speedText = speed + " m/s";
        Component mode = Component.translatable(ship.flightAssist() ? "odyssea.hud.assist_on" : "odyssea.hud.assist_off");
        Component power = Component.translatable(ship.powerMode().translationKey());
        int cx = w / 2;
        g.fill(cx - 90, 4, cx + 90, 30, PANEL);
        g.drawCenteredString(font, speedText, cx, 7, TEXT);
        Component status = Component.empty().append(mode).append("  ·  ").append(power);
        g.drawCenteredString(font, status, cx, 18, ship.flightAssist() ? DIM : 0xFFFFB050);
        String altitude = SpaceSystem.isSpace(ship.level()) ? "" : "Y " + (int) ship.getY();
        if (!altitude.isEmpty()) {
            g.drawString(font, altitude, cx + 94, 7, DIM, true);
        }
        if (ship.hasFlag(ShipEntity.FLAG_BOOST)) {
            g.drawString(font, Component.translatable("odyssea.hud.boost"), cx - 90 - font.width(Component.translatable("odyssea.hud.boost")) - 4, 7, 0xFFFFA040, true);
        }
        if (ship.hasFlag(ShipEntity.FLAG_NO_FUEL) && (ship.tickCount / 10) % 2 == 0) {
            g.drawCenteredString(font, Component.translatable("odyssea.hud.no_fuel"), cx, 36, WARN);
        }

        // ── Armes (bas droite) ──
        int rx = w - 120;
        g.fill(rx - 4, h - 40, w - 4, h - 8, PANEL);
        g.drawString(font, Component.translatable("odyssea.hud.missiles", ship.missiles(), stats.missileCapacity()), rx, h - 36, TEXT, false);
        Entity target = ship.lockedTarget();
        if (target != null) {
            int distance = (int) target.distanceTo(ship);
            g.drawString(font, Component.translatable("odyssea.hud.target", target.getDisplayName(), distance), rx, h - 24, WARN, false);
        } else {
            g.drawString(font, Component.translatable("odyssea.hud.no_target"), rx, h - 24, DIM, false);
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (pilot && !ship.isQuantum()) {
            // Réticule de l'axe du vaisseau : là où pointent les canons.
            Vec3 nose = camera.getPosition().add(ship.forward().scale(600.0));
            float[] p = project(camera, nose, w, h);
            if (p != null) {
                ring(g, p[0], p[1], 7.0f, 0xC04FD8FF);
                g.fill((int) p[0] - 1, (int) p[1] - 1, (int) p[0] + 1, (int) p[1] + 1, 0xFF4FD8FF);
            }
            if (target != null) {
                float[] t = project(camera, target.getBoundingBox().getCenter(), w, h);
                if (t != null) {
                    box(g, t[0], t[1], 10.0f, WARN);
                    if (target instanceof ShipEntity enemy && enemy.design() != null) {
                        float hull = enemy.hull() / enemy.design().stats().hull();
                        float shield = enemy.shield() / Math.max(1, enemy.design().stats().shield());
                        g.fill((int) t[0] - 12, (int) t[1] + 13, (int) t[0] - 12 + (int) (24 * shield), (int) t[1] + 14, ACCENT);
                        g.fill((int) t[0] - 12, (int) t[1] + 15, (int) t[0] - 12 + (int) (24 * hull), (int) t[1] + 16, 0xFF5ADB6A);
                    }
                }
            }
            renderNavigation(g, minecraft, ship, camera, w, h);
            renderSpool(g, font, ship, w, h);
            renderRadar(g, minecraft, ship, camera, w);
        }
    }

    private static void bar(GuiGraphics g, Font font, int x, int y, String label, float fraction, int color, int lowColor) {
        fraction = Mth.clamp(fraction, 0.0f, 1.0f);
        g.drawString(font, Component.translatable(label), x, y, DIM, false);
        int bx = x + 44;
        g.fill(bx, y + 2, bx + 80, y + 7, 0xFF20282E);
        g.fill(bx, y + 2, bx + Math.round(80 * fraction), y + 7, fraction < 0.25f ? lowColor : color);
    }

    private void renderNavigation(GuiGraphics g, Minecraft minecraft, ShipEntity ship, Camera camera, int w, int h) {
        SpaceSystem.Body destination = Navigation.clientDestination();
        if (destination == null) {
            return;
        }
        Font font = minecraft.font;
        Component name = Component.translatable(destination.translationKey());
        if (!SpaceSystem.isSpace(ship.level())) {
            SpaceSystem.Body here = SpaceSystem.bodyForDimension(ship.level().dimension());
            if (here != destination) {
                g.drawCenteredString(font, Component.translatable("odyssea.hud.nav_leave_atmosphere", name), w / 2, 44, 0xFFB487FF);
            }
            return;
        }
        Vec3 arrival = SpaceSystem.quantumArrival(destination, ship.position());
        if (arrival.distanceTo(ship.position()) < 3_000.0) {
            g.drawCenteredString(font, Component.translatable(destination.landable() ? "odyssea.hud.nav_arrived_land" : "odyssea.hud.nav_arrived", name),
                    w / 2, 44, 0xFF7CFF8A);
            return;
        }
        double distance = arrival.distanceTo(ship.position());
        // Le repère vise le point d'arrivée, porté à 2 km de la caméra pour la projection.
        Vec3 direction = arrival.subtract(camera.getPosition()).normalize();
        float[] p = project(camera, camera.getPosition().add(direction.scale(2000.0)), w, h);
        int color = 0xFFB487FF;
        String km = distance > 10_000 ? String.format("%.0f km", distance / 1000.0) : String.format("%.1f km", distance / 1000.0);
        int seconds = SpaceSystem.quantumTravelTicks(distance) / 20;
        double cost = SpaceSystem.quantumFuelCost(distance);
        if (p != null && p[0] > 0 && p[0] < w && p[1] > 0 && p[1] < h) {
            diamond(g, p[0], p[1], 6.0f, color);
            g.drawCenteredString(font, name, (int) p[0], (int) p[1] - 18, color);
            g.drawCenteredString(font, km, (int) p[0], (int) p[1] + 10, DIM);
        } else {
            // Hors champ : flèche au bord de l'écran.
            Vector3f local = cameraSpace(camera, camera.getPosition().add(direction));
            float angle = (float) Math.atan2(-local.y, local.x);
            float ex = w / 2.0f + Mth.cos(angle) * (w / 2.0f - 40);
            float ey = h / 2.0f + Mth.sin(angle) * (h / 2.0f - 40);
            diamond(g, ex, ey, 5.0f, color);
            g.drawCenteredString(font, name, (int) ex, (int) ey - 16, color);
        }
        SpaceSystem.Body blocker = SpaceSystem.quantumBlocker(ship.position());
        Component info = blocker != null
                ? Component.translatable("odyssea.hud.nav_blocked", Component.translatable(blocker.translationKey()))
                : Component.translatable("odyssea.hud.nav_info", km, seconds, Math.round(cost), Math.round(ship.quantumFuel()));
        g.drawCenteredString(font, info, w / 2, 44, blocker != null ? 0xFFFFB050 : DIM);
        double angleToTarget = Math.toDegrees(Math.acos(Math.clamp(ship.forward().dot(arrival.subtract(ship.position()).normalize()), -1, 1)));
        if (blocker == null && angleToTarget > 10.0) {
            g.drawCenteredString(font, Component.translatable("odyssea.hud.nav_align", (int) angleToTarget), w / 2, 56, 0xFFFFB050);
        } else if (blocker == null) {
            g.drawCenteredString(font, Component.translatable("odyssea.hud.nav_ready", Component.keybind("key.odyssea.quantum")), w / 2, 56, 0xFF7CFF8A);
        }
    }

    private void renderSpool(GuiGraphics g, Font font, ShipEntity ship, int w, int h) {
        int spool = ship.spoolTicks();
        if (spool <= 0) {
            return;
        }
        float progress = Math.min(1.0f, spool / (float) ShipEntity.SPOOL_TICKS);
        int bw = 160;
        int x = w / 2 - bw / 2;
        int y = h / 2 + 40;
        g.fill(x - 2, y - 12, x + bw + 2, y + 8, PANEL);
        g.drawCenteredString(font, Component.translatable("odyssea.hud.spool", Math.round(progress * 100)), w / 2, y - 10, 0xFFB487FF);
        g.fill(x, y + 1, x + Math.round(bw * progress), y + 5, 0xFFB487FF);
    }

    private void renderQuantumTravel(GuiGraphics g, Minecraft minecraft, ShipEntity ship, float partial) {
        int w = g.guiWidth();
        int h = g.guiHeight();
        double progress = ship.quantumProgress(partial);
        // Intensité maximale au milieu du saut.
        float intensity = (float) Math.sin(progress * Math.PI);
        g.fill(0, 0, w, h, ((int) (intensity * 70) << 24) | 0x302060);
        RandomSource random = RandomSource.create(1234L);
        float cx = w / 2.0f;
        float cy = h / 2.0f;
        float time = (ship.tickCount + partial) * 0.15f;
        VertexConsumer consumer = g.bufferSource().getBuffer(RenderType.gui());
        Matrix4f pose = g.pose().last().pose();
        for (int i = 0; i < 90; i++) {
            float angle = random.nextFloat() * Mth.TWO_PI;
            float phase = (random.nextFloat() + time * (0.5f + random.nextFloat())) % 1.0f;
            float r0 = 20.0f + phase * phase * Math.max(w, h) * 0.7f;
            float r1 = r0 + 10.0f + intensity * 60.0f * phase;
            float dx = Mth.cos(angle);
            float dy = Mth.sin(angle);
            int alpha = (int) (200 * intensity * phase);
            int color = (alpha << 24) | (random.nextFloat() < 0.3f ? 0xC8A8FF : 0xE0F0FF);
            line(consumer, pose, cx + dx * r0, cy + dy * r0, cx + dx * r1, cy + dy * r1, 1.0f + phase, color);
        }
        Vec3 to = ship.quantumTo();
        double total = ship.quantumFrom().distanceTo(to);
        double remaining = total * (1.0 - progress);
        g.drawCenteredString(minecraft.font, Component.translatable("odyssea.hud.quantum_travel",
                String.format("%.0f", remaining / 1000.0)), w / 2, h / 2 - 50, 0xFFE0D0FF);
    }

    private void renderRadar(GuiGraphics g, Minecraft minecraft, ShipEntity ship, Camera camera, int w) {
        float radius = 38.0f;
        float cx = w - radius - 10;
        float cy = radius + 10;
        double range = 800.0;
        ring(g, cx, cy, radius, 0x904FD8FF);
        ring(g, cx, cy, radius * 0.5f, 0x504FD8FF);
        g.fill((int) cx - 1, (int) cy - 1, (int) cx + 1, (int) cy + 1, ACCENT);
        float yaw = camera.getYRot() * Mth.DEG_TO_RAD;
        float sin = Mth.sin(yaw);
        float cos = Mth.cos(yaw);
        Vec3 here = ship.position();
        for (ShipEntity other : ShipRegistry.ships(ship.level())) {
            if (other != ship) {
                blip(g, cx, cy, radius, range, here, other.position(), sin, cos, other.isHostile() ? WARN : 0xFF7CFF8A, 2);
            }
        }
        for (Entity entity : ship.level().getEntities(ship, ship.getBoundingBox().inflate(range * 0.6), e -> e instanceof Enemy)) {
            blip(g, cx, cy, radius, range, here, entity.position(), sin, cos, 0xFFFFB050, 1);
        }
    }

    private static void blip(GuiGraphics g, float cx, float cy, float radius, double range, Vec3 here, Vec3 there,
                             float sin, float cos, int color, int size) {
        double dx = there.x - here.x;
        double dz = there.z - here.z;
        // Rotation dans le repère de la caméra : l'avant est en haut du radar.
        double rx = dx * cos + dz * sin;
        double rz = -dx * sin + dz * cos;
        double scale = radius / range;
        float px = (float) (-rx * scale);
        float py = (float) (-rz * scale);
        float len = Mth.sqrt(px * px + py * py);
        if (len > radius) {
            px *= radius / len;
            py *= radius / len;
        }
        g.fill((int) (cx + px) - size, (int) (cy + py) - size, (int) (cx + px) + size, (int) (cy + py) + size, color);
    }

    // ───────────────────────────── Combinaison ─────────────────────────────

    private void renderOxygen(GuiGraphics g, Minecraft minecraft, int oxygen) {
        int w = g.guiWidth();
        int h = g.guiHeight();
        float fraction = oxygen / (float) SpaceSuitItem.MAX_OXYGEN;
        int x = w / 2 + 96;
        int y = h - 20;
        int seconds = oxygen / 20;
        g.fill(x - 2, y - 2, x + 62, y + 14, PANEL);
        int color = fraction < 0.2f ? (minecraft.player.tickCount / 8 % 2 == 0 ? WARN : 0xFF802020) : ACCENT;
        g.drawString(minecraft.font, String.format("O₂ %d:%02d", seconds / 60, seconds % 60), x + 2, y, color, false);
        g.fill(x, y + 10, x + 60, y + 12, 0xFF20282E);
        g.fill(x, y + 10, x + Math.round(60 * fraction), y + 12, color);
    }

    // ───────────────────────────── Projection et tracés ─────────────────────────────

    private static Vector3f cameraSpace(Camera camera, Vec3 world) {
        Vec3 d = world.subtract(camera.getPosition());
        Vector3f look = camera.getLookVector();
        Vector3f up = camera.getUpVector();
        Vector3f left = camera.getLeftVector();
        float z = (float) (d.x * look.x + d.y * look.y + d.z * look.z);
        float x = (float) -(d.x * left.x + d.y * left.y + d.z * left.z);
        float y = (float) (d.x * up.x + d.y * up.y + d.z * up.z);
        return new Vector3f(x, y, z);
    }

    /** Point monde → écran (pixels GUI), ou null s'il est derrière la caméra. */
    static float @Nullable [] project(Camera camera, Vec3 world, int width, int height) {
        Vector3f c = cameraSpace(camera, world);
        if (c.z <= 0.05f) {
            return null;
        }
        float focal = (height / 2.0f) / (float) Math.tan(Math.toRadians(ShipControls.fov()) / 2.0);
        return new float[]{width / 2.0f + c.x / c.z * focal, height / 2.0f - c.y / c.z * focal};
    }

    private static void ring(GuiGraphics g, float cx, float cy, float r, int color) {
        VertexConsumer consumer = g.bufferSource().getBuffer(RenderType.gui());
        Matrix4f pose = g.pose().last().pose();
        int segments = 32;
        for (int i = 0; i < segments; i++) {
            float a0 = Mth.TWO_PI * i / segments;
            float a1 = Mth.TWO_PI * (i + 1) / segments;
            line(consumer, pose, cx + Mth.cos(a0) * r, cy + Mth.sin(a0) * r, cx + Mth.cos(a1) * r, cy + Mth.sin(a1) * r, 1.0f, color);
        }
    }

    private static void box(GuiGraphics g, float cx, float cy, float r, int color) {
        VertexConsumer consumer = g.bufferSource().getBuffer(RenderType.gui());
        Matrix4f pose = g.pose().last().pose();
        line(consumer, pose, cx - r, cy - r, cx + r, cy - r, 1.0f, color);
        line(consumer, pose, cx + r, cy - r, cx + r, cy + r, 1.0f, color);
        line(consumer, pose, cx + r, cy + r, cx - r, cy + r, 1.0f, color);
        line(consumer, pose, cx - r, cy + r, cx - r, cy - r, 1.0f, color);
    }

    private static void diamond(GuiGraphics g, float cx, float cy, float r, int color) {
        VertexConsumer consumer = g.bufferSource().getBuffer(RenderType.gui());
        Matrix4f pose = g.pose().last().pose();
        line(consumer, pose, cx, cy - r, cx + r, cy, 1.5f, color);
        line(consumer, pose, cx + r, cy, cx, cy + r, 1.5f, color);
        line(consumer, pose, cx, cy + r, cx - r, cy, 1.5f, color);
        line(consumer, pose, cx - r, cy, cx, cy - r, 1.5f, color);
    }

    /** Segment épais tracé comme un quadrilatère (le GUI vanilla n'a pas de primitive de ligne). */
    public static void line(VertexConsumer consumer, Matrix4f pose, float x0, float y0, float x1, float y1, float width, int color) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = Mth.sqrt(dx * dx + dy * dy);
        if (len < 1.0e-3f) {
            return;
        }
        float nx = -dy / len * width * 0.5f;
        float ny = dx / len * width * 0.5f;
        consumer.addVertex(pose, x0 + nx, y0 + ny, 0).setColor(color);
        consumer.addVertex(pose, x0 - nx, y0 - ny, 0).setColor(color);
        consumer.addVertex(pose, x1 - nx, y1 - ny, 0).setColor(color);
        consumer.addVertex(pose, x1 + nx, y1 + ny, 0).setColor(color);
    }
}
