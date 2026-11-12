package odyssea.atmosphere;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import odyssea.OdysseaCore;
import odyssea.dimension.PlanetaryAtmosphere;
import odyssea.dimension.PlanetaryAtmospheres;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Gestionnaire d'atmosphère d'un {@link ServerLevel} : diffuseurs, pièces scellées, brèches et physiologie.
 *
 * <p>Contrat d'intégration des block entities de diffuseur : {@link #addDiffuser} au placement et à chaque
 * {@code onLoad}, {@link #removeDiffuser} uniquement à la destruction du bloc ; {@link #airDemandKg} et
 * {@link #injectAir} depuis l'endpoint réseau du diffuseur. Toutes les méthodes publiques s'appellent
 * sur le thread serveur.
 */
public final class AtmosphereManager extends SavedData {
    public static final double AIR_GAS_CONSTANT = 287.05;
    public static final double CABIN_TEMPERATURE_K = 293.15;
    public static final double AIR_OXYGEN_FRACTION = 0.2095;
    public static final double TARGET_PRESSURE_PA = 101_325.0;
    public static final double HYPOXIA_THRESHOLD_PPO2_PA = 16_000.0;
    // Limite d'Armstrong : en dessous, l'eau des tissus bout à 37 °C (ébullisme).
    public static final double ARMSTRONG_LIMIT_PA = 6_300.0;
    public static final double DIFFUSER_MAX_FLOW_KG_PER_S = 1.5;

    public static final TagKey<Item> PRESSURE_SUIT = TagKey.create(Registries.ITEM, OdysseaCore.id("pressure_suit"));
    public static final TagKey<EntityType<?>> VACUUM_IMMUNE = TagKey.create(Registries.ENTITY_TYPE, OdysseaCore.id("vacuum_immune"));
    public static final ResourceKey<DamageType> HYPOXIA_DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, OdysseaCore.id("hypoxia"));
    public static final String VISOR_OPEN_KEY = "odyssea_visor_open";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_NAME = "odyssea_atmosphere";
    private static final double TICK_SECONDS = 0.05;
    private static final int REFLOOD_DEBOUNCE_TICKS = 10;
    private static final int UNKNOWN_TERRAIN_RETRY_TICKS = 40;
    private static final int MAX_SNAPSHOTS_PER_TICK = 4;
    private static final int LOCAL_PROBE_LIMIT = 2048;
    private static final int ORGANISM_INTERVAL_TICKS = 10;
    private static final int BREACH_EFFECT_INTERVAL_TICKS = 20;
    private static final int HYPOXIA_CLEANUP_INTERVAL_TICKS = 1200;
    private static final double ORPHAN_EQUILIBRIUM_TOLERANCE_PA = 50.0;
    private static final double HYPOXIA_RATE_PER_SECOND = 0.04;
    private static final double VACUUM_HYPOXIA_MULTIPLIER = 2.5;
    private static final double RECOVERY_RATE_PER_SECOND = 0.08;
    private static final double SUCTION_COEFFICIENT = 0.002;
    private static final double MAX_SUCTION_PER_TICK = 0.3;
    private static final int MAX_SUCTION_SOURCES = 64;
    private static final int GRID_SHIFT = 6;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final SavedData.Factory<AtmosphereManager> FACTORY =
            new SavedData.Factory<>(AtmosphereManager::new, AtmosphereManager::load, null);

    private static final Object EXECUTOR_LOCK = new Object();
    private static ExecutorService executor;

    private final LongLinkedOpenHashSet diffusers = new LongLinkedOpenHashSet();
    private final Long2ObjectOpenHashMap<LongArrayList> diffuserGrid = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap invalidatedAt = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap dirtySince = new Long2LongOpenHashMap();
    private final LongOpenHashSet inFlight = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<FloodFill.Outcome> outcomes = new Long2ObjectOpenHashMap<>();
    private final Long2DoubleOpenHashMap restoredMass = new Long2DoubleOpenHashMap();
    private final Int2ObjectOpenHashMap<SealedRoom> rooms = new Int2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<SealedRoom> diffuserRooms = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<ObjectArrayList<SealedRoom>> sectionIndex = new Long2ObjectOpenHashMap<>();
    // Seul point de contact entre workers et thread serveur.
    private final ConcurrentLinkedQueue<FloodFill.Result> completed = new ConcurrentLinkedQueue<>();
    private final Object2FloatOpenHashMap<UUID> hypoxia = new Object2FloatOpenHashMap<>();
    private long globalGeneration;
    private long tickCounter;
    private int nextRoomId = 1;

    private AtmosphereManager() {
    }

    public static AtmosphereManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public static void onBlockStateChange(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (oldState != newState) {
            get(level).handleBlockChange(level, pos);
        }
    }

    public static void shutdownWorkers() {
        synchronized (EXECUTOR_LOCK) {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }

    // ───────────────────────────── API publique ─────────────────────────────

    public void addDiffuser(BlockPos pos) {
        long key = pos.asLong();
        if (diffusers.add(key)) {
            gridAdd(key);
            setDirty();
        }
        invalidate(key);
        dirtySince.put(key, tickCounter - REFLOOD_DEBOUNCE_TICKS);
    }

    public void removeDiffuser(BlockPos pos) {
        long key = pos.asLong();
        if (!diffusers.remove(key)) {
            return;
        }
        gridRemove(key);
        invalidatedAt.remove(key);
        dirtySince.remove(key);
        inFlight.remove(key);
        outcomes.remove(key);
        restoredMass.remove(key);
        SealedRoom room = diffuserRooms.remove(key);
        if (room != null) {
            // L'air ne disparaît pas avec le diffuseur : la pièce devient orpheline et continue de fuir.
            room.removeDiffuser(key);
        }
        setDirty();
    }

    public @Nullable SealedRoom roomAt(BlockPos pos) {
        return roomAt(pos.asLong());
    }

    public @Nullable SealedRoom roomOf(BlockPos diffuserPos) {
        return diffuserRooms.get(diffuserPos.asLong());
    }

    public @Nullable FloodFill.Outcome lastOutcome(BlockPos diffuserPos) {
        return outcomes.get(diffuserPos.asLong());
    }

    public double pressureAt(ServerLevel level, BlockPos pos) {
        SealedRoom room = roomAt(pos.asLong());
        return room != null ? room.pressurePa() : PlanetaryAtmospheres.get(level.dimension()).pressureAt(pos.getY() + 0.5);
    }

    public boolean isBreathable(ServerLevel level, BlockPos pos) {
        SealedRoom room = roomAt(pos.asLong());
        double partialO2 = room != null
                ? room.oxygenPartialPressurePa()
                : PlanetaryAtmospheres.get(level.dimension()).oxygenPartialPressureAt(pos.getY() + 0.5);
        return partialO2 >= HYPOXIA_THRESHOLD_PPO2_PA;
    }

    public double airDemandKg(BlockPos diffuserPos, double dtSeconds) {
        SealedRoom room = diffuserRooms.get(diffuserPos.asLong());
        if (room == null) {
            return 0.0;
        }
        double deficit = room.massForPressure(TARGET_PRESSURE_PA) - room.gasMassKg();
        if (deficit <= 0.0) {
            return 0.0;
        }
        return Math.min(deficit / Math.max(1, room.diffuserCount()), DIFFUSER_MAX_FLOW_KG_PER_S * dtSeconds);
    }

    public double injectAir(BlockPos diffuserPos, double kg) {
        SealedRoom room = diffuserRooms.get(diffuserPos.asLong());
        if (room == null || kg <= 0.0) {
            return 0.0;
        }
        double capacity = room.massForPressure(TARGET_PRESSURE_PA) - room.gasMassKg();
        if (capacity <= 0.0) {
            return 0.0;
        }
        return room.addGas(Math.min(kg, capacity));
    }

    // ───────────────────────────── Tick ─────────────────────────────

    public void tick(ServerLevel level) {
        tickCounter++;
        PlanetaryAtmosphere ambient = PlanetaryAtmospheres.get(level.dimension());
        applyCompletedFloods(level, ambient);
        scheduleFloods(level);
        if (!rooms.isEmpty()) {
            tickRooms(level, ambient);
            setDirty();
        }
        if (tickCounter % ORGANISM_INTERVAL_TICKS == 0) {
            tickOrganisms(level, ambient);
        }
        if (tickCounter % HYPOXIA_CLEANUP_INTERVAL_TICKS == 0) {
            hypoxia.keySet().removeIf(id -> level.getEntity(id) == null);
        }
    }

    private void applyCompletedFloods(ServerLevel level, PlanetaryAtmosphere ambient) {
        FloodFill.Result result;
        while ((result = completed.poll()) != null) {
            long diffuser = result.origin();
            inFlight.remove(diffuser);
            if (!diffusers.contains(diffuser)) {
                continue;
            }
            // Le monde a changé dans la boîte pendant le calcul : résultat périmé.
            if (invalidatedAt.get(diffuser) > result.generation()) {
                markDirty(diffuser);
                continue;
            }
            outcomes.put(diffuser, result.outcome());
            if (result.sealed()) {
                installRoom(result, ambient);
            } else {
                handleUnsealed(level, diffuser, result);
            }
        }
    }

    private void scheduleFloods(ServerLevel level) {
        if (dirtySince.isEmpty()) {
            return;
        }
        int started = 0;
        ObjectIterator<Long2LongMap.Entry> it = dirtySince.long2LongEntrySet().fastIterator();
        while (it.hasNext() && started < MAX_SNAPSHOTS_PER_TICK) {
            Long2LongMap.Entry entry = it.next();
            long diffuser = entry.getLongKey();
            if (inFlight.contains(diffuser) || tickCounter - entry.getLongValue() < REFLOOD_DEBOUNCE_TICKS) {
                continue;
            }
            BlockPos origin = BlockPos.of(diffuser);
            if (!level.isLoaded(origin)) {
                it.remove();
                continue;
            }
            long generation = globalGeneration;
            RegionSnapshot snapshot = FloodFill.captureFor(level, origin);
            try {
                CompletableFuture.supplyAsync(() -> FloodFill.run(snapshot, diffuser, generation), executor())
                        .exceptionally(error -> {
                            LOGGER.error("Atmosphere flood fill failed at {}", origin, error);
                            return FloodFill.Result.failure(diffuser, generation, FloodFill.Outcome.FAILED);
                        })
                        .thenAccept(completed::add);
            } catch (RejectedExecutionException e) {
                return;
            }
            it.remove();
            inFlight.add(diffuser);
            started++;
        }
    }

    // ───────────────────────────── Changements de blocs ─────────────────────────────

    private void handleBlockChange(ServerLevel level, BlockPos pos) {
        if (sectionIndex.isEmpty() && diffuserGrid.isEmpty()) {
            return;
        }
        long key = pos.asLong();
        if (!sectionIndex.isEmpty()) {
            refreshAround(level, key);
        }
        if (!diffuserGrid.isEmpty()) {
            invalidateNearbyDiffusers(pos, key);
        }
    }

    /** La cellule changée et ses six voisines : une porte intérieure qui s'ouvre modifie la frontière voisine. */
    private void refreshAround(ServerLevel level, long key) {
        for (int i = -1; i < DIRECTIONS.length; i++) {
            long cell = i < 0 ? key : BlockPos.offset(key, DIRECTIONS[i]);
            ObjectArrayList<SealedRoom> candidates = sectionIndex.get(SectionPos.blockToSection(cell));
            if (candidates == null) {
                continue;
            }
            for (SealedRoom room : candidates) {
                if (room.isBoundary(cell)) {
                    refreshBoundaryCell(level, room, cell, false);
                }
            }
        }
    }

    private void invalidateNearbyDiffusers(BlockPos pos, long key) {
        int reach = FloodFill.RADIUS + 1;
        int gx0 = (pos.getX() - reach) >> GRID_SHIFT;
        int gy0 = (pos.getY() - reach) >> GRID_SHIFT;
        int gz0 = (pos.getZ() - reach) >> GRID_SHIFT;
        int gx1 = (pos.getX() + reach) >> GRID_SHIFT;
        int gy1 = (pos.getY() + reach) >> GRID_SHIFT;
        int gz1 = (pos.getZ() + reach) >> GRID_SHIFT;
        for (int gx = gx0; gx <= gx1; gx++) {
            for (int gy = gy0; gy <= gy1; gy++) {
                for (int gz = gz0; gz <= gz1; gz++) {
                    LongArrayList cell = diffuserGrid.get(BlockPos.asLong(gx, gy, gz));
                    if (cell == null) {
                        continue;
                    }
                    for (int i = 0; i < cell.size(); i++) {
                        long diffuser = cell.getLong(i);
                        if (!insideSafetyBox(diffuser, pos)) {
                            continue;
                        }
                        SealedRoom room = diffuserRooms.get(diffuser);
                        // Une pièce scellée ne dépend que de son intérieur et de sa frontière ; un calcul
                        // en vol ou une pièce non scellée dépend de toute la boîte.
                        boolean relevant = inFlight.contains(diffuser) || room == null || !room.isSealed()
                                || room.contains(key) || room.isBoundary(key);
                        if (relevant) {
                            invalidate(diffuser);
                            markDirty(diffuser);
                        }
                    }
                }
            }
        }
    }

    private void refreshBoundaryCell(ServerLevel level, SealedRoom room, long cell, boolean forceBreach) {
        BlockPos cellPos = BlockPos.of(cell);
        if (!level.isLoaded(cellPos)) {
            return;
        }
        int cellMask = Airtightness.faceMask(level, cellPos, level.getBlockState(cellPos));
        int towardInterior = room.boundaryMask(cell);
        int open = 0;
        for (Direction direction : DIRECTIONS) {
            if ((towardInterior & Airtightness.bit(direction)) == 0) {
                continue;
            }
            BlockPos innerPos = cellPos.relative(direction);
            if (!level.isLoaded(innerPos)) {
                continue;
            }
            int innerMask = Airtightness.faceMask(level, innerPos, level.getBlockState(innerPos));
            if (Airtightness.canFlow(innerMask, cellMask, direction.getOpposite())) {
                open |= Airtightness.bit(direction);
            }
        }
        if (open == 0) {
            room.setBreachFaces(cell, 0);
            return;
        }
        if (forceBreach || room.isBreachCell(cell) || probeReachesExterior(level, room, cellPos)) {
            room.setBreachFaces(cell, open);
        } else {
            // Ouverture vers une poche fermée (niche, couloir scellé) : pas de fuite, re-flood pour fusionner.
            markRoomDirty(room);
        }
    }

    /**
     * Sonde BFS synchrone et bornée depuis une cellule frontière nouvellement ouverte, sans entrer dans la pièce.
     * Au-delà de {@value #LOCAL_PROBE_LIMIT} cellules, hors du monde ou dans un chunk non chargé : extérieur.
     */
    private boolean probeReachesExterior(ServerLevel level, SealedRoom room, BlockPos start) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        BlockPos.MutableBlockPos current = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        seen.add(start.asLong());
        queue.enqueue(start.asLong());
        while (!queue.isEmpty()) {
            current.set(queue.dequeueLong());
            int mask = Airtightness.faceMask(level, current, level.getBlockState(current));
            for (Direction direction : DIRECTIONS) {
                if ((mask & Airtightness.bit(direction)) != 0) {
                    continue;
                }
                next.setWithOffset(current, direction);
                long nextKey = next.asLong();
                if (seen.contains(nextKey) || room.contains(nextKey)) {
                    continue;
                }
                if (level.isOutsideBuildHeight(next) || !level.isLoaded(next)) {
                    return true;
                }
                int nextMask = Airtightness.faceMask(level, next, level.getBlockState(next));
                if (!Airtightness.canFlow(mask, nextMask, direction)) {
                    continue;
                }
                if (seen.size() >= LOCAL_PROBE_LIMIT) {
                    return true;
                }
                seen.add(nextKey);
                queue.enqueue(nextKey);
            }
        }
        return false;
    }

    // ───────────────────────────── Résultats de flood-fill ─────────────────────────────

    private void installRoom(FloodFill.Result result, PlanetaryAtmosphere ambient) {
        LongOpenHashSet interior = result.interior();
        LongArrayList members = collectMemberDiffusers(result);
        LongOpenHashSet memberSet = new LongOpenHashSet(members);

        ReferenceOpenHashSet<SealedRoom> candidates = new ReferenceOpenHashSet<>();
        for (LongIterator it = result.sections().iterator(); it.hasNext(); ) {
            ObjectArrayList<SealedRoom> list = sectionIndex.get(it.nextLong());
            if (list != null) {
                candidates.addAll(list);
            }
        }

        // Conservation de la masse : chaque pièce recouverte cède la fraction de son gaz située dans le nouveau volume.
        double mass = 0.0;
        int inheritedCells = 0;
        List<SealedRoom> merged = new ArrayList<>();
        for (SealedRoom previous : candidates) {
            int overlap = overlapCount(previous, interior, result.bounds());
            if (overlap == 0) {
                continue;
            }
            merged.add(previous);
            double share = (double) overlap / previous.volumeM3();
            mass += previous.gasMassKg() * share;
            inheritedCells += overlap;

            LongArrayList leftovers = new LongArrayList();
            for (LongIterator it = previous.diffusers().iterator(); it.hasNext(); ) {
                long diffuser = it.nextLong();
                if (!memberSet.contains(diffuser)) {
                    leftovers.add(diffuser);
                }
            }
            double remainder = previous.gasMassKg() * (1.0 - share);
            if (!leftovers.isEmpty() && remainder > 0.0) {
                double each = remainder / leftovers.size();
                for (int i = 0; i < leftovers.size(); i++) {
                    long diffuser = leftovers.getLong(i);
                    restoredMass.addTo(diffuser, each);
                    invalidate(diffuser);
                    markDirty(diffuser);
                }
            }
        }
        for (SealedRoom previous : merged) {
            retire(previous);
        }

        double restored = 0.0;
        boolean anyRestored = false;
        for (int i = 0; i < members.size(); i++) {
            long diffuser = members.getLong(i);
            if (restoredMass.containsKey(diffuser)) {
                restored += restoredMass.remove(diffuser);
                anyRestored = true;
            }
        }
        int freshCells = interior.size() - inheritedCells;
        if (anyRestored) {
            mass += restored;
        } else if (freshCells > 0) {
            // Les cellules nouvellement incluses contiennent l'air ambiant (vide en orbite).
            double centerY = (result.bounds().minY() + result.bounds().maxY() + 1) * 0.5;
            mass += ambient.pressureAt(centerY) * freshCells / (AIR_GAS_CONSTANT * CABIN_TEMPERATURE_K);
        }

        SealedRoom room = new SealedRoom(nextRoomId++, interior, result.boundary(), result.sections(), result.bounds(), mass);
        for (int i = 0; i < members.size(); i++) {
            long diffuser = members.getLong(i);
            room.addDiffuser(diffuser);
            SealedRoom old = diffuserRooms.put(diffuser, room);
            if (old != null && old != room) {
                old.removeDiffuser(diffuser);
            }
        }
        rooms.put(room.id(), room);
        index(room);
        setDirty();
    }

    private void handleUnsealed(ServerLevel level, long diffuser, FloodFill.Result result) {
        SealedRoom room = diffuserRooms.get(diffuser);
        switch (result.outcome()) {
            case UNKNOWN_TERRAIN, FAILED -> dirtySince.put(diffuser, tickCounter + UNKNOWN_TERRAIN_RETRY_TICKS - REFLOOD_DEBOUNCE_TICKS);
            case NO_START_CELL -> {
                if (room != null) {
                    diffuserRooms.remove(diffuser);
                    room.removeDiffuser(diffuser);
                }
            }
            case LEAKS_TO_EXTERIOR, EXCEEDS_SAFETY_VOLUME -> {
                // Le suivi incrémental n'a vu aucune brèche alors que le volume fuit (ex. poche jugée fermée puis
                // ouverte plus loin, ou volume connecté plus grand que la boîte) : rescan avec fuite forcée.
                if (room != null && room.isSealed()) {
                    rescanBoundary(level, room);
                }
            }
            case SEALED -> {
            }
        }
    }

    private void rescanBoundary(ServerLevel level, SealedRoom room) {
        LongArrayList cells = new LongArrayList(room.boundaryCells());
        for (int i = 0; i < cells.size(); i++) {
            refreshBoundaryCell(level, room, cells.getLong(i), true);
        }
    }

    private LongArrayList collectMemberDiffusers(FloodFill.Result result) {
        LongArrayList members = new LongArrayList();
        BoundingBox bounds = result.bounds();
        LongSet interior = result.interior();
        for (int gx = (bounds.minX() - 1) >> GRID_SHIFT; gx <= (bounds.maxX() + 1) >> GRID_SHIFT; gx++) {
            for (int gy = (bounds.minY() - 1) >> GRID_SHIFT; gy <= (bounds.maxY() + 1) >> GRID_SHIFT; gy++) {
                for (int gz = (bounds.minZ() - 1) >> GRID_SHIFT; gz <= (bounds.maxZ() + 1) >> GRID_SHIFT; gz++) {
                    LongArrayList cell = diffuserGrid.get(BlockPos.asLong(gx, gy, gz));
                    if (cell == null) {
                        continue;
                    }
                    for (int i = 0; i < cell.size(); i++) {
                        long diffuser = cell.getLong(i);
                        for (Direction direction : DIRECTIONS) {
                            if (interior.contains(BlockPos.offset(diffuser, direction))) {
                                members.add(diffuser);
                                break;
                            }
                        }
                    }
                }
            }
        }
        if (!members.contains(result.origin())) {
            members.add(result.origin());
        }
        return members;
    }

    private static int overlapCount(SealedRoom room, LongOpenHashSet interior, BoundingBox bounds) {
        if (!room.bounds().intersects(bounds)) {
            return 0;
        }
        LongSet cells = room.interior();
        int count = 0;
        if (cells.size() <= interior.size()) {
            for (LongIterator it = cells.iterator(); it.hasNext(); ) {
                if (interior.contains(it.nextLong())) {
                    count++;
                }
            }
        } else {
            for (LongIterator it = interior.iterator(); it.hasNext(); ) {
                if (room.contains(it.nextLong())) {
                    count++;
                }
            }
        }
        return count;
    }

    private void retire(SealedRoom room) {
        unindex(room);
        rooms.remove(room.id());
        for (LongIterator it = room.diffusers().iterator(); it.hasNext(); ) {
            long diffuser = it.nextLong();
            if (diffuserRooms.get(diffuser) == room) {
                diffuserRooms.remove(diffuser);
            }
        }
    }

    // ───────────────────────────── Pièces : fuites, aspiration, effets ─────────────────────────────

    private void tickRooms(ServerLevel level, PlanetaryAtmosphere ambient) {
        boolean effectsTick = tickCounter % BREACH_EFFECT_INTERVAL_TICKS == 0;
        ObjectIterator<SealedRoom> it = rooms.values().iterator();
        while (it.hasNext()) {
            SealedRoom room = it.next();
            double external = ambient.pressureAt(room.centerY());
            room.tickLeak(TICK_SECONDS, external);
            if (!room.isSealed()) {
                applySuction(level, room);
                if (effectsTick) {
                    emitBreachEffects(level, room, external);
                }
            }
            if (room.diffuserCount() == 0 && Math.abs(room.pressurePa() - external) < ORPHAN_EQUILIBRIUM_TOLERANCE_PA) {
                it.remove();
                unindex(room);
            }
        }
    }

    /** Accélération vers la brèche la plus proche, proportionnelle au débit par face et en 1/(1 + d²). */
    private void applySuction(ServerLevel level, SealedRoom room) {
        double outflow = room.lastLeakRateKgPerSecond();
        if (outflow <= 1.0e-3 || room.breachAreaM2() == 0) {
            return;
        }
        double perFace = outflow / room.breachAreaM2();
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, room.entityBounds().inflate(1.0),
                entity -> !entity.isSpectator() && !entity.noPhysics && !(entity instanceof Player player && player.getAbilities().flying));
        for (Entity entity : entities) {
            double bestDistanceSq = Double.MAX_VALUE;
            double tx = 0.0;
            double ty = 0.0;
            double tz = 0.0;
            int scanned = 0;
            double ex = entity.getX();
            double ey = entity.getY() + entity.getBbHeight() * 0.5;
            double ez = entity.getZ();
            for (LongIterator it = room.breaches().keySet().iterator(); it.hasNext() && scanned < MAX_SUCTION_SOURCES; scanned++) {
                long cell = it.nextLong();
                double dx = BlockPos.getX(cell) + 0.5 - ex;
                double dy = BlockPos.getY(cell) + 0.5 - ey;
                double dz = BlockPos.getZ(cell) + 0.5 - ez;
                double distanceSq = dx * dx + dy * dy + dz * dz;
                if (distanceSq < bestDistanceSq) {
                    bestDistanceSq = distanceSq;
                    tx = dx;
                    ty = dy;
                    tz = dz;
                }
            }
            double distance = Math.sqrt(bestDistanceSq);
            if (distance < 1.0e-3) {
                continue;
            }
            double strength = Math.min(MAX_SUCTION_PER_TICK, SUCTION_COEFFICIENT * perFace / (1.0 + bestDistanceSq));
            if (strength < 1.0e-3) {
                continue;
            }
            entity.push(tx / distance * strength, ty / distance * strength, tz / distance * strength);
            // Synchronise la vélocité vers le client pour les joueurs.
            entity.hurtMarked = true;
        }
    }

    private void emitBreachEffects(ServerLevel level, SealedRoom room, double external) {
        double differential = room.pressurePa() - external;
        if (differential < 500.0) {
            return;
        }
        float intensity = (float) Math.min(1.0, differential / TARGET_PRESSURE_PA);
        int emitted = 0;
        for (LongIterator it = room.breaches().keySet().iterator(); it.hasNext() && emitted < 4; emitted++) {
            long cell = it.nextLong();
            double x = BlockPos.getX(cell) + 0.5;
            double y = BlockPos.getY(cell) + 0.5;
            double z = BlockPos.getZ(cell) + 0.5;
            level.playSound(null, x, y, z, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS,
                    0.3f + 0.9f * intensity, 0.5f + 0.4f * intensity);
            level.sendParticles(ParticleTypes.CLOUD, x, y, z, 2 + (int) (6 * intensity),
                    0.25, 0.25, 0.25, 0.04 + 0.1 * intensity);
        }
    }

    // ───────────────────────────── Physiologie ─────────────────────────────

    private void tickOrganisms(ServerLevel level, PlanetaryAtmosphere ambient) {
        if (rooms.isEmpty() && hypoxia.isEmpty()
                && ambient.oxygenPartialPressureAt(level.getMaxBuildHeight()) >= HYPOXIA_THRESHOLD_PPO2_PA) {
            return;
        }
        double dt = ORGANISM_INTERVAL_TICKS * TICK_SECONDS;
        // Copie préalable : des dégâts mortels retirent des entités pendant l'itération.
        List<LivingEntity> organisms = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof LivingEntity living && living.isAlive()) {
                organisms.add(living);
            }
        }
        for (LivingEntity living : organisms) {
            tickOrganism(level, ambient, living, dt);
        }
    }

    private void tickOrganism(ServerLevel level, PlanetaryAtmosphere ambient, LivingEntity entity, double dt) {
        UUID id = entity.getUUID();
        if (isExempt(entity)) {
            hypoxia.removeFloat(id);
            return;
        }
        double eyeY = entity.getEyeY();
        SealedRoom room = roomAt(BlockPos.asLong(Mth.floor(entity.getX()), Mth.floor(eyeY), Mth.floor(entity.getZ())));
        double pressure = room != null ? room.pressurePa() : ambient.pressureAt(eyeY);
        double oxygenFraction = room != null ? AIR_OXYGEN_FRACTION : ambient.oxygenFraction();
        // Cabine d'un vaisseau : pressurisée tant que la coque tient.
        if (entity.getVehicle() instanceof odyssea.ship.ShipEntity ship && ship.hull() > 0.0f) {
            pressure = TARGET_PRESSURE_PA;
            oxygenFraction = AIR_OXYGEN_FRACTION;
        }
        boolean suited = hasSealedSuit(entity);
        double partialO2 = suited ? Double.POSITIVE_INFINITY : pressure * oxygenFraction;
        float current = hypoxia.getFloat(id);
        float next;
        if (partialO2 >= HYPOXIA_THRESHOLD_PPO2_PA) {
            if (current <= 0.0f) {
                return;
            }
            next = (float) Math.max(0.0, current - RECOVERY_RATE_PER_SECOND * dt);
        } else {
            double deficit = 1.0 - partialO2 / HYPOXIA_THRESHOLD_PPO2_PA;
            double rate = HYPOXIA_RATE_PER_SECOND * deficit * (pressure < ARMSTRONG_LIMIT_PA ? VACUUM_HYPOXIA_MULTIPLIER : 1.0);
            next = (float) Math.min(1.0, current + rate * dt);
        }
        if (next <= 0.0f) {
            hypoxia.removeFloat(id);
        } else {
            hypoxia.put(id, next);
        }
        applySymptoms(level, entity, next, !suited && pressure < ARMSTRONG_LIMIT_PA);
    }

    private static void applySymptoms(ServerLevel level, LivingEntity entity, float severity, boolean ebullism) {
        if (severity >= 0.3f) {
            entity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, severity >= 0.6f ? 1 : 0, false, false, true));
            entity.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 40, 0, false, false, true));
        }
        if (severity >= 0.55f) {
            entity.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 120, 0, false, false, true));
            entity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 0, false, false, true));
        }
        if (severity >= 0.8f) {
            entity.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0, false, false, true));
        }
        float damage = (severity >= 1.0f ? 2.0f : 0.0f) + (ebullism ? 1.0f : 0.0f);
        if (damage > 0.0f) {
            entity.hurt(new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(HYPOXIA_DAMAGE)), damage);
        }
        if (entity instanceof ServerPlayer player && severity >= 0.3f) {
            boolean critical = severity >= 0.8f;
            player.displayClientMessage(Component.translatable(critical ? "odyssea.atmosphere.hypoxia.critical" : "odyssea.atmosphere.hypoxia")
                    .withStyle(critical ? ChatFormatting.RED : ChatFormatting.GOLD), true);
        }
    }

    private static boolean isExempt(LivingEntity entity) {
        if (entity instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return true;
        }
        EntityType<?> type = entity.getType();
        return entity.isInvulnerable() || type.is(VACUUM_IMMUNE) || type.is(EntityTypeTags.UNDEAD);
    }

    private static boolean hasSealedSuit(LivingEntity entity) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (stack.isEmpty() || !stack.is(PRESSURE_SUIT)) {
                return false;
            }
        }
        // Combinaison Odyssea : étanche tant que sa réserve d'oxygène n'est pas vide.
        if (!odyssea.suit.SpaceSuitItem.hasOxygen(entity)) {
            return false;
        }
        CustomData data = entity.getItemBySlot(EquipmentSlot.HEAD).get(DataComponents.CUSTOM_DATA);
        return data == null || !data.copyTag().getBoolean(VISOR_OPEN_KEY);
    }

    /** Vrai si l'entité respire sans aide à cet endroit (air ambiant, salle pressurisée ou cabine de vaisseau). */
    public boolean canBreatheUnaided(ServerLevel level, LivingEntity entity) {
        if (entity.getVehicle() instanceof odyssea.ship.ShipEntity ship && ship.hull() > 0.0f) {
            return true;
        }
        return isBreathable(level, BlockPos.containing(entity.getX(), entity.getEyeY(), entity.getZ()));
    }

    // ───────────────────────────── Index et utilitaires ─────────────────────────────

    private @Nullable SealedRoom roomAt(long pos) {
        if (sectionIndex.isEmpty()) {
            return null;
        }
        ObjectArrayList<SealedRoom> candidates = sectionIndex.get(SectionPos.blockToSection(pos));
        if (candidates == null) {
            return null;
        }
        for (SealedRoom room : candidates) {
            if (room.contains(pos)) {
                return room;
            }
        }
        return null;
    }

    private void index(SealedRoom room) {
        for (LongIterator it = room.sections().iterator(); it.hasNext(); ) {
            long section = it.nextLong();
            ObjectArrayList<SealedRoom> list = sectionIndex.get(section);
            if (list == null) {
                list = new ObjectArrayList<>(2);
                sectionIndex.put(section, list);
            }
            list.add(room);
        }
    }

    private void unindex(SealedRoom room) {
        for (LongIterator it = room.sections().iterator(); it.hasNext(); ) {
            long section = it.nextLong();
            ObjectArrayList<SealedRoom> list = sectionIndex.get(section);
            if (list != null) {
                list.remove(room);
                if (list.isEmpty()) {
                    sectionIndex.remove(section);
                }
            }
        }
    }

    private void markRoomDirty(SealedRoom room) {
        for (LongIterator it = room.diffusers().iterator(); it.hasNext(); ) {
            long diffuser = it.nextLong();
            invalidate(diffuser);
            markDirty(diffuser);
        }
    }

    private void invalidate(long diffuser) {
        invalidatedAt.put(diffuser, ++globalGeneration);
    }

    private void markDirty(long diffuser) {
        if (!dirtySince.containsKey(diffuser)) {
            dirtySince.put(diffuser, tickCounter);
        }
    }

    private void gridAdd(long diffuser) {
        long cell = gridKey(diffuser);
        LongArrayList list = diffuserGrid.get(cell);
        if (list == null) {
            list = new LongArrayList(4);
            diffuserGrid.put(cell, list);
        }
        list.add(diffuser);
    }

    private void gridRemove(long diffuser) {
        long cell = gridKey(diffuser);
        LongArrayList list = diffuserGrid.get(cell);
        if (list != null) {
            list.rem(diffuser);
            if (list.isEmpty()) {
                diffuserGrid.remove(cell);
            }
        }
    }

    private static long gridKey(long pos) {
        return BlockPos.asLong(BlockPos.getX(pos) >> GRID_SHIFT, BlockPos.getY(pos) >> GRID_SHIFT, BlockPos.getZ(pos) >> GRID_SHIFT);
    }

    private static boolean insideSafetyBox(long diffuser, BlockPos pos) {
        int dx = pos.getX() - BlockPos.getX(diffuser);
        int dy = pos.getY() - BlockPos.getY(diffuser);
        int dz = pos.getZ() - BlockPos.getZ(diffuser);
        int low = -FloodFill.RADIUS - 1;
        int high = FloodFill.RADIUS;
        return dx >= low && dx <= high && dy >= low && dy <= high && dz >= low && dz <= high;
    }

    private static ExecutorService executor() {
        synchronized (EXECUTOR_LOCK) {
            if (executor == null || executor.isShutdown()) {
                int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
                AtomicInteger counter = new AtomicInteger();
                executor = Executors.newFixedThreadPool(threads, runnable -> {
                    Thread thread = new Thread(runnable, "Odyssea-Atmosphere-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    thread.setUncaughtExceptionHandler((t, error) -> LOGGER.error("Uncaught error in {}", t.getName(), error));
                    return thread;
                });
            }
            return executor;
        }
    }

    // ───────────────────────────── Persistance ─────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("diffusers", diffusers.toLongArray());
        // Les pièces sont recalculées au chargement ; seule la masse de gaz est conservée, répartie par diffuseur.
        Long2DoubleOpenHashMap masses = new Long2DoubleOpenHashMap(restoredMass);
        for (SealedRoom room : rooms.values()) {
            int count = room.diffuserCount();
            if (count == 0) {
                continue;
            }
            double share = room.gasMassKg() / count;
            for (LongIterator it = room.diffusers().iterator(); it.hasNext(); ) {
                masses.addTo(it.nextLong(), share);
            }
        }
        ListTag gas = new ListTag();
        for (Long2DoubleMap.Entry entry : masses.long2DoubleEntrySet()) {
            CompoundTag element = new CompoundTag();
            element.putLong("pos", entry.getLongKey());
            element.putDouble("mass_kg", entry.getDoubleValue());
            gas.add(element);
        }
        tag.put("gas", gas);
        return tag;
    }

    private static AtmosphereManager load(CompoundTag tag, HolderLookup.Provider registries) {
        AtmosphereManager manager = new AtmosphereManager();
        for (long diffuser : tag.getLongArray("diffusers")) {
            manager.diffusers.add(diffuser);
            manager.gridAdd(diffuser);
            manager.invalidate(diffuser);
            manager.dirtySince.put(diffuser, -REFLOOD_DEBOUNCE_TICKS);
        }
        ListTag gas = tag.getList("gas", Tag.TAG_COMPOUND);
        for (int i = 0; i < gas.size(); i++) {
            CompoundTag element = gas.getCompound(i);
            manager.restoredMass.addTo(element.getLong("pos"), element.getDouble("mass_kg"));
        }
        return manager;
    }
}
