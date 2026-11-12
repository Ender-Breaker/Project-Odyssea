package odyssea.ship;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * Lecture des schématiques Sponge (versions 2 et 3, format de WorldEdit et de Litematica/Forgematica en export
 * {@code .schem}). Les blocs sont stockés en varints indexant une palette d'états textuels ; l'ordre des cellules est
 * {@code (y · longueur + z) · largeur + x}.
 *
 * <p>Les barrières et les vides de structure servent aux constructeurs (collisions invisibles, gabarits) : ils sont
 * retirés, sinon un vaisseau garé laisserait des blocs incassables.
 */
public final class SchematicReader {
    private static final Logger LOGGER = LogUtils.getLogger();

    private SchematicReader() {
    }

    public static ShipStructure read(InputStream input, HolderLookup<Block> blocks) throws IOException {
        CompoundTag root = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
        // Version 3 : tout est rangé sous « Schematic », les blocs dans un sous-composé « Blocks ».
        CompoundTag schematic = root.contains("Schematic", Tag.TAG_COMPOUND) ? root.getCompound("Schematic") : root;
        int width = schematic.getShort("Width") & 0xFFFF;
        int height = schematic.getShort("Height") & 0xFFFF;
        int length = schematic.getShort("Length") & 0xFFFF;
        CompoundTag container = schematic.contains("Blocks", Tag.TAG_COMPOUND) ? schematic.getCompound("Blocks") : schematic;
        CompoundTag paletteTag = container.getCompound("Palette");
        byte[] data = container.getByteArray(container.contains("Data") ? "Data" : "BlockData");

        Map<Integer, BlockState> palette = new HashMap<>();
        for (String key : paletteTag.getAllKeys()) {
            palette.put(paletteTag.getInt(key), parseState(key, blocks));
        }

        ShipStructure.Builder builder = new ShipStructure.Builder(width, height, length);
        int index = 0;
        int offset = 0;
        int total = width * height * length;
        while (offset < data.length && index < total) {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                b = data[offset++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && offset < data.length);
            BlockState state = palette.getOrDefault(value, Blocks.AIR.defaultBlockState());
            int x = index % width;
            int z = (index / width) % length;
            int y = index / (width * length);
            builder.set(x, y, z, state);
            index++;
        }

        ListTag entities = container.getList("BlockEntities", Tag.TAG_COMPOUND);
        if (entities.isEmpty()) {
            entities = schematic.getList("TileEntities", Tag.TAG_COMPOUND);
        }
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag entry = entities.getCompound(i).copy();
            int[] pos = entry.getIntArray("Pos");
            if (pos.length != 3) {
                continue;
            }
            // v3 range les données propres au bloc sous « Data » ; v2 les met à plat à côté de Pos et Id.
            CompoundTag payload = entry.contains("Data", Tag.TAG_COMPOUND) ? entry.getCompound("Data").copy() : entry;
            String id = entry.getString("Id");
            payload.remove("Pos");
            payload.remove("Id");
            if (!id.isEmpty()) {
                payload.putString("id", id);
            }
            builder.blockEntity(pos[0], pos[1], pos[2], payload);
        }
        return builder.build();
    }

    private static BlockState parseState(String text, HolderLookup<Block> blocks) {
        try {
            BlockState state = BlockStateParser.parseForBlock(blocks, text, false).blockState();
            if (state.is(Blocks.BARRIER) || state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.LIGHT) || state.is(Blocks.STRUCTURE_BLOCK)) {
                return Blocks.AIR.defaultBlockState();
            }
            return state;
        } catch (Exception e) {
            LOGGER.warn("Bloc de schématique inconnu « {} » remplacé par de l'air", text);
            return Blocks.AIR.defaultBlockState();
        }
    }
}
