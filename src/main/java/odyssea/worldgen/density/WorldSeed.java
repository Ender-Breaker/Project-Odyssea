package odyssea.worldgen.density;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.Locale;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import odyssea.worldgen.noise.SeedMath;

/**
 * Graine monde accessible depuis une density function.
 *
 * <p>Vanilla ne transmet la graine aux density functions qu'à travers les {@link DensityFunction.NoiseHolder} :
 * le visiteur de {@code RandomState} les relie à une {@link NormalNoise} initialisée par la graine du monde.
 * On échantillonne donc ce bruit en quelques points fixes et on hache les valeurs : le résultat est une graine
 * 64 bits qui change avec le monde et reste identique d'un chargement à l'autre.
 */
final class WorldSeed {
    private WorldSeed() {
    }

    static long derive(DensityFunction.NoiseHolder holder, String salt) {
        long h = SeedMath.hash(salt);
        NormalNoise noise = holder.noise();
        if (noise == null) {
            return h;
        }
        for (int i = 0; i < 4; i++) {
            double v = noise.getValue(i * 1013.37 + 0.123, i * 71.71 - 3.31, -i * 311.97 + 7.77);
            h = SeedMath.mix(h, Double.doubleToRawLongBits(v));
        }
        return h;
    }

    static <E extends Enum<E>> Codec<E> enumCodec(Class<E> type) {
        return Codec.STRING.comapFlatMap(name -> {
            try {
                return DataResult.success(Enum.valueOf(type, name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return DataResult.error(() -> "Unknown " + type.getSimpleName() + ": " + name);
            }
        }, value -> value.name().toLowerCase(Locale.ROOT));
    }
}
