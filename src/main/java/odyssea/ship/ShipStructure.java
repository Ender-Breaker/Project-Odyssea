package odyssea.ship;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Blocs d'un vaisseau, dans une grille locale (x, y, z ≥ 0). Stockage dense par palette : un {@code short} par
 * cellule, l'indice 0 étant l'air. Les données des blocs à entité (coffres, boîtes de Shulker…) sont conservées et
 * suivent le vaisseau : la soute transporte vraiment sa cargaison.
 *
 * <p>Deux sérialisations : NBT (palette textuelle, stable d'une version à l'autre) pour la sauvegarde, et réseau
 * (identifiants numériques des états, valables pour la session) pour l'envoi au client. Les cellules sont compressées
 * (deflate) : un destroyer de 300 blocs de long et 87 000 blocs tient en quelques dizaines de kilo-octets.
 */
public final class ShipStructure {
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final BlockState[] palette;
    private final short[] cells;
    private final Long2ObjectOpenHashMap<CompoundTag> blockEntities;
    private final int blockCount;

    public ShipStructure(int sizeX, int sizeY, int sizeZ, BlockState[] palette, short[] cells, Long2ObjectOpenHashMap<CompoundTag> blockEntities) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.palette = palette;
        this.cells = cells;
        this.blockEntities = blockEntities;
        int count = 0;
        for (short cell : cells) {
            if (cell != 0) {
                count++;
            }
        }
        this.blockCount = count;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int blockCount() {
        return blockCount;
    }

    private long contentHash;

    /** Empreinte du contenu (dimensions, palette, cellules) : clé du cache de maillages côté client. */
    public long contentHash() {
        long hash = contentHash;
        if (hash == 0L) {
            hash = 0xCBF29CE484222325L;
            hash = (hash ^ sizeX) * 0x100000001B3L;
            hash = (hash ^ sizeY) * 0x100000001B3L;
            hash = (hash ^ sizeZ) * 0x100000001B3L;
            for (BlockState state : palette) {
                hash = (hash ^ Block.getId(state)) * 0x100000001B3L;
            }
            for (short cell : cells) {
                hash = (hash ^ cell) * 0x100000001B3L;
            }
            contentHash = hash == 0L ? 1L : hash;
        }
        return contentHash;
    }

