package odyssea.atmosphere;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import odyssea.OdysseaCore;

/**
 * Étanchéité par face : le bit {@code d} du masque indique que la face {@code d} de la cellule bloque l'air.
 * Une porte fermée n'a qu'une face pleine ; le passage est donc correct quel que soit le côté d'approche.
 */
public final class Airtightness {
    public static final TagKey<Block> AIRTIGHT = TagKey.create(Registries.BLOCK, OdysseaCore.id("airtight"));
    public static final TagKey<Block> AIR_PERMEABLE = TagKey.create(Registries.BLOCK, OdysseaCore.id("air_permeable"));
    public static final int ALL_FACES = 0b111111;

    private static final Direction[] DIRECTIONS = Direction.values();
    // Partagé entre le thread serveur (sondes) et les workers (flood-fill) ; vidé au rechargement des tags.
    private static final ConcurrentHashMap<BlockState, Byte> CACHE = new ConcurrentHashMap<>();

    private Airtightness() {
    }

    public static int bit(Direction direction) {
        return 1 << direction.get3DDataValue();
    }

    public static int faceMask(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return 0;
        }
        Byte cached = CACHE.get(state);
        if (cached != null) {
            return cached;
        }
        int mask = compute(level, pos, state);
        CACHE.putIfAbsent(state, (byte) mask);
        return mask;
    }

    /** Passage de {@code from} vers sa voisine {@code to} située dans la direction {@code direction}. */
    public static boolean canFlow(int fromMask, int toMask, Direction direction) {
        return (fromMask & bit(direction)) == 0 && (toMask & bit(direction.getOpposite())) == 0;
    }

    public static void invalidateCache() {
        CACHE.clear();
    }

    private static int compute(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.is(AIR_PERMEABLE)) {
            return 0;
        }
        if (state.is(AIRTIGHT)) {
            return ALL_FACES;
        }
        int mask = 0;
        try {
            for (Direction direction : DIRECTIONS) {
                if (state.isFaceSturdy(level, pos, direction, SupportType.FULL)) {
                    mask |= bit(direction);
                }
            }
        } catch (RuntimeException e) {
            // Forme dépendante d'un block entity absent du snapshot : considéré perméable (choix conservateur).
            return 0;
        }
        return mask;
    }
}
