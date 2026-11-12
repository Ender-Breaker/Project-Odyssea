package odyssea.dimension;

import java.util.EnumMap;
import java.util.Map;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Profil atmosphérique d'une dimension : grandeurs de gameplay (pression, O₂) et coefficients
 * de diffusion pour le rendu, dérivés de la même composition de gaz.
 */
public record PlanetaryAtmosphere(
        double surfacePressurePa,
        double seaLevelY,
        double pressureScaleHeightBlocks,
        double oxygenFraction,
        double temperatureK,
        double specificGasConstant,
        double gravity,
        Scattering scattering) {

    public static final double UNIVERSAL_GAS_CONSTANT = 8.314462618;
    public static final double BOLTZMANN = 1.380649e-23;
    public static final double LOSCHMIDT = 2.6867811e25;
    public static final double EARTH_SURFACE_PRESSURE_PA = 101_325.0;
    private static final double[] WAVELENGTHS_M = {680e-9, 550e-9, 440e-9};
    private static final Vector3fc METHANE_ABSORPTION_PER_KM_AT_1_BAR = new Vector3f(0.42f, 0.065f, 0.012f);

    public static final PlanetaryAtmosphere VACUUM = new PlanetaryAtmosphere(
            0.0, 63.0, 1.0, 0.0, 3.0, 287.05, 0.0,
            new Scattering(new Vector3f(), 1.0f, new Vector3f(), new Vector3f(), 1.0f, new Vector3f(), new Vector3f(), 1.0f, 0.0f));

    public PlanetaryAtmosphere {
        if (surfacePressurePa < 0.0 || pressureScaleHeightBlocks <= 0.0 || temperatureK <= 0.0 || specificGasConstant <= 0.0) {
            throw new IllegalArgumentException("Invalid atmosphere parameters");
        }
        oxygenFraction = Math.clamp(oxygenFraction, 0.0, 1.0);
    }

    /**
     * Coefficients en km⁻¹ au niveau de la mer ; le shader applique le profil exp(−h/H).
     *
     * @param mieAnisotropy paramètre g de la fonction de phase, par canal (R, V, B) : des poussières qui diffusent le
     *                      bleu plus vers l'avant donnent le halo bleu des couchers de soleil martiens
     */
    public record Scattering(
            Vector3fc rayleighPerKm,
            float rayleighScaleHeightKm,
            Vector3fc mieScatteringPerKm,
            Vector3fc mieExtinctionPerKm,
            float mieScaleHeightKm,
            Vector3fc mieAnisotropy,
            Vector3fc absorptionPerKm,
            float planetRadiusKm,
            float thicknessKm) {

        public boolean hasAtmosphere() {
            return thicknessKm > 0.0f;
        }
    }

    public record Aerosols(Vector3fc scatteringPerKm, Vector3fc extinctionPerKm, float scaleHeightKm, Vector3fc anisotropy) {
        public static final Aerosols NONE = new Aerosols(new Vector3f(), new Vector3f(), 1.0f, 0.0f);

        public Aerosols(Vector3fc scatteringPerKm, Vector3fc extinctionPerKm, float scaleHeightKm, float anisotropy) {
            this(scatteringPerKm, extinctionPerKm, scaleHeightKm, new Vector3f(anisotropy));
        }
    }

    /** Réfractivité (n − 1) aux conditions normales (273,15 K, 1 atm) et facteur de King (dépolarisation). */
    public enum Gas {
        N2(0.028014, 2.98e-4, 1.034),
        O2(0.031998, 2.71e-4, 1.096),
        AR(0.039948, 2.81e-4, 1.000),
        CO2(0.044010, 4.50e-4, 1.150),
        H2(0.002016, 1.32e-4, 1.020),
        HE(0.004003, 0.35e-4, 1.000),
        CH4(0.016040, 4.44e-4, 1.000),
        H2O(0.018015, 2.56e-4, 1.000);

        public final double molarMassKgPerMol;
        public final double refractivityStp;
        public final double kingFactor;

        Gas(double molarMassKgPerMol, double refractivityStp, double kingFactor) {
            this.molarMassKgPerMol = molarMassKgPerMol;
            this.refractivityStp = refractivityStp;
            this.kingFactor = kingFactor;
        }
    }

    public static PlanetaryAtmosphere fromComposition(
            Map<Gas, Double> moleFractions,
            double surfacePressurePa,
            double temperatureK,
            double gravity,
            double seaLevelY,
            double pressureScaleHeightBlocks,
            float planetRadiusKm,
            float thicknessKm,
            Aerosols aerosols) {
        EnumMap<Gas, Double> fractions = new EnumMap<>(Gas.class);
        double total = 0.0;
        for (Map.Entry<Gas, Double> entry : moleFractions.entrySet()) {
            double x = Math.max(0.0, entry.getValue());
            fractions.put(entry.getKey(), x);
            total += x;
        }
        if (total <= 0.0) {
            throw new IllegalArgumentException("Empty gas composition");
        }

        double molarMass = 0.0;
        double refractivityStp = 0.0;
        double king = 0.0;
        for (Map.Entry<Gas, Double> entry : fractions.entrySet()) {
            double x = entry.getValue() / total;
            Gas gas = entry.getKey();
            molarMass += x * gas.molarMassKgPerMol;
            refractivityStp += x * gas.refractivityStp;
            king += x * gas.kingFactor;
        }
        double specificGasConstant = UNIVERSAL_GAS_CONSTANT / molarMass;
        double numberDensity = surfacePressurePa / (BOLTZMANN * temperatureK);
        // (n − 1) proportionnel à la densité (loi de Gladstone-Dale)
        double refractivity = refractivityStp * numberDensity / LOSCHMIDT;

        Vector3f rayleigh = new Vector3f(
                (float) rayleighPerKm(refractivity, numberDensity, king, WAVELENGTHS_M[0]),
                (float) rayleighPerKm(refractivity, numberDensity, king, WAVELENGTHS_M[1]),
                (float) rayleighPerKm(refractivity, numberDensity, king, WAVELENGTHS_M[2]));
        // Hauteur d'échelle isotherme H = R_s·T / g
        float scaleHeightKm = gravity > 0.0 ? (float) (specificGasConstant * temperatureK / gravity / 1000.0) : thicknessKm;
        double methane = fractions.getOrDefault(Gas.CH4, 0.0) / total;
        Vector3f absorption = new Vector3f(METHANE_ABSORPTION_PER_KM_AT_1_BAR)
                .mul((float) (methane * surfacePressurePa / EARTH_SURFACE_PRESSURE_PA));
        double oxygen = fractions.getOrDefault(Gas.O2, 0.0) / total;

        Scattering scattering = new Scattering(
                rayleigh, Math.max(scaleHeightKm, 0.1f),
                new Vector3f(aerosols.scatteringPerKm()), new Vector3f(aerosols.extinctionPerKm()),
                aerosols.scaleHeightKm(), new Vector3f(aerosols.anisotropy()),
                absorption, planetRadiusKm, thicknessKm);
        return new PlanetaryAtmosphere(surfacePressurePa, seaLevelY, pressureScaleHeightBlocks, oxygen,
                temperatureK, specificGasConstant, gravity, scattering);
    }

    /** β_R(λ) = 8π³(n² − 1)² / (3Nλ⁴) · F_K ≈ 32π³(n − 1)² / (3Nλ⁴) · F_K, converti en km⁻¹. */
    private static double rayleighPerKm(double refractivity, double numberDensity, double king, double wavelength) {
        if (numberDensity <= 0.0) {
            return 0.0;
        }
        double lambda2 = wavelength * wavelength;
        return 32.0 * Math.PI * Math.PI * Math.PI / 3.0 * refractivity * refractivity
                / (numberDensity * lambda2 * lambda2) * king * 1000.0;
    }

    /** Même atmosphère pour le jeu (pression, O₂…), diffusion remplacée pour le rendu. */
    public PlanetaryAtmosphere withScattering(Scattering visual) {
        return new PlanetaryAtmosphere(surfacePressurePa, seaLevelY, pressureScaleHeightBlocks, oxygenFraction,
                temperatureK, specificGasConstant, gravity, visual);
    }

    public double pressureAt(double y) {
        if (surfacePressurePa <= 0.0) {
            return 0.0;
        }
        double pressure = surfacePressurePa * Math.exp(-(y - seaLevelY) / pressureScaleHeightBlocks);
        return Math.min(pressure, surfacePressurePa * 2.0);
    }

    public double densityAt(double y) {
        return pressureAt(y) / (specificGasConstant * temperatureK);
    }

    public double oxygenPartialPressureAt(double y) {
        return pressureAt(y) * oxygenFraction;
    }
}
