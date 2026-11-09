package odyssea.physics;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Bouclier cinétique à réserve tampon. Chaque impact consomme la charge au prorata de son énergie
 * (E/η) et échauffe les émetteurs ; au-delà du seuil thermique le champ s'effondre (surcharge), et une
 * fois la charge épuisée, l'énergie résiduelle détruit localement la coque.
 *
 * <p>Flux type : {@link OdysseaShipPhysics#resolveContact} → {@link #handleImpact} → {@link #applyHullDamage}.
 * Les blocs détruits ouvrent des brèches que l'atmosphère détecte d'elle-même via le changement de blocs.
 */
public final class ShipShieldSystem {
    public enum State {
        OFFLINE,
        CHARGING,
        ONLINE,
        DEPLETED,
        OVERLOADED
    }

    /**
     * @param chargeEfficiency     fraction de l'énergie électrique stockée dans le champ (le reste chauffe)
     * @param absorptionEfficiency joules cinétiques absorbés par joule de charge
     * @param heatPerAbsorbedJoule fraction de l'énergie absorbée convertie en chaleur dans les émetteurs
     * @param resumeHeatFraction   hystérésis : le champ se relance sous overloadHeatJ × cette fraction
     */
    public record Config(double capacityJ, double rechargePowerW, double chargeEfficiency, double absorptionEfficiency,
                         double heatPerAbsorbedJoule, double overloadHeatJ, double resumeHeatFraction,
                         double radiatorPowerW, double rechargeDelaySeconds, double collapseBleedPowerW) {
        public static final Config CORVETTE = new Config(25.0e6, 2.5e6, 0.85, 0.9, 0.12, 6.0e6, 0.4, 0.8e6, 3.0, 5.0e6);

        public Config {
            if (capacityJ <= 0.0 || overloadHeatJ <= 0.0 || chargeEfficiency <= 0.0 || chargeEfficiency > 1.0
                    || absorptionEfficiency <= 0.0 || absorptionEfficiency > 1.0) {
                throw new IllegalArgumentException("Invalid shield configuration");
            }
            resumeHeatFraction = Math.clamp(resumeHeatFraction, 0.0, 1.0);
        }
    }

    public record HullDamage(BlockPos impactBlock, Vec3 impactPoint, double penetratingEnergyJ,
                             List<BlockPos> destroyedBlocks, double unspentEnergyJ) {
    }

    public interface Listener {
        default void onStateChanged(State previous, State current) {
        }

        default void onImpactAbsorbed(double absorbedJ, Vec3 impactPoint) {
        }

        default void onHullDamage(HullDamage damage) {
        }
    }

    // Énergie de rupture d'un bloc : (2·dureté + résistance aux explosions + 1) × échelle.
    private static final double TOUGHNESS_SCALE_J = 25_000.0;
    // Atténuation radiale du budget d'énergie : l'onde de choc se répartit sur une surface croissante.
    private static final double DISTANCE_ATTENUATION = 0.15;
    private static final int MAX_DAMAGE_RADIUS = 8;
    private static final double MIN_DAMAGE_ENERGY_J = 1_000.0;

    private final Config config;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private double charge;
    private double heat;
    private double rechargeCooldown;
    private boolean enabled = true;
    private State state = State.CHARGING;

    public ShipShieldSystem(Config config) {
        this.config = config;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void tick(double dt, OdysseaShipPhysics.ResourceProvider resources) {
        heat = Math.max(0.0, heat - config.radiatorPowerW() * dt);
        if (!enabled) {
            charge = Math.max(0.0, charge - config.collapseBleedPowerW() * dt);
            transition(State.OFFLINE);
            return;
        }
        if (state == State.OVERLOADED) {
            // Champ effondré : la charge restante est évacuée et le bouclier n'absorbe rien jusqu'au refroidissement.
            charge = Math.max(0.0, charge - config.collapseBleedPowerW() * dt);
            if (heat <= config.overloadHeatJ() * config.resumeHeatFraction()) {
                rechargeCooldown = config.rechargeDelaySeconds();
                transition(State.CHARGING);
            }
            return;
        }
        if (rechargeCooldown > 0.0) {
            rechargeCooldown = Math.max(0.0, rechargeCooldown - dt);
            if (rechargeCooldown > 0.0) {
                return;
            }
        }
        double missing = config.capacityJ() - charge;
        if (missing > 0.0) {
            double request = Math.min(config.rechargePowerW() * dt, missing / config.chargeEfficiency());
            double drawn = Math.max(0.0, resources.drawEnergy(request));
            charge = Math.min(config.capacityJ(), charge + drawn * config.chargeEfficiency());
            heat += drawn * (1.0 - config.chargeEfficiency());
            if (heat >= config.overloadHeatJ()) {
                transition(State.OVERLOADED);
                return;
            }
        }
        if (charge >= config.capacityJ() * 0.999) {
            transition(State.ONLINE);
        } else if (charge > 0.0) {
            transition(State.CHARGING);
        }
    }

    /**
     * Traite un impact d'énergie {@code energyJ} (typiquement {@link OdysseaShipPhysics.ImpactResult#dissipatedEnergyJ()}).
     *
     * @param shipBlocks vue des blocs du vaisseau, dans le repère où {@code impactPoint} est exprimé (shipyard)
     * @return les dégâts de coque si le bouclier n'a pas tout absorbé
     */
    public Optional<HullDamage> handleImpact(BlockGetter shipBlocks, Vec3 impactPoint, double energyJ) {
        if (!(energyJ > 0.0)) {
            return Optional.empty();
        }
        double penetrating = energyJ;
        if (canAbsorb()) {
            double cost = energyJ / config.absorptionEfficiency();
            double spent = Math.min(charge, cost);
            double absorbed = spent * config.absorptionEfficiency();
            charge -= spent;
            heat += absorbed * config.heatPerAbsorbedJoule();
            penetrating = energyJ - absorbed;
            for (Listener listener : listeners) {
                listener.onImpactAbsorbed(absorbed, impactPoint);
            }
            if (heat >= config.overloadHeatJ()) {
                transition(State.OVERLOADED);
            } else if (charge <= 1.0e-6) {
                charge = 0.0;
                rechargeCooldown = config.rechargeDelaySeconds();
                transition(State.DEPLETED);
            }
        }
        if (penetrating < MIN_DAMAGE_ENERGY_J) {
            return Optional.empty();
        }
        HullDamage damage = computeHullDamage(shipBlocks, impactPoint, penetrating);
        for (Listener listener : listeners) {
            listener.onHullDamage(damage);
        }
        return Optional.of(damage);
    }

    /**
     * Sélection des blocs détruits : candidats dans une sphère de rayon ∛(E/E_ref) triés par distance au point
     * d'impact ; chaque bloc coûte sa ténacité majorée par la distance, jusqu'à épuisement du budget.
     */
    public static HullDamage computeHullDamage(BlockGetter level, Vec3 impactPoint, double energyJ) {
        BlockPos center = BlockPos.containing(impactPoint);
        int radius = (int) Math.ceil(Mth.clamp(Math.cbrt(energyJ / TOUGHNESS_SCALE_J), 1.0, MAX_DAMAGE_RADIUS));
        double radiusSq = (radius + 0.5) * (radius + 0.5);
        ObjectArrayList<BlockPos> candidates = new ObjectArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            if (pos.distToCenterSqr(impactPoint) > radiusSq) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0f) {
                continue;
            }
            candidates.add(pos.immutable());
        }
        candidates.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(impactPoint)));

        double budget = energyJ;
        List<BlockPos> destroyed = new ArrayList<>();
        for (BlockPos pos : candidates) {
            BlockState state = level.getBlockState(pos);
            double cost = toughness(level, pos, state) * (1.0 + DISTANCE_ATTENUATION * pos.distToCenterSqr(impactPoint));
            if (cost > budget) {
                continue;
            }
            budget -= cost;
            destroyed.add(pos);
        }
        return new HullDamage(center, impactPoint, energyJ, List.copyOf(destroyed), budget);
    }

    public static void applyHullDamage(ServerLevel level, HullDamage damage, boolean dropItems) {
        for (BlockPos pos : damage.destroyedBlocks()) {
            level.destroyBlock(pos, dropItems);
        }
        if (!damage.destroyedBlocks().isEmpty()) {
            Vec3 point = damage.impactPoint();
            float volume = (float) Mth.clamp(damage.penetratingEnergyJ() / 2.0e6, 0.6, 4.0);
            level.playSound(null, point.x, point.y, point.z, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, volume, 0.5f);
        }
    }

    private static double toughness(BlockGetter level, BlockPos pos, BlockState state) {
        double hardness = Math.max(0.0f, state.getDestroySpeed(level, pos));
        double resistance = state.getBlock().getExplosionResistance();
        return (hardness * 2.0 + resistance + 1.0) * TOUGHNESS_SCALE_J;
    }

    private boolean canAbsorb() {
        return enabled && charge > 0.0 && (state == State.ONLINE || state == State.CHARGING);
    }

    private void transition(State next) {
        if (state == next) {
            return;
        }
        State previous = state;
        state = next;
        for (Listener listener : listeners) {
            listener.onStateChanged(previous, next);
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public State state() {
        return state;
    }

    public double chargeJ() {
        return charge;
    }

    public double chargeFraction() {
        return charge / config.capacityJ();
    }

    public double heatFraction() {
        return heat / config.overloadHeatJ();
    }

    public Config config() {
        return config;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("charge", charge);
        tag.putDouble("heat", heat);
        tag.putDouble("cooldown", rechargeCooldown);
        tag.putBoolean("enabled", enabled);
        tag.putString("state", state.name());
        return tag;
    }

    public void load(CompoundTag tag) {
        charge = Math.clamp(tag.getDouble("charge"), 0.0, config.capacityJ());
        heat = Math.max(0.0, tag.getDouble("heat"));
        rechargeCooldown = Math.max(0.0, tag.getDouble("cooldown"));
        enabled = !tag.contains("enabled") || tag.getBoolean("enabled");
        try {
            state = State.valueOf(tag.getString("state"));
        } catch (IllegalArgumentException e) {
            state = State.CHARGING;
        }
    }
}
