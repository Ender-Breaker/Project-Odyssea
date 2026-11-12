package odyssea.client;

import com.mojang.logging.LogUtils;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import odyssea.OdysseaCore;
import odyssea.client.ship.ShipControls;
import odyssea.network.OdysseaNetwork;
import odyssea.registry.OdysseaItems;
import odyssea.ship.ShipDesigns;
import odyssea.ship.ShipEntity;
import odyssea.ship.ShipFlight;
import odyssea.space.Navigation;
import odyssea.space.SpaceSystem;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.slf4j.Logger;

/**
 * Scénario automatique « ships » du test client : horizon de Terra, déploiement et vol d'un Cutlass, tir, montée
 * jusqu'à l'espace, vue de la Terre, saut quantique vers Mars, combat contre des pirates, Destroyer, entrée dans
 * l'atmosphère martienne. Une capture d'écran par étape ({@code screenshots/odyssea_ships_*.png}).
 */
final class ClientShipSmokeTest {
    private static final Logger LOGGER = LogUtils.getLogger();

    private enum Phase {
        HORIZON, DEPLOY, HOVER, CLIMB, SPACE_TURN, NAVIGATE, QUANTUM, ARRIVED, COMBAT, DESTROYER, DESCENT, DONE
    }

    private static Phase phase = Phase.HORIZON;
    private static int ticks;
    private static int phaseTicks;
    private static boolean started;
    private static boolean exitSent;
    private static volatile boolean setupDone;
    private static int shiplessTicks;
    private static int marsTicks;
    private static int afterExit;
    private static volatile int startX;
    private static volatile int startZ;

    private ClientShipSmokeTest() {
    }

