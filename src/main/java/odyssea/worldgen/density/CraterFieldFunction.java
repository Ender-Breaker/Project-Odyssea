package odyssea.worldgen.density;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import odyssea.worldgen.noise.SeedMath;

/**
 * {@code odyssea:craters} : champ de cratères d'impact, en variation d'altitude (blocs).
 *
 * <p>Le plan est découpé en cellules de {@code cell_size} blocs ; chaque cellule porte au plus un cratère
 * (probabilité {@code chance}), de centre et de rayon tirés d'un hachage de la cellule. Le rayon suit une loi
 * de puissance ({@code size_power} &gt; 1 favorise les petits), comme la distribution observée des impacts.
 *
 * <p>Morphologie (rapport profondeur/diamètre, remparts, éjectas) :
 * <ul>
 *   <li>Cratère simple (R &lt; {@code complex_radius}) : cuvette parabolique, profondeur ≈ {@code depth_ratio}·R.</li>
 *   <li>Cratère complexe : profondeur qui croît comme √R, fond plat, pic central.</li>
 *   <li>Rempart surélevé ({@code rim_ratio}·profondeur), puis couverture d'éjectas en r⁻³ jusqu'à 2,2 R.</li>
 *   <li>Contour irrégulier ({@code irregularity}) et dégradation aléatoire : les cratères anciens sont émoussés.</li>
 * </ul>
 * Les cratères qui se chevauchent s'additionnent, ce qui reproduit les impacts successifs.
 */
public final class CraterFieldFunction implements DensityFunction {
    private static final double REACH = 2.2;
    private static final double EJECTA_AT_REACH = Math.pow(REACH, -3.0);

