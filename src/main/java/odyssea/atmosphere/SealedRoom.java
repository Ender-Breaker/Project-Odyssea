package odyssea.atmosphere;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMaps;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/**
 * Volume hermétique validé par flood-fill. La grandeur conservée est la masse de gaz (fusion et scission
 * de pièces exactes) ; la pression en découle par la loi des gaz parfaits P = m·R·T / V.
 */
public final class SealedRoom {
    public static final double HEAT_CAPACITY_RATIO = 1.4;
    public static final double DISCHARGE_COEFFICIENT = 0.62;
    public static final double FACE_AREA_M2 = 1.0;

    private static final double CRITICAL_PRESSURE_RATIO =
            Math.pow(2.0 / (HEAT_CAPACITY_RATIO + 1.0), HEAT_CAPACITY_RATIO / (HEAT_CAPACITY_RATIO - 1.0));
    private static final double CHOKED_FLOW_FACTOR =
            Math.pow(2.0 / (HEAT_CAPACITY_RATIO + 1.0), (HEAT_CAPACITY_RATIO + 1.0) / (HEAT_CAPACITY_RATIO - 1.0));

    private final int id;
    private final LongOpenHashSet interior;
    private final Long2ByteOpenHashMap boundary;
    private final LongOpenHashSet sections;
    private final BoundingBox bounds;
    private final AABB entityBounds;
    private final Long2ByteOpenHashMap breachFaces = new Long2ByteOpenHashMap();
    private final LongOpenHashSet diffusers = new LongOpenHashSet();
    private double gasMassKg;
    private int breachArea;
    private double lastLeakRateKgPerSecond;

    SealedRoom(int id, LongOpenHashSet interior, Long2ByteOpenHashMap boundary, LongOpenHashSet sections,
               BoundingBox bounds, double gasMassKg) {
        if (interior.isEmpty()) {
            throw new IllegalArgumentException("A sealed room needs at least one interior cell");
        }
        this.id = id;
        this.interior = interior;
        this.boundary = boundary;
        this.sections = sections;
        this.bounds = bounds;
        this.entityBounds = AABB.of(bounds);
        this.gasMassKg = Math.max(0.0, gasMassKg);
    }

    public int id() {
        return id;
    }

    public int volumeM3() {
        return interior.size();
    }

    public double gasMassKg() {
        return gasMassKg;
    }

    public double pressurePa() {
        return gasMassKg * AtmosphereManager.AIR_GAS_CONSTANT * AtmosphereManager.CABIN_TEMPERATURE_K / volumeM3();
    }

    public double oxygenPartialPressurePa() {
        return pressurePa() * AtmosphereManager.AIR_OXYGEN_FRACTION;
    }

    public double massForPressure(double pressurePa) {
        return pressurePa * volumeM3() / (AtmosphereManager.AIR_GAS_CONSTANT * AtmosphereManager.CABIN_TEMPERATURE_K);
    }

    public boolean isSealed() {
        return breachArea == 0;
    }

    public int breachAreaM2() {
        return breachArea;
    }

    public double lastLeakRateKgPerSecond() {
        return lastLeakRateKgPerSecond;
    }

    public boolean contains(long pos) {
        return interior.contains(pos);
    }

    public boolean isBoundary(long pos) {
        return boundary.containsKey(pos);
    }

    /** Directions, depuis la cellule frontière, vers les cellules intérieures adjacentes. */
    public int boundaryMask(long pos) {
        return boundary.get(pos) & Airtightness.ALL_FACES;
    }

    public boolean isBreachCell(long pos) {
        return breachFaces.containsKey(pos);
    }

    public LongSet interior() {
        return LongSets.unmodifiable(interior);
    }

    public LongSet boundaryCells() {
        return LongSets.unmodifiable(boundary.keySet());
    }

    public Long2ByteMap breaches() {
        return Long2ByteMaps.unmodifiable(breachFaces);
    }

    public LongSet sections() {
        return LongSets.unmodifiable(sections);
    }

    public LongSet diffusers() {
        return LongSets.unmodifiable(diffusers);
    }

