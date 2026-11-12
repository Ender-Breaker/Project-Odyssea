package odyssea.client.ship;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import odyssea.client.map.SpaceMapScreen;
import odyssea.network.OdysseaNetwork;
import odyssea.ship.ShipEntity;
import odyssea.ship.ShipFlight;
import odyssea.ship.ShipRegistry;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

/**
 * Commandes du vaisseau. Les touches de déplacement vanilla deviennent les commandes de vol (avant/arrière, dérapage,
 * saut = monter, s'accroupir = descendre, sprint = postcombustion) ; la souris oriente le vaisseau, clic gauche
 * = canons, clic droit = missile. Touches propres (catégorie « Odyssea – Vaisseaux ») : roulis, assistance de vol,
 * répartition d'énergie, verrouillage de cible, saut quantique, regard libre, sortie, carte.
 */
public final class ShipControls {
    private static final String CATEGORY = "key.categories.odyssea";
    public static final KeyMapping ROLL_LEFT = key("roll_left", GLFW.GLFW_KEY_Z);
    public static final KeyMapping ROLL_RIGHT = key("roll_right", GLFW.GLFW_KEY_C);
    public static final KeyMapping FLIGHT_ASSIST = key("flight_assist", GLFW.GLFW_KEY_X);
    public static final KeyMapping POWER = key("power", GLFW.GLFW_KEY_G);
    public static final KeyMapping TARGET = key("target", GLFW.GLFW_KEY_R);
    public static final KeyMapping QUANTUM = key("quantum", GLFW.GLFW_KEY_J);
    public static final KeyMapping FREE_LOOK = key("free_look", GLFW.GLFW_KEY_LEFT_ALT);
    public static final KeyMapping EXIT = key("exit", GLFW.GLFW_KEY_V);
    public static final KeyMapping CARGO = key("cargo", GLFW.GLFW_KEY_B);
    public static final KeyMapping MAP = new KeyMapping("key.odyssea.map", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M, CATEGORY);

    /** Commandes imposées par le test automatique du client (null en jeu normal). */
    public static volatile ShipFlight.@Nullable Input testInput;
    public static volatile byte testFlags;

    private static @Nullable CameraType cameraBeforePiloting;
    private static boolean wasPiloting;
    private static @Nullable ShipEntity lastShip;
    private static float zoom = 1.0f;
    private static float lastFov = 70.0f;

    private ShipControls() {
    }

