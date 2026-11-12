package odyssea.ship;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/**
 * Vaisseaux présents dans chaque monde. Les recherches d'entités vanilla se fient à la position de l'entité (son
 * centre) : un vaisseau de 300 blocs ne serait pas trouvé par une recherche locale près de sa proue. Ce registre
 * permet de tester directement la coque de chaque vaisseau (tirs, clic droit).
 */
public final class ShipRegistry {
    private static final Map<Level, Set<ShipEntity>> SHIPS = Collections.synchronizedMap(new WeakHashMap<>());

    private ShipRegistry() {
    }

    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof ShipEntity ship) {
            SHIPS.computeIfAbsent(event.getLevel(), level -> Collections.newSetFromMap(new WeakHashMap<>())).add(ship);
        }
    }

    public static void onLeave(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof ShipEntity ship) {
            Set<ShipEntity> set = SHIPS.get(event.getLevel());
            if (set != null) {
                set.remove(ship);
            }
        }
    }

    /** Copie de la liste des vaisseaux vivants du monde. */
    public static java.util.List<ShipEntity> ships(Level level) {
        Set<ShipEntity> set = SHIPS.get(level);
        if (set == null) {
            return java.util.List.of();
        }
        synchronized (SHIPS) {
            return set.stream().filter(ship -> !ship.isRemoved()).toList();
        }
    }
}
