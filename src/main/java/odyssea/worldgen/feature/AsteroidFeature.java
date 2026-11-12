package odyssea.worldgen.feature;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.phys.Vec3;
import odyssea.registry.OdysseaBlocks;
import odyssea.space.SpaceSystem;

/**
 * Astéroïde de la dimension spatiale : un à trois lobes sphériques déformés, avec une croûte, un cœur et des
 * minerais. La densité dépend du lieu :
 * <ul>
 *   <li>ceinture principale (200 000 à 250 000 blocs du Soleil) : champ dense, gros blocs rocheux et métalliques ;</li>
 *   <li>anneaux de Saturne : débris de glace serrés dans le plan des anneaux ;</li>
 *   <li>au-delà de 560 000 blocs : corps glacés ; ailleurs, quelques rochers isolés.</li>
 * </ul>
 * Les astéroïdes se concentrent près du plan de l'écliptique. Rayon limité à 13 blocs : une feature ne peut écrire
 * qu'un tronçon autour du sien.
 */
public class AsteroidFeature extends Feature<NoneFeatureConfiguration> {
    private static final int MAX_RADIUS = 13;

    private enum Type {
        STONY, BASALTIC, METALLIC, ICY
    }

    public AsteroidFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        RandomSource random = context.random();
        double distance = Math.sqrt((double) origin.getX() * origin.getX() + (double) origin.getZ() * origin.getZ());
        double height = origin.getY() - SpaceSystem.ECLIPTIC_Y;

        double belt = smoothstep(195_000, 207_000, distance) * (1.0 - smoothstep(243_000, 256_000, distance));
        Vec3 saturn = SpaceSystem.position("saturn");
        double saturnDistance = Math.hypot(origin.getX() - saturn.x, origin.getZ() - saturn.z);
        double rings = smoothstep(37_000, 38_500, saturnDistance) * (1.0 - smoothstep(68_500, 70_100, saturnDistance))
                * Math.exp(-(height * height) / (25.0 * 25.0));
        double outer = smoothstep(560_000, 600_000, distance) * 0.15;
        double spread = belt > 0.0 ? 75.0 : 110.0;
        double vertical = Math.exp(-(height * height) / (spread * spread));
        double chance = rings * 0.9 + (0.002 + belt * 0.55 + outer) * vertical;
        if (random.nextDouble() >= chance) {
            return false;
        }

        Type type;
        int radius;
        if (rings > 0.2) {
            type = Type.ICY;
            radius = 1 + random.nextInt(4);
        } else {
            double roll = random.nextDouble();
            type = distance > 560_000 ? Type.ICY : roll < 0.6 ? Type.STONY : roll < 0.85 ? Type.BASALTIC : roll < 0.95 ? Type.METALLIC : Type.ICY;
            double size = Math.pow(random.nextDouble(), belt > 0.0 ? 2.0 : 3.0);
            radius = 2 + (int) (size * (MAX_RADIUS - 2));
        }

