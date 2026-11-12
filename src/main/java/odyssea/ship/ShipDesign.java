package odyssea.ship;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;

/**
 * Caractéristiques d'un modèle de vaisseau, chargées depuis {@code data/<ns>/ships/<id>.json}. Les points (sièges,
 * tuyères, canons) sont exprimés dans la grille de la structure après rotation : l'avant du vaisseau est +Z.
 *
 * @param seats  le premier est le siège du pilote
 * @param engines tuyères (flammes et traînées), dirigées vers −Z
 */
public record ShipDesign(ResourceLocation id, Stats stats, List<Vec3> seats, List<Vec3> engines, List<Vec3> guns,
                         List<Vec3> missiles, float cameraDistance, boolean hostile) {

    /**
     * Grandeurs de jeu. Vitesses en m/s (1 bloc = 1 m), accélérations en m/s², vitesses angulaires en rad/s,
     * temps de recharge en ticks.
     */
    public record Stats(float hull, float shield, float shieldRegen, float shieldDelay,
                        float energy, float energyRegen, float fuel, float fuelBurn, float quantumFuel,
                        float maxSpeed, float boostSpeed, float accelForward, float accelBack, float accelStrafe,
                        float accelVertical, float turnRate, float rollRate, float angularAccel,
                        float gunDamage, int gunCooldown, float gunEnergy, float boltSpeed, int boltColor,
                        float missileDamage, float missileExplosion, int missileCapacity, int missileCooldown) {

        static Stats parse(JsonObject o) {
            return new Stats(
                    f(o, "hull", 2000), f(o, "shield", 1000), f(o, "shield_regen", 60), f(o, "shield_delay", 4),
                    f(o, "energy", 1000), f(o, "energy_regen", 100), f(o, "fuel", 1000), f(o, "fuel_burn", 2.5f),
                    f(o, "quantum_fuel", 100),
                    f(o, "max_speed", 200), f(o, "boost_speed", 360), f(o, "accel_forward", 30), f(o, "accel_back", 18),
                    f(o, "accel_strafe", 16), f(o, "accel_vertical", 20),
                    f(o, "turn_rate", 1.0f), f(o, "roll_rate", 1.4f), f(o, "angular_accel", 2.5f),
                    f(o, "gun_damage", 8), GsonHelper.getAsInt(o, "gun_cooldown", 4), f(o, "gun_energy", 10),
                    f(o, "bolt_speed", 320), Integer.parseUnsignedInt(GsonHelper.getAsString(o, "bolt_color", "FF4030"), 16),
                    f(o, "missile_damage", 30), f(o, "missile_explosion", 2.5f), GsonHelper.getAsInt(o, "missiles", 8),
                    GsonHelper.getAsInt(o, "missile_cooldown", 30));
        }

        private static float f(JsonObject o, String key, float fallback) {
            return GsonHelper.getAsFloat(o, key, fallback);
        }

        void write(FriendlyByteBuf buf) {
            float[] values = {hull, shield, shieldRegen, shieldDelay, energy, energyRegen, fuel, fuelBurn, quantumFuel,
                    maxSpeed, boostSpeed, accelForward, accelBack, accelStrafe, accelVertical, turnRate, rollRate,
                    angularAccel, gunDamage, gunEnergy, boltSpeed, missileDamage, missileExplosion};
            for (float value : values) {
                buf.writeFloat(value);
            }
            buf.writeVarInt(gunCooldown);
            buf.writeInt(boltColor);
            buf.writeVarInt(missileCapacity);
            buf.writeVarInt(missileCooldown);
        }

        static Stats read(FriendlyByteBuf buf) {
            float[] v = new float[23];
            for (int i = 0; i < v.length; i++) {
                v[i] = buf.readFloat();
            }
            int gunCooldown = buf.readVarInt();
            int boltColor = buf.readInt();
            int missileCapacity = buf.readVarInt();
            int missileCooldown = buf.readVarInt();
            return new Stats(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13],
                    v[14], v[15], v[16], v[17], v[18], gunCooldown, v[19], v[20], boltColor, v[21], v[22],
                    missileCapacity, missileCooldown);
        }
    }

    public String translationKey() {
        return "ship." + id.getNamespace() + "." + id.getPath();
    }

    public Vec3 pilotSeat() {
        return seats.isEmpty() ? Vec3.ZERO : seats.getFirst();
    }

    // ───────────────────────────── JSON ─────────────────────────────

    /** Rotation qui amène l'avant déclaré du schéma ({@code forward}) sur +Z. */
    static Rotation rotationFor(String forward) {
        return switch (forward) {
            case "+x", "east" -> Rotation.CLOCKWISE_90;
            case "-x", "west" -> Rotation.COUNTERCLOCKWISE_90;
            case "-z", "north" -> Rotation.CLOCKWISE_180;
            default -> Rotation.NONE;
        };
    }

    /**
     * @param raw    structure telle que lue (avant rotation)
     * @param offset décalage du recadrage, dans la grille tournée
     */
    static ShipDesign parse(ResourceLocation id, JsonObject json, ShipStructure raw, Rotation rotation, int[] offset) {
        Stats stats = Stats.parse(GsonHelper.getAsJsonObject(json, "stats", new JsonObject()));
        return new ShipDesign(id, stats,
                points(json, "seats", raw, rotation, offset),
                points(json, "engines", raw, rotation, offset),
                points(json, "guns", raw, rotation, offset),
                points(json, "missiles", raw, rotation, offset),
                GsonHelper.getAsFloat(json, "camera_distance", 30.0f),
                GsonHelper.getAsBoolean(json, "hostile", false));
    }

    private static List<Vec3> points(JsonObject json, String key, ShipStructure raw, Rotation rotation, int[] offset) {
        List<Vec3> points = new ArrayList<>();
        JsonArray array = GsonHelper.getAsJsonArray(json, key, new JsonArray());
        for (JsonElement element : array) {
            JsonArray p = element.getAsJsonArray();
            double x = p.get(0).getAsDouble();
            double y = p.get(1).getAsDouble();
            double z = p.get(2).getAsDouble();
            double[] r = raw.rotatePoint(x, z, rotation);
            points.add(new Vec3(r[0] - offset[0], y - offset[1], r[1] - offset[2]));
        }
        return List.copyOf(points);
    }

    // ───────────────────────────── Réseau ─────────────────────────────

    public void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(id);
        stats.write(buf);
        writePoints(buf, seats);
        writePoints(buf, engines);
        writePoints(buf, guns);
        writePoints(buf, missiles);
        buf.writeFloat(cameraDistance);
        buf.writeBoolean(hostile);
    }

    public static ShipDesign read(FriendlyByteBuf buf) {
        ResourceLocation id = buf.readResourceLocation();
        Stats stats = Stats.read(buf);
        return new ShipDesign(id, stats, readPoints(buf), readPoints(buf), readPoints(buf), readPoints(buf),
                buf.readFloat(), buf.readBoolean());
    }

    private static void writePoints(FriendlyByteBuf buf, List<Vec3> points) {
        buf.writeVarInt(points.size());
        for (Vec3 p : points) {
            buf.writeFloat((float) p.x);
            buf.writeFloat((float) p.y);
            buf.writeFloat((float) p.z);
        }
    }

    private static List<Vec3> readPoints(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Vec3> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat()));
        }
        return List.copyOf(points);
    }
}
