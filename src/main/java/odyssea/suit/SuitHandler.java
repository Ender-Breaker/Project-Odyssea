package odyssea.suit;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import odyssea.OdysseaCore;
import odyssea.atmosphere.AtmosphereManager;
import odyssea.dimension.Planets;
import odyssea.space.SpaceSystem;

/**
 * Le joueur hors de son vaisseau :
 * <ul>
 *   <li>gravité de chaque corps (attribut {@code gravity}), dégâts de chute et hauteur de saut ajustés — on bondit
 *       sur la Lune, on flotte dans l'espace ;</li>
 *   <li>réserve d'oxygène de la combinaison ;</li>
 *   <li>sortie extravéhiculaire : dans l'espace, la combinaison complète permet de manœuvrer librement ;</li>
 *   <li>passage espace ↔ planète à pied (jetpack, élytres, mode créatif), avec une descente freinée à l'arrivée.</li>
 * </ul>
 */
public final class SuitHandler {
    private static final ResourceLocation GRAVITY_ID = OdysseaCore.id("planet_gravity");
    private static final String EVA_KEY = "odyssea_eva";
    private static final Map<UUID, Double> APPLIED_RATIO = new ConcurrentHashMap<>();

    private SuitHandler() {
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        applyGravity(player, level);
        tickOxygen(player, level);
        tickEva(player, level);
        if (!player.isPassenger() && !player.isSpectator() && player.tickCount % 4 == 0) {
            tickTransitions(player, level);
        }
    }

    public static double gravityRatio(ServerLevel level) {
        if (SpaceSystem.isSpace(level)) {
            return 0.0;
        }
        Planets.Planet planet = Planets.get(level.dimension());
        return planet == null ? 1.0 : planet.gravity() / 9.81;
    }

    private static void applyGravity(ServerPlayer player, ServerLevel level) {
        double ratio = gravityRatio(level);
        Double applied = APPLIED_RATIO.get(player.getUUID());
        if (applied != null && applied == ratio && player.tickCount % 100 != 0) {
            return;
        }
        APPLIED_RATIO.put(player.getUUID(), ratio);
        modifier(player, Attributes.GRAVITY, ratio - 1.0);
        modifier(player, Attributes.FALL_DAMAGE_MULTIPLIER, ratio - 1.0);
        modifier(player, Attributes.SAFE_FALL_DISTANCE, ratio > 0.05 ? Math.min(6.0, 1.0 / ratio) - 1.0 : 0.0);
        // Saut : au plus 2,5 fois la hauteur terrestre, même sous une gravité très faible.
        modifier(player, Attributes.JUMP_STRENGTH, ratio < 1.0 ? Math.sqrt(Math.min(1.0, 2.5 * ratio)) - 1.0 : 0.0);
    }

    private static void modifier(Player player, Holder<Attribute> attribute, double amount) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        if (Math.abs(amount) < 1.0e-6) {
            instance.removeModifier(GRAVITY_ID);
        } else {
            instance.addOrUpdateTransientModifier(new AttributeModifier(GRAVITY_ID, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void tickOxygen(ServerPlayer player, ServerLevel level) {
        if (player.tickCount % 20 != 0 || !SpaceSuitItem.isFullSuit(player) || player.isCreative() || player.isSpectator()) {
            return;
        }
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        int oxygen = SpaceSuitItem.oxygen(chest);
        boolean breathable = AtmosphereManager.get(level).canBreatheUnaided(level, player);
        int next = breathable ? oxygen + 300 : oxygen - 20;
        next = Math.clamp(next, 0, SpaceSuitItem.MAX_OXYGEN);
        if (next != oxygen) {
            SpaceSuitItem.setOxygen(chest, next);
        }
        if (!breathable) {
            if (next == 0) {
                player.displayClientMessage(Component.translatable("odyssea.suit.oxygen_empty").withStyle(ChatFormatting.RED), true);
            } else if (next <= 1200) {
                player.displayClientMessage(Component.translatable("odyssea.suit.oxygen_low", next / 20).withStyle(ChatFormatting.GOLD), true);
            }
        }
    }

    private static void tickEva(ServerPlayer player, ServerLevel level) {
        boolean eva = SpaceSystem.isSpace(level) && !player.isPassenger() && SpaceSuitItem.isFullSuit(player)
                && SpaceSuitItem.hasOxygen(player);
        CompoundTag data = player.getPersistentData();
        boolean granted = data.getBoolean(EVA_KEY);
        if (eva && !granted) {
            data.putBoolean(EVA_KEY, true);
            if (!player.getAbilities().mayfly) {
                player.getAbilities().mayfly = true;
                player.getAbilities().flying = true;
            }
            player.getAbilities().setFlyingSpeed(0.03f);
            player.onUpdateAbilities();
        } else if (!eva && granted) {
            data.putBoolean(EVA_KEY, false);
            if (!player.isCreative() && !player.isSpectator()) {
                player.getAbilities().mayfly = false;
                player.getAbilities().flying = false;
            }
            player.getAbilities().setFlyingSpeed(0.05f);
            player.onUpdateAbilities();
        }
    }

    private static void tickTransitions(ServerPlayer player, ServerLevel level) {
        if (SpaceSystem.isSpace(level)) {
            SpaceSystem.Body body = SpaceSystem.entryBodyAt(player.position());
            if (body == null) {
                return;
            }
            ServerLevel target = level.getServer().getLevel(body.dimension());
            if (target == null) {
                return;
            }
            SpaceSystem.Transfer transfer = SpaceSystem.enterPlanet(body, player.position(), player.getDeltaMovement().scale(20.0));
            int x = (int) Math.floor(transfer.position().x);
            int z = (int) Math.floor(transfer.position().z);
            // À pied, on arrive 40 blocs au-dessus du sol, freiné : sans vaisseau, une rentrée depuis l'orbite serait mortelle.
            int surface = target.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
            double y = Math.max(surface, target.getSeaLevel()) + 40.0;
            player.changeDimension(new DimensionTransition(target, new Vec3(transfer.position().x, y, transfer.position().z),
                    Vec3.ZERO, player.getYRot(), player.getXRot(), DimensionTransition.DO_NOTHING));
            if (!player.getAbilities().flying) {
                player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 30, 0, false, true, true));
            }
            player.displayClientMessage(Component.translatable("odyssea.space.entering", Component.translatable(body.translationKey()))
                    .withStyle(ChatFormatting.AQUA), true);
        } else {
            SpaceSystem.Body body = SpaceSystem.bodyForDimension(level.dimension());
            if (body == null || player.getY() < SpaceSystem.EXIT_Y) {
                return;
            }
            ServerLevel space = level.getServer().getLevel(SpaceSystem.SPACE);
            if (space == null) {
                return;
            }
            SpaceSystem.Transfer transfer = SpaceSystem.leavePlanet(body, player.position(), player.getDeltaMovement().scale(20.0));
            player.changeDimension(new DimensionTransition(space, transfer.position(), Vec3.ZERO, player.getYRot(), player.getXRot(),
                    DimensionTransition.DO_NOTHING));
            player.displayClientMessage(Component.translatable("odyssea.space.leaving").withStyle(ChatFormatting.AQUA), true);
        }
    }
}
