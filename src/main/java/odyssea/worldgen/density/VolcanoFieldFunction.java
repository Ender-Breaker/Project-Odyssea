package odyssea.worldgen.density;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import odyssea.worldgen.noise.SeedMath;

/**
 * {@code odyssea:volcanoes} : édifices volcaniques répartis par cellules, en variation d'altitude (blocs).
 *
 * <ul>
 *   <li>{@code shield} : volcan bouclier (sommet large, flancs concaves), éventuellement ceinturé d'un
 *       escarpement basal ({@code scarp}), comme Olympus Mons.</li>
 *   <li>{@code cone} : stratovolcan à sommet pointu.</li>
 *   <li>{@code dome} : dôme en galette (sommet plat, bords raides), typique de Vénus.</li>
 * </ul>
 * Caldeira emboîtée au sommet ({@code caldera_ratio}, {@code caldera_depth}) et fines rainures radiales
 * figurant les coulées.
 */
public final class VolcanoFieldFunction implements DensityFunction {
    public enum Shape {
        SHIELD, CONE, DOME
    }

    private static final double REACH = 1.05;

    public static final MapCodec<VolcanoFieldFunction> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            NoiseHolder.CODEC.fieldOf("noise").forGetter(f -> f.noise),
            Codec.STRING.fieldOf("salt").forGetter(f -> f.salt),
            Codec.doubleRange(4.0, 1.0e7).fieldOf("cell_size").forGetter(f -> f.cellSize),
            Codec.doubleRange(0.0, 1.0).optionalFieldOf("chance", 0.3).forGetter(f -> f.chance),
            Codec.doubleRange(1.0, 1.0e6).fieldOf("min_radius").forGetter(f -> f.minRadius),
            Codec.doubleRange(1.0, 1.0e6).fieldOf("max_radius").forGetter(f -> f.maxRadius),
            Codec.doubleRange(0.1, 10.0).optionalFieldOf("size_power", 1.5).forGetter(f -> f.sizePower),
            Codec.doubleRange(0.0, 4.0).fieldOf("height_ratio").forGetter(f -> f.heightRatio),
            WorldSeed.enumCodec(Shape.class).optionalFieldOf("shape", Shape.SHIELD).forGetter(f -> f.shape),
            Codec.doubleRange(0.0, 0.9).optionalFieldOf("scarp", 0.0).forGetter(f -> f.scarp),
            Codec.doubleRange(0.0, 0.6).optionalFieldOf("caldera_ratio", 0.1).forGetter(f -> f.calderaRatio),
            Codec.doubleRange(0.0, 1.0).optionalFieldOf("caldera_depth", 0.12).forGetter(f -> f.calderaDepth),
            Codec.doubleRange(0.0, 0.5).optionalFieldOf("irregularity", 0.1).forGetter(f -> f.irregularity)
    ).apply(instance, VolcanoFieldFunction::new));
    public static final KeyDispatchDataCodec<VolcanoFieldFunction> CODEC = KeyDispatchDataCodec.of(MAP_CODEC);

    private final NoiseHolder noise;
    private final String salt;
    private final double cellSize;
    private final double chance;
    private final double minRadius;
    private final double maxRadius;
    private final double sizePower;
    private final double heightRatio;
    private final Shape shape;
    private final double scarp;
    private final double calderaRatio;
    private final double calderaDepth;
    private final double irregularity;
    private final long seed;
    private final int range;
    /** Précalculé : vanilla réindexe tout le routeur (HashMap) à chaque création de NoiseChunk. */
    private final int hash;

    public VolcanoFieldFunction(NoiseHolder noise, String salt, double cellSize, double chance, double minRadius,
                                double maxRadius, double sizePower, double heightRatio, Shape shape, double scarp,
                                double calderaRatio, double calderaDepth, double irregularity) {
        this.noise = noise;
        this.salt = salt;
        this.cellSize = cellSize;
        this.chance = chance;
        this.minRadius = Math.min(minRadius, maxRadius);
        this.maxRadius = Math.max(minRadius, maxRadius);
        this.sizePower = sizePower;
        this.heightRatio = heightRatio;
        this.shape = shape;
        this.scarp = scarp;
        this.calderaRatio = calderaRatio;
        this.calderaDepth = calderaDepth;
        this.irregularity = irregularity;
        this.seed = WorldSeed.derive(noise, salt);
        this.range = (int) Math.ceil(this.maxRadius * REACH * (1.0 + irregularity) / cellSize);
        this.hash = java.util.Objects.hash(System.identityHashCode(noise.noise()), salt, cellSize, chance, minRadius, maxRadius, sizePower,
                heightRatio, shape, scarp, calderaRatio, calderaDepth, irregularity);
    }

    @Override
    public double compute(FunctionContext context) {
        if (noise.noise() == null) {
            return 0.0;
        }
        double x = context.blockX();
        double z = context.blockZ();
        int cellX = (int) Math.floor(x / cellSize);
        int cellZ = (int) Math.floor(z / cellSize);
        double total = 0.0;
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                total += volcano(cellX + dx, cellZ + dz, x, z);
            }
        }
        return total;
    }

    private double volcano(int cellX, int cellZ, double x, double z) {
        long h = SeedMath.cell(seed, cellX, cellZ);
        if (SeedMath.unit(h) >= chance) {
            return 0.0;
        }
        h = SeedMath.splitMix(h);
        // Centre tenu loin des bords de cellule : deux grands édifices voisins ne fusionnent pas.
        double cx = (cellX + 0.2 + 0.6 * SeedMath.unit(h)) * cellSize;
        h = SeedMath.splitMix(h);
        double cz = (cellZ + 0.2 + 0.6 * SeedMath.unit(h)) * cellSize;
        h = SeedMath.splitMix(h);
        double radius = minRadius + (maxRadius - minRadius) * Math.pow(SeedMath.unit(h), sizePower);
        double ox = x - cx;
        double oz = z - cz;
        double d2 = ox * ox + oz * oz;
        double reach = radius * REACH * (1.0 + irregularity);
        if (d2 >= reach * reach) {
            return 0.0;
        }
        h = SeedMath.splitMix(h);
        double heightScale = 0.75 + 0.5 * SeedMath.unit(h);
        double height = radius * heightRatio * heightScale;
        double distance = Math.sqrt(d2);
        double theta = Math.atan2(oz, ox);
        h = SeedMath.splitMix(h);
        double p1 = SeedMath.unit(h) * Math.PI * 2.0;
        h = SeedMath.splitMix(h);
        double p2 = SeedMath.unit(h) * Math.PI * 2.0;
        double localRadius = radius * (1.0 + irregularity * (0.6 * Math.sin(2.0 * theta + p1) + 0.4 * Math.sin(5.0 * theta + p2)));
        double xn = distance / localRadius;
        if (xn >= 1.0) {
            return 0.0;
        }
        double value = height * profile(xn);
        if (shape != Shape.DOME) {
            // Rainures radiales discrètes : coulées successives, plus marquées à mi-pente. Deux fréquences
            // incommensurables évitent l'aspect d'étoile régulière.
            double furrows = 0.6 * Math.sin(theta * 53.0 + p1 * 3.0) + 0.4 * Math.sin(theta * 89.0 + p2 * 5.0);
            // Modulation angulaire : des secteurs de coulées marquées alternent avec des flancs lisses.
            double sectors = Math.max(0.0, Math.sin(theta * 5.0 + p2) * 0.7 + Math.sin(theta * 11.0 + p1) * 0.3);
            value *= 1.0 + 0.006 * furrows * sectors * xn * (1.0 - xn) * 4.0;
        }
        if (calderaRatio > 0.0) {
            // Caldeira principale légèrement décentrée et caldeira secondaire plus petite : effondrements emboîtés.
            double offset = calderaRatio * radius * 0.35;
            double c1x = ox - Math.cos(p2) * offset;
            double c1z = oz - Math.sin(p2) * offset;
            double c1 = Math.sqrt(c1x * c1x + c1z * c1z) / (calderaRatio * radius);
            double c2x = ox + Math.cos(p2) * offset * 1.3;
            double c2z = oz + Math.sin(p2) * offset * 1.3;
            double c2 = Math.sqrt(c2x * c2x + c2z * c2z) / (calderaRatio * radius * 0.6);
            double pit = Math.max(1.0 - CraterFieldFunction.smoothstep(0.82, 1.0, c1),
                    0.8 * (1.0 - CraterFieldFunction.smoothstep(0.78, 1.0, c2)));
            value -= calderaDepth * height * pit;
        }
        return value;
    }

    private double profile(double x) {
        return switch (shape) {
            case SHIELD -> {
                // (1 − x)^p·(1 + p·x) : sommet plat, flancs concaves, raccord tangent au sol.
                double p = 2.4;
                double base = Math.pow(1.0 - x, p) * (1.0 + p * x);
                if (scarp <= 0.0) {
                    yield base;
                }
                double cliff = 1.0 - CraterFieldFunction.smoothstep(0.84, 0.9, x);
                yield (1.0 - scarp) * base + scarp * cliff * Math.pow(1.0 - 0.6 * x, 1.5);
            }
            case CONE -> Math.pow(1.0 - x, 1.35) * (1.0 + 0.35 * x);
            case DOME -> Math.pow(Math.max(0.0, 1.0 - Math.pow(x, 6.0)), 0.45);
        };
    }

    @Override
    public void fillArray(double[] values, ContextProvider provider) {
        provider.fillAllDirectly(values, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        NoiseHolder wired = visitor.visitNoise(noise);
        if (wired.noise() == noise.noise()) {
            return visitor.apply(this);
        }
        return visitor.apply(new VolcanoFieldFunction(wired, salt, cellSize, chance, minRadius, maxRadius, sizePower,
                heightRatio, shape, scarp, calderaRatio, calderaDepth, irregularity));
    }

    @Override
    public double minValue() {
        return -calderaDepth * maxRadius * heightRatio * 1.25 * 4.0;
    }

    @Override
    public double maxValue() {
        return maxRadius * heightRatio * 1.25 * 1.1 * 4.0;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VolcanoFieldFunction f && f.noise.noise() == noise.noise() && f.noise.noiseData().equals(noise.noiseData())
                && f.salt.equals(salt) && f.cellSize == cellSize && f.chance == chance && f.minRadius == minRadius
                && f.maxRadius == maxRadius && f.sizePower == sizePower && f.heightRatio == heightRatio && f.shape == shape
                && f.scarp == scarp && f.calderaRatio == calderaRatio && f.calderaDepth == calderaDepth
                && f.irregularity == irregularity;
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
