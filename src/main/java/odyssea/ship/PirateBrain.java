package odyssea.ship;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * Pilote pirate : choisit le vaisseau habité le plus proche, l'approche, tourne autour en mitraillant avec une
 * visée anticipée (avance sur la cible), tire un missile de temps en temps et rompt le combat quand sa coque faiblit.
 * Sans proie pendant une minute, il quitte la zone (disparaît).
 */
final class PirateBrain {
    private static final double ENGAGE_RANGE = 1600.0;

    private @Nullable UUID targetId;
    private @Nullable Vec3 aimPoint;
    private boolean fire;
    private int idleTicks;
    private int strafeTimer;
    private float strafe = 0.6f;
    private int missileTimer = 200;

    @Nullable Vec3 aimPoint() {
        return aimPoint;
    }

    boolean wantsFire() {
        return fire;
    }

    void onAttacked(Entity attacker) {
        Entity root = attacker.getRootVehicle();
        if (root instanceof ShipEntity ship && !ship.isHostile()) {
            targetId = ship.getUUID();
        }
    }

    ShipFlight.Input think(ShipEntity self, ServerLevel level) {
        fire = false;
        ShipEntity target = targetId == null ? null : level.getEntity(targetId) instanceof ShipEntity s && s.isAlive() ? s : null;
        if (target == null || self.tickCount % 40 == 0) {
            target = findTarget(self, level, target);
            targetId = target == null ? null : target.getUUID();
            self.setTargetFromAi(target);
        }
        if (target == null) {
            aimPoint = null;
            if (++idleTicks > 1200) {
                self.discard();
            }
            return ShipFlight.Input.NEUTRAL;
        }
        idleTicks = 0;
        ShipDesign design = self.design();
        if (design == null) {
            return ShipFlight.Input.NEUTRAL;
        }
        Vec3 here = self.position();
        Vec3 there = target.getBoundingBox().getCenter();
        double distance = here.distanceTo(there);
        double boltSpeed = design.stats().boltSpeed();
        Vec3 lead = there.add(target.velocity().subtract(self.velocity()).scale(distance / boltSpeed));
        aimPoint = lead;
        Vec3 aim = lead.subtract(here).normalize();

        boolean fleeing = self.hull() < design.stats().hull() * 0.25f;
        if (fleeing) {
            Vec3 away = here.subtract(there).normalize();
            return new ShipFlight.Input(0, 0.2f, 1.0f, 0, true, new Vector3d(away.x, away.y, away.z));
        }
        if (--strafeTimer <= 0) {
            strafeTimer = 50 + self.getRandom().nextInt(60);
            strafe = -strafe;
        }
        float thrust;
        boolean boost = false;
        if (distance > 600.0) {
            thrust = 1.0f;
            boost = true;
        } else if (distance > 260.0) {
            thrust = 1.0f;
        } else if (distance < 90.0) {
            thrust = -0.7f;
        } else {
            thrust = 0.35f;
        }
        Vec3 forward = self.forward();
        double angle = Math.acos(Math.clamp(forward.dot(aim), -1.0, 1.0));
        fire = angle < Math.toRadians(6.0) && distance < 450.0;
        if (--missileTimer <= 0 && distance < 500.0 && angle < Math.toRadians(20.0)) {
            missileTimer = 240 + self.getRandom().nextInt(200);
            self.setTargetFromAi(target);
            self.fireMissile(null);
        }
        float vertical = (float) Math.clamp((there.y - here.y) / 60.0, -0.4, 0.4);
        return new ShipFlight.Input(distance < 320.0 ? strafe : 0.0f, vertical, thrust, 0, boost, new Vector3d(aim.x, aim.y, aim.z));
    }

    private static @Nullable ShipEntity findTarget(ShipEntity self, ServerLevel level, @Nullable ShipEntity current) {
        ShipEntity best = current;
        double bestDistance = current == null ? ENGAGE_RANGE * ENGAGE_RANGE : current.distanceToSqr(self) * 0.8;
        for (Entity entity : level.getEntities(self, self.getBoundingBox().inflate(ENGAGE_RANGE),
                e -> e instanceof ShipEntity ship && !ship.isHostile() && ship.isAlive() && !ship.getPassengers().isEmpty())) {
            double d = entity.distanceToSqr(self);
            if (d < bestDistance) {
                bestDistance = d;
                best = (ShipEntity) entity;
            }
        }
        return best;
    }
}
