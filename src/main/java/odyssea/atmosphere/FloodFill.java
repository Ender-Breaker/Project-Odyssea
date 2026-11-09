package odyssea.atmosphere;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.BitSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Flood-fill 3D borné à un cube de sécurité de {@value #SIZE}³ centré sur le diffuseur.
 * Pur et sans état : exécutable sur n'importe quel thread à partir d'un {@link RegionSnapshot}.
 */
public final class FloodFill {
    public static final int RADIUS = 32;
    public static final int SIZE = RADIUS * 2;
    private static final int PADDED = SIZE + 2;
    private static final byte MASK_UNSET = -1;
    private static final Direction[] DIRECTIONS = Direction.values();

    public enum Outcome {
        SEALED,
        LEAKS_TO_EXTERIOR,
        EXCEEDS_SAFETY_VOLUME,
        UNKNOWN_TERRAIN,
        NO_START_CELL,
        FAILED
    }

    /**
     * @param boundary cellule non intérieure → masque des directions pointant vers les cellules intérieures adjacentes
     * @param sections sections couvertes par l'intérieur et la frontière (index spatial)
     */
    public record Result(long origin, long generation, Outcome outcome, LongOpenHashSet interior,
                         Long2ByteOpenHashMap boundary, LongOpenHashSet sections, BoundingBox bounds) {

        public boolean sealed() {
            return outcome == Outcome.SEALED;
        }

        static Result failure(long origin, long generation, Outcome outcome) {
            BlockPos pos = BlockPos.of(origin);
            return new Result(origin, generation, outcome, new LongOpenHashSet(), new Long2ByteOpenHashMap(),
                    new LongOpenHashSet(), new BoundingBox(pos));
        }
    }

    private FloodFill() {
    }

    /** Capture la boîte de sécurité plus une marge d'une cellule pour connaître l'état des cellules frontières. */
    public static RegionSnapshot captureFor(ServerLevel level, BlockPos origin) {
        return RegionSnapshot.capture(level,
                origin.getX() - RADIUS - 1, origin.getY() - RADIUS - 1, origin.getZ() - RADIUS - 1,
                origin.getX() + RADIUS, origin.getY() + RADIUS, origin.getZ() + RADIUS);
    }

    public static Result run(RegionSnapshot snapshot, long origin, long generation) {
        int ox = BlockPos.getX(origin);
        int oy = BlockPos.getY(origin);
        int oz = BlockPos.getZ(origin);
        int minX = ox - RADIUS;
        int minY = oy - RADIUS;
        int minZ = oz - RADIUS;

        byte[] masks = new byte[PADDED * PADDED * PADDED];
        Arrays.fill(masks, MASK_UNSET);
        BitSet visited = new BitSet(SIZE * SIZE * SIZE);
        IntArrayFIFOQueue queue = new IntArrayFIFOQueue(4096);
        LongOpenHashSet interior = new LongOpenHashSet();
        Long2ByteOpenHashMap boundary = new Long2ByteOpenHashMap();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int[] bounds = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

        // Le diffuseur souffle par ses six faces : une voisine est semée si sa face tournée vers lui n'est pas étanche.
        for (Direction direction : DIRECTIONS) {
            int x = ox + direction.getStepX();
            int y = oy + direction.getStepY();
            int z = oz + direction.getStepZ();
            if (!snapshot.isKnown(x, y, z)) {
                continue;
            }
            int mask = maskAt(snapshot, masks, cursor, x, y, z, minX, minY, minZ);
            if ((mask & Airtightness.bit(direction.getOpposite())) != 0) {
                continue;
            }
            int local = localIndex(x - minX, y - minY, z - minZ);
            if (!visited.get(local)) {
                visited.set(local);
                queue.enqueue(local);
                addInterior(interior, bounds, x, y, z);
            }
            boundary.put(origin, (byte) (boundary.get(origin) | Airtightness.bit(direction)));
        }
        if (queue.isEmpty()) {
            return Result.failure(origin, generation, Outcome.NO_START_CELL);
        }

        while (!queue.isEmpty()) {
            int local = queue.dequeueInt();
            int x = minX + (local >>> 12);
            int y = minY + ((local >>> 6) & 63);
            int z = minZ + (local & 63);
            int mask = maskAt(snapshot, masks, cursor, x, y, z, minX, minY, minZ);

            for (Direction direction : DIRECTIONS) {
                if ((mask & Airtightness.bit(direction)) != 0) {
                    continue;
                }
                int nx = x + direction.getStepX();
                int ny = y + direction.getStepY();
                int nz = z + direction.getStepZ();
                if (!snapshot.isKnown(nx, ny, nz)) {
                    return Result.failure(origin, generation,
                            snapshot.isOutsideWorld(ny) ? Outcome.LEAKS_TO_EXTERIOR : Outcome.UNKNOWN_TERRAIN);
                }
                int neighbourMask = maskAt(snapshot, masks, cursor, nx, ny, nz, minX, minY, minZ);
                if (!Airtightness.canFlow(mask, neighbourMask, direction)) {
                    long key = BlockPos.asLong(nx, ny, nz);
                    boundary.put(key, (byte) (boundary.get(key) | Airtightness.bit(direction.getOpposite())));
                    continue;
                }
                int lx = nx - minX;
                int ly = ny - minY;
                int lz = nz - minZ;
                if (lx < 0 || ly < 0 || lz < 0 || lx >= SIZE || ly >= SIZE || lz >= SIZE) {
                    return Result.failure(origin, generation, Outcome.EXCEEDS_SAFETY_VOLUME);
                }
                int neighbourLocal = localIndex(lx, ly, lz);
                if (visited.get(neighbourLocal)) {
                    continue;
                }
                visited.set(neighbourLocal);
                queue.enqueue(neighbourLocal);
                addInterior(interior, bounds, nx, ny, nz);
            }
        }

        // Une cellule bloquée depuis une voisine mais atteinte par un autre chemin est intérieure, pas frontière.
        for (LongIterator it = boundary.keySet().iterator(); it.hasNext(); ) {
            if (interior.contains(it.nextLong())) {
                it.remove();
            }
        }

        BoundingBox box = new BoundingBox(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
        return new Result(origin, generation, Outcome.SEALED, interior, boundary, sectionsCovering(box), box);
    }

    private static LongOpenHashSet sectionsCovering(BoundingBox interiorBounds) {
        LongOpenHashSet sections = new LongOpenHashSet();
        int sx0 = SectionPos.blockToSectionCoord(interiorBounds.minX() - 1);
        int sy0 = SectionPos.blockToSectionCoord(interiorBounds.minY() - 1);
        int sz0 = SectionPos.blockToSectionCoord(interiorBounds.minZ() - 1);
        int sx1 = SectionPos.blockToSectionCoord(interiorBounds.maxX() + 1);
        int sy1 = SectionPos.blockToSectionCoord(interiorBounds.maxY() + 1);
        int sz1 = SectionPos.blockToSectionCoord(interiorBounds.maxZ() + 1);
        for (int sx = sx0; sx <= sx1; sx++) {
            for (int sy = sy0; sy <= sy1; sy++) {
                for (int sz = sz0; sz <= sz1; sz++) {
                    sections.add(SectionPos.asLong(sx, sy, sz));
                }
            }
        }
        return sections;
    }

    private static void addInterior(LongOpenHashSet interior, int[] bounds, int x, int y, int z) {
        interior.add(BlockPos.asLong(x, y, z));
        bounds[0] = Math.min(bounds[0], x);
        bounds[1] = Math.min(bounds[1], y);
        bounds[2] = Math.min(bounds[2], z);
        bounds[3] = Math.max(bounds[3], x);
        bounds[4] = Math.max(bounds[4], y);
        bounds[5] = Math.max(bounds[5], z);
    }

    private static int localIndex(int lx, int ly, int lz) {
        return (lx << 12) | (ly << 6) | lz;
    }

    private static int maskAt(RegionSnapshot snapshot, byte[] masks, BlockPos.MutableBlockPos cursor,
                              int x, int y, int z, int minX, int minY, int minZ) {
        int px = x - minX + 1;
        int py = y - minY + 1;
        int pz = z - minZ + 1;
        int index = (px * PADDED + py) * PADDED + pz;
        byte cached = masks[index];
        if (cached != MASK_UNSET) {
            return cached;
        }
        int mask = Airtightness.faceMask(snapshot, cursor.set(x, y, z), snapshot.getState(x, y, z));
        masks[index] = (byte) mask;
        return mask;
    }
}
