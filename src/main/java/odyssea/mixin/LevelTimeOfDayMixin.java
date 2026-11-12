package odyssea.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import odyssea.dimension.Planets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Durée du jour propre à chaque planète. {@code getTimeOfDay} est une méthode par défaut de
 * {@code LevelTimeAccess} ; on l'ajoute à {@link Level}, qui l'emporte donc sur l'interface. Tout ce qui en dérive
 * suit : course du Soleil, obscurcissement du ciel, éclairage des blocs, apparition des monstres, lits.
 */
@Mixin(Level.class)
public abstract class LevelTimeOfDayMixin {
    @Shadow
    public abstract ResourceKey<Level> dimension();

    @Shadow
    public abstract DimensionType dimensionType();

    @Shadow
    public abstract long getDayTime();

    public float getTimeOfDay(float partialTick) {
        DimensionType type = dimensionType();
        Planets.Planet planet = Planets.get(dimension());
        if (planet == null || planet.solarDay() == 1.0 || type.hasFixedTime()) {
            return type.timeOfDay(getDayTime());
        }
        return planet.timeOfDay(getDayTime());
    }
}
