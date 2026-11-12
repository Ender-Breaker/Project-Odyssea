package odyssea.worldgen.preview;

import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import odyssea.OdysseaCore;
import org.slf4j.Logger;

/**
 * Mode développement ({@code ./gradlew runPreviewServer}) : au démarrage du serveur, rend l'overworld et chaque
 * planète dans {@code run/preview/odyssea_previews/}, puis arrête le serveur. Inactif sans la propriété système
 * {@code odyssea.preview}.
 */
public final class PreviewOnStartup {
    private static final Logger LOGGER = LogUtils.getLogger();

    private PreviewOnStartup() {
    }

    public static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("odyssea.preview")) {
            return;
        }
        MinecraftServer server = event.getServer();
        // Garde-fou : le serveur s'arrête quoi qu'il arrive au bout du délai (rendu trop lent, blocage).
        long timeoutMinutes = Long.getLong("odyssea.preview.timeoutMinutes", 20L);
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(timeoutMinutes * 60_000L);
                LOGGER.error("[Odyssea preview] délai de {} min dépassé, arrêt du serveur", timeoutMinutes);
                server.halt(false);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "Odyssea-Preview-Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        Path directory = server.getServerDirectory().resolve("odyssea_previews");
        int centerX = Integer.getInteger("odyssea.preview.x", 0);
        int centerZ = Integer.getInteger("odyssea.preview.z", 0);
        String only = System.getProperty("odyssea.preview.only", "");
        // Monde jetable : on avance l'horloge (à dayTime = 0 toutes les planètes sont à l'aube, rien ne se distingue).
        server.overworld().setDayTime(30_000L);
        for (ServerLevel level : server.getAllLevels()) {
            // Contrôle du mixin de durée du jour : l'heure locale doit différer de l'heure vanilla hors de la Terre.
            LOGGER.info("[Odyssea preview] {} : heure locale {} (vanilla {}), jour : {}", level.dimension().location(),
                    String.format("%.3f", level.getTimeOfDay(1.0f)),
                    String.format("%.3f", level.dimensionType().timeOfDay(level.getDayTime())), level.isDay());
        }
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (ServerLevel level : server.getAllLevels()) {
            boolean ours = level.dimension() == Level.OVERWORLD || level.dimension().location().getNamespace().equals(OdysseaCore.MOD_ID);
            if (!ours || (!only.isEmpty() && !only.contains(level.dimension().location().getPath()))) {
                continue;
            }
            chain = chain.thenCompose(ignored -> TerrainPreview.renderAsync(server, level,
                    TerrainPreview.Options.standard(centerX, centerZ), directory));
        }
        chain.whenComplete((ignored, error) -> {
            if (error != null) {
                LOGGER.error("[Odyssea preview] échec", error);
            } else {
                LOGGER.info("[Odyssea preview] terminé : {}", directory.toAbsolutePath());
            }
            server.execute(() -> server.halt(false));
        });
    }
}
