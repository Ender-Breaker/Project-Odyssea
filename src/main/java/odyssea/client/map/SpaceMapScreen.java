package odyssea.client.map;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import odyssea.client.ship.ShipHud;
import odyssea.dimension.Planets;
import odyssea.network.OdysseaNetwork;
import odyssea.ship.ShipEntity;
import odyssea.space.Navigation;
import odyssea.space.SpaceSystem;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * Carte du système solaire (touche M) : orbites, planètes, lunes et ceinture d'astéroïdes, position du joueur et
 * de sa destination. Glisser pour se déplacer, molette pour zoomer autour du curseur, clic pour sélectionner un
 * corps, double-clic pour le centrer. Le bouton « Destination » prépare le saut quantique.
 */
public class SpaceMapScreen extends Screen {
    private static final int PANEL = 0xC0101418;
    private static final int GRID = 0x30406070;
    private static final double MIN_SCALE = 2.0;
    private static final double MAX_SCALE = 6_000.0;

    private double centerX;
    private double centerZ;
    private double scale;
    private @Nullable SpaceSystem.Body selected;
    private long lastClick;
    private Button destinationButton;

    public SpaceMapScreen() {
        super(Component.translatable("odyssea.map.title"));
    }

    @Override
    protected void init() {
        Vec3 here = playerSystemPosition();
        if (scale == 0.0) {
            centerX = here.x;
            centerZ = here.z;
            scale = Mth.clamp(900_000.0 / Math.max(100, height / 2 - 30), MIN_SCALE, MAX_SCALE);
            selected = Navigation.clientDestination();
        }
        int panelX = width - 170;
        destinationButton = addRenderableWidget(Button.builder(Component.translatable("odyssea.map.set_destination"), b -> {
            if (selected != null) {
                PacketDistributor.sendToServer(new OdysseaNetwork.SetDestination(selected.id()));
                Navigation.setClientDestination(selected.id());
            }
        }).bounds(panelX, height - 74, 160, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("odyssea.map.clear_destination"), b -> {
            PacketDistributor.sendToServer(new OdysseaNetwork.SetDestination(""));
            Navigation.setClientDestination("");
        }).bounds(panelX, height - 50, 78, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("odyssea.map.center"), b -> {
            Vec3 p = playerSystemPosition();
            centerX = p.x;
            centerZ = p.z;
        }).bounds(panelX + 82, height - 50, 78, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(panelX, height - 26, 160, 20).build());
    }

    /** Position du joueur dans le système : réelle dans l'espace, celle de la planète ailleurs. */
    private Vec3 playerSystemPosition() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return Vec3.ZERO;
        }
        if (SpaceSystem.isSpace(player.level())) {
            return player.position();
        }
        SpaceSystem.Body body = SpaceSystem.bodyForDimension(player.level().dimension());
        return body != null ? SpaceSystem.position(body.id()) : SpaceSystem.position("terra");
    }

    private float sx(double x) {
        return (float) (width / 2.0 + (x - centerX) / scale);
    }

    private float sy(double z) {
        return (float) (height / 2.0 + (z - centerZ) / scale);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xF0050810);
        Matrix4f pose = g.pose().last().pose();

        // Grille de 100 000 blocs.
        double step = 100_000.0;
        while (step / scale < 60.0) {
            step *= 2.0;
        }
        while (step / scale > 240.0) {
            step /= 2.0;
        }
        double startX = Math.floor((centerX - width / 2.0 * scale) / step) * step;
        double startZ = Math.floor((centerZ - height / 2.0 * scale) / step) * step;
        for (double x = startX; sx(x) < width; x += step) {
            g.fill((int) sx(x), 0, (int) sx(x) + 1, height, GRID);
        }
        for (double z = startZ; sy(z) < height; z += step) {
            g.fill(0, (int) sy(z), width, (int) sy(z) + 1, GRID);
        }

        // Orbites et ceinture.
        for (SpaceSystem.Body body : SpaceSystem.bodies()) {
            if (body.parent() == null) {
                continue;
            }
            Vec3 parent = SpaceSystem.position(body.parent());
            double r = body.orbitRadius() / scale;
            if (body.kind() == SpaceSystem.Kind.BELT) {
                circle(g, pose, sx(parent.x), sy(parent.z), (float) (200_000 / scale), 0x40A08A70, 1.0f);
                circle(g, pose, sx(parent.x), sy(parent.z), (float) (250_000 / scale), 0x40A08A70, 1.0f);
                circle(g, pose, sx(parent.x), sy(parent.z), (float) (225_000 / scale), 0x30A08A70, (float) Math.max(1.0, 50_000 / scale));
                continue;
            }
            if (r < 6.0 || r > 6_000.0) {
                continue;
            }
            int alpha = body.kind() == SpaceSystem.Kind.MOON ? 0x30 : 0x50;
            circle(g, pose, sx(parent.x), sy(parent.z), (float) r, (alpha << 24) | 0x6FA8C8, 1.0f);
        }

        // Corps.
        SpaceSystem.Body hovered = bodyAt(mouseX, mouseY);
        for (SpaceSystem.Body body : SpaceSystem.bodies()) {
            if (body.kind() == SpaceSystem.Kind.BELT) {
                continue;
            }
            Vec3 p = SpaceSystem.position(body.id());
            float x = sx(p.x);
            float y = sy(p.z);
            if (x < -50 || y < -50 || x > width + 50 || y > height + 50) {
                continue;
            }
            boolean moon = body.kind() == SpaceSystem.Kind.MOON;
            if (moon && body.orbitRadius() / scale < 8.0) {
                continue;
            }
            float radius = (float) Math.max(moon ? 1.5 : 2.5, body.radius() / scale);
            disc(g, pose, x, y, radius, 0xFF000000 | body.color());
            if (body.kind() == SpaceSystem.Kind.STAR) {
                circle(g, pose, x, y, radius + 4, 0x60FFE9A8, 2.0f);
            }
            if (body == selected) {
                circle(g, pose, x, y, radius + 5, 0xFF4FD8FF, 1.5f);
            } else if (body == hovered) {
                circle(g, pose, x, y, radius + 4, 0x904FD8FF, 1.0f);
            }
            if (!moon || body.orbitRadius() / scale > 30.0 || body == hovered || body == selected) {
                g.drawCenteredString(font, Component.translatable(body.translationKey()), (int) x, (int) (y + radius + 4),
                        moon ? 0xFF9FB4C0 : 0xFFE0F0FF);
            }
        }

        // Joueur et destination.
        Vec3 here = playerSystemPosition();
        float px = sx(here.x);
        float py = sy(here.z);
        SpaceSystem.Body destination = Navigation.clientDestination();
        if (destination != null) {
            Vec3 arrival = SpaceSystem.quantumArrival(destination, here);
            dashed(g, pose, px, py, sx(arrival.x), sy(arrival.z), 0xC0B487FF);
        }
        LocalPlayer player = Minecraft.getInstance().player;
        float yaw = player != null ? player.getYRot() * Mth.DEG_TO_RAD : 0.0f;
        float fx = -Mth.sin(yaw);
        float fz = Mth.cos(yaw);
        ShipHud.line(g.bufferSource().getBuffer(RenderType.gui()), pose, px - fz * 5 - fx * 4, py + fx * 5 - fz * 4, px + fx * 8, py + fz * 8, 2.0f, 0xFF7CFF8A);
        ShipHud.line(g.bufferSource().getBuffer(RenderType.gui()), pose, px + fz * 5 - fx * 4, py - fx * 5 - fz * 4, px + fx * 8, py + fz * 8, 2.0f, 0xFF7CFF8A);
        g.drawString(font, Component.translatable("odyssea.map.you"), (int) px + 8, (int) py - 4, 0xFF7CFF8A, true);

        // Panneau d'information.
        int panelX = width - 176;
        g.fill(panelX, 0, width, height, PANEL);
        g.drawString(font, title, panelX + 6, 8, 0xFF4FD8FF, false);
        g.drawString(font, Component.translatable("odyssea.map.scale", formatDistance(step)), panelX + 6, 22, 0xFF7FA6B8, false);
        int y = 42;
        if (selected != null) {
            g.drawString(font, Component.translatable(selected.translationKey()), panelX + 6, y, 0xFFFFFFFF, false);
            y += 12;
            g.drawString(font, Component.translatable("odyssea.map.kind." + selected.kind().name().toLowerCase(java.util.Locale.ROOT)),
                    panelX + 6, y, 0xFF9FB4C0, false);
            y += 14;
            Vec3 target = SpaceSystem.position(selected.id());
            double distance = target.distanceTo(here);
            y = info(g, panelX, y, "odyssea.map.distance", formatDistance(distance));
            if (selected.dimension() != null) {
                Planets.Planet planet = Planets.get(selected.dimension());
                y = info(g, panelX, y, "odyssea.map.landable", Component.translatable("gui.yes").getString());
                if (planet != null) {
                    y = info(g, panelX, y, "odyssea.map.gravity", String.format("%.2f g", planet.gravity() / 9.81));
                    y = info(g, panelX, y, "odyssea.map.atmosphere", planet.atmosphere().scattering().hasAtmosphere()
                            ? Component.translatable("odyssea.map.atmosphere.yes").getString()
                            : Component.translatable("odyssea.map.atmosphere.no").getString());
                }
            } else if (selected.kind() != SpaceSystem.Kind.BELT && selected.kind() != SpaceSystem.Kind.STAR) {
                y = info(g, panelX, y, "odyssea.map.landable", Component.translatable("gui.no").getString());
            }
            Vec3 arrival = SpaceSystem.quantumArrival(selected, here);
            double jump = arrival.distanceTo(here);
            y += 6;
            y = info(g, panelX, y, "odyssea.map.travel_time", (SpaceSystem.quantumTravelTicks(jump) / 20) + " s");
            y = info(g, panelX, y, "odyssea.map.quantum_cost", String.valueOf(Math.round(SpaceSystem.quantumFuelCost(jump))));
            Entity vehicle = player != null ? player.getVehicle() : null;
            if (vehicle instanceof ShipEntity ship && ship.design() != null) {
                y = info(g, panelX, y, "odyssea.map.quantum_fuel", Math.round(ship.quantumFuel()) + " / "
                        + Math.round(ship.design().stats().quantumFuel()));
            }
            y += 6;
            g.drawWordWrap(font, Component.translatable("odyssea.map.desc." + selected.id()), panelX + 6, y, 164, 0xFF9FB4C0);
        } else {
            g.drawWordWrap(font, Component.translatable("odyssea.map.help"), panelX + 6, y, 164, 0xFF9FB4C0);
        }
        if (destination != null) {
            g.drawString(font, Component.translatable("odyssea.map.current_destination", Component.translatable(destination.translationKey())),
                    panelX + 6, height - 92, 0xFFB487FF, false);
        }
        destinationButton.active = selected != null && selected.kind() != SpaceSystem.Kind.STAR;
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Fond dessiné dans render().
    }

    private int info(GuiGraphics g, int panelX, int y, String key, String value) {
        g.drawString(font, Component.translatable(key), panelX + 6, y, 0xFF7FA6B8, false);
        g.drawString(font, value, width - 8 - font.width(value), y, 0xFFE0F0FF, false);
        return y + 11;
    }

    private static String formatDistance(double blocks) {
        if (blocks >= 10_000) {
            return String.format("%,.0f km", blocks / 1000.0);
        }
        return String.format("%.1f km", blocks / 1000.0);
    }

    private @Nullable SpaceSystem.Body bodyAt(double mouseX, double mouseY) {
        SpaceSystem.Body best = null;
        double bestDistance = 12.0;
        for (SpaceSystem.Body body : SpaceSystem.bodies()) {
            if (body.kind() == SpaceSystem.Kind.BELT) {
                continue;
            }
            if (body.kind() == SpaceSystem.Kind.MOON && body.orbitRadius() / scale < 8.0) {
                continue;
            }
            Vec3 p = SpaceSystem.position(body.id());
            double radius = Math.max(3.0, body.radius() / scale);
            double d = Math.hypot(sx(p.x) - mouseX, sy(p.z) - mouseY) - radius;
            if (d < bestDistance) {
                bestDistance = d;
                best = body;
            }
        }
        if (best == null) {
            // Clic dans la ceinture : la sélectionner.
            double r = Math.hypot((mouseX - width / 2.0) * scale + centerX, (mouseY - height / 2.0) * scale + centerZ);
            if (r > 200_000 && r < 250_000) {
                return SpaceSystem.body("belt");
            }
        }
        return best;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (mouseX > width - 176) {
            return false;
        }
        SpaceSystem.Body body = bodyAt(mouseX, mouseY);
        long now = System.currentTimeMillis();
        if (body != null) {
            if (body == selected && now - lastClick < 350) {
                Vec3 p = SpaceSystem.position(body.id());
                centerX = p.x;
                centerZ = p.z;
                double extent = body.kind() == SpaceSystem.Kind.BELT ? 260_000 : childExtent(body);
                scale = Mth.clamp(extent / Math.max(100, height / 2 - 40), MIN_SCALE, MAX_SCALE);
            }
            selected = body;
        }
        lastClick = now;
        return true;
    }

    /** Rayon utile autour d'un corps : sa plus grande orbite de lune, ou quelques rayons. */
    private static double childExtent(SpaceSystem.Body body) {
        double extent = body.radius() * 4.0;
        for (SpaceSystem.Body other : SpaceSystem.bodies()) {
            if (body.id().equals(other.parent())) {
                extent = Math.max(extent, other.orbitRadius() * 1.15);
            }
        }
        return extent;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (mouseX < width - 176) {
            centerX -= dragX * scale;
            centerZ -= dragY * scale;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double factor = scrollY > 0 ? 0.8 : 1.25;
        double next = Mth.clamp(scale * factor, MIN_SCALE, MAX_SCALE);
        // Zoom centré sur le curseur.
        double worldX = (mouseX - width / 2.0) * scale + centerX;
        double worldZ = (mouseY - height / 2.0) * scale + centerZ;
        scale = next;
        centerX = worldX - (mouseX - width / 2.0) * scale;
        centerZ = worldZ - (mouseY - height / 2.0) * scale;
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void circle(GuiGraphics g, Matrix4f pose, float cx, float cy, float r, int color, float width) {
        VertexConsumer consumer = g.bufferSource().getBuffer(RenderType.gui());
        int segments = Mth.clamp((int) (r * 0.8f), 24, 360);
        for (int i = 0; i < segments; i++) {
            float a0 = Mth.TWO_PI * i / segments;
            float a1 = Mth.TWO_PI * (i + 1) / segments;
            ShipHud.line(consumer, pose, cx + Mth.cos(a0) * r, cy + Mth.sin(a0) * r, cx + Mth.cos(a1) * r, cy + Mth.sin(a1) * r, width, color);
        }
    }

    private static void disc(GuiGraphics g, Matrix4f pose, float cx, float cy, float r, int color) {
        VertexConsumer consumer = g.bufferSource().getBuffer(RenderType.gui());
        int segments = Mth.clamp((int) (r * 2), 12, 96);
        for (int i = 0; i < segments; i++) {
            float a0 = Mth.TWO_PI * i / segments;
            float a1 = Mth.TWO_PI * (i + 1) / segments;
            consumer.addVertex(pose, cx, cy, 0).setColor(color);
            consumer.addVertex(pose, cx, cy, 0).setColor(color);
            consumer.addVertex(pose, cx + Mth.cos(a1) * r, cy + Mth.sin(a1) * r, 0).setColor(color);
            consumer.addVertex(pose, cx + Mth.cos(a0) * r, cy + Mth.sin(a0) * r, 0).setColor(color);
        }
    }

    private static void dashed(GuiGraphics g, Matrix4f pose, float x0, float y0, float x1, float y1, int color) {
        VertexConsumer consumer = g.bufferSource().getBuffer(RenderType.gui());
        float length = Mth.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0));
        int dashes = Math.max(1, (int) (length / 8));
        for (int i = 0; i < dashes; i += 2) {
            float t0 = i / (float) dashes;
            float t1 = Math.min(1.0f, (i + 1) / (float) dashes);
            ShipHud.line(consumer, pose, Mth.lerp(t0, x0, x1), Mth.lerp(t0, y0, y1), Mth.lerp(t1, x0, x1), Mth.lerp(t1, y0, y1), 1.5f, color);
        }
    }
}
