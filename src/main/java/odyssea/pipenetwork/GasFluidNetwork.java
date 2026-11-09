package odyssea.pipenetwork;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * Réseau de canalisations et de câbles d'un niveau, représenté comme un graphe de composantes connexes.
 *
 * <ul>
 *   <li>Les tuyaux ne servent qu'à la connectivité : ils ne stockent rien et ne sont jamais itérés pendant un tick.</li>
 *   <li>Fusion à la pose (petite composante absorbée dans la grande), scission à la casse par BFS : le travail
 *       en O(taille) n'a lieu qu'à la mutation du graphe.</li>
 *   <li>Chaque tick, chaque composante est résolue en O(endpoints) : producteurs → consommateurs par priorité,
 *       surplus vers les tampons, déficit couvert par les tampons, le tout plafonné par le palier le plus faible.</li>
 * </ul>
 * Toutes les méthodes s'appellent sur le thread serveur ; les callbacks {@link Endpoint#transfer} aussi.
 */
public final class GasFluidNetwork extends SavedData {
    private static final String DATA_NAME = "odyssea_pipe_network";
    private static final double TICK_SECONDS = 0.05;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final SavedData.Factory<GasFluidNetwork> FACTORY =
            new SavedData.Factory<>(GasFluidNetwork::new, GasFluidNetwork::load, null);

    /** Unité : kg pour les fluides et gaz, J pour l'énergie. */
    public enum Medium {
        OXYGEN,
        HYDROGEN,
        CHEMICAL_FUEL,
        PLASMA,
        XENON,
        WATER,
        ENERGY;

        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static @Nullable Medium byName(String name) {
            for (Medium medium : values()) {
                if (medium.serializedName().equals(name)) {
                    return medium;
                }
            }
            return null;
        }
    }

    /** Débit maximal par seconde : kg/s pour la matière, W pour l'énergie. */
    public enum PipeTier {
        BASIC(5.0, 2.0e5),
        REINFORCED(25.0, 2.0e6),
        CRYOGENIC(120.0, 2.0e7),
        SUPERCONDUCTING(600.0, 2.0e8);

        private final double massRate;
        private final double powerRate;

        PipeTier(double massRate, double powerRate) {
            this.massRate = massRate;
            this.powerRate = powerRate;
        }

        public double ratePerSecond(Medium medium) {
            return medium == Medium.ENERGY ? powerRate : massRate;
        }

        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static @Nullable PipeTier byName(String name) {
            for (PipeTier tier : values()) {
                if (tier.serializedName().equals(name)) {
                    return tier;
                }
            }
            return null;
        }
    }

    public enum Role {
        PRODUCER,
        CONSUMER,
        BUFFER
    }

    /**
     * Appareil branché au réseau (panneau solaire, générateur plasma, réservoir, propulseur, diffuseur…).
     * Un endpoint se connecte à toute composante de même fluide adjacente à sa position. Un réservoir touchant
     * deux lignes distinctes est résolu successivement dans chacune et sert ainsi de pont.
     */
    public interface Endpoint {
        BlockPos position();

        Medium medium();

        Role role();

        /** Plus élevé = servi en premier (support vie avant boucliers avant propulsion). */
        default int priority() {
            return 0;
        }

        /** PRODUCER, BUFFER : quantité extractible pendant ce tick. */
        double available(double dt);

        /** CONSUMER, BUFFER : quantité acceptable pendant ce tick. */
        double demand(double dt);

        /** Appelé une fois par composante et par tick avec les quantités effectivement retirées et livrées. */
        void transfer(double extracted, double inserted);
    }

    public record ComponentStats(int componentId, Medium medium, int pipeCount, int endpointCount,
                                 double capacityPerTick, double supplied, double demanded, double delivered,
                                 double bufferedIn, double bufferedOut) {
        public double satisfaction() {
            return demanded <= 0.0 ? 1.0 : delivered / demanded;
        }
    }

    private record Pipe(Medium medium, PipeTier tier) {
    }