    public static final MapCodec<CraterFieldFunction> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            NoiseHolder.CODEC.fieldOf("noise").forGetter(f -> f.noise),
            Codec.STRING.fieldOf("salt").forGetter(f -> f.salt),
            Codec.doubleRange(4.0, 1.0e6).fieldOf("cell_size").forGetter(f -> f.cellSize),
            Codec.doubleRange(0.0, 1.0).optionalFieldOf("chance", 0.5).forGetter(f -> f.chance),
            Codec.doubleRange(1.0, 1.0e6).fieldOf("min_radius").forGetter(f -> f.minRadius),
            Codec.doubleRange(1.0, 1.0e6).fieldOf("max_radius").forGetter(f -> f.maxRadius),
            Codec.doubleRange(0.1, 10.0).optionalFieldOf("size_power", 2.0).forGetter(f -> f.sizePower),
            Codec.doubleRange(0.0, 2.0).optionalFieldOf("depth_ratio", 0.4).forGetter(f -> f.depthRatio),
            Codec.doubleRange(1.0, 1.0e6).optionalFieldOf("complex_radius", 120.0).forGetter(f -> f.complexRadius),
            Codec.doubleRange(0.0, 10.0).optionalFieldOf("rim_ratio", 0.22).forGetter(f -> f.rimRatio),
            Codec.doubleRange(0.0, 0.5).optionalFieldOf("irregularity", 0.08).forGetter(f -> f.irregularity),
            Codec.doubleRange(0.0, 1.0).optionalFieldOf("degradation", 0.5).forGetter(f -> f.degradation)
    ).apply(instance, CraterFieldFunction::new));
    public static final KeyDispatchDataCodec<CraterFieldFunction> CODEC = KeyDispatchDataCodec.of(MAP_CODEC);

    private final NoiseHolder noise;
    private final String salt;
    private final double cellSize;
    private final double chance;
    private final double minRadius;
    private final double maxRadius;
    private final double sizePower;
    private final double depthRatio;
    private final double complexRadius;
    private final double rimRatio;
    private final double irregularity;
    private final double degradation;
    private final long seed;
    private final int range;
    /** Précalculé : vanilla réindexe tout le routeur (HashMap) à chaque création de NoiseChunk. */
    private final int hash;

    public CraterFieldFunction(NoiseHolder noise, String salt, double cellSize, double chance, double minRadius,
                               double maxRadius, double sizePower, double depthRatio, double complexRadius,
                               double rimRatio, double irregularity, double degradation) {
        this.noise = noise;
        this.salt = salt;
        this.cellSize = cellSize;
        this.chance = chance;
        this.minRadius = Math.min(minRadius, maxRadius);
        this.maxRadius = Math.max(minRadius, maxRadius);
        this.sizePower = sizePower;
        this.depthRatio = depthRatio;
        this.complexRadius = complexRadius;
        this.rimRatio = rimRatio;
        this.irregularity = irregularity;
        this.degradation = degradation;
        this.seed = WorldSeed.derive(noise, salt);
        // Un cratère déborde de sa cellule jusqu'à REACH·R·(1 + irrégularité).
        this.range = (int) Math.ceil(this.maxRadius * REACH * (1.0 + irregularity) / cellSize);
        this.hash = java.util.Objects.hash(System.identityHashCode(noise.noise()), salt, cellSize, chance, minRadius, maxRadius, sizePower,
                depthRatio, complexRadius, rimRatio, irregularity, degradation);
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
                total += crater(cellX + dx, cellZ + dz, x, z);
            }
        }
        return total;
    }

    private double crater(int cellX, int cellZ, double x, double z) {
        long h = SeedMath.cell(seed, cellX, cellZ);
        if (SeedMath.unit(h) >= chance) {
            return 0.0;
        }
        h = SeedMath.splitMix(h);
        double cx = (cellX + SeedMath.unit(h)) * cellSize;
        h = SeedMath.splitMix(h);
        double cz = (cellZ + SeedMath.unit(h)) * cellSize;
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
        // Fraîcheur : 1 pour un impact récent, jusqu'à (1 − degradation) pour un cratère très ancien.
        double age = 1.0 - degradation * SeedMath.unit(h);
        double distance = Math.sqrt(d2);
        double localRadius = radius;
        if (irregularity > 0.0 && distance > 1e-3) {
            double theta = Math.atan2(oz, ox);
            h = SeedMath.splitMix(h);
            double p1 = SeedMath.unit(h) * Math.PI * 2.0;
            h = SeedMath.splitMix(h);
            double p2 = SeedMath.unit(h) * Math.PI * 2.0;
            h = SeedMath.splitMix(h);
            double p3 = SeedMath.unit(h) * Math.PI * 2.0;
            localRadius *= 1.0 + irregularity * (0.5 * Math.sin(3.0 * theta + p1) + 0.3 * Math.sin(5.0 * theta + p2)
                    + 0.2 * Math.sin(11.0 * theta + p3));
        }
        return profile(distance / localRadius, radius, age);
    }

    /** Altitude relative (blocs) à la distance normalisée x = r/R. */
    private double profile(double x, double radius, double age) {
        boolean complex = radius > complexRadius;
        double depth = complex
                ? depthRatio * complexRadius * Math.sqrt(radius / complexRadius)
                : depthRatio * radius;
        depth *= age;
        double rim = rimRatio * depth * age;
        if (x >= REACH) {
            return 0.0;
        }
        if (x >= 1.0) {
            // Couverture d'éjectas : décroissance en r⁻³, ramenée à zéro en bord de portée.
            return rim * (Math.pow(x, -3.0) - EJECTA_AT_REACH) / (1.0 - EJECTA_AT_REACH);
        }
        double bowl = x * x - 1.0;
        double complexity = smoothstep(complexRadius * 0.8, complexRadius * 2.0, radius);
        if (complexity > 0.0) {
            // Fond plat : la cuvette est tronquée à 55 % de sa profondeur pour les grands cratères.
            double floor = -1.0 + 0.45 * complexity;
            bowl = smoothMax(bowl, floor, 0.08);
        }
        double value = depth * bowl + rim * square(smoothstep(0.55, 1.0, x));
        if (complexity > 0.0) {
            double peak = 0.45 * depth * complexity;
            value += peak * Math.exp(-square(x / 0.14));
            // Parois en gradins (effondrements en terrasses des grands cratères).
            double wall = smoothstep(0.55, 0.7, x) * (1.0 - smoothstep(0.92, 1.0, x));
            if (wall > 0.0) {
                double step = Math.max(depth * 0.18, 3.0);
                double v = value / step;
                double k = Math.floor(v);
                double terraced = (k + smoothstep(0.6, 1.0, v - k)) * step;
                value += (terraced - value) * wall * complexity;
            }
        }
        return value;
    }

    static double smoothstep(double edge0, double edge1, double v) {
        double t = Math.min(1.0, Math.max(0.0, (v - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }

    static double smoothMax(double a, double b, double k) {
        double h = Math.min(1.0, Math.max(0.0, 0.5 + 0.5 * (a - b) / k));
        return b + (a - b) * h + k * h * (1.0 - h);
    }

    private static double square(double v) {
        return v * v;
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
        return visitor.apply(new CraterFieldFunction(wired, salt, cellSize, chance, minRadius, maxRadius, sizePower,
                depthRatio, complexRadius, rimRatio, irregularity, degradation));
    }

    @Override
    public double minValue() {
        // Borne prudente : quelques cuvettes de profondeur maximale superposées.
        return -4.0 * depthRatio * Math.max(maxRadius, complexRadius) - 1.0;
    }

    @Override
    public double maxValue() {
        return 4.0 * depthRatio * Math.max(maxRadius, complexRadius) * (rimRatio + 0.5) + 1.0;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CraterFieldFunction f && f.noise.noise() == noise.noise() && f.noise.noiseData().equals(noise.noiseData())
                && f.salt.equals(salt) && f.cellSize == cellSize && f.chance == chance && f.minRadius == minRadius
                && f.maxRadius == maxRadius && f.sizePower == sizePower && f.depthRatio == depthRatio
                && f.complexRadius == complexRadius && f.rimRatio == rimRatio && f.irregularity == irregularity
                && f.degradation == degradation;
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
