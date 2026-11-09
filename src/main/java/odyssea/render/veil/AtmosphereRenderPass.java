package odyssea.render.veil;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import foundry.veil.api.client.render.shader.uniform.ShaderUniform;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.platform.VeilEventPlatform;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import odyssea.OdysseaCore;
import odyssea.dimension.PlanetaryAtmosphere;
import odyssea.dimension.PlanetaryAtmospheres;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Passe céleste exécutée à l'étape {@code AFTER_SKY} de Veil : un triangle plein écran par astre, du plus
 * lointain au plus proche, puis l'atmosphère de la planète d'accueil vue de l'intérieur. Composition en alpha
 * prémultiplié : la transmittance de chaque passe atténue ce qui a été dessiné avant elle.
 *
 * <p>Toutes les positions envoyées au GPU sont relatives à la caméra et exprimées en kilomètres, ce qui borne
 * l'erreur flottante même pour un astre à plusieurs millions de kilomètres.
 */
public final class AtmosphereRenderPass {
    public static final ResourceLocation PROGRAM = OdysseaCore.id("planetary_atmosphere");

    private static final float EXPOSURE = 0.9f;
    private static final float SUN_ANGULAR_RADIUS = 0.00465f;
    private static final Vector3fc SUNLIGHT = new Vector3f(1.0f, 0.96f, 0.90f);
    private static final AtmosphereRenderPass INSTANCE = new AtmosphereRenderPass();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Map<ResourceKey<Level>, SkyLayout> LAYOUTS = new ConcurrentHashMap<>();

    public record SurfacePalette(Vector3fc ocean, Vector3fc lowland, Vector3fc highland, float seaLevel,
                                 float oceanSpecular, int style, float seed) {
        public static final int ROCKY = 0;
        public static final int BANDED = 1;
    }

    public record RingSystem(float innerRadiusKm, float outerRadiusKm, Vector3fc color, float opacity) {
    }

    public record CelestialBody(String name, Vector3dc systemPositionKm, float radiusKm, float reliefKm,
                                PlanetaryAtmosphere atmosphere, Vector3fc axis, float rotationPeriodSeconds,
                                SurfacePalette palette, @Nullable RingSystem rings) {
        public CelestialBody {
            systemPositionKm = new Vector3d(systemPositionKm);
            axis = new Vector3f(axis).normalize();
        }
    }

    /**
     * @param homeBody               planète sous les pieds de l'observateur (atmosphère intérieure, sol laissé au terrain)
     * @param observerOriginKm       position système du bloc (0, seaLevelY, 0)
     * @param verticalKmPerBlock     échelle d'altitude visuelle : à 0,08 km/bloc, Y = 1200 correspond à ~90 km, au bord de l'espace
     * @param horizontalKmPerBlock   parallaxe horizontale (0 au sol : voûte céleste fixe)
     */
    public record SkyLayout(@Nullable CelestialBody homeBody, Vector3dc observerOriginKm, double verticalKmPerBlock,
                            double horizontalKmPerBlock, double seaLevelY, List<CelestialBody> visibleBodies,
                            Vector3fc starColor, float starIlluminance, float starAngularRadius,
                            float starPathTiltRadians, boolean drawStarDisc) {

        public static SkyLayout onSurface(CelestialBody home, double verticalKmPerBlock, double seaLevelY,
                                          List<CelestialBody> visibleBodies, float starIlluminance,
                                          float starAngularRadius, boolean drawStarDisc) {
            Vector3d origin = new Vector3d(home.systemPositionKm()).add(0.0, home.radiusKm(), 0.0);
            return new SkyLayout(home, origin, verticalKmPerBlock, 0.0, seaLevelY, List.copyOf(visibleBodies),
                    SUNLIGHT, starIlluminance, starAngularRadius, 0.35f, drawStarDisc);
        }

        public static SkyLayout inSpace(Vector3dc observerOriginKm, double verticalKmPerBlock, double horizontalKmPerBlock,
                                        double seaLevelY, List<CelestialBody> visibleBodies, float starIlluminance) {
            return new SkyLayout(null, new Vector3d(observerOriginKm), verticalKmPerBlock, horizontalKmPerBlock, seaLevelY,
                    List.copyOf(visibleBodies), SUNLIGHT, starIlluminance, SUN_ANGULAR_RADIUS, 0.35f, true);
        }
    }