    private static final class Component {
        final int id;
        final Medium medium;
        final LongOpenHashSet pipes = new LongOpenHashSet();
        final int[] tierCounts = new int[PipeTier.values().length];
        final ObjectArrayList<Endpoint> producers = new ObjectArrayList<>();
        final ObjectArrayList<Endpoint> consumers = new ObjectArrayList<>();
        final ObjectArrayList<Endpoint> buffers = new ObjectArrayList<>();
        boolean endpointsDirty = true;
        boolean removed;
        @Nullable ComponentStats stats;

        Component(int id, Medium medium) {
            this.id = id;
            this.medium = medium;
        }

        void addPipe(long pos, PipeTier tier) {
            if (pipes.add(pos)) {
                tierCounts[tier.ordinal()]++;
            }
        }

        void removePipe(long pos, PipeTier tier) {
            if (pipes.remove(pos)) {
                tierCounts[tier.ordinal()]--;
            }
        }

        /** Le palier le plus faible présent fixe le débit de toute la composante (goulot d'étranglement). */
        double ratePerSecond() {
            PipeTier[] tiers = PipeTier.values();
            for (int i = 0; i < tiers.length; i++) {
                if (tierCounts[i] > 0) {
                    return tiers[i].ratePerSecond(medium);
                }
            }
            return 0.0;
        }

        int endpointCount() {
            return producers.size() + consumers.size() + buffers.size();
        }
    }

    private final Long2ObjectOpenHashMap<Pipe> pipes = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Component> pipeComponents = new Long2ObjectOpenHashMap<>();
    private final ReferenceLinkedOpenHashSet<Component> components = new ReferenceLinkedOpenHashSet<>();
    private final Long2ObjectOpenHashMap<ObjectArrayList<Endpoint>> endpoints = new Long2ObjectOpenHashMap<>();
    private int nextComponentId = 1;
    private double[] scratchOffer = new double[16];
    private double[] scratchRequest = new double[16];
    private double[] scratchGive = new double[16];
    private double[] scratchBufferOut = new double[16];
    private double[] scratchBufferIn = new double[16];

    private GasFluidNetwork() {
    }

    public static GasFluidNetwork get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    // ───────────────────────────── Mutations du graphe ─────────────────────────────

    public void addPipe(BlockPos pos, Medium medium, PipeTier tier) {
        long key = pos.asLong();
        Pipe existing = pipes.get(key);
        if (existing != null) {
            if (existing.medium() == medium) {
                if (existing.tier() != tier) {
                    Component component = pipeComponents.get(key);
                    component.tierCounts[existing.tier().ordinal()]--;
                    component.tierCounts[tier.ordinal()]++;
                    pipes.put(key, new Pipe(medium, tier));
                    setDirty();
                }
                return;
            }
            removePipe(pos);
        }
        pipes.put(key, new Pipe(medium, tier));

        ReferenceArrayList<Component> neighbours = new ReferenceArrayList<>(6);
        for (Direction direction : DIRECTIONS) {
            long neighbour = BlockPos.offset(key, direction);
            Pipe pipe = pipes.get(neighbour);
            if (pipe == null || pipe.medium() != medium) {
                continue;
            }
            Component component = pipeComponents.get(neighbour);
            if (!neighbours.contains(component)) {
                neighbours.add(component);
            }
        }

        Component target;
        if (neighbours.isEmpty()) {
            target = new Component(nextComponentId++, medium);
            components.add(target);
        } else {
            target = neighbours.get(0);
            for (Component candidate : neighbours) {
                if (candidate.pipes.size() > target.pipes.size()) {
                    target = candidate;
                }
            }
            for (Component other : neighbours) {
                if (other != target) {
                    merge(other, target);
                }
            }
        }
        target.addPipe(key, tier);
        pipeComponents.put(key, target);
        target.endpointsDirty = true;
        setDirty();
    }

    public void removePipe(BlockPos pos) {
        long key = pos.asLong();
        Pipe pipe = pipes.remove(key);
        if (pipe == null) {
            return;
        }
        Component component = pipeComponents.remove(key);
        component.removePipe(key, pipe.tier());
        setDirty();
        if (component.pipes.isEmpty()) {
            discard(component);
            return;
        }
        component.endpointsDirty = true;

        LongArrayList seeds = new LongArrayList(6);
        for (Direction direction : DIRECTIONS) {
            long neighbour = BlockPos.offset(key, direction);
            if (pipeComponents.get(neighbour) == component) {
                seeds.add(neighbour);
            }
        }
        // Retirer une extrémité ne peut pas déconnecter le graphe.
        if (seeds.size() > 1) {
            split(component, seeds);
        }
    }

