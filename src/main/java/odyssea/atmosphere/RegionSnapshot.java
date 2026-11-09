package odyssea.atmosphere;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * Copie immuable d'une région, capturée sur le thread serveur puis lue par les workers.
 * Seules les palettes des sections sont copiées ; les sections entièrement vides ne coûtent rien.
 */
public final class RegionSnapshot implements BlockGetter {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final byte UNKNOWN = 0;
    private static final byte AIR_ONLY = 1;
    private static final byte COPIED = 2;

    private final int minSectionX;
    private final int minSectionY;
    private final int minSectionZ;
    private final int sectionsX;
    private final int sectionsY;
    private final int sectionsZ;
    private final PalettedContainer<BlockState>[] sections;
    private final byte[] status;
    private final int minBuildHeight;
    private final int height;

    @SuppressWarnings("unchecked")
    private RegionSnapshot(int minSectionX, int minSectionY, int minSectionZ, int sectionsX, int sectionsY, int sectionsZ,
                           int minBuildHeight, int height) {
        this.minSectionX = minSectionX;
        this.minSectionY = minSectionY;
        this.minSectionZ = minSectionZ;
        this.sectionsX = sectionsX;
        this.sectionsY = sectionsY;
        this.sectionsZ = sectionsZ;
        this.minBuildHeight = minBuildHeight;
        this.height = height;
        int count = sectionsX * sectionsY * sectionsZ;
        this.sections = new PalettedContainer[count];
        this.status = new byte[count];
    }

    /** Doit être appelé sur le thread serveur. Ne charge aucun chunk : un chunk absent reste « inconnu ». */
    public static RegionSnapshot capture(ServerLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int sx0 = SectionPos.blockToSectionCoord(minX);
        int sy0 = SectionPos.blockToSectionCoord(minY);
        int sz0 = SectionPos.blockToSectionCoord(minZ);
        int sx1 = SectionPos.blockToSectionCoord(maxX);
        int sy1 = SectionPos.blockToSectionCoord(maxY);
        int sz1 = SectionPos.blockToSectionCoord(maxZ);
        RegionSnapshot snapshot = new RegionSnapshot(sx0, sy0, sz0, sx1 - sx0 + 1, sy1 - sy0 + 1, sz1 - sz0 + 1,
                level.getMinBuildHeight(), level.getHeight());
        ServerChunkCache chunkSource = level.getChunkSource();
        int sectionCount = level.getSectionsCount();
        for (int cx = sx0; cx <= sx1; cx++) {
            for (int cz = sz0; cz <= sz1; cz++) {
                LevelChunk chunk = chunkSource.getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (int cy = sy0; cy <= sy1; cy++) {
                    int sectionIndex = level.getSectionIndexFromSectionY(cy);
                    if (sectionIndex < 0 || sectionIndex >= sectionCount) {
                        continue;
                    }
                    int index = snapshot.indexOf(cx, cy, cz);
                    LevelChunkSection section = chunk.getSection(sectionIndex);
                    if (section.hasOnlyAir()) {
                        snapshot.status[index] = AIR_ONLY;
                    } else {
                        snapshot.sections[index] = section.getStates().copy();
                        snapshot.status[index] = COPIED;
                    }
                }
            }
        }
        return snapshot;
    }

    public boolean isKnown(int x, int y, int z) {
        int index = indexOf(x >> 4, y >> 4, z >> 4);
        return index >= 0 && status[index] != UNKNOWN;
    }

    public boolean isOutsideWorld(int y) {
        return y < minBuildHeight || y >= minBuildHeight + height;
    }

    public BlockState getState(int x, int y, int z) {
        int index = indexOf(x >> 4, y >> 4, z >> 4);
        if (index < 0 || status[index] != COPIED) {
            return AIR;
        }
        return sections[index].get(x & 15, y & 15, z & 15);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return getState(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinBuildHeight() {
        return minBuildHeight;
    }

    private int indexOf(int sectionX, int sectionY, int sectionZ) {
        int lx = sectionX - minSectionX;
        int ly = sectionY - minSectionY;
        int lz = sectionZ - minSectionZ;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= sectionsX || ly >= sectionsY || lz >= sectionsZ) {
            return -1;
        }
        return (lx * sectionsY + ly) * sectionsZ + lz;
    }
}