    public int diffuserCount() {
        return diffusers.size();
    }

    public BoundingBox bounds() {
        return bounds;
    }

    public AABB entityBounds() {
        return entityBounds;
    }

    public double centerY() {
        return (bounds.minY() + bounds.maxY() + 1) * 0.5;
    }

    void addDiffuser(long pos) {
        diffusers.add(pos);
    }

    void removeDiffuser(long pos) {
        diffusers.remove(pos);
    }

    double addGas(double kg) {
        double applied = Math.max(kg, -gasMassKg);
        gasMassKg += applied;
        return applied;
    }

    void setBreachFaces(long cell, int openFaces) {
        int previous = breachFaces.containsKey(cell) ? Integer.bitCount(breachFaces.get(cell) & Airtightness.ALL_FACES) : 0;
        if (openFaces == 0) {
            breachFaces.remove(cell);
        } else {
            breachFaces.put(cell, (byte) openFaces);
        }
        breachArea += Integer.bitCount(openFaces & Airtightness.ALL_FACES) - previous;
    }

    /**
     * Échange avec l'extérieur par les brèches. Le débit est linéarisé en k = ṁ / |m − m_eq| puis intégré
     * exactement : m(t + dt) = m_eq + (m − m_eq)·e^(−k·dt). Le schéma est inconditionnellement stable, sans
     * dépassement de l'équilibre, et exact en régime sonique vers le vide (ṁ ∝ m).
     */
    void tickLeak(double dt, double externalPressurePa) {
        double area = breachArea * FACE_AREA_M2;
        if (area <= 0.0) {
            lastLeakRateKgPerSecond = 0.0;
            return;
        }
        double equilibrium = massForPressure(externalPressurePa);
        double excess = gasMassKg - equilibrium;
        if (Math.abs(excess) < 1.0e-9) {
            lastLeakRateKgPerSecond = 0.0;
            return;
        }
        double internal = pressurePa();
        double massFlow = excess > 0.0
                ? orificeMassFlow(internal, externalPressurePa, area, AtmosphereManager.CABIN_TEMPERATURE_K)
                : orificeMassFlow(externalPressurePa, internal, area, AtmosphereManager.CABIN_TEMPERATURE_K);
        double rate = massFlow / Math.abs(excess);
        double next = equilibrium + excess * Math.exp(-rate * dt);
        lastLeakRateKgPerSecond = (gasMassKg - next) / dt;
        gasMassKg = Math.max(0.0, next);
    }

    /**
     * Débit massique d'un orifice (gaz parfait, isentropique). Si P_aval/P_amont ≤ (2/(γ+1))^(γ/(γ−1)) ≈ 0,528,
     * l'écoulement est sonique : ṁ = C_d·A·√(γ·ρ₀·P₀·(2/(γ+1))^((γ+1)/(γ−1))), indépendant de l'aval.
     * Sinon : ṁ = C_d·A·√(2ρ₀P₀·γ/(γ−1)·[r^(2/γ) − r^((γ+1)/γ)]).
     */
    public static double orificeMassFlow(double upstreamPa, double downstreamPa, double areaM2, double temperatureK) {
        if (upstreamPa <= downstreamPa || areaM2 <= 0.0) {
            return 0.0;
        }
        double gamma = HEAT_CAPACITY_RATIO;
        double density = upstreamPa / (AtmosphereManager.AIR_GAS_CONSTANT * temperatureK);
        double ratio = downstreamPa / upstreamPa;
        if (ratio <= CRITICAL_PRESSURE_RATIO) {
            return DISCHARGE_COEFFICIENT * areaM2 * Math.sqrt(gamma * density * upstreamPa * CHOKED_FLOW_FACTOR);
        }
        double expansion = Math.pow(ratio, 2.0 / gamma) - Math.pow(ratio, (gamma + 1.0) / gamma);
        return DISCHARGE_COEFFICIENT * areaM2 * Math.sqrt(2.0 * density * upstreamPa * gamma / (gamma - 1.0) * expansion);
    }
}