    public void attachEndpoint(Endpoint endpoint) {
        long key = endpoint.position().asLong();
        ObjectArrayList<Endpoint> list = endpoints.get(key);
        if (list == null) {
            list = new ObjectArrayList<>(2);
            endpoints.put(key, list);
        }
        if (!list.contains(endpoint)) {
            list.add(endpoint);
        }
        markAdjacentDirty(key, endpoint.medium());
    }

    public void detachEndpoint(Endpoint endpoint) {
        long key = endpoint.position().asLong();
        ObjectArrayList<Endpoint> list = endpoints.get(key);
        if (list == null || !list.remove(endpoint)) {
            return;
        }
        if (list.isEmpty()) {
            endpoints.remove(key);
        }
        markAdjacentDirty(key, endpoint.medium());
    }

    private void merge(Component from, Component into) {
        for (LongIterator it = from.pipes.iterator(); it.hasNext(); ) {
            long pos = it.nextLong();
            into.pipes.add(pos);
            pipeComponents.put(pos, into);
        }
        for (int i = 0; i < into.tierCounts.length; i++) {
            into.tierCounts[i] += from.tierCounts[i];
        }
        discard(from);
        into.endpointsDirty = true;
    }

    /**
     * BFS depuis chaque voisin du tuyau retiré. Si le premier parcours atteint tout, il n'y a pas de scission.
     * Sinon la plus grande région conserve la composante d'origine, ce qui minimise les réaffectations.
     */
    private void split(Component original, LongArrayList seeds) {
        LongOpenHashSet assigned = new LongOpenHashSet();
        List<LongOpenHashSet> regions = new ArrayList<>();
        for (int i = 0; i < seeds.size(); i++) {
            long seed = seeds.getLong(i);
            if (assigned.contains(seed)) {
                continue;
            }
            LongOpenHashSet region = collectRegion(original, seed);
            if (regions.isEmpty() && region.size() == original.pipes.size()) {
                return;
            }
            assigned.addAll(region);
            regions.add(region);
        }
        int largest = 0;
        for (int i = 1; i < regions.size(); i++) {
            if (regions.get(i).size() > regions.get(largest).size()) {
                largest = i;
            }
        }
        for (int i = 0; i < regions.size(); i++) {
            if (i == largest) {
                continue;
            }
            Component fresh = new Component(nextComponentId++, original.medium);
            for (LongIterator it = regions.get(i).iterator(); it.hasNext(); ) {
                long pos = it.nextLong();
                PipeTier tier = pipes.get(pos).tier();
                original.removePipe(pos, tier);
                fresh.addPipe(pos, tier);
                pipeComponents.put(pos, fresh);
            }
            components.add(fresh);
        }
        original.endpointsDirty = true;
    }

    private LongOpenHashSet collectRegion(Component component, long seed) {
        LongOpenHashSet region = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        region.add(seed);
        queue.enqueue(seed);
        while (!queue.isEmpty()) {
            long current = queue.dequeueLong();
            for (Direction direction : DIRECTIONS) {
                long neighbour = BlockPos.offset(current, direction);
                if (pipeComponents.get(neighbour) == component && region.add(neighbour)) {
                    queue.enqueue(neighbour);
                }
            }
        }
        return region;
    }

    private void discard(Component component) {
        component.removed = true;
        components.remove(component);
    }

    private void markAdjacentDirty(long key, Medium medium) {
        for (Direction direction : DIRECTIONS) {
            Component component = pipeComponents.get(BlockPos.offset(key, direction));
            if (component != null && component.medium == medium) {
                component.endpointsDirty = true;
            }
        }
    }

