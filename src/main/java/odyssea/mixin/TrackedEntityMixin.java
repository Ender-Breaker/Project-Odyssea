package odyssea.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import odyssea.ship.ShipEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Le serveur ne montre une entité à un joueur que si le tronçon où elle se trouve lui a déjà été envoyé. Un vaisseau
 * lancé à plusieurs centaines de m/s devance l'envoi des tronçons (surtout à travers l'espace encore vierge) : il
 * disparaissait alors du client, et son pilote se retrouvait sans véhicule. Le vaisseau qu'un joueur occupe reste
 * donc toujours suivi par ce joueur.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {
    @Shadow
    @Final
    Entity entity;

    @WrapOperation(method = "updatePlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;isChunkTracked(Lnet/minecraft/server/level/ServerPlayer;II)Z"))
    private boolean odyssea$keepOwnShip(ChunkMap map, ServerPlayer player, int x, int z, Operation<Boolean> original) {
        if (entity instanceof ShipEntity && player.getRootVehicle() == entity) {
            return true;
        }
        return original.call(map, player, x, z);
    }
}
