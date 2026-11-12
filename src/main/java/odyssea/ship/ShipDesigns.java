package odyssea.ship;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.io.InputStream;
import java.io.Reader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Modèles de vaisseaux du datapack : {@code data/<ns>/ships/<id>.json} décrit le vaisseau, {@code schematic} désigne
 * un fichier {@code data/<ns>/ships/<nom>.schem} (Sponge v2/v3). Ajouter un vaisseau = déposer ces deux fichiers.
 */
public final class ShipDesigns extends SimplePreparableReloadListener<Map<ResourceLocation, ShipDesigns.Loaded>> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile Map<ResourceLocation, Loaded> designs = Map.of();

    public record Loaded(ShipDesign design, ShipStructure structure) {
    }

    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ShipDesigns());
    }

    public static @Nullable Loaded get(ResourceLocation id) {
        return designs.get(id);
    }

    public static Map<ResourceLocation, Loaded> all() {
        return designs;
    }

    @Override
    protected Map<ResourceLocation, Loaded> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, Loaded> loaded = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> files = manager.listResources("ships", location -> location.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> entry : files.entrySet()) {
            ResourceLocation file = entry.getKey();
            String path = file.getPath().substring("ships/".length(), file.getPath().length() - ".json".length());
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(file.getNamespace(), path);
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonObject json = GsonHelper.parse(reader);
                ResourceLocation schematic = ResourceLocation.parse(GsonHelper.getAsString(json, "schematic", id.toString()));
                ResourceLocation schematicFile = schematic.withPath(p -> "ships/" + p + ".schem");
                Optional<Resource> resource = manager.getResource(schematicFile);
                if (resource.isEmpty()) {
                    LOGGER.error("Vaisseau {} : schématique {} introuvable", id, schematicFile);
                    continue;
                }
                ShipStructure raw;
                try (InputStream input = resource.get().open()) {
                    raw = SchematicReader.read(input, BuiltInRegistries.BLOCK.asLookup());
                }
                raw = raw.withoutStrayBlocks(GsonHelper.getAsInt(json, "stray_block_limit", 2));
                Rotation rotation = ShipDesign.rotationFor(GsonHelper.getAsString(json, "forward", "+z"));
                ShipStructure rotated = raw.rotated(rotation);
                int[] offset = rotated.trimOffset();
                ShipStructure structure = rotated.trimmed();
                ShipDesign design = ShipDesign.parse(id, json, raw, rotation, offset);
                loaded.put(id, new Loaded(design, structure));
                LOGGER.info("Vaisseau {} chargé : {} blocs, {}×{}×{}", id, structure.blockCount(),
                        structure.sizeX(), structure.sizeY(), structure.sizeZ());
            } catch (Exception e) {
                LOGGER.error("Vaisseau {} illisible", id, e);
            }
        }
        return loaded;
    }

    @Override
    protected void apply(Map<ResourceLocation, Loaded> loaded, ResourceManager manager, ProfilerFiller profiler) {
        designs = Collections.unmodifiableMap(loaded);
    }
}
