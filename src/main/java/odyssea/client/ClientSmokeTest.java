package odyssea.client;

import com.mojang.logging.LogUtils;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import odyssea.dimension.Planets;
import org.slf4j.Logger;

/**
 * Test de fumée du client ({@code ./gradlew runSmokeClient}) : le client entre directement dans un monde
 * (--quickPlaySingleplayer), on mesure le temps jusqu'à l'affichage du monde puis les FPS. Avec
 * {@code -Dodyssea.smokeTest.tour=odyssea:mercury,odyssea:mars,…}, le joueur (en spectateur) visite chaque dimension
 * un matin local et des captures d'écran sont prises vers le Soleil et à l'opposé (dossier {@code screenshots/}).
 * Le jeu se ferme seul à la fin. Inactif sans la propriété système {@code odyssea.smokeTest}.
 */
final class ClientSmokeTest {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long TIMEOUT_MS = 240_000L;
    private static final int MEASURE_TICKS = 400;
    private static final int SETTLE_TICKS = 220;

    private static final long START = System.currentTimeMillis();
    private static final Deque<String> TOUR = new ArrayDeque<>();
    private static long worldShownAt = -1L;
    private static long minFps = Long.MAX_VALUE;
    private static long fpsSum;
    private static int fpsSamples;
    private static int ticks;
    private static String currentStop;
    private static int stopTicks;
    private static boolean creationRequested;
    /** Monde jetable recréé à chaque test (les sauvegardes du joueur ne sont jamais touchées). */
    private static final String SMOKE_WORLD = "odyssea_smoke";

    private ClientSmokeTest() {
    }

    static boolean enabled() {
        return Boolean.getBoolean("odyssea.smokeTest");
    }

    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (!creationRequested && Boolean.getBoolean("odyssea.smokeTest.create") && minecraft.level == null
                && minecraft.screen instanceof TitleScreen) {
            creationRequested = true;
            createFreshWorld(minecraft);
            return;
        }
        if (worldShownAt < 0L) {
            if (minecraft.level != null && minecraft.player != null && minecraft.screen == null) {
                worldShownAt = now;
                LOGGER.info("[Odyssea smoke] monde affiché {} s après le lancement (distance de rendu {})",
                        (now - START) / 1000.0, minecraft.options.getEffectiveRenderDistance());
                String tour = System.getProperty("odyssea.smokeTest.tour", "");
                if (!tour.isBlank()) {
                    TOUR.addAll(Arrays.asList(tour.split(",")));
                }
            } else if (now - START > TIMEOUT_MS) {
                LOGGER.error("[Odyssea smoke] ÉCHEC : monde non affiché après {} s (écran : {})", TIMEOUT_MS / 1000,
                        minecraft.screen == null ? "aucun" : minecraft.screen.getClass().getSimpleName());
                minecraft.stop();
            }
            return;
        }
        ticks++;
        if (ticks <= MEASURE_TICKS) {
            if (ticks % 20 == 0) {
                long fps = minecraft.getFps();
                minFps = Math.min(minFps, fps);
                fpsSum += fps;
                fpsSamples++;
            }
            if (ticks == MEASURE_TICKS) {
                LOGGER.info("[Odyssea smoke] OK : FPS moyen {}, minimum {} ({})", fpsSamples == 0 ? 0 : fpsSum / fpsSamples, minFps,
                        minecraft.levelRenderer.getSectionStatistics());
            }
            return;
        }
        tour(minecraft);
    }

    private static void tour(Minecraft minecraft) {
        if (currentStop == null) {
            if (TOUR.isEmpty()) {
                LOGGER.info("[Odyssea smoke] visite terminée");
                minecraft.stop();
                return;
            }
            currentStop = TOUR.poll().trim();
            stopTicks = 0;
            if (!currentStop.equals("ships")) {
                teleport(minecraft, currentStop);
            }
            return;
        }
        stopTicks++;
        if (currentStop.equals("ships")) {
            if (ClientShipSmokeTest.tick(minecraft)) {
                currentStop = null;
            }
            return;
        }
        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null
                || !minecraft.level.dimension().location().toString().equals(currentStop)) {
            if (stopTicks > 1200) {
                LOGGER.error("[Odyssea smoke] {} : la dimension ne s'est pas chargée", currentStop);
                currentStop = null;
            }
            return;
        }
        minecraft.options.hideGui = true;
        String name = currentStop.replace(':', '_');
        if (stopTicks == SETTLE_TICKS) {
            look(minecraft, -90.0f, -12.0f);
        } else if (stopTicks == SETTLE_TICKS + 20) {
            shoot(minecraft, name + "_sun");
            look(minecraft, 90.0f, -25.0f);
        } else if (stopTicks == SETTLE_TICKS + 40) {
            shoot(minecraft, name + "_away");
            look(minecraft, 180.0f, 20.0f);
        } else if (stopTicks == SETTLE_TICKS + 60) {
            shoot(minecraft, name + "_down");
            LOGGER.info("[Odyssea smoke] {} : {} FPS", currentStop, minecraft.getFps());
            currentStop = null;
        }
    }

    private static void teleport(Minecraft minecraft, String dimension) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null) {
            return;
        }
        java.util.UUID id = minecraft.player.getUUID();
        server.execute(() -> {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension));
            ServerLevel target = server.getLevel(key);
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (target == null || player == null) {
                LOGGER.error("[Odyssea smoke] dimension inconnue : {}", dimension);
                return;
            }
            // Matin local (~9 h) : le Soleil est bas à l'est, les ombres et l'atmosphère se lisent bien.
            Planets.Planet planet = Planets.get(key);
            double dayLength = planet == null ? 1.0 : Math.abs(planet.solarDay());
            server.overworld().setDayTime((long) (0.12 * 24_000.0 * dayLength));
            player.setGameMode(GameType.SPECTATOR);
            int x = 0;
            int z = 0;
            target.getChunk(x >> 4, z >> 4);
            int ground = target.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            double y = ground > target.getMinBuildHeight() + 1 ? ground + 24.0 : 100.0;
            player.teleportTo(target, x + 0.5, y, z + 0.5, -90.0f, -12.0f);
            LOGGER.info("[Odyssea smoke] {} : téléporté à y = {} (sol à {})", dimension, (int) y, ground);
        });
    }

    private static void createFreshWorld(Minecraft minecraft) {
        Path folder = minecraft.gameDirectory.toPath().resolve("saves").resolve(SMOKE_WORLD);
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.walk(folder)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException e) {
                LOGGER.warn("[Odyssea smoke] impossible de supprimer l'ancien monde de test", e);
            }
        }
        LevelSettings settings = new LevelSettings(SMOKE_WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        LOGGER.info("[Odyssea smoke] création du monde de test {}", SMOKE_WORLD);
        minecraft.createWorldOpenFlows().createFreshLevel(SMOKE_WORLD, settings, new WorldOptions(20261111L, true, false),
                WorldPresets::createNormalWorldDimensions, minecraft.screen);
    }

    private static void look(Minecraft minecraft, float yaw, float pitch) {
        minecraft.player.setYRot(yaw);
        minecraft.player.setXRot(pitch);
        minecraft.player.yRotO = yaw;
        minecraft.player.xRotO = pitch;
    }

    private static void shoot(Minecraft minecraft, String name) {
        Screenshot.grab(minecraft.gameDirectory, "odyssea_" + name + ".png", minecraft.getMainRenderTarget(),
                message -> LOGGER.info("[Odyssea smoke] capture {}", name));
    }
}
