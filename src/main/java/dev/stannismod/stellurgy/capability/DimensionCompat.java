package dev.stannismod.stellurgy.capability;

import dev.stannismod.stellurgy.Stellurgy;

public class DimensionCompat {

    private static final String JED_CONFIGS = "fi.dy.masa.justenoughdimensions.config.Configs";

    /**
     * The dimension a player respawns in by default: Just Enough Dimensions' initial-spawn override
     * when that mod is installed and the override is on, the overworld otherwise. Asked on respawn
     * only, so JED's config is read where it is needed instead of being cached at a class load that
     * happens mid-game.
     */
    public static int getDefaultSpawnDimension() {
        Class<?> configs;
        try {
            configs = Class.forName(JED_CONFIGS);
        } catch (ClassNotFoundException absent) {
            return 0;
        }
        try {
            if ((boolean) configs.getDeclaredField("enableInitialSpawnDimensionOverride").get(null)) {
                return (int) configs.getDeclaredField("initialSpawnDimensionId").get(null);
            }
            return 0;
        } catch (ReflectiveOperationException | ClassCastException e) {
            Stellurgy.logger.warn("Just Enough Dimensions is installed but its initial-spawn settings"
                    + " could not be read; respawning in the overworld instead of its override", e);
            return 0;
        }
    }

}
