package odyssea.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ColorRGBA;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ColoredFallingBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import odyssea.OdysseaCore;

/**
 * Blocs géologiques des planètes. Les modèles, textures, tables de butin, tags et traductions sont générés par
 * {@code tools/assets/generate_blocks.py}, dont la liste doit rester alignée sur celle-ci.
 */
public final class OdysseaBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(OdysseaCore.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(OdysseaCore.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, OdysseaCore.MOD_ID);
    private static final List<DeferredItem<BlockItem>> TAB_ORDER = new ArrayList<>();

    // ── Lune ──
    public static final DeferredBlock<Block> LUNAR_REGOLITH = regolith("lunar_regolith", 0xA9A7A2, MapColor.COLOR_LIGHT_GRAY);
    public static final DeferredBlock<Block> LUNAR_MARE_REGOLITH = regolith("lunar_mare_regolith", 0x5E5D5B, MapColor.COLOR_GRAY);
    public static final DeferredBlock<Block> ANORTHOSITE = rock("anorthosite", MapColor.QUARTZ, SoundType.STONE, 1.6f);
    public static final DeferredBlock<Block> LUNAR_BASALT = rock("lunar_basalt", MapColor.COLOR_GRAY, SoundType.BASALT, 1.4f);
    // ── Mercure ──
    public static final DeferredBlock<Block> MERCURIAN_REGOLITH = regolith("mercurian_regolith", 0x55504A, MapColor.COLOR_GRAY);
    public static final DeferredBlock<Block> MERCURIAN_ROCK = rock("mercurian_rock", MapColor.DEEPSLATE, SoundType.DEEPSLATE, 2.0f);
    public static final DeferredBlock<Block> HOLLOW_CRUST = rock("hollow_crust", MapColor.SNOW, SoundType.CALCITE, 0.8f);
    // ── Vénus ──
    public static final DeferredBlock<Block> VENUSIAN_REGOLITH = regolith("venusian_regolith", 0x7A5236, MapColor.COLOR_BROWN);
    public static final DeferredBlock<Block> VENUSIAN_BASALT = rock("venusian_basalt", MapColor.COLOR_BLACK, SoundType.BASALT, 1.6f);
    public static final DeferredBlock<Block> SULFUR_CRUST = rock("sulfur_crust", MapColor.COLOR_YELLOW, SoundType.NETHERRACK, 0.6f);
    // ── Mars ──
    public static final DeferredBlock<Block> MARTIAN_REGOLITH = regolith("martian_regolith", 0xB5603A, MapColor.COLOR_ORANGE);
    public static final DeferredBlock<Block> MARTIAN_ROCK = rock("martian_rock", MapColor.TERRACOTTA_RED, SoundType.STONE, 1.5f);
    public static final DeferredBlock<Block> MARTIAN_BASALT = rock("martian_basalt", MapColor.TERRACOTTA_BLACK, SoundType.BASALT, 1.5f);
    public static final DeferredBlock<Block> DRY_ICE = ice("dry_ice", MapColor.SNOW, 0.98f);
    // ── Uranus : manteau d'eau, d'ammoniac et de méthane gelés ──
    public static final DeferredBlock<Block> URANIAN_ICE = ice("uranian_ice", MapColor.ICE, 0.6f);
    public static final DeferredBlock<Block> METHANE_ICE = ice("methane_ice", MapColor.COLOR_CYAN, 0.98f);
    public static final DeferredBlock<Block> AMMONIA_ICE = ice("ammonia_ice", MapColor.TERRACOTTA_WHITE, 0.6f);
    public static final DeferredBlock<Block> ICE_SLUSH = register("ice_slush", () -> new Block(BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_BLUE).strength(0.5f).sound(SoundType.MUD).speedFactor(0.6f)));

    static {
        TABS.register("planets", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.odyssea.planets"))
                .icon(() -> new ItemStack(MARTIAN_REGOLITH.get()))
                .displayItems((parameters, output) -> TAB_ORDER.forEach(item -> output.accept(item.get())))
                .build());
    }

    private OdysseaBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        TABS.register(modBus);
    }

    public static List<DeferredItem<BlockItem>> items() {
        return Collections.unmodifiableList(TAB_ORDER);
    }

    /** Poussière fine qui s'écoule comme le sable. */
    private static DeferredBlock<Block> regolith(String name, int dustColor, MapColor mapColor) {
        return register(name, () -> new ColoredFallingBlock(new ColorRGBA(0xFF000000 | dustColor), BlockBehaviour.Properties.of()
                .mapColor(mapColor).strength(0.5f).sound(SoundType.SAND)));
    }

    private static DeferredBlock<Block> rock(String name, MapColor mapColor, SoundType sound, float hardness) {
        return register(name, () -> new Block(BlockBehaviour.Properties.of()
                .mapColor(mapColor).strength(hardness, 6.0f).sound(sound).requiresCorrectToolForDrops()));
    }

    private static DeferredBlock<Block> ice(String name, MapColor mapColor, float friction) {
        return register(name, () -> new Block(BlockBehaviour.Properties.of()
                .mapColor(mapColor).strength(0.6f).sound(SoundType.GLASS).friction(friction).requiresCorrectToolForDrops()));
    }

    private static DeferredBlock<Block> register(String name, java.util.function.Supplier<Block> factory) {
        DeferredBlock<Block> block = BLOCKS.register(name, factory);
        TAB_ORDER.add(ITEMS.registerSimpleBlockItem(block));
        return block;
    }
}
