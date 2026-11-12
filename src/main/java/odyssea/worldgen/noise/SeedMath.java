package odyssea.worldgen.noise;

/** Hachages 64 bits déterministes (SplitMix64) pour les graines, les cellules et les tirages uniformes. */
public final class SeedMath {
    private SeedMath() {
    }

    public static long splitMix(long x) {
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    public static long mix(long a, long b) {
        return splitMix(a ^ splitMix(b));
    }

    public static long hash(String text) {
        long h = 1125899906842597L;
        for (int i = 0; i < text.length(); i++) {
            h = 31 * h + text.charAt(i);
        }
        return splitMix(h);
    }

    public static long cell(long seed, int x, int z) {
        return splitMix(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL));
    }

    /** Tirage uniforme dans [0, 1) à partir des 53 bits de poids fort. */
    public static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
