package odyssea.registry;

import com.mojang.serialization.Codec;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import odyssea.OdysseaCore;
import odyssea.ship.ShipBeaconItem;
import odyssea.suit.SpaceSuitItem;

/** Objets des vaisseaux et de l'exploration spatiale. */
public final class OdysseaItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(OdysseaCore.MOD_ID);
    private static final DeferredRegister<ArmorMaterial> ARMOR = DeferredRegister.create(Registries.ARMOR_MATERIAL, OdysseaCore.MOD_ID);
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, OdysseaCore.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, OdysseaCore.MOD_ID);

    /** Réserve d'oxygène de la combinaison, en ticks de respiration. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> OXYGEN = COMPONENTS.registerComponentType("oxygen",
            builder -> builder.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> SPACE_SUIT_MATERIAL = ARMOR.register("space_suit", () -> {
        EnumMap<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
        defense.put(ArmorItem.Type.HELMET, 2);
        defense.put(ArmorItem.Type.CHESTPLATE, 5);
        defense.put(ArmorItem.Type.LEGGINGS, 4);
        defense.put(ArmorItem.Type.BOOTS, 2);
        defense.put(ArmorItem.Type.BODY, 4);
        return new ArmorMaterial(defense, 10, SoundEvents.ARMOR_EQUIP_IRON, () -> Ingredient.of(Items.IRON_INGOT),
                List.of(new ArmorMaterial.Layer(OdysseaCore.id("space_suit"))), 1.0f, 0.05f);
    });

    public static final DeferredItem<ShipBeaconItem> CUTLASS_BEACON = ITEMS.register("cutlass_beacon",
            () -> new ShipBeaconItem(OdysseaCore.id("cutlass"), new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));
    public static final DeferredItem<ShipBeaconItem> DESTROYER_BEACON = ITEMS.register("destroyer_beacon",
            () -> new ShipBeaconItem(OdysseaCore.id("destroyer"), new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));
    public static final DeferredItem<Item> HYDROGEN_CELL = ITEMS.registerSimpleItem("hydrogen_cell", new Item.Properties().stacksTo(16));
    public static final DeferredItem<Item> QUANTUM_CELL = ITEMS.registerSimpleItem("quantum_cell", new Item.Properties().stacksTo(16).rarity(Rarity.UNCOMMON));
    public static final DeferredItem<Item> SHIP_MISSILE = ITEMS.registerSimpleItem("ship_missile", new Item.Properties().stacksTo(16));
    public static final DeferredItem<Item> REPAIR_KIT = ITEMS.registerSimpleItem("hull_repair_kit", new Item.Properties().stacksTo(8));

    public static final DeferredItem<SpaceSuitItem> SPACE_HELMET = suit("space_helmet", ArmorItem.Type.HELMET);
    public static final DeferredItem<SpaceSuitItem> SPACE_SUIT = suit("space_suit", ArmorItem.Type.CHESTPLATE);
    public static final DeferredItem<SpaceSuitItem> SPACE_LEGGINGS = suit("space_leggings", ArmorItem.Type.LEGGINGS);
    public static final DeferredItem<SpaceSuitItem> SPACE_BOOTS = suit("space_boots", ArmorItem.Type.BOOTS);

    static {
        TABS.register("ships", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.odyssea.ships"))
                .icon(() -> new ItemStack(CUTLASS_BEACON.get()))
                .displayItems((parameters, output) -> {
                    output.accept(CUTLASS_BEACON.get());
                    output.accept(DESTROYER_BEACON.get());
                    output.accept(HYDROGEN_CELL.get());
                    output.accept(QUANTUM_CELL.get());
                    output.accept(SHIP_MISSILE.get());
                    output.accept(REPAIR_KIT.get());
                    output.accept(SPACE_HELMET.get());
                    output.accept(SPACE_SUIT.get());
                    output.accept(SPACE_LEGGINGS.get());
                    output.accept(SPACE_BOOTS.get());
                })
                .build());
    }

    private OdysseaItems() {
    }

    private static DeferredItem<SpaceSuitItem> suit(String name, ArmorItem.Type type) {
        return ITEMS.register(name, () -> new SpaceSuitItem(SPACE_SUIT_MATERIAL, type,
                new Item.Properties().durability(type.getDurability(22))));
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        ARMOR.register(modBus);
        COMPONENTS.register(modBus);
        TABS.register(modBus);
    }

    /** Balise correspondant à un modèle de vaisseau (vide si le modèle n'en a pas). */
    public static ItemStack beaconFor(ResourceLocation design) {
        if (design.equals(OdysseaCore.id("cutlass"))) {
            return new ItemStack(CUTLASS_BEACON.get());
        }
        if (design.equals(OdysseaCore.id("destroyer"))) {
            return new ItemStack(DESTROYER_BEACON.get());
        }
        return ItemStack.EMPTY;
    }
}
