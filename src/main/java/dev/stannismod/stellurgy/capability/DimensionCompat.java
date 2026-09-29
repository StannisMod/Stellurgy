package dev.stannismod.stellurgy.capability;

import dev.stannismod.stellurgy.Stellurgy;

import java.lang.reflect.Field;

public class DimensionCompat {

    static Field JEDSpawnID, JEDEnableOverride;

    static {
        try {
            JEDSpawnID = Class.forName("fi.dy.masa.justenoughdimensions.config.Configs").getDeclaredField("initialSpawnDimensionId");
            JEDEnableOverride = Class.forName("fi.dy.masa.justenoughdimensions.config.Configs").getDeclaredField("enableInitialSpawnDimensionOverride");
            Stellurgy.logger.info("JED Found, compat loaded");
        } catch (Exception e) {
            Stellurgy.logger.info("JED compat not loaded");
            JEDSpawnID = null;
            JEDEnableOverride = null;
        }
    }

    public static int getDefaultSpawnDimension() {
        try {
            if (JEDSpawnID != null && JEDEnableOverride != null && (boolean) JEDEnableOverride.get(null)) {

                return (int) JEDSpawnID.get(null);

            }
        } catch (Exception e) {
            //No nonsense
            return 0;
        }


        return 0;
    }

}
