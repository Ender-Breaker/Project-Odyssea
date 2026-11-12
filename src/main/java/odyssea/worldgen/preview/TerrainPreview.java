package odyssea.worldgen.preview;

import com.mojang.logging.LogUtils;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;

/**
 * Rendu hors ligne du terrain d'une dimension, pour juger la génération sans lancer de client :
 * <ul>
 *   <li>{@code relief} : altitude (teinte hypsométrique + ombrage) calculée par le générateur seul ;</li>
 *   <li>{@code biomes} : biome de surface, une couleur par biome (légende dans le journal) ;</li>
 *   <li>{@code section} : coupe verticale (pierre, fluides, air) révélant surplombs et grottes ;</li>
 *   <li>{@code surface} : blocs réellement générés (règles de surface comprises), couleurs de carte vanilla.</li>
 * </ul>
 * Les trois premiers rendus n'appellent que des méthodes sans état du générateur et tournent en parallèle hors du
 * thread serveur ; le dernier charge de vrais chunks et s'exécute sur le thread serveur.
 */
public final class TerrainPreview {
    private static final Logger LOGGER = LogUtils.getLogger();

    private TerrainPreview() {
    }

    public record Options(int centerX, int centerZ, int pixels, int step, int detailPixels, int detailStep,
                          int sectionWidth, int surfaceChunks) {
        public static Options standard(int centerX, int centerZ) {
            return new Options(centerX, centerZ, 640, 24, 512, 4, 768, 12);
        }
    }