    /** Reconstruction des listes d'endpoints : O(tuyaux), uniquement après une mutation. */
    private void rebuildEndpoints(Component component) {
        component.endpointsDirty = false;
        component.producers.clear();
        component.consumers.clear();
        component.buffers.clear();
        ReferenceOpenHashSet<Endpoint> found = new ReferenceOpenHashSet<>();
        for (LongIterator it = component.pipes.iterator(); it.hasNext(); ) {
            long pipe = it.nextLong();
            for (Direction direction : DIRECTIONS) {
                ObjectArrayList<Endpoint> list = endpoints.get(BlockPos.offset(pipe, direction));
                if (list == null) {
                    continue;
                }
                for (Endpoint endpoint : list) {
                    if (endpoint.medium() == component.medium && found.add(endpoint)) {
                        switch (endpoint.role()) {
                            case PRODUCER -> component.producers.add(endpoint);
                            case CONSUMER -> component.consumers.add(endpoint);
                            case BUFFER -> component.buffers.add(endpoint);
                        }
                    }
                }
            }
        }
        component.consumers.sort(Comparator.comparingInt(Endpoint::priority).reversed());
    }

    // ───────────────────────────── Résolution des flux ─────────────────────────────

    public void tick(ServerLevel level) {
        if (components.isEmpty()) {
            return;
        }
        // Copie : un callback d'endpoint peut casser un bloc et donc muter le graphe.
        Component[] snapshot = components.toArray(new Component[0]);
        for (Component component : snapshot) {
            if (component.removed) {
                continue;
            }
            if (component.endpointsDirty) {
                rebuildEndpoints(component);
            }
            solve(component, TICK_SECONDS);
        }
    }

    private void solve(Component component, double dt) {
        int producerCount = component.producers.size();
        int consumerCount = component.consumers.size();
        int bufferCount = component.buffers.size();
        if (producerCount + bufferCount == 0 || consumerCount + bufferCount == 0) {
            component.stats = new ComponentStats(component.id, component.medium, component.pipes.size(),
                    component.endpointCount(), component.ratePerSecond() * dt, 0.0, 0.0, 0.0, 0.0, 0.0);
            return;
        }
        ensureScratch(Math.max(producerCount, Math.max(consumerCount, bufferCount)));
        double capacity = component.ratePerSecond() * dt;

        double supply = 0.0;
        for (int i = 0; i < producerCount; i++) {
            scratchOffer[i] = sanitize(component.producers.get(i).available(dt));
            supply += scratchOffer[i];
        }
        double demand = 0.0;
        for (int i = 0; i < consumerCount; i++) {
            scratchRequest[i] = sanitize(component.consumers.get(i).demand(dt));
            demand += scratchRequest[i];
        }
        double bufferAvailable = 0.0;
        double bufferRoom = 0.0;
        for (int i = 0; i < bufferCount; i++) {
            Endpoint buffer = component.buffers.get(i);
            scratchBufferOut[i] = sanitize(buffer.available(dt));
            scratchBufferIn[i] = sanitize(buffer.demand(dt));
            bufferAvailable += scratchBufferOut[i];
            bufferRoom += scratchBufferIn[i];
        }

        // Bilan plafonné par le débit de la composante : direct, puis tampons en appoint ou en stockage, jamais les deux.
        double direct = Math.min(Math.min(supply, demand), capacity);
        double fromBuffers = Math.max(0.0, Math.min(Math.min(demand - direct, capacity - direct), bufferAvailable));
        double toBuffers = Math.max(0.0, Math.min(Math.min(supply - direct, capacity - direct - fromBuffers), bufferRoom));
        double delivered = direct + fromBuffers;

        distributeByPriority(component.consumers, consumerCount, delivered);

        double producedFraction = supply > 0.0 ? (direct + toBuffers) / supply : 0.0;
        double bufferOutFraction = bufferAvailable > 0.0 ? fromBuffers / bufferAvailable : 0.0;
        double bufferInFraction = bufferRoom > 0.0 ? toBuffers / bufferRoom : 0.0;
        for (int i = 0; i < producerCount; i++) {
            double extracted = scratchOffer[i] * producedFraction;
            if (extracted > 0.0) {
                component.producers.get(i).transfer(extracted, 0.0);
            }
        }
        for (int i = 0; i < consumerCount; i++) {
            if (scratchGive[i] > 0.0) {
                component.consumers.get(i).transfer(0.0, scratchGive[i]);
            }
        }
        for (int i = 0; i < bufferCount; i++) {
            double out = scratchBufferOut[i] * bufferOutFraction;
            double in = scratchBufferIn[i] * bufferInFraction;
            if (out > 0.0 || in > 0.0) {
                component.buffers.get(i).transfer(out, in);
            }
        }
        component.stats = new ComponentStats(component.id, component.medium, component.pipes.size(),
                component.endpointCount(), capacity, supply, demand, delivered, toBuffers, fromBuffers);
    }

