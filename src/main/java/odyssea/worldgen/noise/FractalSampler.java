package odyssea.worldgen.noise;

/**
 * Somme fractale de bruits simplex 2D, en coordonnées de blocs.
 *
 * <ul>
 *   <li>{@link Mode#FBM} : mouvement brownien fractionnaire, sortie dans [−1, 1].</li>
 *   <li>{@link Mode#RIDGED} : multifractale à crêtes de Musgrave, sortie dans [0, 1]. Les octaves fines sont
 *       pondérées par le signal déjà accumulé : crêtes rugueuses, fonds de vallée lisses.</li>
 *   <li>{@link Mode#BILLOW} : valeur absolue (collines arrondies, dunes), sortie dans [0, 1].</li>
 * </ul>
 *
 * <p>{@code erosion} applique l'atténuation par la pente accumulée (contribution ÷ (1 + k·|∇|²)) : les détails
 * fins disparaissent sur les versants raides, comme sous l'effet du ruissellement. {@code warp} déforme le
 * domaine par un second bruit, ce qui casse l'aspect isotrope et donne des chaînes sinueuses.
 */
public final class FractalSampler {
    public enum Mode {
        FBM, RIDGED, BILLOW
    }

    private static final int WARP_OCTAVES = 3;

    private final Mode mode;
    private final double frequency;
    private final int octaves;
    private final double lacunarity;
    private final double gain;
    private final double erosion;
    private final double warpAmount;
    private final double warpFrequency;
    private final double cos;
    private final double sin;
    private final double inverseStretch;
    private final SimplexNoise2D[] layers;
    private final SimplexNoise2D[] warpX;
    private final SimplexNoise2D[] warpZ;
    private final double normalization;

    public FractalSampler(long seed, Mode mode, double scale, int octaves, double lacunarity, double gain,
                          double erosion, double warpAmount, double warpScale, double stretch, double angleDegrees) {
        this.mode = mode;
        this.frequency = 1.0 / Math.max(scale, 1e-6);
        this.octaves = Math.max(1, octaves);
        this.lacunarity = lacunarity;
        this.gain = gain;
        this.erosion = Math.max(0.0, erosion);
        this.warpAmount = warpAmount;
        this.warpFrequency = 1.0 / Math.max(warpScale, 1e-6);
        double angle = Math.toRadians(angleDegrees);
        this.cos = Math.cos(angle);
        this.sin = Math.sin(angle);
        this.inverseStretch = 1.0 / Math.max(stretch, 1e-6);
        this.layers = new SimplexNoise2D[this.octaves];
        for (int i = 0; i < this.octaves; i++) {
            layers[i] = new SimplexNoise2D(SeedMath.mix(seed, i + 1));
        }
        if (warpAmount != 0.0) {
            warpX = new SimplexNoise2D[WARP_OCTAVES];
            warpZ = new SimplexNoise2D[WARP_OCTAVES];
            for (int i = 0; i < WARP_OCTAVES; i++) {
                warpX[i] = new SimplexNoise2D(SeedMath.mix(seed, 1001 + i));
                warpZ[i] = new SimplexNoise2D(SeedMath.mix(seed, 2001 + i));
            }
        } else {
            warpX = null;
            warpZ = null;
        }
        double norm = 0.0;
        double amplitude = 1.0;
        for (int i = 0; i < this.octaves; i++) {
            norm += amplitude;
            amplitude *= gain;
        }
        this.normalization = 1.0 / norm;
    }

    public double sample(double x, double z) {
        // Anisotropie : rotation puis compression de l'axe u (formes allongées selon u, comme des dunes).
        double u = (x * cos + z * sin) * inverseStretch;
        double v = -x * sin + z * cos;
        if (warpX != null) {
            double wx = warpFbm(warpX, u * warpFrequency, v * warpFrequency);
            double wz = warpFbm(warpZ, u * warpFrequency + 5.2, v * warpFrequency + 1.3);
            u += warpAmount * wx;
            v += warpAmount * wz;
        }
        u *= frequency;
        v *= frequency;
        return switch (mode) {
            case FBM -> fbm(u, v);
            case RIDGED -> ridged(u, v);
            case BILLOW -> billow(u, v);
        };
    }

    private double fbm(double u, double v) {
        double[] d = new double[2];
        double sum = 0.0;
        double amplitude = 1.0;
        double gx = 0.0;
        double gy = 0.0;
        for (int i = 0; i < octaves; i++) {
            double n = layers[i].sample(u, v, d);
            gx += d[0] * amplitude;
            gy += d[1] * amplitude;
            sum += amplitude * n / (1.0 + erosion * (gx * gx + gy * gy));
            amplitude *= gain;
            u = u * lacunarity + 17.31;
            v = v * lacunarity - 9.17;
        }
        return sum * normalization;
    }

    private double ridged(double u, double v) {
        double[] d = new double[2];
        double sum = 0.0;
        double amplitude = 1.0;
        double weight = 1.0;
        double gx = 0.0;
        double gy = 0.0;
        for (int i = 0; i < octaves; i++) {
            double n = layers[i].sample(u, v, d);
            double ridge = 1.0 - Math.abs(n);
            double signal = ridge * ridge * weight;
            // d(ridge²)/dp = −2·ridge·sign(n)·∇n
            double k = -2.0 * ridge * Math.signum(n) * weight * amplitude;
            gx += d[0] * k;
            gy += d[1] * k;
            sum += amplitude * signal / (1.0 + erosion * (gx * gx + gy * gy));
            weight = Math.min(1.0, Math.max(0.0, signal * 2.0));
            amplitude *= gain;
            u = u * lacunarity + 17.31;
            v = v * lacunarity - 9.17;
        }
        return sum * normalization;
    }

    private double billow(double u, double v) {
        double sum = 0.0;
        double amplitude = 1.0;
        for (int i = 0; i < octaves; i++) {
            sum += amplitude * Math.abs(layers[i].sample(u, v));
            amplitude *= gain;
            u = u * lacunarity + 17.31;
            v = v * lacunarity - 9.17;
        }
        return sum * normalization;
    }

    private static double warpFbm(SimplexNoise2D[] noises, double u, double v) {
        double sum = 0.0;
        double amplitude = 1.0;
        double norm = 0.0;
        for (SimplexNoise2D noise : noises) {
            sum += amplitude * noise.sample(u, v);
            norm += amplitude;
            amplitude *= 0.5;
            u *= 2.0;
            v *= 2.0;
        }
        return sum / norm;
    }

    public double minValue() {
        return mode == Mode.FBM ? -1.0 : 0.0;
    }

    public double maxValue() {
        return 1.0;
    }
}
