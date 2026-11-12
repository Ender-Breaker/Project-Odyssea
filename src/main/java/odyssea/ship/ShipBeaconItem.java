package odyssea.ship;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Balise de déploiement : fait apparaître le vaisseau devant le joueur, posé sur le bloc visé (ou en vol libre dans
 * l'espace). Un vaisseau posé se remballe (accroupi, main vide, clic droit sur la coque) avec l'état de ses réservoirs.
 */
public class ShipBeaconItem extends Item {
    private final ResourceLocation design;

    public ShipBeaconItem(ResourceLocation design, Properties properties) {
        super(properties);
        this.design = design;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (context.getLevel() instanceof ServerLevel level) {
            Vec3 anchor = Vec3.atBottomCenterOf(context.getClickedPos().above());
            deploy(level, player, context.getItemInHand(), anchor, true);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel server) {
            deploy(server, player, stack, player.getEyePosition(), false);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private void deploy(ServerLevel level, Player player, ItemStack stack, Vec3 anchor, boolean onGround) {
        ShipDesigns.Loaded loaded = ShipDesigns.get(design);
        if (loaded == null) {
            player.displayClientMessage(Component.translatable("odyssea.ship.missing_design", design.toString()).withStyle(ChatFormatting.RED), true);
            return;
        }
        ShipStructure structure = loaded.structure();
        float yaw = player.getYRot();
        Vec3 look = Vec3.directionFromRotation(0.0f, yaw);
        double clearance = Math.max(structure.sizeX(), structure.sizeZ()) / 2.0 + 3.0;
        ShipEntity ship = ShipEntity.create(level, loaded, anchor, yaw);
        Vec3 center = anchor.add(look.scale(clearance));
        double y = onGround ? anchor.y + ship.pivot().y + 0.05 : center.y;
        ship.setPos(center.x, y, center.z);
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag tag = data.copyTag();
            ship.loadPackedState(tag);
        }
        level.addFreshEntity(ship);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.5f, 0.8f);
        player.displayClientMessage(Component.translatable("odyssea.ship.deployed", Component.translatable(loaded.design().translationKey()))
                .withStyle(ChatFormatting.AQUA), true);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.odyssea.ship_beacon.tooltip").withStyle(ChatFormatting.GRAY));
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.copyTag().contains("Fuel")) {
            CompoundTag tag = data.copyTag();
            tooltip.add(Component.translatable("item.odyssea.ship_beacon.state", Math.round(tag.getFloat("Hull")),
                    Math.round(tag.getFloat("Fuel"))).withStyle(ChatFormatting.DARK_AQUA));
        }
    }
}
