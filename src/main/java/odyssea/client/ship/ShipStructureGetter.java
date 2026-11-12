package odyssea.client.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import odyssea.ship.ShipStructure;
import org.jetbrains.annotations.Nullable;

/**
 * Vue de la structure d'un vaisseau comme un petit monde, pour le rendu des modèles de blocs : faces cachées entre
 * blocs voisins, occlusion ambiante, teintes de biome (herbe, feuilles) et éclairage plein ciel. L'assombrissement de
 * la nuit est appliqué ensuite par la texture d'éclairage, comme pour le terrain.
 */
final class ShipStructureGetter implements BlockAndTintGetter {
    private final ShipStructure structure;
    private final Biome biome;

    ShipStructureGetter(ShipStructure structure, Biome biome) {
        this.structure = structure;
        this.biome = biome;
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        if (!shade) {
            return 1.0f;
        }
        return switch (direction) {
            case DOWN -> 0.5f;
            case UP -> 1.0f;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        };
    }

    @Override
    public LevelLightEngine getLightEngine() {
        throw new UnsupportedOperationException("Éclairage fixe : getBrightness est redéfini");
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return layer == LightLayer.SKY ? 15 : 0;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int darkening) {
        return 15 - darkening;
    }

    @Override
    public boolean canSeeSky(BlockPos pos) {
        return true;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return resolver.getColor(biome, pos.getX(), pos.getZ());
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return structure.get(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return structure.sizeY() + 2;
    }

    @Override
    public int getMinBuildHeight() {
        return -1;
    }
}
