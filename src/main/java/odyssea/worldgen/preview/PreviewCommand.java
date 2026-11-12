package odyssea.worldgen.preview;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.nio.file.Path;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /odyssea preview [échelle]} : rend le terrain autour de l'exécutant dans
 * {@code <dossier du serveur>/odyssea_previews/}. Réservé aux opérateurs (niveau 2).
 */
public final class PreviewCommand {
    private PreviewCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("odyssea")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("preview")
                        .executes(context -> run(context, 24))
                        .then(Commands.argument("step", IntegerArgumentType.integer(1, 256))
                                .executes(context -> run(context, IntegerArgumentType.getInteger(context, "step"))))));
    }

    private static int run(CommandContext<CommandSourceStack> context, int step) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        Vec3 position = source.getPosition();
        Path directory = source.getServer().getServerDirectory().resolve("odyssea_previews");
        TerrainPreview.Options defaults = TerrainPreview.Options.standard((int) position.x, (int) position.z);
        TerrainPreview.Options options = new TerrainPreview.Options(defaults.centerX(), defaults.centerZ(), defaults.pixels(), step,
                defaults.detailPixels(), defaults.detailStep(), defaults.sectionWidth(), defaults.surfaceChunks());
        source.sendSuccess(() -> Component.literal("Rendu du terrain en cours vers " + directory), true);
        TerrainPreview.renderAsync(source.getServer(), level, options, directory).whenComplete((ignored, error) ->
                source.getServer().execute(() -> {
                    if (error != null) {
                        source.sendFailure(Component.literal("Échec du rendu : " + error.getMessage()));
                    } else {
                        source.sendSuccess(() -> Component.literal("Rendu terminé : " + directory), true);
                    }
                }));
        return 1;
    }
}
