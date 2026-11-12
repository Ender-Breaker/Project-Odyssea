package odyssea.render.veil;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import foundry.veil.api.client.render.shader.uniform.ShaderUniform;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.platform.VeilEventPlatform;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
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
import odyssea.dimension.Planets;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

/**
 * Passe céleste exécutée à l'étape {@code AFTER_SKY} de Veil : un triangle plein écran par astre, du plus
 * lointain au plus proche, puis l'atmosphère de la planète d'accueil vue de l'intérieur. Composition en alpha
 * prémultiplié : la transmittance de chaque passe atténue ce qui a été dessiné avant elle.
 *
 * <p>Toutes les positions envoyées au GPU sont relatives à la caméra et exprimées en kilomètres, ce qui borne
 * l'erreur flottante même pour un astre à plusieurs millions de kilomètres.
 *
 * <p>Dans une dimension gérée ici, le ciel vanilla (dôme, lueur du lever, soleil, lune) est retiré par
 * {@code LevelRendererSkyMixin} ; seules les étoiles vanilla restent, sur un fond noir. Le contenu de chaque ciel
 * (astres, durée du jour, exposition) est décrit par {@link SolarSystemSky}.
 */
public final class AtmosphereRenderPass {
    public static final ResourceLocation PROGRAM = OdysseaCore.id("planetary_atmosphere");

    static final float SUN_ANGULAR_RADIUS = 0.00465f;
    /**
     * Agrandissement visuel du disque stellaire. Le vrai soleil (0,27°) ne ferait que 3 pixels à l'écran ;
     * comme le soleil vanilla, on l'exagère. Le rayon physique reste utilisé pour la pénombre des ombres.
     */
    private static final float STAR_DISC_VISUAL_SCALE = 8.6f;
    /** Diamètre visuel minimal du disque, en pixels : depuis Neptune le Soleil reste un point vif. */
    private static final float STAR_DISC_MIN_PIXELS = 1.6f;
    /** Intensité du halo de diffusion autour de la lune, relative à l'éclairement stellaire. */
    private static final float MOON_HALO_STRENGTH = 6.0e-4f;
    static final Vector3fc SUNLIGHT = new Vector3f(1.0f, 0.96f, 0.90f);
    private static final AtmosphereRenderPass INSTANCE = new AtmosphereRenderPass();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Map<ResourceKey<Level>, SkyLayout> LAYOUTS = new ConcurrentHashMap<>();

    /** Vrai tant que le shader est chargé : sinon le ciel vanilla est conservé plutôt qu'un écran noir. */
    private static volatile boolean shaderReady;

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

