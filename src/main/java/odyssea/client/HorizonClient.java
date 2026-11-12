package odyssea.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import odyssea.network.OdysseaNetwork;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Horizon lointain de Terra côté client : à partir des biomes et altitudes échantillonnés par le serveur, une couleur
 * de sol et une élévation de silhouette par azimut, envoyées au shader céleste ({@code uHorizon[32]}).
 *
 * <p>Couleurs (albédos, vues de loin) : mer et rivières sombres, plages et déserts sable, badlands ocre, neige au-dessus
 * de la limite climatique, roche nue des pics, et pour le reste la teinte d'herbe et de feuillage du biome.
 */
public final class HorizonClient {
    private static final double[] RINGS = {320.0, 700.0, 1300.0, 2400.0};
    private static final float[] RING_WEIGHTS = {0.15f, 0.25f, 0.3f, 0.3f};
    private static final int SEA_LEVEL = 63;
    private static final int SNOW_LINE = 205;

    private static volatile @Nullable Sample sample;
    private static volatile float eyeHeightKm = 0.01f;
    private static final Vector4f[] UNIFORM = new Vector4f[OdysseaNetwork.Horizon.AZIMUTHS];

    static {
        for (int i = 0; i < UNIFORM.length; i++) {
            UNIFORM[i] = new Vector4f();
        }
    }

    private record Sample(ResourceKey<Level> dimension, float[] heights, Vector3f[] colors) {
    }

    private HorizonClient() {
    }

    public static void accept(OdysseaNetwork.Horizon payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Registry<Biome> registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        int azimuths = OdysseaNetwork.Horizon.AZIMUTHS;
        int rings = OdysseaNetwork.Horizon.RINGS;
        Vector3f[] colors = new Vector3f[azimuths];
        for (int a = 0; a < azimuths; a++) {
            Vector3f sum = new Vector3f();
            float weight = 0.0f;
            for (int r = 0; r < rings; r++) {
                int index = a * rings + r;
                Holder<Biome> biome = registry.getHolder(payload.biomes()[index]).orElse(null);
                if (biome == null) {
                    continue;
                }
                double angle = a * Math.PI * 2.0 / azimuths;
                int x = (int) (Math.sin(angle) * RINGS[r]);
                int z = (int) (Math.cos(angle) * RINGS[r]);
                Vector3f color = albedo(biome, payload.heights()[index], x, z);
                sum.add(color.mul(RING_WEIGHTS[r]));
                weight += RING_WEIGHTS[r];
            }
            colors[a] = weight > 0.0f ? sum.div(weight) : new Vector3f(0.13f, 0.25f, 0.08f);
        }
        sample = new Sample(level.dimension(), payload.heights().clone(), colors);
    }

    /** Hauteur de l'œil au-dessus de la mer (km, échelle réelle), calculée au dernier appel de {@link #uniforms}. */
    public static float eyeHeightKm() {
        return eyeHeightKm;
    }

    public static void clear() {
        sample = null;
    }

    /** Couleur vue de loin d'une colonne de terrain. */
    private static Vector3f albedo(Holder<Biome> holder, float height, int x, int z) {
        Biome biome = holder.value();
        if (holder.is(BiomeTags.IS_DEEP_OCEAN)) {
            return new Vector3f(0.02f, 0.06f, 0.15f);
        }
        if (holder.is(BiomeTags.IS_OCEAN)) {
            return biome.coldEnoughToSnow(new BlockPos(x, SEA_LEVEL, z)) ? new Vector3f(0.62f, 0.70f, 0.78f) : new Vector3f(0.03f, 0.09f, 0.19f);
        }
        if (holder.is(BiomeTags.IS_RIVER)) {
            return new Vector3f(0.05f, 0.12f, 0.20f);
        }
        String path = holder.unwrapKey().map(key -> key.location().getPath()).orElse("");
        Vector3f color;
        if (holder.is(BiomeTags.IS_BADLANDS)) {
            color = new Vector3f(0.58f, 0.30f, 0.15f);
        } else if (holder.is(BiomeTags.IS_BEACH) || path.contains("desert")) {
            color = new Vector3f(0.66f, 0.60f, 0.43f);
        } else if (path.contains("peaks") || path.contains("stony") || path.contains("windswept_gravelly")) {
            color = new Vector3f(0.36f, 0.36f, 0.38f);
        } else if (path.contains("mushroom")) {
            color = new Vector3f(0.40f, 0.30f, 0.34f);
        } else {
            int grass = biome.getGrassColor(x, z);
            int foliage = biome.getFoliageColor();
            Vector3f g = new Vector3f(((grass >> 16) & 255) / 255.0f, ((grass >> 8) & 255) / 255.0f, (grass & 255) / 255.0f);
            Vector3f f = new Vector3f(((foliage >> 16) & 255) / 255.0f, ((foliage >> 8) & 255) / 255.0f, (foliage & 255) / 255.0f);
            boolean forest = holder.is(BiomeTags.IS_FOREST) || holder.is(BiomeTags.IS_JUNGLE) || holder.is(BiomeTags.IS_TAIGA);
            color = g.lerp(f, forest ? 0.75f : 0.35f).mul(forest ? 0.28f : 0.36f);
        }
        boolean snowy = biome.coldEnoughToSnow(new BlockPos(x, (int) height, z)) || height > SNOW_LINE;
        if (snowy && !path.contains("desert") && !holder.is(BiomeTags.IS_BADLANDS)) {
            color.lerp(new Vector3f(0.80f, 0.84f, 0.88f), holder.is(BiomeTags.IS_TAIGA) || holder.is(BiomeTags.IS_FOREST) ? 0.55f : 0.9f);
        }
        return color;
    }

    /**
     * Valeurs pour le shader dans la dimension courante, ou null : rgb = albédo, w = élévation de la silhouette
     * (radians au-dessus de l'horizontale de la caméra).
     */
    public static Vector4f @Nullable [] uniforms(ClientLevel level, double cameraY) {
        Sample current = sample;
        // Au-delà de Y 480, le ciel reprend son échelle exagérée (vue d'altitude vers l'espace).
        if (current == null || current.dimension() != level.dimension() || level.dimension() != Level.OVERWORLD || cameraY > 480.0) {
            return null;
        }
        eyeHeightKm = (float) Math.max(0.002, (cameraY - SEA_LEVEL) / 1000.0);
        int azimuths = OdysseaNetwork.Horizon.AZIMUTHS;
        int rings = OdysseaNetwork.Horizon.RINGS;
        for (int a = 0; a < azimuths; a++) {
            double best = -Math.PI / 2.0;
            for (int r = 0; r < rings; r++) {
                double surface = Math.max(current.heights()[a * rings + r], SEA_LEVEL) + 1.0;
                best = Math.max(best, Math.atan2(surface - cameraY, RINGS[r]));
            }
            Vector3f color = current.colors()[a];
            UNIFORM[a].set(color.x, color.y, color.z, (float) best);
        }
        return UNIFORM;
    }
}