    /**
     * Remplissage par paliers de priorité décroissante : un palier n'est servi qu'une fois le précédent satisfait ;
     * à l'intérieur d'un palier insuffisamment servi, partage proportionnel à la demande.
     */
    private void distributeByPriority(ObjectArrayList<Endpoint> consumers, int count, double amount) {
        double remaining = amount;
        int start = 0;
        while (start < count) {
            int priority = consumers.get(start).priority();
            int end = start;
            double tierDemand = 0.0;
            while (end < count && consumers.get(end).priority() == priority) {
                tierDemand += scratchRequest[end];
                end++;
            }
            double fraction = tierDemand <= 0.0 ? 0.0 : Math.min(1.0, remaining / tierDemand);
            for (int i = start; i < end; i++) {
                scratchGive[i] = scratchRequest[i] * fraction;
            }
            remaining = Math.max(0.0, remaining - tierDemand * fraction);
            start = end;
        }
    }

    private void ensureScratch(int size) {
        if (scratchOffer.length >= size) {
            return;
        }
        int capacity = Math.max(size, scratchOffer.length * 2);
        scratchOffer = new double[capacity];
        scratchRequest = new double[capacity];
        scratchGive = new double[capacity];
        scratchBufferOut = new double[capacity];
        scratchBufferIn = new double[capacity];
    }

    private static double sanitize(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }

    // ───────────────────────────── Requêtes ─────────────────────────────

    public Optional<ComponentStats> statsAt(BlockPos pipePos) {
        Component component = pipeComponents.get(pipePos.asLong());
        return component == null ? Optional.empty() : Optional.ofNullable(component.stats);
    }

    public boolean isPipe(BlockPos pos) {
        return pipes.containsKey(pos.asLong());
    }

    public int componentCount() {
        return components.size();
    }

    // ───────────────────────────── Persistance ─────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        // Positions regroupées par (fluide, palier) en LongArrayTag : ~8 octets par tuyau.
        EnumMap<Medium, EnumMap<PipeTier, LongArrayList>> grouped = new EnumMap<>(Medium.class);
        for (Long2ObjectMap.Entry<Pipe> entry : pipes.long2ObjectEntrySet()) {
            Pipe pipe = entry.getValue();
            grouped.computeIfAbsent(pipe.medium(), medium -> new EnumMap<>(PipeTier.class))
                    .computeIfAbsent(pipe.tier(), tier -> new LongArrayList())
                    .add(entry.getLongKey());
        }
        ListTag list = new ListTag();
        grouped.forEach((medium, tiers) -> tiers.forEach((tier, positions) -> {
            CompoundTag group = new CompoundTag();
            group.putString("medium", medium.serializedName());
            group.putString("tier", tier.serializedName());
            group.putLongArray("positions", positions.toLongArray());
            list.add(group);
        }));
        tag.put("pipes", list);
        return tag;
    }

    private static GasFluidNetwork load(CompoundTag tag, HolderLookup.Provider registries) {
        GasFluidNetwork network = new GasFluidNetwork();
        ListTag list = tag.getList("pipes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag group = list.getCompound(i);
            Medium medium = Medium.byName(group.getString("medium"));
            PipeTier tier = PipeTier.byName(group.getString("tier"));
            if (medium == null || tier == null) {
                continue;
            }
            for (long pos : group.getLongArray("positions")) {
                network.addPipe(BlockPos.of(pos), medium, tier);
            }
        }
        network.setDirty(false);
        return network;
    }
}
