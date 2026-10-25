package dev.stannismod.stellurgy.world;

import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.space.SpaceSubsystem;
import net.minecraft.world.World;

import java.util.OptionalLong;

/**
 * A world's time of day as its body's turn makes it: the local solar time of the body at the space clock
 * ({@link DimensionProperties#solarDay()}), on either side — the server's clock on the server, the synced one
 * on a client.
 *
 * <p>A world is a body when it is the overworld or a planet ({@link TimeSkipPolicy#isPlanet}); a station, an
 * asteroid field, the Nether and another mod's world are not, and keep the time vanilla keeps for them. So
 * does a body this side has no properties for: it answers empty and the caller keeps vanilla's counter.</p>
 *
 * <p>A world has one time of day, read at its meridian 0 (block x = 0), by the sky, the light and every
 * daylight decision alike. Only the space model places a block at its own longitude, so a block far east of
 * meridian 0 is that many ticks ahead there — {@code solarDay · x / (2πR)}, a few dozen at ten thousand
 * blocks on the smallest body.</p>
 */
public final class TimeOfDay {

    private TimeOfDay() {
    }

    /** The time of day of {@code world}, in vanilla world-time ticks, or empty when it is no body. */
    public static OptionalLong of(World world) {
        DimensionProperties props = bodyOf(world);
        return props == null ? OptionalLong.empty()
                : OptionalLong.of(props.solarDay().timeOfDayAt(SpaceSubsystem.spaceClock(), 0d));
    }

    /**
     * Space ticks from now to {@code world}'s next dawn — strictly after now — or empty for a world with no
     * dawn: one that is no body, or a body locked to its star, whose time of day stands still.
     */
    public static OptionalLong ticksToDawn(World world) {
        DimensionProperties props = bodyOf(world);
        if (props == null || props.isTidallyLocked()) {
            return OptionalLong.empty();
        }
        long day = props.rotationalPeriod;
        long now = props.solarDay().timeOfDayAt(SpaceSubsystem.spaceClock(), 0d);
        return OptionalLong.of(day - Math.floorMod(now, day));
    }

    /**
     * Space ticks from now to the next moment {@code world} reads {@code timeOfDay} (taken within its day) — zero
     * when it reads it now — or empty for a world whose time of day does not move: no body, or locked to its star.
     */
    public static OptionalLong ticksUntil(World world, long timeOfDay) {
        DimensionProperties props = bodyOf(world);
        if (props == null || props.isTidallyLocked()) {
            return OptionalLong.empty();
        }
        long day = props.rotationalPeriod;
        long now = props.solarDay().timeOfDayAt(SpaceSubsystem.spaceClock(), 0d);
        return OptionalLong.of(Math.floorMod(timeOfDay - now, day));
    }

    /** Whether {@code world} is a body whose time of day this class answers. */
    public static boolean isBody(World world) {
        return bodyOf(world) != null;
    }

    private static DimensionProperties bodyOf(World world) {
        if (world == null || world.provider == null) {
            return null;
        }
        int dim = world.provider.getDimension();
        if (dim != 0 && !TimeSkipPolicy.isPlanet(world.provider)) {
            return null;
        }
        DimensionProperties props = DimensionManager.getInstance().getDimensionPropertiesOrNull(dim);
        return props == null || props.rotationalPeriod <= 0 ? null : props;
    }
}
