package odyssea.client;

import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import odyssea.OdysseaCore;
import odyssea.dimension.PlanetaryAtmospheres;
import odyssea.render.veil.AtmosphereRenderPass;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * Brouillard, nuages et teinte de la lumière de chaque planète.
 *
 * <ul>
 *   <li>Sans atmosphère (Lune, Mercure) : aucun brouillard, le relief reste net jusqu'à l'horizon ; le peu de
 *       brouillard nécessaire pour masquer la limite de rendu est noir comme le ciel.</li>
 *   <li>Mars : voile de poussière ocre ; Vénus : brume orangée épaisse et lumière tamisée ;
 *       géantes gazeuses : brume de la couleur du ciel.</li>
 *   <li>Aucun nuage vanilla hors de la Terre (les nuages vanilla sont blancs et à Y = 192).</li>
 * </ul>
 * La couleur du brouillard suit la hauteur du Soleil calculée comme dans le shader céleste.
 */
public final class PlanetEnvironment {
    /**
     * @param fogColor      couleur du brouillard en plein jour
     * @param nightFloor    fraction conservée la nuit
     * @param fogStart      début du brouillard, en fraction de la distance de rendu
     * @param fogEnd        fin du brouillard, en fraction de la distance de rendu
     * @param lightTint     teinte multipliée sur la part « ciel » de l'éclairage des blocs
     */
    public record Environment(Vector3f fogColor, float nightFloor, float fogStart, float fogEnd, Vector3f lightTint,
                              boolean hasGround) {
    }

    private static final Vector3f NEUTRAL = new Vector3f(1.0f, 1.0f, 1.0f);
    private static final Map<ResourceKey<Level>, Environment> ENVIRONMENTS = Map.of(
            PlanetaryAtmospheres.MERCURY, new Environment(new Vector3f(0.0f), 0.0f, 0.97f, 1.0f, new Vector3f(1.0f, 0.98f, 0.95f), true),
            PlanetaryAtmospheres.LUNA, new Environment(new Vector3f(0.0f), 0.0f, 0.97f, 1.0f, NEUTRAL, true),
            PlanetaryAtmospheres.VENUS, new Environment(new Vector3f(0.62f, 0.40f, 0.18f), 0.05f, 0.0f, 0.62f, new Vector3f(1.0f, 0.74f, 0.42f), true),
            PlanetaryAtmospheres.MARS, new Environment(new Vector3f(0.80f, 0.58f, 0.40f), 0.03f, 0.35f, 1.0f, new Vector3f(1.0f, 0.88f, 0.76f), true),
            PlanetaryAtmospheres.JUPITER, new Environment(new Vector3f(0.62f, 0.62f, 0.66f), 0.03f, 0.25f, 1.0f, new Vector3f(1.0f, 0.95f, 0.88f), false),
            PlanetaryAtmospheres.SATURN, new Environment(new Vector3f(0.70f, 0.64f, 0.50f), 0.03f, 0.25f, 1.0f, new Vector3f(1.0f, 0.95f, 0.84f), false),
            PlanetaryAtmospheres.URANUS, new Environment(new Vector3f(0.55f, 0.76f, 0.79f), 0.03f, 0.3f, 1.0f, new Vector3f(0.85f, 0.97f, 1.0f), true),
            PlanetaryAtmospheres.NEPTUNE, new Environment(new Vector3f(0.24f, 0.36f, 0.70f), 0.03f, 0.25f, 1.0f, new Vector3f(0.78f, 0.88f, 1.0f), false),
            PlanetaryAtmospheres.SPACE, new Environment(new Vector3f(0.0f), 0.0f, 0.98f, 1.0f, NEUTRAL, false));

    private PlanetEnvironment() {
    }

    static @Nullable Environment current() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? null : ENVIRONMENTS.get(level.dimension());
    }

    static void registerEffects(RegisterDimensionSpecialEffectsEvent event) {
        ENVIRONMENTS.forEach((dimension, environment) ->
                event.register(OdysseaCore.id(dimension.location().getPath()), new Effects(environment)));
    }

    static void onRenderFog(ViewportEvent.RenderFog event) {
        Environment environment = current();
        if (environment == null || event.getType() != FogType.NONE || event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }
        float far = event.getFarPlaneDistance();
        event.setNearPlaneDistance(far * environment.fogStart());
        event.setFarPlaneDistance(far * environment.fogEnd());
        event.setCanceled(true);
    }

    static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        Environment environment = current();
        ClientLevel level = Minecraft.getInstance().level;
        if (environment == null || level == null || event.getCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }
        float daylight = daylight(AtmosphereRenderPass.sunElevation(level, (float) event.getPartialTick()), environment.nightFloor());
        event.setRed(environment.fogColor().x() * daylight);
        event.setGreen(environment.fogColor().y() * daylight);
        event.setBlue(environment.fogColor().z() * daylight);
    }

    /** Lumière diffuse selon la hauteur du Soleil : pleine au-dessus de 10°, plancher la nuit, transition au crépuscule. */
    private static float daylight(float sunElevation, float floor) {
        float t = Math.clamp((sunElevation + 0.1f) / 0.28f, 0.0f, 1.0f);
        return floor + (1.0f - floor) * t * t * (3.0f - 2.0f * t);
    }

    private static final class Effects extends DimensionSpecialEffects {
        private final Environment environment;

        Effects(Environment environment) {
            // Nuages désactivés (NaN), ciel « normal » pour garder les étoiles vanilla sous la passe céleste.
            super(Float.NaN, environment.hasGround(), SkyType.NORMAL, false, false);
            this.environment = environment;
        }

        @Override
        public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
            return fogColor.multiply(brightness * 0.94f + 0.06f, brightness * 0.94f + 0.06f, brightness * 0.91f + 0.09f);
        }

        @Override
        public boolean isFoggyAt(int x, int y) {
            return false;
        }

        @Override
        public float @Nullable [] getSunriseColor(float timeOfDay, float partialTick) {
            return null;
        }

        @Override
        public void adjustLightmapColors(ClientLevel level, float partialTicks, float skyDarken, float blockLightRedFlicker,
                                         float skyLight, int pixelX, int pixelY, Vector3f colors) {
            // pixelY : niveau de lumière du ciel, pixelX : niveau de lumière des blocs. On ne teinte que la part du ciel.
            float sky = pixelY / 15.0f * skyLight;
            float blockLight = pixelX / 15.0f;
            float weight = sky / (sky + blockLight + 1e-3f);
            Vector3f tint = environment.lightTint();
            colors.mul(1.0f + (tint.x() - 1.0f) * weight, 1.0f + (tint.y() - 1.0f) * weight, 1.0f + (tint.z() - 1.0f) * weight);
        }
    }
}