    public static CompletableFuture<Void> renderAsync(MinecraftServer server, ServerLevel level, Options options, Path directory) {
        String name = level.dimension().location().toString().replace(':', '_').replace('/', '_');
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(directory);
                if (!(generator instanceof NoiseBasedChunkGenerator)) {
                    LOGGER.info("[Odyssea preview] {} : générateur sans bruit, aucun relief à rendre", name);
                    return null;
                }
                // Le détail, la coupe et la surface sont centrés sur le point culminant de la vue d'ensemble.
                int[] summit = renderRelief(level, generator, randomState, options.centerX(), options.centerZ(), options.pixels(),
                        options.step(), directory.resolve(name + "_relief.png"), directory.resolve(name + "_biomes.png"), name);
                renderRelief(level, generator, randomState, summit[0], summit[1], options.detailPixels(), options.detailStep(),
                        directory.resolve(name + "_relief_detail.png"), null, name + " (détail autour de " + summit[0] + ", " + summit[1] + ")");
                renderSection(level, generator, randomState, summit[0], summit[1], options.sectionWidth(),
                        directory.resolve(name + "_section.png"));
                return summit;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).thenCompose(summit -> summit == null ? CompletableFuture.<Void>completedFuture(null) : CompletableFuture.runAsync(() -> {
            try {
                renderSurface(level, summit[0], summit[1], options.surfaceChunks(), directory.resolve(name + "_surface.png"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, server));
    }

    /** @return coordonnées (x, z) du point le plus haut de la carte */
    private static int[] renderRelief(ServerLevel level, ChunkGenerator generator, RandomState randomState, int centerX, int centerZ,
                                      int pixels, int step, Path reliefOut, Path biomeOut, String label) throws IOException {
        long start = System.nanoTime();
        int half = pixels / 2;
        int[] heights = new int[pixels * pixels];
        @SuppressWarnings("unchecked")
        Holder<Biome>[] biomes = biomeOut != null ? new Holder[pixels * pixels] : null;
        int seaLevel = generator.getSeaLevel();
        IntStream.range(0, pixels).parallel().forEach(py -> {
            for (int px = 0; px < pixels; px++) {
                int x = centerX + (px - half) * step;
                int z = centerZ + (py - half) * step;
                int h = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
                heights[py * pixels + px] = h;
                if (biomes != null) {
                    int by = Math.max(h, seaLevel);
                    biomes[py * pixels + px] = generator.getBiomeSource().getNoiseBiome(
                            QuartPos.fromBlock(x), QuartPos.fromBlock(by), QuartPos.fromBlock(z), randomState.sampler());
                }
            }
        });
        boolean hasWater = hasOcean(level, generator);
        BufferedImage image = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < pixels; py++) {
            for (int px = 0; px < pixels; px++) {
                int h = heights[py * pixels + px];
                int hl = heights[py * pixels + Math.max(px - 1, 0)];
                int hr = heights[py * pixels + Math.min(px + 1, pixels - 1)];
                int hu = heights[Math.max(py - 1, 0) * pixels + px];
                int hd = heights[Math.min(py + 1, pixels - 1) * pixels + px];
                double dx = (hr - hl) / (2.0 * step);
                double dz = (hd - hu) / (2.0 * step);
                double slope = Math.sqrt(dx * dx + dz * dz);
                // Lumière du nord-ouest, 45° au-dessus de l'horizon.
                double nx = -dx;
                double nz = -dz;
                double ny = 1.0;
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double shade = Math.max(0.0, (nx * -0.5 + ny * 0.7071 + nz * -0.5) / len);
                int color = hasWater && h < seaLevel ? waterColor(seaLevel - h) : landColor(h, seaLevel, slope, hasWater);
                image.setRGB(px, py, shadeColor(color, hasWater && h < seaLevel ? 0.85 + 0.15 * shade : 0.35 + 0.85 * shade));
            }
        }
        ImageIO.write(image, "png", reliefOut.toFile());
        int[] sorted = heights.clone();
        Arrays.sort(sorted);
        LOGGER.info("[Odyssea preview] {} : {}×{} px, pas {} blocs ({} s) — altitude min {}, p10 {}, médiane {}, p90 {}, p99 {}, max {}",
                label, pixels, pixels, step, String.format("%.1f", (System.nanoTime() - start) / 1e9), sorted[0],
                sorted[sorted.length / 10], sorted[sorted.length / 2], sorted[sorted.length * 9 / 10],
                sorted[sorted.length * 99 / 100], sorted[sorted.length - 1]);

        if (biomes != null) {
            Map<String, int[]> legend = new LinkedHashMap<>();
            BufferedImage biomeImage = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_RGB);
            for (int i = 0; i < biomes.length; i++) {
                String id = biomes[i].unwrapKey().map(k -> k.location().toString()).orElse("?");
                int[] entry = legend.computeIfAbsent(id, key -> new int[]{biomeColor(key), 0});
                entry[1]++;
                int h = heights[i];
                int base = entry[0];
                biomeImage.setRGB(i % pixels, i / pixels, shadeColor(base, h < seaLevel && hasWater ? 0.7 : 1.0));
            }
            StringBuilder text = new StringBuilder();
            legend.entrySet().stream().sorted((a, b) -> b.getValue()[1] - a.getValue()[1]).forEach(e -> text
                    .append(String.format("%n    #%06X %5.1f %% %s", e.getValue()[0], 100.0 * e.getValue()[1] / biomes.length, e.getKey())));
            LOGGER.info("[Odyssea preview] {} : biomes{}", label, text);
            ImageIO.write(biomeImage, "png", biomeOut.toFile());
        }
        int best = 0;
        for (int i = 1; i < heights.length; i++) {
            if (heights[i] > heights[best]) {
                best = i;
            }
        }
        return new int[]{centerX + (best % pixels - half) * step, centerZ + (best / pixels - half) * step};
    }

    private static boolean hasOcean(ServerLevel level, ChunkGenerator generator) {
        if (generator instanceof NoiseBasedChunkGenerator noise) {
            return !noise.generatorSettings().value().defaultFluid().isAir();
        }
        return false;
    }

    private static void renderSection(ServerLevel level, ChunkGenerator generator, RandomState randomState, int centerX, int centerZ,
                                      int width, Path out) throws IOException {
        int minY = level.getMinBuildHeight();
        int top = minY;
        NoiseColumn[] columns = new NoiseColumn[width];
        IntStream.range(0, width).parallel().forEach(i ->
                columns[i] = generator.getBaseColumn(centerX - width + i * 2, centerZ, level, randomState));
        // Hauteur utile : jusqu'au plus haut bloc non-air de la coupe, plus une marge.
        for (NoiseColumn column : columns) {
            for (int y = level.getMaxBuildHeight() - 1; y > top; y--) {
                if (!column.getBlock(y).isAir()) {
                    top = Math.max(top, y);
                    break;
                }
            }
        }
        top = Math.min(level.getMaxBuildHeight() - 1, top + 48);
        int height = top - minY + 1;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < width; i++) {
            for (int y = minY; y <= top; y++) {
                BlockState state = columns[i].getBlock(y);
                int color;
                if (state.isAir()) {
                    color = 0xA8C8F0;
                } else if (state.is(Blocks.WATER)) {
                    color = 0x2850B0;
                } else if (state.is(Blocks.LAVA)) {
                    color = 0xF06010;
                } else {
                    color = 0x707070 + ((y & 15) == 0 ? 0x101010 : 0);
                }
                image.setRGB(i, top - y, color);
            }
        }
        ImageIO.write(image, "png", out.toFile());
        LOGGER.info("[Odyssea preview] coupe {} : x ∈ [{}, {}] (pas 2), y ∈ [{}, {}]", out.getFileName(), centerX - width,
                centerX + width, minY, top);
    }

