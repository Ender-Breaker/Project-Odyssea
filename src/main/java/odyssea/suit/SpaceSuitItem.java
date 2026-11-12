package odyssea.suit;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import odyssea.registry.OdysseaItems;

/**
 * Pièce de combinaison spatiale. Les quatre pièces forment une combinaison étanche ({@code #odyssea:pressure_suit}) ;
 * le torse porte la réserve d'oxygène (5 minutes), qui se vide hors d'un air respirable et se remplit dans l'air
 * libre, une salle pressurisée ou la cabine d'un vaisseau. Dans l'espace, la combinaison complète donne des
 * propulseurs de manœuvre (vol libre).
 */
public class SpaceSuitItem extends ArmorItem {
    /** Réserve du torse, en ticks de respiration (5 minutes). */
    public static final int MAX_OXYGEN = 6000;

    public SpaceSuitItem(Holder<ArmorMaterial> material, Type type, Properties properties) {
        super(material, type, properties);
    }

    public static boolean isFullSuit(LivingEntity entity) {
        return entity.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof SpaceSuitItem
                && entity.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof SpaceSuitItem
                && entity.getItemBySlot(EquipmentSlot.LEGS).getItem() instanceof SpaceSuitItem
                && entity.getItemBySlot(EquipmentSlot.FEET).getItem() instanceof SpaceSuitItem;
    }

    public static int oxygen(ItemStack chest) {
        return chest.getOrDefault(OdysseaItems.OXYGEN.get(), MAX_OXYGEN);
    }

    public static void setOxygen(ItemStack chest, int value) {
        chest.set(OdysseaItems.OXYGEN.get(), Mth.clamp(value, 0, MAX_OXYGEN));
    }

    /** Faux seulement pour un torse Odyssea vide : les combinaisons d'autres mods gardent leur propre logique. */
    public static boolean hasOxygen(LivingEntity entity) {
        ItemStack chest = entity.getItemBySlot(EquipmentSlot.CHEST);
        return !(chest.getItem() instanceof SpaceSuitItem) || oxygen(chest) > 0;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return getType() == Type.CHESTPLATE || super.isBarVisible(stack);
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        if (getType() == Type.CHESTPLATE) {
            return Math.round(13.0f * oxygen(stack) / MAX_OXYGEN);
        }
        return super.getBarWidth(stack);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return getType() == Type.CHESTPLATE ? 0x4FD8FF : super.getBarColor(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (getType() == Type.CHESTPLATE) {
            int seconds = oxygen(stack) / 20;
            tooltip.add(Component.translatable("item.odyssea.space_suit.oxygen", seconds / 60, String.format("%02d", seconds % 60))
                    .withStyle(ChatFormatting.AQUA));
        }
        tooltip.add(Component.translatable("item.odyssea.space_suit.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