    private record BodyPass(CelestialBody body, Vector3fc centerKm, double distanceKm, boolean home) {
    }

    private final Matrix4f inverseViewProjection = new Matrix4f();
    private final Vector3d cameraSystemKm = new Vector3d();
    private final List<BodyPass> passes = new ArrayList<>();

    private AtmosphereRenderPass() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        DefaultSystem.registerLayouts();
        VeilEventPlatform.INSTANCE.onVeilRenderLevelStage((stage, levelRenderer, bufferSource, matrixStack, frustumMatrix,
                                                           projectionMatrix, renderTick, deltaTracker, camera, frustum) -> {
            if (stage == VeilRenderLevelStageEvent.Stage.AFTER_SKY) {
                INSTANCE.render(frustumMatrix, projectionMatrix, camera, deltaTracker.getGameTimeDeltaPartialTick(false));
            }
        });
    }

    public static void registerLayout(ResourceKey<Level> dimension, SkyLayout layout) {
        LAYOUTS.put(dimension, layout);
    }

    /** @param view rotation caméra seule (le « frustum matrix » vanilla, sans translation) */
    private void render(Matrix4fc view, Matrix4fc projection, Camera camera, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        SkyLayout layout = LAYOUTS.get(level.dimension());
        if (layout == null) {
            return;
        }

        Vec3 cameraPos = camera.getPosition();
        double altitudeKm = Math.max(0.0, (cameraPos.y - layout.seaLevelY()) * layout.verticalKmPerBlock());
        cameraSystemKm.set(layout.observerOriginKm()).add(
                cameraPos.x * layout.horizontalKmPerBlock(), altitudeKm, cameraPos.z * layout.horizontalKmPerBlock());

        passes.clear();
        for (CelestialBody body : layout.visibleBodies()) {
            Vector3d relative = new Vector3d(body.systemPositionKm()).sub(cameraSystemKm);
            passes.add(new BodyPass(body, new Vector3f((float) relative.x, (float) relative.y, (float) relative.z), relative.length(), false));
        }
        passes.sort(Comparator.comparingDouble(BodyPass::distanceKm).reversed());
        CelestialBody home = layout.homeBody();
        // Avec un shaderpack Iris actif, le ciel intérieur appartient au pack ; les astres lointains restent dessinés.
        if (home != null && home.atmosphere().scattering().hasAtmosphere() && !IrisCompat.isShaderPackInUse()) {
            float depth = (float) (home.radiusKm() + altitudeKm);
            passes.add(new BodyPass(home, new Vector3f(0.0f, -depth, 0.0f), depth, true));
        }
        if (passes.isEmpty()) {
            return;
        }

        ShaderProgram shader = VeilRenderSystem.setShader(PROGRAM);
        if (shader == null) {
            return;
        }
        shader.bind();

        inverseViewProjection.set(projection).mul(view).invert();
        double timeSeconds = (level.getGameTime() + partialTick) / 20.0;
        setMatrix(shader, "uInvViewProj", inverseViewProjection);
        // Angle sous-tendu par un pixel : 2·tan(fov/2)/hauteur, avec P[1][1] = 1/tan(fov/2).
        setFloat(shader, "uPixelAngle", 2.0f / (projection.m11() * Math.max(1, minecraft.getWindow().getHeight())));
        setFloat(shader, "uExposure", EXPOSURE);
        setVec3(shader, "uSunDirection", starDirection(level.getTimeOfDay(partialTick), layout.starPathTiltRadians()));
        setVec3(shader, "uSunColor", layout.starColor());
        setFloat(shader, "uSunIlluminance", layout.starIlluminance());
        setFloat(shader, "uStarAngularRadius", layout.starAngularRadius());

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        try {
            for (int i = 0; i < passes.size(); i++) {
                // Le disque stellaire est dessiné dans la passe la plus lointaine : les astres plus proches l'occultent.
                applyBody(shader, passes.get(i), timeSeconds, layout.drawStarDisc() && i == 0);
                VeilRenderSystem.drawScreenQuad();
            }
        } finally {
            ShaderProgram.unbind();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    }

    private static void applyBody(ShaderProgram shader, BodyPass pass, double timeSeconds, boolean drawStarDisc) {
        CelestialBody body = pass.body();
        PlanetaryAtmosphere.Scattering scattering = body.atmosphere().scattering();
        SurfacePalette palette = body.palette();
        float rotation = body.rotationPeriodSeconds() > 0.0f
                ? (float) ((timeSeconds / body.rotationPeriodSeconds()) % 1.0 * (Math.PI * 2.0))
                : 0.0f;

        setVec3(shader, "uPlanetCenter", pass.centerKm());
        setFloat(shader, "uPlanetRadius", body.radiusKm());
        setFloat(shader, "uReliefHeight", pass.home() ? 0.0f : body.reliefKm());
        setVec3(shader, "uPlanetAxis", body.axis());
        setFloat(shader, "uPlanetRotation", rotation);
        setInt(shader, "uRenderSurface", pass.home() ? 0 : 1);
        setInt(shader, "uSurfaceStyle", palette.style());
        setVec3(shader, "uOceanColor", palette.ocean());
        setVec3(shader, "uLowlandColor", palette.lowland());
        setVec3(shader, "uHighlandColor", palette.highland());
        setFloat(shader, "uSeaLevel", palette.seaLevel());
        setFloat(shader, "uOceanSpecular", palette.oceanSpecular());
        setFloat(shader, "uSeed", palette.seed());

        setInt(shader, "uHasAtmosphere", scattering.hasAtmosphere() ? 1 : 0);
        setFloat(shader, "uAtmosphereRadius", body.radiusKm() + scattering.thicknessKm());
        setVec3(shader, "uRayleighScattering", scattering.rayleighPerKm());
        setFloat(shader, "uRayleighScaleHeight", scattering.rayleighScaleHeightKm());
        setVec3(shader, "uMieScattering", scattering.mieScatteringPerKm());
        setVec3(shader, "uMieExtinction", scattering.mieExtinctionPerKm());
        setFloat(shader, "uMieScaleHeight", scattering.mieScaleHeightKm());
        setFloat(shader, "uMieAnisotropy", scattering.mieAnisotropy());
        setVec3(shader, "uAbsorption", scattering.absorptionPerKm());

        RingSystem rings = body.rings();
        setInt(shader, "uHasRings", rings != null ? 1 : 0);
        if (rings != null) {
            setFloat(shader, "uRingInnerRadius", rings.innerRadiusKm());
            setFloat(shader, "uRingOuterRadius", rings.outerRadiusKm());
            setVec3(shader, "uRingColor", rings.color());
            setFloat(shader, "uRingOpacity", rings.opacity());
        }
        setInt(shader, "uDrawStarDisc", drawStarDisc ? 1 : 0);
    }

    /**
     * Direction de l'étoile cohérente avec le soleil vanilla : rotation X de timeOfDay·360° puis Y de −90°,
     * appliquée au zénith, ce qui donne (−sin θ, cos θ, 0). Le plan de course est ensuite incliné autour de X.
     */
    private static Vector3f starDirection(float timeOfDay, float tiltRadians) {
        double theta = timeOfDay * Math.PI * 2.0;
        double x = -Math.sin(theta);
        double y = Math.cos(theta);
        return new Vector3f((float) x, (float) (y * Math.cos(tiltRadians)), (float) (y * Math.sin(tiltRadians))).normalize();
    }

    private static void setFloat(ShaderProgram shader, String name, float value) {
        ShaderUniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.setFloat(value);
        }
    }

    private static void setInt(ShaderProgram shader, String name, int value) {
        ShaderUniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.setInt(value);
        }
    }

    private static void setVec3(ShaderProgram shader, String name, Vector3fc value) {
        ShaderUniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.setVector(value.x(), value.y(), value.z());
        }
    }

    private static void setMatrix(ShaderProgram shader, String name, Matrix4fc value) {
        ShaderUniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.setMatrix(value);
        }
    }

    /** Détection d'Iris par réflexion : aucune dépendance de compilation, aucun crash s'il est absent. */
    private static final class IrisCompat {
        private static final @Nullable Object API;
        private static final @Nullable Method IS_SHADER_PACK_IN_USE;

        static {
            Object api;
            Method method;
            try {
                Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = type.getMethod("getInstance").invoke(null);
                method = type.getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException | LinkageError e) {
                api = null;
                method = null;
            }
            API = api;
            IS_SHADER_PACK_IN_USE = method;
        }

        static boolean isShaderPackInUse() {
            if (API == null || IS_SHADER_PACK_IN_USE == null) {
                return false;
            }
            try {
                return (boolean) IS_SHADER_PACK_IN_USE.invoke(API);
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }
    }

    /** Système stellaire par défaut d'Odyssea. Positions en km dans un repère héliocentrique fictif centré sur Terra. */
    private static final class DefaultSystem {
        static final PlanetaryAtmosphere KRONOS_ATMOSPHERE = PlanetaryAtmosphere.fromComposition(
                Map.of(PlanetaryAtmosphere.Gas.H2, 0.963, PlanetaryAtmosphere.Gas.HE, 0.0325, PlanetaryAtmosphere.Gas.CH4, 0.0045),
                100_000.0, 134.0, 10.44, 63.0, 1000.0, 58_232.0f, 600.0f,
                new PlanetaryAtmosphere.Aerosols(new Vector3f(2.0e-3f, 1.8e-3f, 1.2e-3f), new Vector3f(2.4e-3f, 2.3e-3f, 2.2e-3f), 30.0f, 0.7f));

        static final CelestialBody TERRA = new CelestialBody("terra", new Vector3d(0.0, 0.0, 0.0), 6371.0f, 9.0f,
                PlanetaryAtmospheres.TERRA, new Vector3f(0.0f, 0.917f, 0.398f), 1200.0f,
                new SurfacePalette(new Vector3f(0.02f, 0.07f, 0.18f), new Vector3f(0.13f, 0.25f, 0.08f),
                        new Vector3f(0.42f, 0.36f, 0.28f), 0.53f, 1.0f, SurfacePalette.ROCKY, 11.0f), null);

        static final CelestialBody LUNA = new CelestialBody("luna", new Vector3d(180_000.0, 290_000.0, -150_000.0), 1737.4f, 6.0f,
                PlanetaryAtmosphere.VACUUM, new Vector3f(0.0f, 1.0f, 0.0f), 0.0f,
                new SurfacePalette(new Vector3f(0.30f, 0.30f, 0.31f), new Vector3f(0.45f, 0.44f, 0.42f),
                        new Vector3f(0.62f, 0.61f, 0.58f), 0.42f, 0.0f, SurfacePalette.ROCKY, 4.7f), null);

        static final CelestialBody KRONOS = new CelestialBody("kronos", new Vector3d(-1.1e6, 0.55e6, 0.9e6), 58_232.0f, 0.0f,
                KRONOS_ATMOSPHERE, new Vector3f(0.0f, 0.893f, 0.449f), 3600.0f,
                new SurfacePalette(new Vector3f(0.70f, 0.62f, 0.46f), new Vector3f(0.80f, 0.70f, 0.52f),
                        new Vector3f(0.93f, 0.86f, 0.70f), 0.0f, 0.0f, SurfacePalette.BANDED, 7.3f),
                new RingSystem(74_500.0f, 140_220.0f, new Vector3f(0.83f, 0.76f, 0.62f), 0.85f));

        static final CelestialBody ARES = new CelestialBody("ares", new Vector3d(2.1e6, -0.4e6, 1.6e6), 3389.5f, 12.0f,
                PlanetaryAtmospheres.ARES_ATMOSPHERE, new Vector3f(0.0f, 0.906f, 0.423f), 1240.0f,
                new SurfacePalette(new Vector3f(0.30f, 0.14f, 0.08f), new Vector3f(0.55f, 0.28f, 0.14f),
                        new Vector3f(0.72f, 0.45f, 0.28f), 0.45f, 0.0f, SurfacePalette.ROCKY, 23.0f), null);

        static void registerLayouts() {
            registerLayout(Level.OVERWORLD, SkyLayout.onSurface(TERRA, 0.08, 63.0, List.of(LUNA, KRONOS), 22.0f, SUN_ANGULAR_RADIUS, false));
            registerLayout(PlanetaryAtmospheres.ORBIT, SkyLayout.inSpace(
                    new Vector3d(TERRA.systemPositionKm()).add(0.0, TERRA.radiusKm() + 420.0, 0.0),
                    0.08, 0.02, 63.0, List.of(TERRA, LUNA, KRONOS), 22.0f));
            registerLayout(PlanetaryAtmospheres.LUNA, SkyLayout.onSurface(LUNA, 0.08, 63.0, List.of(TERRA, KRONOS), 22.0f, SUN_ANGULAR_RADIUS, true));
            registerLayout(PlanetaryAtmospheres.ARES, SkyLayout.onSurface(ARES, 0.08, 63.0, List.of(KRONOS), 9.5f, 0.0031f, true));
        }
    }
}
