package odyssea.worldgen.density;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * {@code odyssea:terrace} : quantifie une altitude en gradins (mesas, parois stratifiées de canyons, fronts
 * de coulées). Chaque marche de hauteur {@code step} est plate sur (1 − {@code riser}) de sa largeur puis
 * remonte par une pente raccordée en douceur ; la fonction reste continue et monotone.
 */
public final class TerraceFunction implements DensityFunction {
    public static final MapCodec<TerraceFunction> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("input").forGetter(f -> f.input),
            Codec.doubleRange(0.01, 1.0e6).fieldOf("step").forGetter(f -> f.step),
            Codec.doubleRange(0.01, 1.0).optionalFieldOf("riser", 0.3).forGetter(f -> f.riser),
            Codec.DOUBLE.optionalFieldOf("offset", 0.0).forGetter(f -> f.offset)
    ).apply(instance, TerraceFunction::new));
    public static final KeyDispatchDataCodec<TerraceFunction> CODEC = KeyDispatchDataCodec.of(MAP_CODEC);

    private final DensityFunction input;
    private final double step;
    private final double riser;
    private final double offset;
    /** Précalculé : vanilla réindexe tout le routeur (HashMap) à chaque création de NoiseChunk. */
    private final int hash;

    public TerraceFunction(DensityFunction input, double step, double riser, double offset) {
        this.input = input;
        this.step = step;
        this.riser = riser;
        this.offset = offset;
        this.hash = java.util.Objects.hash(input, step, riser, offset);
    }

    private double transform(double value) {
        double v = (value - offset) / step;
        double k = Math.floor(v);
        double t = CraterFieldFunction.smoothstep(1.0 - riser, 1.0, v - k);
        return (k + t) * step + offset;
    }

    @Override
    public double compute(FunctionContext context) {
        return transform(input.compute(context));
    }

    @Override
    public void fillArray(double[] values, ContextProvider provider) {
        input.fillArray(values, provider);
        for (int i = 0; i < values.length; i++) {
            values[i] = transform(values[i]);
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new TerraceFunction(input.mapAll(visitor), step, riser, offset));
    }

    @Override
    public double minValue() {
        return input.minValue() - step;
    }

    @Override
    public double maxValue() {
        return input.maxValue() + step;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TerraceFunction f && f.input.equals(input) && f.step == step && f.riser == riser && f.offset == offset;
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