        int lobes = radius <= 3 ? 1 : 1 + random.nextInt(3);
        double[][] centers = new double[lobes][4];
        for (int i = 0; i < lobes; i++) {
            double r = i == 0 ? radius : radius * (0.45 + random.nextDouble() * 0.4);
            double offset = i == 0 ? 0.0 : radius - r;
            centers[i][0] = random.nextGaussian() * offset * 0.6;
            centers[i][1] = random.nextGaussian() * offset * 0.4;
            centers[i][2] = random.nextGaussian() * offset * 0.6;
            centers[i][3] = r * (0.8 + random.nextDouble() * 0.25);
        }
        double s1 = random.nextDouble() * 10.0;
        double s2 = random.nextDouble() * 10.0;
        double s3 = random.nextDouble() * 10.0;
        int bound = Math.min(MAX_RADIUS + 1, radius + 1);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        boolean placed = false;
        for (int dx = -bound; dx <= bound; dx++) {
            for (int dy = -bound; dy <= bound; dy++) {
                for (int dz = -bound; dz <= bound; dz++) {
                    double value = Double.MAX_VALUE;
                    for (double[] c : centers) {
                        double ex = dx - c[0];
                        double ey = dy - c[1];
                        double ez = dz - c[2];
                        value = Math.min(value, Math.sqrt(ex * ex + ey * ey + ez * ez) / c[3]);
                    }
                    // Relief de surface : trois ondes croisées, assez pour casser la sphère sans coût de bruit.
                    value += 0.16 * Math.sin(dx * 0.73 + s1) * Math.sin(dy * 0.91 + s2) * Math.sin(dz * 0.67 + s3)
                            + 0.08 * Math.sin(dx * 1.9 + dz * 1.3 + s2);
                    if (value >= 1.0) {
                        continue;
                    }
                    cursor.setWithOffset(origin, dx, dy, dz);
                    if (cursor.getY() <= level.getMinBuildHeight() || cursor.getY() >= level.getMaxBuildHeight()) {
                        continue;
                    }
                    level.setBlock(cursor, material(type, value, radius, random), 2);
                    placed = true;
                }
            }
        }
        return placed;
    }

    private static BlockState material(Type type, double depth, int radius, RandomSource random) {
        boolean crust = depth > 0.8;
        boolean core = depth < 0.45;
        float roll = random.nextFloat();
        switch (type) {
            case ICY -> {
                if (crust) {
                    return roll < 0.6 ? Blocks.SNOW_BLOCK.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState();
                }
                if (roll < 0.08) {
                    return OdysseaBlocks.METHANE_ICE.get().defaultBlockState();
                }
                if (roll < 0.14) {
                    return OdysseaBlocks.AMMONIA_ICE.get().defaultBlockState();
                }
                return roll < 0.35 ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState();
            }
            case METALLIC -> {
                if (crust) {
                    return roll < 0.5 ? Blocks.COBBLED_DEEPSLATE.defaultBlockState() : Blocks.TUFF.defaultBlockState();
                }
                if (core && roll < 0.35) {
                    return Blocks.RAW_IRON_BLOCK.defaultBlockState();
                }
                if (roll < 0.25) {
                    return Blocks.DEEPSLATE_IRON_ORE.defaultBlockState();
                }
                if (roll < 0.33) {
                    return Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState();
                }
                if (roll < 0.36 && core) {
                    return Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState();
                }
                if (roll < 0.362 && core && radius >= 8) {
                    return Blocks.ANCIENT_DEBRIS.defaultBlockState();
                }
                return Blocks.DEEPSLATE.defaultBlockState();
            }
            case BASALTIC -> {
                if (crust) {
                    return roll < 0.5 ? OdysseaBlocks.LUNAR_BASALT.get().defaultBlockState() : Blocks.SMOOTH_BASALT.defaultBlockState();
                }
                if (roll < 0.03) {
                    return Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState();
                }
                if (roll < 0.05) {
                    return Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState();
                }
                return roll < 0.6 ? Blocks.BASALT.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState();
            }
            default -> {
                if (crust) {
                    return roll < 0.45 ? Blocks.TUFF.defaultBlockState()
                            : roll < 0.8 ? Blocks.ANDESITE.defaultBlockState() : OdysseaBlocks.ANORTHOSITE.get().defaultBlockState();
                }
                if (core) {
                    if (roll < 0.004) {
                        return Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState();
                    }
                    if (roll < 0.02) {
                        return Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState();
                    }
                    if (roll < 0.05) {
                        return Blocks.DEEPSLATE_LAPIS_ORE.defaultBlockState();
                    }
                    return Blocks.DEEPSLATE.defaultBlockState();
                }
                if (roll < 0.035) {
                    return Blocks.IRON_ORE.defaultBlockState();
                }
                if (roll < 0.06) {
                    return Blocks.COPPER_ORE.defaultBlockState();
                }
                if (roll < 0.08) {
                    return Blocks.COAL_ORE.defaultBlockState();
                }
                return Blocks.STONE.defaultBlockState();
            }
        }
    }
}
