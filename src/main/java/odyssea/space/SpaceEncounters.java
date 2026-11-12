package odyssea.space;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import odyssea.OdysseaCore;
import odyssea.ship.ShipDesigns;
import odyssea.ship.ShipEntity;
import odyssea.ship.ShipRegistry;

/**
 * Rencontres dans l'espace : des pirates surgissent parfois autour des vaisseaux habités, plus souvent dans la
 * ceinture d'astéroïdes. Désactivable par la règle de jeu {@code odysseaPirates}.
 */
public final class SpaceEncounters {
    public static final GameRules.Key<GameRules.BooleanValue> PIRATES =
            GameRules.register("odysseaPirates", GameRules.Category.SPAWNING, GameRules.BooleanValue.create(true));

    private SpaceEncounters() {
    }

    /** Charge la classe (et donc la règle de jeu) pendant la construction du mod. */
    public static void bootstrap() {
    }

    public static void tick(ServerLevel level) {
        if (!SpaceSystem.isSpace(level) || level.getGameTime() % 100 != 0 || !level.getGameRules().getBoolean(PIRATES)) {
            return;
        }
        ShipDesigns.Loaded raider = ShipDesigns.get(OdysseaCore.id("raider"));
        if (raider == null) {
            return;
        }
        RandomSource random = level.getRandom();
        for (ServerPlayer player : level.players()) {
            if (!(player.getVehicle() instanceof ShipEntity ship) || ship.isHostile() || ship.isQuantum() || ship.pilot() != player) {
                continue;
            }
            long hostiles = ShipRegistry.ships(level).stream()
                    .filter(s -> s.isHostile() && s.distanceToSqr(ship) < 2_500.0 * 2_500.0).count();
            if (hostiles >= 3) {
                continue;
            }
            double distance = Math.hypot(ship.getX(), ship.getZ());
            boolean belt = distance > 195_000 && distance < 256_000;
            if (random.nextDouble() >= (belt ? 0.10 : 0.03)) {
                continue;
            }
            int count = 1 + (random.nextFloat() < 0.35f ? 1 : 0);
            for (int i = 0; i < count; i++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                Vec3 direction = new Vec3(Math.cos(angle), (random.nextDouble() - 0.5) * 0.3, Math.sin(angle)).normalize();
                Vec3 position = ship.position().add(direction.scale(750.0 + random.nextDouble() * 200.0));
                position = new Vec3(position.x, Math.clamp(position.y, SpaceSystem.MIN_SPACE_Y + 60, SpaceSystem.MAX_SPACE_Y - 60), position.z);
                Vec3 look = ship.position().subtract(position);
                float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
                ShipEntity pirate = ShipEntity.create(level, raider, position, yaw);
                level.addFreshEntity(pirate);
            }
            player.displayClientMessage(Component.translatable("odyssea.encounter.pirates").withStyle(ChatFormatting.RED), false);
        }
    }
}