    /** @return vrai quand le scénario est terminé */
    static boolean tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            return false;
        }
        if (!started) {
            started = true;
            setup(minecraft);
        }
        ticks++;
        phaseTicks++;
        if (ticks > 6000) {
            LOGGER.error("[Odyssea ships] ÉCHEC : délai dépassé en phase {}", phase);
            finish();
            return true;
        }
        ShipEntity ship = minecraft.player.getVehicle() instanceof ShipEntity s ? s : null;
        if (phaseTicks % 40 == 1 && phase.ordinal() >= Phase.SPACE_TURN.ordinal()) {
            LOGGER.info("[Odyssea ships] trace {} t={} vaisseau={} véhicule={} écran={} dim={}", phase, phaseTicks, ship != null,
                    minecraft.player.getVehicle(), minecraft.screen, minecraft.level.dimension().location());
        }
        if (ship == null && phase.ordinal() >= Phase.HOVER.ordinal() && phase != Phase.DESCENT && phase != Phase.DONE) {
            if (++shiplessTicks > 300) {
                fail("le joueur n'est plus à bord");
                return false;
            }
        } else {
            shiplessTicks = 0;
        }
        switch (phase) {
            case HORIZON -> {
                minecraft.options.hideGui = true;
                if (!setupDone) {
                    phaseTicks = 0;
                    return false;
                }
                if (phaseTicks == 140) {
                    look(minecraft, 0.0f, 2.0f);
                } else if (phaseTicks == 160) {
                    shoot(minecraft, "horizon_south");
                    look(minecraft, 180.0f, 2.0f);
                } else if (phaseTicks == 180) {
                    shoot(minecraft, "horizon_north");
                    look(minecraft, 90.0f, 2.0f);
                } else if (phaseTicks == 200) {
                    shoot(minecraft, "horizon_west");
                    server(minecraft, ClientShipSmokeTest::deployCutlass);
                    next(Phase.DEPLOY);
                }
            }
            case DEPLOY -> {
                if (ship != null && phaseTicks > 60) {
                    minecraft.options.hideGui = false;
                    ShipControls.testInput = ShipFlight.Input.NEUTRAL;
                    next(Phase.HOVER);
                } else if (phaseTicks > 400) {
                    fail("embarquement impossible");
                }
            }
            case HOVER -> {
                aim(minecraft, ship, new Vec3(1.0, 0.0, 0.0));
                if (phaseTicks < 60) {
                    ShipControls.testInput = input(0.0f, 0.6f, 0.0f, false, new Vec3(1.0, 0.0, 0.0));
                } else if (phaseTicks == 60) {
                    shoot(minecraft, "cutlass_takeoff");
                    ShipControls.testInput = input(0.6f, 0.2f, 0.0f, false, new Vec3(1.0, 0.0, 0.0));
                } else if (phaseTicks == 110) {
                    ShipControls.testFlags = OdysseaNetwork.PilotState.FIRING;
                } else if (phaseTicks == 122) {
                    shoot(minecraft, "cutlass_firing");
                    ShipControls.testFlags = 0;
                    log(ship, "vol");
                    next(Phase.CLIMB);
                }
            }
            case CLIMB -> {
                // Nez vers le ciel, plein gaz en postcombustion jusqu'à la sortie de l'atmosphère.
                Vec3 up = new Vec3(0.15, 1.0, 0.0).normalize();
                aim(minecraft, ship, up);
                ShipControls.testInput = input(phaseTicks > 30 ? 1.0f : 0.2f, 0.0f, 0.0f, phaseTicks > 30, up);
                if (phaseTicks == 90) {
                    shoot(minecraft, "cutlass_climb");
                }
                if (phaseTicks % 60 == 0 && ship != null) {
                    log(ship, "montée");
                    LOGGER.info("[Odyssea ships]   {}", ship.flightDebug());
                }
                if (SpaceSystem.isSpace(minecraft.level) && ship != null) {
                    log(ship, "espace atteint");
                    next(Phase.SPACE_TURN);
                } else if (phaseTicks > 1500) {
                    fail("l'espace n'a pas été atteint (y = " + (int) minecraft.player.getY() + ")");
                }
            }
            case SPACE_TURN -> {
                if (ship == null) {
                    return false;
                }
                Vec3 toTerra = SpaceSystem.position("terra").subtract(ship.position()).normalize();
                aim(minecraft, ship, toTerra);
                ShipControls.testInput = input(phaseTicks < 20 ? 0.0f : -0.2f, 0.0f, 0.0f, false, toTerra);
                if (phaseTicks == 40) {
                    shoot(minecraft, "space_exit");
                } else if (phaseTicks == 120) {
                    shoot(minecraft, "space_terra");
                    UUID id = minecraft.player.getUUID();
                    server(minecraft, server -> {
                        ServerPlayer player = server.getPlayerList().getPlayer(id);
                        if (player != null) {
                            Navigation.setDestination(player, "mars");
                        }
                    });
                    Navigation.setClientDestination("mars");
                    next(Phase.NAVIGATE);
                }
            }
            case NAVIGATE -> {
                if (ship == null) {
                    return false;
                }
                Vec3 arrival = SpaceSystem.quantumArrival(SpaceSystem.body("mars"), ship.position());
                Vec3 to = arrival.subtract(ship.position()).normalize();
                aim(minecraft, ship, to);
                // On s'éloigne d'abord du puits de gravité terrestre en fonçant vers la destination.
                boolean blocked = SpaceSystem.quantumBlocker(ship.position()) != null;
                ShipControls.testInput = input(blocked ? 1.0f : 0.0f, 0.0f, 0.0f, blocked, to);
                double angle = Math.toDegrees(Math.acos(Math.clamp(ship.forward().dot(to), -1.0, 1.0)));
                if (phaseTicks == 20) {
                    shoot(minecraft, "nav_marker");
                }
                if (!blocked && angle < 4.0 && phaseTicks > 40) {
                    PacketDistributor.sendToServer(new OdysseaNetwork.ShipAction(ship.getId(), OdysseaNetwork.Action.QUANTUM));
                    next(Phase.QUANTUM);
                } else if (phaseTicks > 1200) {
                    fail("alignement impossible (angle " + (int) angle + "°, puits " + blocked + ")");
                }
            }
            case QUANTUM -> {
                if (ship == null) {
                    return false;
                }
                if (ship.spoolTicks() > 0 && phaseTicks == 30) {
                    shoot(minecraft, "quantum_spool");
                }
                if (ship.isQuantum() && ship.quantumProgress(0.0f) > 0.45 && ship.quantumProgress(0.0f) < 0.6) {
                    shoot(minecraft, "quantum_travel");
                }
                if (phaseTicks > 80 && !ship.isQuantum() && ship.spoolTicks() == 0) {
                    log(ship, "arrivée");
                    next(Phase.ARRIVED);
                } else if (phaseTicks > 1400) {
                    fail("saut quantique bloqué");
                }
            }
            case ARRIVED -> {
                if (ship == null) {
                    return false;
                }
                Vec3 toMars = SpaceSystem.position("mars").subtract(ship.position()).normalize();
                aim(minecraft, ship, toMars);
                ShipControls.testInput = input(0.0f, 0.0f, 0.0f, false, toMars);
                if (phaseTicks == 10) {
                    minecraft.setScreen(new odyssea.client.map.SpaceMapScreen());
                } else if (phaseTicks == 40) {
                    shoot(minecraft, "map");
                } else if (phaseTicks == 45) {
                    minecraft.setScreen(null);
                }
                if (phaseTicks == 60) {
                    shoot(minecraft, "space_mars");
                    UUID id = minecraft.player.getUUID();
                    server(minecraft, server -> spawnPirates(server, id));
                    next(Phase.COMBAT);
                }
            }
            case COMBAT -> {
                if (ship == null) {
                    return false;
                }
                ShipEntity enemy = null;
                for (ShipEntity other : odyssea.ship.ShipRegistry.ships(minecraft.level)) {
                    if (other.isHostile() && (enemy == null || other.distanceTo(ship) < enemy.distanceTo(ship))) {
                        enemy = other;
                    }
                }
                Vec3 to = enemy != null ? enemy.position().subtract(ship.position()).normalize() : ship.forward();
                aim(minecraft, ship, to);
                ShipControls.testInput = input(0.0f, 0.0f, 0.0f, false, to);
                ShipControls.testFlags = phaseTicks > 40 ? OdysseaNetwork.PilotState.FIRING : 0;
                if (phaseTicks == 30) {
                    PacketDistributor.sendToServer(new OdysseaNetwork.ShipAction(ship.getId(), OdysseaNetwork.Action.LOCK_TARGET));
                }
                if (phaseTicks == 70) {
                    PacketDistributor.sendToServer(new OdysseaNetwork.ShipAction(ship.getId(), OdysseaNetwork.Action.FIRE_MISSILE));
                }
                if (phaseTicks == 90 || phaseTicks == 140) {
                    shoot(minecraft, "combat_" + phaseTicks);
                }
                if (phaseTicks == 200) {
                    shoot(minecraft, "combat_late");
                    ShipControls.testFlags = 0;
                    LOGGER.info("[Odyssea ships] combat : coque {} bouclier {} énergie {}", (int) ship.hull(), (int) ship.shield(), (int) ship.energy());
                    UUID id = minecraft.player.getUUID();
                    server(minecraft, server -> spawnDestroyer(server, id));
                    next(Phase.DESTROYER);
                }
            }
            case DESTROYER -> {
                if (ship == null) {
                    return false;
                }
                ShipEntity destroyer = null;
                for (ShipEntity other : odyssea.ship.ShipRegistry.ships(minecraft.level)) {
                    if (other.design() != null && other.design().id().getPath().equals("destroyer")) {
                        destroyer = other;
                    }
                }
                if (destroyer != null) {
                    Vec3 to = destroyer.position().subtract(ship.position()).normalize();
                    aim(minecraft, ship, to);
                    ShipControls.testInput = input(0.0f, 0.0f, 0.0f, false, to);
                }
                if (phaseTicks == 120) {
                    shoot(minecraft, "destroyer");
                    next(Phase.DESCENT);
                }
            }
            case DESCENT -> {
                if (ship == null) {
                    if (exitSent && ++afterExit == 50) {
                        aim(minecraft, null, new Vec3(1.0, -0.2, 0.0).normalize());
                    } else if (exitSent && afterExit == 70) {
                        shoot(minecraft, "mars_eva");
                        next(Phase.DONE);
                    }
                    return false;
                }
                if (SpaceSystem.isSpace(minecraft.level)) {
                    Vec3 toMars = SpaceSystem.position("mars").subtract(ship.position()).normalize();
                    aim(minecraft, ship, toMars);
                    ShipControls.testInput = input(1.0f, 0.0f, 0.0f, true, toMars);
                    if (phaseTicks > 1400) {
                        fail("entrée dans l'atmosphère martienne impossible");
                    }
                } else {
                    Vec3 level = new Vec3(1.0, -0.35, 0.0).normalize();
                    aim(minecraft, ship, level);
                    ShipControls.testInput = input(0.3f, -0.3f, 0.0f, false, level);
                    if (minecraft.level.dimension().location().getPath().equals("mars")) {
                        marsTicks++;
                        if (marsTicks == 60 || marsTicks == 240) {
                            shoot(minecraft, "mars_entry_" + marsTicks);
                            log(ship, "Mars");
                        }
                        if (marsTicks == 260 && !exitSent) {
                            exitSent = true;
                            PacketDistributor.sendToServer(new OdysseaNetwork.ShipAction(ship.getId(), OdysseaNetwork.Action.EXIT));
                        }
                    }
                }
            }
            case DONE -> {
                finish();
                LOGGER.info("[Odyssea ships] scénario terminé");
                return true;
            }
        }
        return false;
    }

    private static void next(Phase next) {
        LOGGER.info("[Odyssea ships] {} → {} (tick {})", phase, next, ticks);
        phase = next;
        phaseTicks = 0;
    }

    private static void fail(String reason) {
        LOGGER.error("[Odyssea ships] ÉCHEC en phase {} : {}", phase, reason);
        phase = Phase.DONE;
    }

    private static void finish() {
        ShipControls.testInput = null;
        ShipControls.testFlags = 0;
    }

    private static void log(@Nullable ShipEntity ship, String what) {
        if (ship != null) {
            LOGGER.info("[Odyssea ships] {} : pos ({}, {}, {}) vitesse {} m/s carburant {} dimension {}", what, (int) ship.getX(),
                    (int) ship.getY(), (int) ship.getZ(), (int) ship.speed(), (int) ship.fuel(), ship.level().dimension().location());
        }
    }

    private static ShipFlight.Input input(float thrust, float vertical, float strafe, boolean boost, Vec3 aim) {
        return new ShipFlight.Input(strafe, vertical, thrust, 0.0f, boost, new Vector3d(aim.x, aim.y, aim.z));
    }

    /** La caméra (troisième personne) regarde dans la direction visée. */
    private static void aim(Minecraft minecraft, @Nullable ShipEntity ship, Vec3 direction) {
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float) -Math.toDegrees(Math.asin(Math.clamp(direction.y, -1.0, 1.0)));
        look(minecraft, yaw, Math.clamp(pitch, -89.0f, 89.0f));
    }

    private static void look(Minecraft minecraft, float yaw, float pitch) {
        minecraft.player.setYRot(yaw);
        minecraft.player.setXRot(pitch);
        minecraft.player.yRotO = yaw;
        minecraft.player.xRotO = pitch;
    }

    private static void shoot(Minecraft minecraft, String name) {
        Screenshot.grab(minecraft.gameDirectory, "odyssea_ships_" + name + ".png", minecraft.getMainRenderTarget(),
                message -> LOGGER.info("[Odyssea ships] capture {}", name));
    }

    private static void server(Minecraft minecraft, Consumer<IntegratedServer> action) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            server.execute(() -> action.accept(server));
        }
    }

    private static void setup(Minecraft minecraft) {
        UUID id = minecraft.player.getUUID();
        server(minecraft, server -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            ServerLevel overworld = server.overworld();
            if (player == null) {
                return;
            }
            overworld.setDayTime(5000);
            player.setGameMode(GameType.CREATIVE);
            // Point de départ sur la terre ferme, en hauteur, pour voir l'horizon : relief estimé sans générer.
            var generator = overworld.getChunkSource().getGenerator();
            var random = overworld.getChunkSource().randomState();
            int bestX = 0;
            int bestZ = 0;
            int bestHeight = Integer.MIN_VALUE;
            for (int r = 0; r <= 1200; r += 48) {
                for (int i = 0; i < Math.max(1, r / 24); i++) {
                    double angle = i * Math.PI * 2.0 / Math.max(1, r / 24);
                    int x = (int) (Math.cos(angle) * r);
                    int z = (int) (Math.sin(angle) * r);
                    int h = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, overworld, random);
                    if (h > 72 && h < 190 && h > bestHeight) {
                        bestHeight = h;
                        bestX = x;
                        bestZ = z;
                    }
                }
            }
            startX = bestX;
            startZ = bestZ;
            overworld.getChunk(bestX >> 4, bestZ >> 4);
            overworld.getChunk((bestX + 40) >> 4, bestZ >> 4);
            int ground = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bestX, bestZ);
            player.teleportTo(overworld, bestX + 0.5, ground + 1.0, bestZ + 0.5, 0.0f, 0.0f);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(OdysseaItems.SPACE_HELMET.get()));
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(OdysseaItems.SPACE_SUIT.get()));
            player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(OdysseaItems.SPACE_LEGGINGS.get()));
            player.setItemSlot(EquipmentSlot.FEET, new ItemStack(OdysseaItems.SPACE_BOOTS.get()));
            LOGGER.info("[Odyssea ships] départ en ({}, {}, {})", bestX, ground + 1, bestZ);
            setupDone = true;
        });
    }

    private static void deployCutlass(IntegratedServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        ShipDesigns.Loaded cutlass = ShipDesigns.get(OdysseaCore.id("cutlass"));
        if (cutlass == null) {
            LOGGER.error("[Odyssea ships] modèle cutlass absent (datapack non chargé ?)");
            return;
        }
        ServerLevel level = player.serverLevel();
        int x = startX + 40;
        int z = startZ;
        level.getChunk(x >> 4, z >> 4);
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        ShipEntity ship = ShipEntity.create(level, cutlass, new Vec3(x, 0, z), -90.0f);
        ship.setPos(x, ground + ship.pivot().y + 1.0, z);
        level.addFreshEntity(ship);
        player.startRiding(ship, true);
        LOGGER.info("[Odyssea ships] Cutlass déployé ({} blocs) au sol y = {}", ship.structure().blockCount(), ground);
    }

    private static void spawnPirates(IntegratedServer server, UUID id) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        ShipDesigns.Loaded raider = ShipDesigns.get(OdysseaCore.id("raider"));
        if (player == null || raider == null || !(player.getVehicle() instanceof ShipEntity ship)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        Vec3 forward = ship.forward();
        for (int i = 0; i < 2; i++) {
            Vec3 side = new Vec3(-forward.z, 0, forward.x).scale(i == 0 ? 60 : -60);
            Vec3 pos = ship.position().add(forward.scale(260)).add(side);
            ShipEntity pirate = ShipEntity.create(level, raider, pos, ship.getYRot() + 180.0f);
            level.addFreshEntity(pirate);
        }
        LOGGER.info("[Odyssea ships] 2 pirates en approche");
    }

    private static void spawnDestroyer(IntegratedServer server, UUID id) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        ShipDesigns.Loaded destroyer = ShipDesigns.get(OdysseaCore.id("destroyer"));
        if (player == null || destroyer == null || !(player.getVehicle() instanceof ShipEntity ship)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        Vec3 forward = ship.forward();
        Vec3 pos = ship.position().add(forward.scale(420)).add(0, 40, 0);
        ShipEntity big = ShipEntity.create(level, destroyer, pos, -90.0f);
        level.addFreshEntity(big);
        LOGGER.info("[Odyssea ships] Destroyer déployé ({} blocs)", big.structure().blockCount());
    }

    static boolean isLevel(Level level, String path) {
        return level.dimension().location().getPath().equals(path);
    }
}
