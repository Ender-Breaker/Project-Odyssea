package odyssea.client.ship;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.RenderShape;
import net.neoforged.neoforge.client.model.data.ModelData;
import odyssea.ship.ShipStructure;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Maillages des vaisseaux, un par structure (clé : empreinte du contenu), partagés entre toutes les instances d'un
 * même modèle. Construits en arrière-plan avec les modèles de blocs vanilla (faces cachées éliminées, occlusion
 * ambiante), puis envoyés au GPU sur le fil de rendu ; libérés 30 s après leur dernière utilisation.
 */
public final class ShipMeshCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<RenderType> LAYERS = List.of(RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout(), RenderType.translucent());
    private static final Map<Long, Entry> CACHE = new HashMap<>();
    private static final long EXPIRY_MS = 30_000L;

    private record Built(Map<RenderType, MeshData> meshes, List<ByteBufferBuilder> memory, List<BlockPos> animated) {
    }

    public static final class Entry {
        private CompletableFuture<Built> future;
        private final Map<RenderType, VertexBuffer> buffers = new LinkedHashMap<>();
        private List<BlockPos> animated = List.of();
        private boolean uploaded;
        private long lastUsed;

        public Map<RenderType, VertexBuffer> buffers() {
            return buffers;
        }

        /** Blocs dessinés par un rendu d'entité de bloc (coffres, lits, têtes, boîtes de Shulker…). */
        public List<BlockPos> animatedBlocks() {
            return animated;
        }
    }

    private ShipMeshCache() {
    }

    public static @Nullable Entry get(ShipStructure structure) {
        long key = structure.contentHash();
        Entry entry = CACHE.get(key);
        if (entry == null) {
            entry = new Entry();
            Biome biome = biome();
            entry.future = CompletableFuture.supplyAsync(() -> build(structure, biome), Util.backgroundExecutor());
            CACHE.put(key, entry);
        }
        entry.lastUsed = Util.getMillis();
        if (!entry.uploaded && entry.future.isDone()) {
            upload(entry);
        }
        return entry.uploaded ? entry : null;
    }

    private static Biome biome() {
        var level = Minecraft.getInstance().level;
        return level.registryAccess().registryOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
    }

    private static RenderType layerFor(RenderType type) {
        if (LAYERS.contains(type)) {
            return type;
        }
        return type == RenderType.tripwire() ? RenderType.translucent() : RenderType.cutout();
    }

    private static Built build(ShipStructure structure, Biome biome) {
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        ShipStructureGetter getter = new ShipStructureGetter(structure, biome);
        RandomSource random = RandomSource.create();
        Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
        Map<RenderType, ByteBufferBuilder> memory = new LinkedHashMap<>();
        List<BlockPos> animated = new ArrayList<>();
        PoseStack pose = new PoseStack();
        structure.forEachBlock((x, y, z, state) -> {
            RenderShape shape = state.getRenderShape();
            BlockPos pos = new BlockPos(x, y, z);
            if (state.hasBlockEntity() && shape != RenderShape.MODEL) {
                animated.add(pos);
            }
            if (shape != RenderShape.MODEL) {
                return;
            }
            BakedModel model = dispatcher.getBlockModel(state);
            ModelData data = model.getModelData(getter, pos, state, ModelData.EMPTY);
            random.setSeed(state.getSeed(pos));
            for (RenderType type : model.getRenderTypes(state, random, data)) {
                RenderType layer = layerFor(type);
                BufferBuilder builder = builders.computeIfAbsent(layer, key -> {
                    ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 18);
                    memory.put(key, bytes);
                    return new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                });
                pose.pushPose();
                pose.translate(x, y, z);
                dispatcher.renderBatched(state, pos, getter, pose, builder, true, random, data, type);
                pose.popPose();
            }
        });
        Map<RenderType, MeshData> meshes = new LinkedHashMap<>();
        for (RenderType layer : LAYERS) {
            BufferBuilder builder = builders.get(layer);
            if (builder != null) {
                MeshData mesh = builder.build();
                if (mesh != null) {
                    meshes.put(layer, mesh);
                }
            }
        }
        return new Built(meshes, new ArrayList<>(memory.values()), List.copyOf(animated.subList(0, Math.min(animated.size(), 256))));
    }

    private static void upload(Entry entry) {
        entry.uploaded = true;
        Built built;
        try {
            built = entry.future.join();
        } catch (Exception e) {
            LOGGER.error("Maillage de vaisseau impossible", e);
            return;
        }
        try {
            for (Map.Entry<RenderType, MeshData> mesh : built.meshes().entrySet()) {
                VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                buffer.bind();
                buffer.upload(mesh.getValue());
                entry.buffers.put(mesh.getKey(), buffer);
            }
            VertexBuffer.unbind();
        } finally {
            built.memory().forEach(ByteBufferBuilder::close);
        }
        entry.animated = built.animated();
    }

    /** Libère les maillages inutilisés (appelé à chaque tick client). */
    public static void tick() {
        long now = Util.getMillis();
        Iterator<Entry> iterator = CACHE.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (now - entry.lastUsed > EXPIRY_MS && entry.future.isDone()) {
                release(entry);
                iterator.remove();
            }
        }
    }

    public static void clear() {
        for (Entry entry : CACHE.values()) {
            if (entry.future.isDone()) {
                release(entry);
            }
        }
        CACHE.clear();
    }

    private static void release(Entry entry) {
        if (!entry.uploaded) {
            try {
                Built built = entry.future.join();
                built.meshes().values().forEach(MeshData::close);
                built.memory().forEach(ByteBufferBuilder::close);
            } catch (Exception ignored) {
                // construction échouée : rien à libérer
            }
        }
        entry.buffers.values().forEach(VertexBuffer::close);
        entry.buffers.clear();
    }
}
