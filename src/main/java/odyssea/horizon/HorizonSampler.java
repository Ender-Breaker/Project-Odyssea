package odyssea.horizon;

import com.mojang.logging.LogUtils;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import odyssea.network.OdysseaNetwork;
import org.slf4j.Logger;

/**
 * Horizon lointain de Terra. Au-delà de la distance de rendu, le ciel dessinait un sol vert uniforme ; on échantillonne
 * ici, sans générer de tronçon, le relief et le biome que le générateur produirait sur quatre anneaux (320 à 2 400
 * blocs) dans 32 directions. Le client en tire la silhouette des montagnes lointaines et la couleur du sol (océans,
 * déserts, forêts, neiges) dans chaque direction.
 *
 * <p>Calcul hors du fil serveur (le générateur est sûr en accès concurrent), au plus une requête en cours par joueur,
 * renouvelée quand le joueur s'est déplacé de 96 blocs ou toutes les 30 secondes.
 */
public final class HorizonSampler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double[] RINGS = {320.0, 700.0, 1300.0, 2400.0};
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Odyssea horizon");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static final Map<UUID, Request> LAST = new ConcurrentHashMap<>();

    private record Request(Vec3 position, long gameTime, boolean running) {
    }

    private HorizonSampler() {
    }

    public static void tick(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD || level.getGameTime() % 20 != 0) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            Request last = LAST.get(player.getUUID());
            Vec3 here = player.position();
            if (last != null && (last.running()
                    || (last.position().distanceToSqr(here) < 96.0 * 96.0 && level.getGameTime() - last.gameTime() < 600))) {
                continue;
            }
            LAST.put(player.getUUID(), new Request(here, level.getGameTime(), true));
            submit(level, player, here, player.getEyeY());
        }
    }

    private static void submit(ServerLevel level, ServerPlayer player, Vec3 here, double eyeY) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState random = level.getChunkSource().randomState();
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        int seaLevel = generator.getSeaLevel();
        long time = level.getGameTime();
        try {
            WORKER.execute(() -> {
                int azimuths = OdysseaNetwork.Horizon.AZIMUTHS;
                int rings = OdysseaNetwork.Horizon.RINGS;
                float[] elevation = new float[azimuths];
                int[] ids = new int[azimuths * rings];
                float[] heights = new float[azimuths * rings];
                try {
                    for (int a = 0; a < azimuths; a++) {
                        double angle = a * Math.PI * 2.0 / azimuths;
                        double sin = Math.sin(angle);
                        double cos = Math.cos(angle);
                        double best = -Math.PI / 2.0;
                        for (int r = 0; r < rings; r++) {
                            int x = (int) Math.floor(here.x + sin * RINGS[r]);
                            int z = (int) Math.floor(here.z + cos * RINGS[r]);
                            int height = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
                            int surface = Math.max(height, seaLevel);
                            Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(x >> 2, surface >> 2, z >> 2, random.sampler());
                            ids[a * rings + r] = biomes.getId(biome.value());
                            heights[a * rings + r] = height;
                            best = Math.max(best, Math.atan2(surface - eyeY, RINGS[r]));
                        }
                        elevation[a] = (float) best;
                    }
                    OdysseaNetwork.Horizon payload = new OdysseaNetwork.Horizon((float) eyeY, elevation, ids, heights);
                    level.getServer().execute(() -> {
                        if (!player.hasDisconnected() && player.level() == level) {
                            PacketDistributor.sendToPlayer(player, payload);
                        }
                    });
                } catch (Exception e) {
                    LOGGER.debug("Échantillonnage de l'horizon interrompu", e);
                } finally {
                    LAST.put(player.getUUID(), new Request(here, time, false));
                }
            });
        } catch (RejectedExecutionException e) {
            LAST.remove(player.getUUID());
        }
    }

    public static void forget(UUID player) {
        LAST.remove(player);
    }
}
