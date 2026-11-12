package odyssea.worldgen.density;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import odyssea.worldgen.noise.FractalSampler;
import org.jetbrains.annotations.Nullable;

/**
 * {@code odyssea:fractal} : bruit fractal 2D (fBm, crêtes, « billow ») avec érosion par la pente,
 * déformation de domaine et anisotropie. Ne dépend pas de Y : à envelopper dans {@code flat_cache}.
 *
 * <pre>{@code
 * { "type": "odyssea:fractal", "noise": "odyssea:seed", "salt": "terra/peaks", "mode": "ridged",
 *   "scale": 2400, "octaves": 9, "erosion": 1.2, "warp": 450, "warp_scale": 3000 }
 * }</pre>
 */
public final class FractalNoiseFunction implements DensityFunction {
    public static final MapCodec<FractalNoiseFunction> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            NoiseHolder.CODEC.fieldOf("noise").forGetter(f -> f.noise),
            Codec.STRING.fieldOf("salt").forGetter(f -> f.salt),
            WorldSeed.enumCodec(FractalSampler.Mode.class).optionalFieldOf("mode", FractalSampler.Mode.FBM).forGetter(f -> f.mode),
            Codec.doubleRange(1.0, 1.0e7).fieldOf("scale").forGetter(f -> f.scale),
            Codec.intRange(1, 16).optionalFieldOf("octaves", 6).forGetter(f -> f.octaves),
            Codec.doubleRange(1.0, 4.0).optionalFieldOf("lacunarity", 2.0).forGetter(f -> f.lacunarity),
            Codec.doubleRange(0.0, 1.0).optionalFieldOf("gain", 0.5).forGetter(f -> f.gain),
            Codec.doubleRange(0.0, 100.0).optionalFieldOf("erosion", 0.0).forGetter(f -> f.erosion),
            Codec.DOUBLE.optionalFieldOf("warp", 0.0).forGetter(f -> f.warp),
            Codec.doubleRange(1.0, 1.0e7).optionalFieldOf("warp_scale", 1000.0).forGetter(f -> f.warpScale),
            Codec.doubleRange(0.01, 100.0).optionalFieldOf("stretch", 1.0).forGetter(f -> f.stretch),
            Codec.DOUBLE.optionalFieldOf("angle", 0.0).forGetter(f -> f.angle)
    ).apply(instance, FractalNoiseFunction::new));
    public static final KeyDispatchDataCodec<FractalNoiseFunction> CODEC = KeyDispatchDataCodec.of(MAP_CODEC);

    private final NoiseHolder noise;
    private final String salt;
    private final FractalSampler.Mode mode;
    private final double scale;
    private final int octaves;
    private final double lacunarity;
    private final double gain;
    private final double erosion;
    private final double warp;
    private final double warpScale;
    private final double stretch;
    private final double angle;
    private final @Nullable FractalSampler sampler;
    /** Précalculé : vanilla réindexe tout le routeur (HashMap) à chaque création de NoiseChunk. */
    private final int hash;

    public FractalNoiseFunction(NoiseHolder noise, String salt, FractalSampler.Mode mode, double scale, int octaves,
                                double lacunarity, double gain, double erosion, double warp, double warpScale,
                                double stretch, double angle) {
        this.noise = noise;
        this.salt = salt;
        this.mode = mode;
        this.scale = scale;
        this.octaves = octaves;
        this.lacunarity = lacunarity;
        this.gain = gain;
        this.erosion = erosion;
        this.warp = warp;
        this.warpScale = warpScale;
        this.stretch = stretch;
        this.angle = angle;
        // Tant que le bruit n'est pas relié à la graine du monde (instance issue du codec), aucun échantillonneur.
        this.sampler = noise.noise() == null ? null : new FractalSampler(WorldSeed.derive(noise, salt), mode, scale,
                octaves, lacunarity, gain, erosion, warp, warpScale, stretch, angle);
        this.hash = java.util.Objects.hash(System.identityHashCode(noise.noise()), salt, mode, scale, octaves, lacunarity, gain, erosion,
                warp, warpScale, stretch, angle);
    }

    @Override
    public double compute(FunctionContext context) {
        return sampler == null ? 0.0 : sampler.sample(context.blockX(), context.blockZ());
    }

    @Override
    public void fillArray(double[] values, ContextProvider provider) {
        provider.fillAllDirectly(values, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        NoiseHolder wired = visitor.visitNoise(noise);
        // Les visiteurs de NoiseChunk renvoient le même bruit : on garde l'instance (et ses tables) telle quelle.
        if (wired.noise() == noise.noise()) {
            return visitor.apply(this);
        }
        return visitor.apply(new FractalNoiseFunction(wired, salt, mode, scale, octaves, lacunarity, gain, erosion,
                warp, warpScale, stretch, angle));
    }

    @Override
    public double minValue() {
        return mode == FractalSampler.Mode.FBM ? -1.0 : 0.0;
    }

    @Override
    public double maxValue() {
        return 1.0;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    /**
     * Égalité par paramètres et par instance de bruit relié : deux références au même JSON partagent alors le
     * même cache dans {@code NoiseChunk} (les marqueurs de cache y sont indexés par égalité).
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof FractalNoiseFunction f && f.noise.noise() == noise.noise() && f.noise.noiseData().equals(noise.noiseData())
                && f.salt.equals(salt) && f.mode == mode && f.scale == scale && f.octaves == octaves && f.lacunarity == lacunarity
                && f.gain == gain && f.erosion == erosion && f.warp == warp && f.warpScale == warpScale && f.stretch == stretch
                && f.angle == angle;
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