    private static KeyMapping key(String name, int key) {
        return new KeyMapping("key.odyssea." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, key, CATEGORY);
    }

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        for (KeyMapping mapping : new KeyMapping[]{ROLL_LEFT, ROLL_RIGHT, FLIGHT_ASSIST, POWER, TARGET, QUANTUM, FREE_LOOK, EXIT, CARGO, MAP}) {
            event.register(mapping);
        }
    }

    public static @Nullable ShipEntity currentShip() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.getVehicle() instanceof ShipEntity ship ? ship : null;
    }

    public static boolean isPiloting() {
        ShipEntity ship = currentShip();
        return ship != null && ship.isLocallyPiloted();
    }

    public static float fov() {
        return lastFov;
    }

    /**
     * Pendant un saut quantique, le vaisseau reste physiquement au départ ; le ciel est dessiné depuis le point du
     * trajet atteint, ce qui fait défiler planètes et Soleil. Renvoie ce décalage (nul hors saut).
     */
    public static Vec3 quantumOffset(float partialTick) {
        ShipEntity ship = currentShip();
        if (ship == null || !ship.isQuantum()) {
            return Vec3.ZERO;
        }
        return ship.quantumTo().subtract(ship.quantumFrom()).scale(ship.quantumProgress(partialTick));
    }

    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        ShipMeshCache.tick();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            ShipEntity.localPilotInput = ShipFlight.Input.NEUTRAL;
            return;
        }
        ShipEntity ship = currentShip();
        boolean piloting = ship != null && ship.pilot() == player;
        if (piloting && testInput != null) {
            ShipEntity.localPilotInput = testInput;
            ShipEntity.localPilotFlags = testFlags;
        } else if (piloting) {
            Options options = minecraft.options;
            boolean focused = minecraft.screen == null;
            float thrust = axis(focused && options.keyUp.isDown(), focused && options.keyDown.isDown());
            float strafe = axis(focused && options.keyRight.isDown(), focused && options.keyLeft.isDown());
            float vertical = axis(focused && options.keyJump.isDown(), focused && options.keyShift.isDown());
            float roll = axis(focused && ROLL_RIGHT.isDown(), focused && ROLL_LEFT.isDown());
            boolean boost = focused && options.keySprint.isDown();
            boolean freeLook = !focused || FREE_LOOK.isDown();
            Vec3 look = player.getViewVector(1.0f);
            ShipEntity.localPilotInput = new ShipFlight.Input(strafe, vertical, thrust, roll, boost,
                    freeLook ? null : new Vector3d(look.x, look.y, look.z));
            byte flags = 0;
            if (focused && options.keyAttack.isDown()) {
                flags |= OdysseaNetwork.PilotState.FIRING;
            }
            if (boost) {
                flags |= OdysseaNetwork.PilotState.BOOST;
            }
            ShipEntity.localPilotFlags = flags;
            while (FLIGHT_ASSIST.consumeClick()) {
                send(ship, OdysseaNetwork.Action.TOGGLE_ASSIST);
            }
            while (POWER.consumeClick()) {
                send(ship, OdysseaNetwork.Action.CYCLE_POWER);
            }
            while (TARGET.consumeClick()) {
                send(ship, OdysseaNetwork.Action.LOCK_TARGET);
            }
            while (QUANTUM.consumeClick()) {
                send(ship, OdysseaNetwork.Action.QUANTUM);
            }
        } else {
            ShipEntity.localPilotInput = ShipFlight.Input.NEUTRAL;
            ShipEntity.localPilotFlags = 0;
        }
        if (ship != null) {
            while (EXIT.consumeClick()) {
                send(ship, OdysseaNetwork.Action.EXIT);
            }
            while (CARGO.consumeClick()) {
                send(ship, OdysseaNetwork.Action.CARGO);
            }
        } else {
            while (EXIT.consumeClick()) {
                // rien à quitter
            }
            while (CARGO.consumeClick()) {
                // pas de soute à pied
            }
        }
        while (MAP.consumeClick()) {
            if (minecraft.screen == null) {
                minecraft.setScreen(new SpaceMapScreen());
            }
        }
        // Remplace l'invite vanilla « s'accroupir pour descendre » : à bord, s'accroupir fait descendre le vaisseau.
        if (ship != null && ship != lastShip) {
            minecraft.gui.setOverlayMessage(Component.translatable("odyssea.ship.exit_hint", EXIT.getTranslatedKeyMessage(),
                    MAP.getTranslatedKeyMessage()), false);
        }
        lastShip = ship;
        // Troisième personne à bord : caméra ancrée au centre du vaisseau.
        boolean detached = !minecraft.options.getCameraType().isFirstPerson();
        Entity desiredCamera = ship != null && detached ? ship : player;
        if (minecraft.getCameraEntity() != desiredCamera) {
            minecraft.setCameraEntity(desiredCamera);
        }
        ShipEntity.cameraViewer = ship != null ? player : null;
        if (piloting != wasPiloting) {
            if (piloting) {
                cameraBeforePiloting = minecraft.options.getCameraType();
                minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                zoom = 1.0f;
            } else if (cameraBeforePiloting != null) {
                minecraft.options.setCameraType(cameraBeforePiloting);
                cameraBeforePiloting = null;
            }
            wasPiloting = piloting;
        }
    }

    /**
     * Le client ne fait tiquer que les entités des tronçons déjà reçus. Le vaisseau qu'on pilote doit continuer de
     * voler même s'il devance l'arrivée des tronçons (haute vitesse, espace vierge) : on le fait tiquer nous-mêmes.
     */
    public static void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ShipEntity ship = currentShip();
        if (ship != null && minecraft.level != null && !minecraft.isPaused() && ship.isLocallyPiloted()
                && !ship.isRemoved() && !ship.tickedThisClientTick()) {
            minecraft.level.tickNonPassenger(ship);
        }
    }

    private static float axis(boolean positive, boolean negative) {
        return (positive ? 1.0f : 0.0f) - (negative ? 1.0f : 0.0f);
    }

    private static void send(ShipEntity ship, OdysseaNetwork.Action action) {
        PacketDistributor.sendToServer(new OdysseaNetwork.ShipAction(ship.getId(), action));
    }

    /** Clic gauche/droit aux commandes : armes. À pied : clic droit précis sur la coque pour embarquer ou ravitailler. */
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        ShipEntity ship = currentShip();
        if (ship != null && ship.pilot() == player) {
            if (event.isAttack()) {
                event.setCanceled(true);
                event.setSwingHand(false);
            } else if (event.isUseItem()) {
                event.setCanceled(true);
                event.setSwingHand(false);
                if (event.getHand() == InteractionHand.MAIN_HAND) {
                    send(ship, OdysseaNetwork.Action.FIRE_MISSILE);
                }
            }
            return;
        }
        if (!event.isUseItem() || player.isPassenger()) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(7.0));
        double blockDistance = Double.MAX_VALUE;
        HitResult hit = minecraft.hitResult;
        if (hit != null && hit.getType() != HitResult.Type.MISS) {
            blockDistance = hit.getLocation().distanceToSqr(eye);
        }
        ShipEntity best = null;
        double bestDistance = blockDistance;
        for (ShipEntity candidate : ShipRegistry.ships(minecraft.level)) {
            if (!candidate.getBoundingBox().inflate(7.0).contains(eye)) {
                continue;
            }
            Vec3 point = candidate.raycastHull(eye, end);
            if (point != null && point.distanceToSqr(eye) < bestDistance) {
                bestDistance = point.distanceToSqr(eye);
                best = candidate;
            }
        }
        if (best != null && !best.isHostile()) {
            event.setCanceled(true);
            event.setSwingHand(true);
            PacketDistributor.sendToServer(new OdysseaNetwork.ShipInteract(best.getId(), event.getHand() == InteractionHand.MAIN_HAND));
        }
    }

    /** Molette aux commandes : recul de la caméra au lieu de changer d'objet. */
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (currentShip() != null && Minecraft.getInstance().screen == null) {
            zoom = Math.clamp(zoom * (event.getScrollDeltaY() > 0 ? 0.85f : 1.18f), 0.35f, 3.0f);
            event.setCanceled(true);
        }
    }

    public static void onCameraDistance(CalculateDetachedCameraDistanceEvent event) {
        Entity entity = event.getCamera().getEntity();
        ShipEntity ship = entity instanceof ShipEntity s ? s : entity != null && entity.getVehicle() instanceof ShipEntity s ? s : null;
        if (ship != null && ship.design() != null) {
            event.setDistance(ship.design().cameraDistance() * zoom);
        }
    }

    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        lastFov = (float) event.getFOV();
    }
}
