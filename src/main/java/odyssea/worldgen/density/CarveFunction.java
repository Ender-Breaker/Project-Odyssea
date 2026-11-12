package odyssea.worldgen.density;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * {@code odyssea:carve} : creuse une altitude vers un plancher, {@code input − max(input − floor, 0) · weight}.
 *
 * <p>Équivalent à une combinaison de fonctions vanilla, mais {@code input} n'est évalué qu'une fois : hors des
 * caches de {@code NoiseChunk} (choix des biomes, recherche du point d'apparition), le relief complet n'est pas
 * recalculé deux fois.
 */
public record CarveFunction(DensityFunction input, DensityFunction floor, DensityFunction weight) implements DensityFunction {
    public static final MapCodec<CarveFunction> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("input").forGetter(CarveFunction::input),
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("floor").forGetter(CarveFunction::floor),
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("weight").forGetter(CarveFunction::weight)
    ).apply(instance, CarveFunction::new));
    public static final KeyDispatchDataCodec<CarveFunction> CODEC = KeyDispatchDataCodec.of(MAP_CODEC);

    @Override
    public double compute(FunctionContext context) {
        double value = input.compute(context);
        double w = weight.compute(context);
        if (w <= 0.0) {
            return value;
        }
        return value - Math.max(value - floor.compute(context), 0.0) * w;
    }

    @Override
    public void fillArray(double[] values, ContextProvider provider) {
        provider.fillAllDirectly(values, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new CarveFunction(input.mapAll(visitor), floor.mapAll(visitor), weight.mapAll(visitor)));
    }

    @Override
    public double minValue() {
        return Math.min(input.minValue(), floor.minValue());
    }

    @Override
    public double maxValue() {
        return input.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
