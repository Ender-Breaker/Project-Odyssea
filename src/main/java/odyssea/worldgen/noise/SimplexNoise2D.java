package odyssea.worldgen.noise;

/**
 * Bruit simplex 2D avec gradient analytique. Le gradient sert au fBm « érodé » (atténuation des octaves
 * fines sur les pentes fortes), qui produit des vallées lisses et des crêtes nettes à la manière d'un relief
 * réellement érodé.
 *
 * <p>Permutation tirée d'une graine 64 bits : deux instances de même graine donnent exactement le même champ,
 * quel que soit le thread. L'objet est immuable après construction et donc partageable entre threads.
 */
public final class SimplexNoise2D {
    private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
    private static final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;
    private static final int GRADIENTS = 32;
    private static final double[] GRAD_X = new double[GRADIENTS];
    private static final double[] GRAD_Y = new double[GRADIENTS];
    /** Ramène l'amplitude dans [−1, 1] pour des gradients unitaires et un noyau (0,5 − r²)⁴. */
    private static final double NORMALIZATION = 99.204;

    static {
        for (int i = 0; i < GRADIENTS; i++) {
            double angle = (i + 0.5) * (Math.PI * 2.0 / GRADIENTS);
            GRAD_X[i] = Math.cos(angle);
            GRAD_Y[i] = Math.sin(angle);
        }
    }

    private final byte[] perm = new byte[512];

    public SimplexNoise2D(long seed) {
        int[] values = new int[256];
        for (int i = 0; i < 256; i++) {
            values[i] = i;
        }
        long state = seed;
        for (int i = 255; i > 0; i--) {
            state = SeedMath.splitMix(state);
            int j = (int) Math.floorMod(state, (long) (i + 1));
            int tmp = values[i];
            values[i] = values[j];
            values[j] = tmp;
        }
        for (int i = 0; i < 512; i++) {
            perm[i] = (byte) values[i & 255];
        }
    }

    private int hash(int i, int j) {
        return (perm[(i & 255) + (perm[j & 255] & 255)] & 255) & (GRADIENTS - 1);
    }

    /**
     * @param derivative tableau de 2 cases recevant ∂n/∂x et ∂n/∂y, ou {@code null}
     * @return valeur dans [−1, 1] environ
     */
    public double sample(double x, double y, double[] derivative) {
        double s = (x + y) * F2;
        int i = floor(x + s);
        int j = floor(y + s);
        double t = (i + j) * G2;
        double x0 = x - (i - t);
        double y0 = y - (j - t);
        int i1;
        int j1;
        if (x0 > y0) {
            i1 = 1;
            j1 = 0;
        } else {
            i1 = 0;
            j1 = 1;
        }
        double x1 = x0 - i1 + G2;
        double y1 = y0 - j1 + G2;
        double x2 = x0 - 1.0 + 2.0 * G2;
        double y2 = y0 - 1.0 + 2.0 * G2;

        double value = 0.0;
        double dx = 0.0;
        double dy = 0.0;

        double t0 = 0.5 - x0 * x0 - y0 * y0;
        if (t0 > 0.0) {
            int g = hash(i, j);
            double gdot = GRAD_X[g] * x0 + GRAD_Y[g] * y0;
            double t02 = t0 * t0;
            double t04 = t02 * t02;
            value += t04 * gdot;
            double k = -8.0 * t02 * t0 * gdot;
            dx += k * x0 + t04 * GRAD_X[g];
            dy += k * y0 + t04 * GRAD_Y[g];
        }
        double t1 = 0.5 - x1 * x1 - y1 * y1;
        if (t1 > 0.0) {
            int g = hash(i + i1, j + j1);
            double gdot = GRAD_X[g] * x1 + GRAD_Y[g] * y1;
            double t12 = t1 * t1;
            double t14 = t12 * t12;
            value += t14 * gdot;
            double k = -8.0 * t12 * t1 * gdot;
            dx += k * x1 + t14 * GRAD_X[g];
            dy += k * y1 + t14 * GRAD_Y[g];
        }
        double t2 = 0.5 - x2 * x2 - y2 * y2;
        if (t2 > 0.0) {
            int g = hash(i + 1, j + 1);
            double gdot = GRAD_X[g] * x2 + GRAD_Y[g] * y2;
            double t22 = t2 * t2;
            double t24 = t22 * t22;
            value += t24 * gdot;
            double k = -8.0 * t22 * t2 * gdot;
            dx += k * x2 + t24 * GRAD_X[g];
            dy += k * y2 + t24 * GRAD_Y[g];
        }
        if (derivative != null) {
            derivative[0] = dx * NORMALIZATION;
            derivative[1] = dy * NORMALIZATION;
        }
        return value * NORMALIZATION;
    }

    public double sample(double x, double y) {
        return sample(x, y, null);
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
