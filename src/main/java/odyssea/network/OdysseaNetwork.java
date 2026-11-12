package odyssea.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import odyssea.OdysseaCore;
import odyssea.ship.ShipEntity;
import odyssea.space.Navigation;

/**
 * Paquets d'Odyssea. Le client du pilote a l'autorité sur le mouvement de son vaisseau (comme pour les bateaux
 * vanilla) ; le serveur garde celle des ressources (carburant, énergie, boucliers, coque), des armes et des sauts.
 */
public final class OdysseaNetwork {
    private OdysseaNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(PilotState.TYPE, PilotState.CODEC, OdysseaNetwork::handlePilotState);
        registrar.playToServer(ShipAction.TYPE, ShipAction.CODEC, OdysseaNetwork::handleShipAction);
        registrar.playToServer(ShipInteract.TYPE, ShipInteract.CODEC, OdysseaNetwork::handleShipInteract);
        registrar.playToServer(SetDestination.TYPE, SetDestination.CODEC, OdysseaNetwork::handleSetDestination);
        registrar.playToClient(ShipTeleport.TYPE, ShipTeleport.CODEC, (payload, context) -> ClientHandlers.teleport(payload));
        registrar.playToClient(Horizon.TYPE, Horizon.CODEC, (payload, context) -> ClientHandlers.horizon(payload));
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String name) {
        return new CustomPacketPayload.Type<>(OdysseaCore.id(name));
    }

    // ───────────────────────────── Pilote → serveur ─────────────────────────────

    /** État du vaisseau calculé par le client du pilote, envoyé à chaque tick. */
    public record PilotState(int ship, int teleportId, double x, double y, double z, float qx, float qy, float qz, float qw,
                             float vx, float vy, float vz, float thrust, float impact, byte flags) implements CustomPacketPayload {
        public static final byte FIRING = 1;
        public static final byte BOOST = 2;
        public static final byte FREE_LOOK = 4;
        public static final Type<PilotState> TYPE = payloadType("pilot_state");
        public static final StreamCodec<ByteBuf, PilotState> CODEC = StreamCodec.of((buf, p) -> {
            FriendlyByteBuf b = new FriendlyByteBuf(buf);
            b.writeVarInt(p.ship);
            b.writeVarInt(p.teleportId);
            b.writeDouble(p.x);
            b.writeDouble(p.y);
            b.writeDouble(p.z);
            b.writeFloat(p.qx);
            b.writeFloat(p.qy);
            b.writeFloat(p.qz);
            b.writeFloat(p.qw);
            b.writeFloat(p.vx);
            b.writeFloat(p.vy);
            b.writeFloat(p.vz);
            b.writeFloat(p.thrust);
            b.writeFloat(p.impact);
            b.writeByte(p.flags);
        }, buf -> {
            FriendlyByteBuf b = new FriendlyByteBuf(buf);
            return new PilotState(b.readVarInt(), b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble(),
                    b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(),
                    b.readFloat(), b.readFloat(), b.readByte());
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public enum Action {
        TOGGLE_ASSIST, CYCLE_POWER, QUANTUM, EXIT, FIRE_MISSILE, LOCK_TARGET, CARGO;

        private static final Action[] VALUES = values();

        public static Action byId(int id) {
            return VALUES[Math.floorMod(id, VALUES.length)];
        }
    }

    public record ShipAction(int ship, byte action) implements CustomPacketPayload {
        public static final Type<ShipAction> TYPE = payloadType("ship_action");
        public static final StreamCodec<ByteBuf, ShipAction> CODEC = StreamCodec.of((buf, p) -> {
            FriendlyByteBuf b = new FriendlyByteBuf(buf);
            b.writeVarInt(p.ship);
            b.writeByte(p.action);
        }, buf -> {
            FriendlyByteBuf b = new FriendlyByteBuf(buf);
            return new ShipAction(b.readVarInt(), b.readByte());
        });

        public ShipAction(int ship, Action action) {
            this(ship, (byte) action.ordinal());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Clic droit sur la coque, détecté par un lancer de rayon précis côté client. */
    public record ShipInteract(int ship, boolean mainHand) implements CustomPacketPayload {
        public static final Type<ShipInteract> TYPE = payloadType("ship_interact");
        public static final StreamCodec<ByteBuf, ShipInteract> CODEC = StreamCodec.of((buf, p) -> {
            FriendlyByteBuf b = new FriendlyByteBuf(buf);
            b.writeVarInt(p.ship);
            b.writeBoolean(p.mainHand);
        }, buf -> {
            FriendlyByteBuf b = new FriendlyByteBuf(buf);
            return new ShipInteract(b.readVarInt(), b.readBoolean());
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SetDestination(String body) implements CustomPacketPayload {
        public static final Type<SetDestination> TYPE = payloadType("set_destination");
        public static final StreamCodec<ByteBuf, SetDestination> CODEC = StreamCodec.of(
                (buf, p) -> new FriendlyByteBuf(buf).writeUtf(p.body, 64),
                buf -> new SetDestination(new FriendlyByteBuf(buf).readUtf(64)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ───────────────────────────── Serveur → client ─────────────────────────────

    /** Téléportation imposée par le serveur (fin de saut quantique) : le client du pilote réinitialise son vol. */
    public record ShipTeleport(int ship, int teleportId, double x, double y, double z, float qx, float qy, float qz, float qw,
                               float vx, float vy, float vz) implements CustomPacketPayload {
        public static final Type<ShipTeleport> TYPE = payloadType("ship_teleport");
        public static final StreamCodec<ByteBuf, ShipTeleport> CODEC = StreamCodec.of((buf, p) -> {
            FriendlyByteBuf b = new FriendlyByteBuf(buf);
            b.writeVarInt(p.ship);
            b.writeVarInt(p.teleportId);
            b.writeDouble(p.x);
            b.writeDouble(p.y);
            b.writeDouble(p.z);
            b.writeFloat(p.qx);
            b.writeFloat(p.qy);
            b.writeFloat(p.qz);
            b.writeFloat(p.qw);
            b.writeFloat(p.vx);
            b.writeFloat(p.vy);
            b.writeFloat(p.vz);
        }, buf -> {
            FriendlyByteBuf b = new FriendlyByteBuf(buf);
            return new ShipTeleport(b.readVarInt(), b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble(),
                    b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat());
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Horizon lointain autour du joueur : pour chaque azimut, l'élévation apparente du relief au-delà de la distance de
     * rendu et l'identifiant des biomes échantillonnés (anneaux de plus en plus lointains).
     */
    public record Horizon(float eyeY, float[] elevation, int[] biomes, float[] heights) implements CustomPacketPayload {
        public static final int AZIMUTHS = 32;
        public static final int RINGS = 4;
        public static final Type<Horizon> TYPE = payloadType("horizon");
        public static final StreamCodec<RegistryFriendlyByteBuf, Horizon> CODEC = StreamCodec.of((b, p) -> {
            b.writeFloat(p.eyeY);
            for (float value : p.elevation) {
                b.writeFloat(value);
            }
            for (int id : p.biomes) {
                b.writeVarInt(id);
            }
            for (float value : p.heights) {
                b.writeFloat(value);
            }
        }, b -> {
            float eye = b.readFloat();
            float[] elevation = new float[AZIMUTHS];
            for (int i = 0; i < elevation.length; i++) {
                elevation[i] = b.readFloat();
            }
            int[] biomes = new int[AZIMUTHS * RINGS];
            for (int i = 0; i < biomes.length; i++) {
                biomes[i] = b.readVarInt();
            }
            float[] heights = new float[AZIMUTHS * RINGS];
            for (int i = 0; i < heights.length; i++) {
                heights[i] = b.readFloat();
            }
            return new Horizon(eye, elevation, biomes, heights);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ───────────────────────────── Traitement serveur ─────────────────────────────

    private static ShipEntity ship(IPayloadContext context, int id) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return null;
        }
        Entity entity = player.serverLevel().getEntity(id);
        return entity instanceof ShipEntity ship ? ship : null;
    }

    private static void handlePilotState(PilotState payload, IPayloadContext context) {
        ShipEntity ship = ship(context, payload.ship());
        if (ship != null && context.player() instanceof ServerPlayer player) {
            ship.acceptPilotState(player, payload);
        }
    }

    private static void handleShipAction(ShipAction payload, IPayloadContext context) {
        ShipEntity ship = ship(context, payload.ship());
        if (ship != null && context.player() instanceof ServerPlayer player) {
            ship.handleAction(player, Action.byId(payload.action()));
        }
    }

    private static void handleShipInteract(ShipInteract payload, IPayloadContext context) {
        ShipEntity ship = ship(context, payload.ship());
        if (ship != null && context.player() instanceof ServerPlayer player
                && player.distanceToSqr(ship.closestHullPoint(player.getEyePosition())) < 10.0 * 10.0) {
            ship.interactFrom(player, payload.mainHand() ? net.minecraft.world.InteractionHand.MAIN_HAND
                    : net.minecraft.world.InteractionHand.OFF_HAND);
        }
    }

    private static void handleSetDestination(SetDestination payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            Navigation.setDestination(player, payload.body());
        }
    }
}