        public CelestialBody at(Vector3dc position) {
            return new CelestialBody(name, position, radiusKm, reliefKm, atmosphere, axis, rotationPeriodSeconds, palette, rings);
        }
    }

    /**
     * Astre qui suit la course du ciel, comme la lune vanilla : sa direction est recalculée à chaque frame
     * et sa position système est ignorée.
     *
     * @param distanceKm         distance visuelle, choisie pour la taille apparente voulue
     * @param cycleDays          période synodique en jours locaux (négative pour une orbite rétrograde : l'astre
     *                           traverse alors le ciel plus vite que le Soleil)
     * @param inclinationRadians inclinaison de sa course sur celle de l'étoile : évite une éclipse à chaque nouvelle lune
     */
    public record SkyTrack(CelestialBody body, double distanceKm, double cycleDays, float inclinationRadians) {
        private static final double FULL_MOON_LOCAL_DAY = 0.75; // minuit du jour 0 : pleine lune, comme la phase vanilla 0

        Vector3f direction(float timeOfDay, double localDays, float pathTiltRadians) {
            double cycles = (localDays - FULL_MOON_LOCAL_DAY) / cycleDays;
            double phase = cycles - Math.floor(cycles);
            // Opposée à l'étoile à la pleine lune, puis en retard d'une fraction de tour chaque jour :
            // elle se lève plus tard chaque soir et ses phases suivent l'angle qu'elle fait avec l'étoile.
            return trackDirection(timeOfDay + 0.5f - (float) phase, pathTiltRadians + inclinationRadians, 0.0f);
        }
    }

    /**
     * @param homeBody             planète sous les pieds de l'observateur (atmosphère intérieure, sol au-delà du terrain)
     * @param planet               durée du jour et sens de rotation ({@code null} : horloge vanilla)
     * @param observerOriginKm     position système du bloc (0, seaLevelY, 0)
     * @param verticalKmPerBlock   échelle d'altitude visuelle : à 0,08 km/bloc, Y = 1200 correspond à ~90 km, au bord de l'espace
     * @param horizontalKmPerBlock parallaxe horizontale (0 au sol : voûte céleste fixe)
     * @param trackedBodies        astres qui suivent la course du ciel (lunes)
     * @param starPathTiltRadians  inclinaison de la course diurne (latitude de l'observateur)
     * @param starDeclinationRadians déclinaison du Soleil (saison) : décale sa course vers un pôle
     * @param exposure             adaptation de l'œil : compense en partie la chute d'éclairement loin du Soleil
     * @param dayStarBrightness    luminosité minimale des étoiles en plein jour (ciel noir des corps sans atmosphère)
     */
    public record SkyLayout(@Nullable CelestialBody homeBody, @Nullable Planets.Planet planet, Vector3dc observerOriginKm,
                            double verticalKmPerBlock, double horizontalKmPerBlock, double seaLevelY,
                            List<CelestialBody> visibleBodies, List<SkyTrack> trackedBodies, Vector3fc starColor,
                            float starIlluminance, float starAngularRadius, float starPathTiltRadians,
                            float starDeclinationRadians, float exposure, float dayStarBrightness, boolean heliocentric) {

        public static Builder onSurface(CelestialBody home, Planets.Planet planet, double seaLevelY) {
            return new Builder(home, planet, new Vector3d(home.systemPositionKm()).add(0.0, home.radiusKm(), 0.0), seaLevelY);
        }

        /**
         * Ciel de la dimension spatiale : un bloc = un kilomètre, l'étoile au point {@code starPositionKm}. Sa direction,
         * son diamètre apparent et son éclairement dépendent de la position de la caméra (et du saut quantique en cours).
         */
        public static SkyLayout heliocentric(List<CelestialBody> visibleBodies, Vector3dc starPositionKm) {
            return new SkyLayout(null, null, new Vector3d(starPositionKm), 1.0, 1.0, 0.0,
                    List.copyOf(visibleBodies), List.of(), SUNLIGHT, 22.0f, SUN_ANGULAR_RADIUS, 0.0f, 0.0f, 0.9f, 1.0f, true);
        }

        /** Direction de l'étoile selon l'heure locale, la latitude, la saison et le sens de rotation. */
        Vector3f sunDirection(float timeOfDay) {
            Vector3f direction = trackDirection(timeOfDay, starPathTiltRadians, starDeclinationRadians);
            return retrograde() ? direction.mul(-1.0f, 1.0f, 1.0f) : direction;
        }

        boolean retrograde() {
            return planet != null && planet.retrograde();
        }

        double localDays(double dayTime) {
            return planet != null ? planet.localDays(dayTime) : dayTime / 24_000.0;
        }
    }

    public static final class Builder {
        private final CelestialBody home;
        private final Planets.Planet planet;
        private final Vector3d origin;
        private final double seaLevelY;
        private final List<CelestialBody> visible = new ArrayList<>();
        private final List<SkyTrack> tracks = new ArrayList<>();
        private float illuminance = 22.0f;
        private float angularRadius = SUN_ANGULAR_RADIUS;
        private float tilt = 0.35f;
        private float declination = 0.0f;
        private float exposure = 0.9f;
        private float dayStars = 0.0f;

        private Builder(CelestialBody home, Planets.Planet planet, Vector3d origin, double seaLevelY) {
            this.home = home;
            this.planet = planet;
            this.origin = origin;
            this.seaLevelY = seaLevelY;
        }

        public Vector3dc origin() {
            return origin;
        }

        public Builder sun(float illuminance, float angularRadius) {
            this.illuminance = illuminance;
            this.angularRadius = angularRadius;
            return this;
        }

        public Builder path(float tiltRadians, float declinationRadians) {
            this.tilt = tiltRadians;
            this.declination = declinationRadians;
            return this;
        }

        public Builder exposure(float exposure) {
            this.exposure = exposure;
            return this;
        }

        public Builder dayStars(float brightness) {
            this.dayStars = brightness;
            return this;
        }

        public Builder visible(CelestialBody body) {
            visible.add(body);
            return this;
        }

        public Builder track(SkyTrack track) {
            tracks.add(track);
            return this;
        }

        public SkyLayout build() {
            return new SkyLayout(home, planet, origin, SolarSystemSky.VERTICAL_KM_PER_BLOCK, 0.0, seaLevelY, List.copyOf(visible),
                    List.copyOf(tracks), SUNLIGHT, illuminance, angularRadius, tilt, declination, exposure, dayStars, false);
        }
    }

    private record BodyPass(CelestialBody body, Vector3fc centerKm, double distanceKm, boolean home) {
    }

    /** Corps de rayon nul : le shader n'y dessine que le disque et le halo de l'étoile. */
    private static final CelestialBody STAR_ONLY = new CelestialBody("star_only", new Vector3d(), 0.0f, 0.0f,
            PlanetaryAtmosphere.VACUUM, new Vector3f(0.0f, 1.0f, 0.0f), 0.0f,
            new SurfacePalette(new Vector3f(), new Vector3f(), new Vector3f(), 0.0f, 0.0f, SurfacePalette.ROCKY, 0.0f), null);

    private final Matrix4f inverseViewProjection = new Matrix4f();
    private final Vector3d cameraSystemKm = new Vector3d();
    private final List<BodyPass> passes = new ArrayList<>();

    private AtmosphereRenderPass() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        SolarSystemSky.registerLayouts();
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

    public static @Nullable SkyLayout layout(ResourceKey<Level> dimension) {
        return LAYOUTS.get(dimension);
    }

    /**
     * Appelé par le mixin du ciel vanilla. Avec un shaderpack Iris, le pack garde la main sur le ciel.
     */
    public static boolean replacesVanillaSky() {
        ClientLevel level = Minecraft.getInstance().level;
        return shaderReady && level != null && LAYOUTS.containsKey(level.dimension()) && !IrisCompat.isShaderPackInUse();
    }

    /** Luminosité minimale des étoiles vanilla dans la dimension courante (0 si le ciel vanilla est conservé). */
    public static float dayStarBrightness() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !replacesVanillaSky()) {
            return 0.0f;
        }
        SkyLayout layout = LAYOUTS.get(level.dimension());
        return layout != null ? layout.dayStarBrightness() : 0.0f;
    }

    /**
     * Hauteur du Soleil local (sinus de l'élévation) pour la dimension courante, calculée comme dans le shader.
     * Sert à teinter le brouillard selon l'heure.
     */
    public static float sunElevation(ClientLevel level, float partialTick) {
        SkyLayout layout = LAYOUTS.get(level.dimension());
        float timeOfDay = level.getTimeOfDay(partialTick);
        return layout != null ? layout.sunDirection(timeOfDay).y : trackDirection(timeOfDay, 0.0f, 0.0f).y;
    }

    /**
     * Remplace le dôme vanilla par un fond noir. Un effacement est nécessaire : sous le dôme, le framebuffer
     * contient la couleur du brouillard, qui transparaîtrait au travers de l'atmosphère.
     */
    public static void clearSkyBackground() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer previous = stack.mallocFloat(4);
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, previous);
            RenderSystem.clearColor(0.0f, 0.0f, 0.0f, 0.0f);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.clearColor(previous.get(0), previous.get(1), previous.get(2), previous.get(3));
        }
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
        // Avec un shaderpack Iris actif, le ciel intérieur, le soleil et la lune appartiennent au pack ;
        // seuls les astres lointains restent dessinés.
        boolean irisSky = IrisCompat.isShaderPackInUse();

        Vec3 cameraPos = camera.getPosition();
        // Plancher de 5 m : au niveau exact de la sphère, l'intersection avec le sol devient instable.
        double altitudeKm = Math.max(0.005, (cameraPos.y - layout.seaLevelY()) * layout.verticalKmPerBlock());
        float timeOfDay = level.getTimeOfDay(partialTick);
        Vector3f sunDirection;
        float starIlluminance = layout.starIlluminance();
        float starAngularRadius = layout.starAngularRadius();
        float exposure = layout.exposure();
        if (layout.heliocentric()) {
            // Espace : la caméra est à sa position réelle dans le système (plus le trajet du saut quantique en cours).
            Vec3 offset = odyssea.client.ship.ShipControls.quantumOffset(partialTick);
            cameraSystemKm.set(cameraPos.x + offset.x, cameraPos.y + offset.y, cameraPos.z + offset.z);
            Vector3d toStar = new Vector3d(layout.observerOriginKm()).sub(cameraSystemKm);
            double distance = Math.max(toStar.length(), 1.0);
            double flux = Math.min(400.0, Math.pow(odyssea.space.SpaceSystem.AU / distance, 2.0));
            sunDirection = new Vector3f((float) (toStar.x / distance), (float) (toStar.y / distance), (float) (toStar.z / distance));
            starIlluminance = (float) (22.0 * flux);
            starAngularRadius = (float) Math.min(0.6, SUN_ANGULAR_RADIUS * odyssea.space.SpaceSystem.AU / distance);
            exposure = (float) Math.clamp(0.9 * Math.pow(1.0 / flux, 0.65), 0.05, 60.0);
        } else {
            cameraSystemKm.set(layout.observerOriginKm()).add(
                    cameraPos.x * layout.horizontalKmPerBlock(), altitudeKm, cameraPos.z * layout.horizontalKmPerBlock());
            sunDirection = layout.sunDirection(timeOfDay);
        }

        passes.clear();
        float pixelAngleEstimate = 2.0f / (projection.m11() * Math.max(1, minecraft.getWindow().getHeight()));
        for (CelestialBody body : layout.visibleBodies()) {
            Vector3d relative = new Vector3d(body.systemPositionKm()).sub(cameraSystemKm);
            double distance = relative.length();
            // Astre plus petit qu'un tiers de pixel (atmosphère et anneaux compris) : invisible, passe économisée.
            double extent = body.radiusKm() + body.atmosphere().scattering().thicknessKm()
                    + (body.rings() != null ? body.rings().outerRadiusKm() : 0.0);
            if (layout.heliocentric() && extent / distance < pixelAngleEstimate * 0.3) {
                continue;
            }
            passes.add(new BodyPass(body, new Vector3f((float) relative.x, (float) relative.y, (float) relative.z), distance, false));
        }
        Vector3f moonDirection = null;
        if (!irisSky) {
            double localDays = layout.localDays(level.getDayTime() + partialTick);
            for (SkyTrack track : layout.trackedBodies()) {
                Vector3f direction = track.direction(timeOfDay, localDays, layout.starPathTiltRadians());
                if (layout.retrograde()) {
                    direction.mul(-1.0f, 1.0f, 1.0f);
                }
                passes.add(new BodyPass(track.body(), new Vector3f(direction).mul((float) track.distanceKm()), track.distanceKm(), false));
                if (moonDirection == null) {
                    moonDirection = direction;
                }
            }
        }
        passes.sort(Comparator.comparingDouble(BodyPass::distanceKm).reversed());

        CelestialBody home = layout.homeBody();
        boolean homeDrawn = home != null && home.atmosphere().scattering().hasAtmosphere() && !irisSky;
        if (homeDrawn) {
            // En dernier : l'atmosphère atténue les astres dessinés avant elle (la face sombre de la lune
            // disparaît de jour) et ajoute sa propre diffusion par-dessus.
            float depth = (float) (home.radiusKm() + altitudeKm);
            passes.add(new BodyPass(home, new Vector3f(0.0f, -depth, 0.0f), depth, true));
        }
        if (passes.isEmpty()) {
            if (irisSky) {
                return;
            }
            // Ciel sans atmosphère ni astre visible (Mercure) : une passe réduite au disque stellaire.
            passes.add(new BodyPass(STAR_ONLY, new Vector3f(), 0.0, false));
        }

        ShaderProgram shader = VeilRenderSystem.setShader(PROGRAM);
        if (shader == null) {
            shaderReady = false;
            return;
        }
        shaderReady = true;
        shader.bind();

        inverseViewProjection.set(projection).mul(view).invert();
        double timeSeconds = (level.getGameTime() + partialTick) / 20.0;
        // Angle sous-tendu par un pixel : 2·tan(fov/2)/hauteur, avec P[1][1] = 1/tan(fov/2).
        float pixelAngle = 2.0f / (projection.m11() * Math.max(1, minecraft.getWindow().getHeight()));
        setMatrix(shader, "uInvViewProj", inverseViewProjection);
        setFloat(shader, "uPixelAngle", pixelAngle);
        setFloat(shader, "uExposure", exposure);
        setVec3(shader, "uSunDirection", sunDirection);
        setVec3(shader, "uSunColor", layout.starColor());
        setFloat(shader, "uSunIlluminance", starIlluminance);
        setFloat(shader, "uStarAngularRadius", starAngularRadius);
        setFloat(shader, "uStarDiscRadius", Math.max(starAngularRadius * STAR_DISC_VISUAL_SCALE, pixelAngle * STAR_DISC_MIN_PIXELS));
        org.joml.Vector4f[] horizon = layout.homeBody() != null ? odyssea.client.HorizonClient.uniforms(level, cameraPos.y) : null;

        float moonGlow = 0.0f;
        if (moonDirection != null) {
            // Fraction éclairée vue d'ici : 1 à l'opposition (pleine lune), 0 en conjonction.
            float illuminated = 0.5f * (1.0f - moonDirection.dot(sunDirection));
            float aboveHorizon = Math.clamp((moonDirection.y + 0.05f) / 0.1f, 0.0f, 1.0f);
            moonGlow = MOON_HALO_STRENGTH * illuminated * aboveHorizon;
            setVec3(shader, "uMoonDirection", moonDirection);
        }
        setFloat(shader, "uMoonGlow", moonGlow);

        // Le disque stellaire est dessiné dans l'atmosphère d'accueil s'il y en a une (il rougit au couchant),
        // sinon dans la passe la plus lointaine, que les astres plus proches occultent.
        int starDiscPass = irisSky ? -1 : (homeDrawn ? passes.size() - 1 : 0);

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        try {
            for (int i = 0; i < passes.size(); i++) {
                BodyPass pass = passes.get(i);
                applyBody(shader, pass, timeSeconds, i == starDiscPass);
                setHorizon(shader, pass.home() ? horizon : null);
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
        // L'observateur tourne avec sa planète : sa surface et ses nuages sont fixes dans son repère.
        float rotation = !pass.home() && body.rotationPeriodSeconds() > 0.0f
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
        setVec3(shader, "uMieAnisotropy", scattering.mieAnisotropy());
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
     * Direction sur la course céleste, cohérente avec le soleil vanilla : à déclinaison nulle, rotation X de
     * fraction·360° puis Y de −90° appliquée au zénith, soit (−sin θ, cos θ, 0). La déclinaison décale la course
     * vers +Z (petit cercle), puis le plan est incliné autour de X de la latitude de l'observateur.
     */
    static Vector3f trackDirection(float turnFraction, float tiltRadians, float declinationRadians) {
        double theta = turnFraction * Math.PI * 2.0;
        double cosDec = Math.cos(declinationRadians);
        double x = -Math.sin(theta) * cosDec;
        double y = Math.cos(theta) * cosDec;
        double z = Math.sin(declinationRadians);
        double cosTilt = Math.cos(tiltRadians);
        double sinTilt = Math.sin(tiltRadians);
        return new Vector3f((float) x, (float) (y * cosTilt - z * sinTilt), (float) (y * sinTilt + z * cosTilt)).normalize();
    }

    /** Horizon lointain (Terra) : silhouette du relief et couleur du sol par azimut, sinon désactivé. */
    private static void setHorizon(ShaderProgram shader, org.joml.Vector4f @Nullable [] horizon) {
        setInt(shader, "uHorizonActive", horizon != null ? 1 : 0);
        if (horizon == null) {
            return;
        }
        ShaderUniform uniform = shader.getUniform("uHorizon");
        if (uniform == null) {
            uniform = shader.getUniform("uHorizon[0]");
        }
        if (uniform != null) {
            uniform.setVectors(horizon);
        }
        setFloat(shader, "uHorizonEyeHeight", odyssea.client.HorizonClient.eyeHeightKm());
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
    static final class IrisCompat {
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
}