    private static void renderSurface(ServerLevel level, int centerX, int centerZ, int chunks, Path out) throws IOException {
        long start = System.nanoTime();
        int size = chunks * 16;
        int originX = (centerX >> 4) - chunks / 2;
        int originZ = (centerZ >> 4) - chunks / 2;
        int[] heights = new int[size * size];
        int[] colors = new int[size * size];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cz = 0; cz < chunks; cz++) {
            for (int cx = 0; cx < chunks; cx++) {
                ChunkAccess chunk = level.getChunk(originX + cx, originZ + cz, ChunkStatus.FULL, true);
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                        pos.set((originX + cx) * 16 + lx, y, (originZ + cz) * 16 + lz);
                        BlockState state = chunk.getBlockState(pos);
                        MapColor mapColor = state.getMapColor(level, pos);
                        int index = (cz * 16 + lz) * size + cx * 16 + lx;
                        heights[index] = y;
                        colors[index] = mapColor == MapColor.NONE ? 0x000000 : mapColor.col;
                    }
                }
            }
        }
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int index = z * size + x;
                int north = heights[Math.max(z - 1, 0) * size + x];
                int west = heights[z * size + Math.max(x - 1, 0)];
                double diff = (heights[index] - north) + (heights[index] - west);
                double shade = Math.max(0.55, Math.min(1.25, 1.0 + diff * 0.06));
                image.setRGB(x, z, shadeColor(colors[index], shade));
            }
        }
        ImageIO.write(image, "png", out.toFile());
        LOGGER.info("[Odyssea preview] surface {} : {} chunks générés en {} s", out.getFileName(), chunks * chunks,
                String.format("%.1f", (System.nanoTime() - start) / 1e9));
    }

    private static int waterColor(int depth) {
        double t = Math.min(1.0, depth / 220.0);
        return lerpColor(0x4A90C8, 0x0A1E50, Math.sqrt(t));
    }

    private static int landColor(int h, int seaLevel, double slope, boolean earthLike) {
        double a = h - seaLevel;
        if (!earthLike) {
            double t = Math.min(1.0, Math.max(0.0, (h + 128.0) / 1100.0));
            return lerpColor(0x302820, 0xF0E8E0, t);
        }
        int color;
        if (a < 4) {
            color = 0xD8C890;
        } else if (a < 120) {
            color = lerpColor(0x4E8A3A, 0x7C9A48, a / 120.0);
        } else if (a < 320) {
            color = lerpColor(0x7C9A48, 0x9A8458, (a - 120) / 200.0);
        } else if (a < 520) {
            color = lerpColor(0x9A8458, 0x8C8076, (a - 320) / 200.0);
        } else {
            color = lerpColor(0x8C8076, 0xF4F6FA, Math.min(1.0, (a - 520) / 120.0));
        }
        if (slope > 1.2 && a > 30 && a < 560) {
            color = lerpColor(color, 0x6E6A66, Math.min(1.0, (slope - 1.2) / 1.0));
        }
        return color;
    }

    private static int biomeColor(String id) {
        long h = id.hashCode() * 0x9E3779B97F4A7C15L;
        float hue = ((h >>> 40) & 0xFFFF) / 65535.0f;
        float saturation = 0.45f + 0.4f * (((h >>> 20) & 0xFF) / 255.0f);
        float value = 0.6f + 0.35f * (((h >>> 8) & 0xFF) / 255.0f);
        return java.awt.Color.HSBtoRGB(hue, saturation, value) & 0xFFFFFF;
    }

    private static int lerpColor(int a, int b, double t) {
        t = Math.min(1.0, Math.max(0.0, t));
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int shadeColor(int color, double factor) {
        int r = (int) Math.min(255, ((color >> 16) & 255) * factor);
        int g = (int) Math.min(255, ((color >> 8) & 255) * factor);
        int b = (int) Math.min(255, (color & 255) * factor);
        return (r << 16) | (g << 8) | b;
    }
}