    public boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
    }

    private int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    public BlockState get(int x, int y, int z) {
        return inside(x, y, z) ? palette[cells[index(x, y, z)]] : Blocks.AIR.defaultBlockState();
    }

    public boolean isSolidAt(int x, int y, int z) {
        return inside(x, y, z) && cells[index(x, y, z)] != 0;
    }

    public @Nullable CompoundTag blockEntity(int x, int y, int z) {
        return blockEntities.get(BlockPos.asLong(x, y, z));
    }

    public Long2ObjectOpenHashMap<CompoundTag> blockEntities() {
        return blockEntities;
    }

    public interface BlockVisitor {
        void accept(int x, int y, int z, BlockState state);
    }

    public void forEachBlock(BlockVisitor visitor) {
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                int row = (y * sizeZ + z) * sizeX;
                for (int x = 0; x < sizeX; x++) {
                    short cell = cells[row + x];
                    if (cell != 0) {
                        visitor.accept(x, y, z, palette[cell]);
                    }
                }
            }
        }
    }

    /** Blocs les plus bas de chaque colonne et blocs extrêmes de chaque ligne : points de contact avec le terrain. */
    public List<BlockPos> hullSamples(int maxSamples) {
        List<BlockPos> samples = new ArrayList<>();
        for (int z = 0; z < sizeZ; z++) {
            for (int x = 0; x < sizeX; x++) {
                for (int y = 0; y < sizeY; y++) {
                    if (isSolidAt(x, y, z)) {
                        samples.add(new BlockPos(x, y, z));
                        break;
                    }
                }
            }
        }
        for (int y = 0; y < sizeY; y++) {
            for (int x = 0; x < sizeX; x++) {
                for (int z = sizeZ - 1; z >= 0; z--) {
                    if (isSolidAt(x, y, z)) {
                        samples.add(new BlockPos(x, y, z));
                        break;
                    }
                }
                for (int z = 0; z < sizeZ; z++) {
                    if (isSolidAt(x, y, z)) {
                        samples.add(new BlockPos(x, y, z));
                        break;
                    }
                }
            }
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    if (isSolidAt(x, y, z)) {
                        samples.add(new BlockPos(x, y, z));
                        break;
                    }
                }
                for (int x = sizeX - 1; x >= 0; x--) {
                    if (isSolidAt(x, y, z)) {
                        samples.add(new BlockPos(x, y, z));
                        break;
                    }
                }
            }
        }
        if (samples.size() <= maxSamples) {
            return samples;
        }
        List<BlockPos> reduced = new ArrayList<>(maxSamples);
        double step = samples.size() / (double) maxSamples;
        for (int i = 0; i < maxSamples; i++) {
            reduced.add(samples.get((int) (i * step)));
        }
        return reduced;
    }

    /** Cellule d'air la plus proche de {@code near} (siège du pilote), recherche en couronnes successives. */
    public BlockPos nearestFreeCell(BlockPos near) {
        for (int radius = 0; radius < 8; radius++) {
            for (int dy = 0; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        for (int sign : new int[]{1, -1}) {
                            int x = near.getX() + dx;
                            int y = near.getY() + dy * sign;
                            int z = near.getZ() + dz;
                            if (inside(x, y, z) && !isSolidAt(x, y, z) && !isSolidAt(x, y + 1, z)) {
                                return new BlockPos(x, y, z);
                            }
                        }
                    }
                }
            }
        }
        return near;
    }

    // ───────────────────────────── Rotation ─────────────────────────────

    /** Rotation autour de l'axe vertical (quarts de tour), états de blocs compris. */
    public ShipStructure rotated(Rotation rotation) {
        if (rotation == Rotation.NONE) {
            return this;
        }
        boolean swap = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
        int nx = swap ? sizeZ : sizeX;
        int nz = swap ? sizeX : sizeZ;
        BlockState[] newPalette = new BlockState[palette.length];
        for (int i = 0; i < palette.length; i++) {
            newPalette[i] = palette[i].rotate(rotation);
        }
        short[] newCells = new short[cells.length];
        Long2ObjectOpenHashMap<CompoundTag> newEntities = new Long2ObjectOpenHashMap<>();
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    BlockPos target = rotatePos(x, z, rotation);
                    newCells[(y * nz + target.getZ()) * nx + target.getX()] = cells[index(x, y, z)];
                    CompoundTag tag = blockEntities.get(BlockPos.asLong(x, y, z));
                    if (tag != null) {
                        newEntities.put(BlockPos.asLong(target.getX(), y, target.getZ()), tag);
                    }
                }
            }
        }
        return new ShipStructure(nx, sizeY, nz, newPalette, newCells, newEntities);
    }

    /** Position d'une cellule (x, z) après rotation, dans la nouvelle grille. */
    public BlockPos rotatePos(int x, int z, Rotation rotation) {
        return switch (rotation) {
            case NONE -> new BlockPos(x, 0, z);
            case CLOCKWISE_90 -> new BlockPos(sizeZ - 1 - z, 0, x);
            case CLOCKWISE_180 -> new BlockPos(sizeX - 1 - x, 0, sizeZ - 1 - z);
            case COUNTERCLOCKWISE_90 -> new BlockPos(z, 0, sizeX - 1 - x);
        };
    }

    /** Même rotation appliquée à un point continu (sièges, moteurs, canons) exprimé en coordonnées de grille. */
    public double[] rotatePoint(double x, double z, Rotation rotation) {
        return switch (rotation) {
            case NONE -> new double[]{x, z};
            case CLOCKWISE_90 -> new double[]{sizeZ - z, x};
            case CLOCKWISE_180 -> new double[]{sizeX - x, sizeZ - z};
            case COUNTERCLOCKWISE_90 -> new double[]{z, sizeX - x};
        };
    }

    // ───────────────────────────── Construction ─────────────────────────────

    public static final class Builder {
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final short[] cells;
        private final List<BlockState> palette = new ArrayList<>();
        private final Object2IntOpenHashMap<BlockState> indices = new Object2IntOpenHashMap<>();
        private final Long2ObjectOpenHashMap<CompoundTag> blockEntities = new Long2ObjectOpenHashMap<>();

        public Builder(int sizeX, int sizeY, int sizeZ) {
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.cells = new short[sizeX * sizeY * sizeZ];
            palette.add(Blocks.AIR.defaultBlockState());
            indices.put(Blocks.AIR.defaultBlockState(), 0);
        }

        public Builder set(int x, int y, int z, BlockState state) {
            if (state.isAir()) {
                return this;
            }
            int id = indices.computeIfAbsent(state, s -> {
                palette.add((BlockState) s);
                return palette.size() - 1;
            });
            cells[(y * sizeZ + z) * sizeX + x] = (short) id;
            return this;
        }

        public Builder blockEntity(int x, int y, int z, CompoundTag tag) {
            blockEntities.put(BlockPos.asLong(x, y, z), tag);
            return this;
        }

        public ShipStructure build() {
            return new ShipStructure(sizeX, sizeY, sizeZ, palette.toArray(BlockState[]::new), cells, blockEntities);
        }
    }

    /**
     * Retire les groupes de blocs isolés (au plus {@code maxSize} blocs connexes) : marqueurs de coin laissés par les
     * outils de schématique, qui gonfleraient la boîte englobante.
     */
    public ShipStructure withoutStrayBlocks(int maxSize) {
        boolean[] visited = new boolean[cells.length];
        it.unimi.dsi.fastutil.ints.IntArrayList stray = new it.unimi.dsi.fastutil.ints.IntArrayList();
        it.unimi.dsi.fastutil.ints.IntArrayList component = new it.unimi.dsi.fastutil.ints.IntArrayList();
        it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue queue = new it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue();
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int start = 0; start < cells.length; start++) {
            if (cells[start] == 0 || visited[start]) {
                continue;
            }
            component.clear();
            visited[start] = true;
            queue.enqueue(start);
            while (!queue.isEmpty()) {
                int index = queue.dequeueInt();
                component.add(index);
                int x = index % sizeX;
                int z = (index / sizeX) % sizeZ;
                int y = index / (sizeX * sizeZ);
                for (int[] step : steps) {
                    int nx = x + step[0];
                    int ny = y + step[1];
                    int nz = z + step[2];
                    if (inside(nx, ny, nz)) {
                        int next = index(nx, ny, nz);
                        if (cells[next] != 0 && !visited[next]) {
                            visited[next] = true;
                            queue.enqueue(next);
                        }
                    }
                }
            }
            if (component.size() <= maxSize) {
                stray.addAll(component);
            }
        }
        if (stray.isEmpty()) {
            return this;
        }
        short[] copy = cells.clone();
        for (int i = 0; i < stray.size(); i++) {
            copy[stray.getInt(i)] = 0;
        }
        return new ShipStructure(sizeX, sizeY, sizeZ, palette, copy, blockEntities);
    }

    /** Recadre la grille sur la boîte englobante des blocs non vides. */
    public ShipStructure trimmed() {
        int minX = sizeX;
        int minY = sizeY;
        int minZ = sizeZ;
        int maxX = -1;
        int maxY = -1;
        int maxZ = -1;
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    if (cells[index(x, y, z)] != 0) {
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        minZ = Math.min(minZ, z);
                        maxX = Math.max(maxX, x);
                        maxY = Math.max(maxY, y);
                        maxZ = Math.max(maxZ, z);
                    }
                }
            }
        }
        if (maxX < 0 || (minX == 0 && minY == 0 && minZ == 0 && maxX == sizeX - 1 && maxY == sizeY - 1 && maxZ == sizeZ - 1)) {
            return this;
        }
        Builder builder = new Builder(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
        int ox = minX;
        int oy = minY;
        int oz = minZ;
        forEachBlock((x, y, z, state) -> builder.set(x - ox, y - oy, z - oz, state));
        blockEntities.forEach((pos, tag) -> builder.blockEntity(BlockPos.getX(pos) - ox, BlockPos.getY(pos) - oy, BlockPos.getZ(pos) - oz, tag));
        return builder.build();
    }

    public int[] trimOffset() {
        int minX = sizeX;
        int minY = sizeY;
        int minZ = sizeZ;
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    if (cells[index(x, y, z)] != 0) {
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        minZ = Math.min(minZ, z);
                    }
                }
            }
        }
        return new int[]{minX, minY, minZ};
    }

    // ───────────────────────────── Sérialisation ─────────────────────────────

    private byte[] packCells() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            // Codage par plages : (longueur, indice) — l'air domine les grandes coques.
            int i = 0;
            while (i < cells.length) {
                short value = cells[i];
                int run = 1;
                while (i + run < cells.length && cells[i + run] == value && run < 65535) {
                    run++;
                }
                out.writeShort(run);
                out.writeShort(value);
                i += run;
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    private static short[] unpackCells(byte[] packed, int length) {
        short[] cells = new short[length];
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new java.io.ByteArrayInputStream(packed)))) {
            int i = 0;
            while (i < length) {
                int run = in.readUnsignedShort();
                short value = in.readShort();
                for (int k = 0; k < run && i < length; k++) {
                    cells[i++] = value;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return cells;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("SizeX", sizeX);
        tag.putInt("SizeY", sizeY);
        tag.putInt("SizeZ", sizeZ);
        ListTag paletteTag = new ListTag();
        for (BlockState state : palette) {
            paletteTag.add(NbtUtils.writeBlockState(state));
        }
        tag.put("Palette", paletteTag);
        tag.putByteArray("Cells", packCells());
        ListTag entities = new ListTag();
        blockEntities.forEach((pos, data) -> {
            CompoundTag entry = data.copy();
            entry.putLong("OdysseaPos", pos);
            entities.add(entry);
        });
        tag.put("BlockEntities", entities);
        return tag;
    }

    public static ShipStructure load(CompoundTag tag, HolderGetter<Block> blocks) {
        int sx = tag.getInt("SizeX");
        int sy = tag.getInt("SizeY");
        int sz = tag.getInt("SizeZ");
        ListTag paletteTag = tag.getList("Palette", Tag.TAG_COMPOUND);
        BlockState[] palette = new BlockState[Math.max(1, paletteTag.size())];
        palette[0] = Blocks.AIR.defaultBlockState();
        for (int i = 0; i < paletteTag.size(); i++) {
            palette[i] = NbtUtils.readBlockState(blocks, paletteTag.getCompound(i));
        }
        short[] cells = unpackCells(tag.getByteArray("Cells"), sx * sy * sz);
        Long2ObjectOpenHashMap<CompoundTag> entities = new Long2ObjectOpenHashMap<>();
        ListTag list = tag.getList("BlockEntities", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i).copy();
            long pos = entry.getLong("OdysseaPos");
            entry.remove("OdysseaPos");
            entities.put(pos, entry);
        }
        return new ShipStructure(sx, sy, sz, palette, cells, entities);
    }

    /** Envoi au client : identifiants numériques des états (pas de données de blocs à entité). */
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(sizeX);
        buf.writeVarInt(sizeY);
        buf.writeVarInt(sizeZ);
        buf.writeVarInt(palette.length);
        for (BlockState state : palette) {
            buf.writeVarInt(Block.getId(state));
        }
        buf.writeByteArray(packCells());
    }

    public static ShipStructure read(FriendlyByteBuf buf) {
        int sx = buf.readVarInt();
        int sy = buf.readVarInt();
        int sz = buf.readVarInt();
        BlockState[] palette = new BlockState[buf.readVarInt()];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = Block.stateById(buf.readVarInt());
        }
        short[] cells = unpackCells(buf.readByteArray(16 * 1024 * 1024), sx * sy * sz);
        return new ShipStructure(sx, sy, sz, palette, cells, new Long2ObjectOpenHashMap<>());
    }

    /** Liste des positions non vides (indices de cellule), pour itérer vite sur les grandes coques. */
    public IntArrayList solidCells() {
        IntArrayList list = new IntArrayList(blockCount);
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != 0) {
                list.add(i);
            }
        }
        return list;
    }
}
