package zmaster587.advancedRocketry.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.World;

import zmaster587.advancedRocketry.test.trace.TestTrace;
import zmaster587.advancedRocketry.world.provider.WorldProviderPlanet;

/**
 * A PLANET'S SKY CHANGED — the tick the custom weather cycle began or ended rain in one dimension.
 *
 * <h2>Why the cycle's own tick and not the setter</h2>
 *
 * <p>The state itself lives in {@code PlanetWeatherState}, reached through
 * {@code ARDimensionWorldInfo}, and neither knows which dimension it belongs to — the wrapper holds
 * a delegate and a state and deliberately no world reference, because one would leak the whole
 * dimension. A record of "it started raining" that cannot say WHERE is no use to a test whose
 * subject is that one planet rains and its neighbours do not. {@code WorldProviderPlanet} is the
 * cycle, it runs per dimension, and it has its world in hand.</p>
 *
 * <h2>Edge only</h2>
 *
 * <p>{@code updateWeather} runs every tick of every AR planet, so only a CHANGE is recorded: the
 * state is sampled at the method's RETURN and compared with what this provider last announced. One
 * record per transition, per dimension. The first tick of a dimension announces its state whatever
 * it is, because "it was already raining when the world came up" and "it started raining" are
 * different facts and a reader must not have to guess which it is looking at.</p>
 *
 * <h2>Side and silence</h2>
 *
 * <p>Routed by the provider's own world, so a client-side provider — which the cycle returns out of
 * before touching anything — would file in the client log rather than be dropped. SILENT about:
 * thunder (a separate marker, and no test links on it yet), the vanilla fallback path (the cycle
 * returns early when the config disables per-dimension weather, and nothing is sampled), rain
 * TIMERS, and a weather change made by a command or a Weather Controller satellite — those write
 * through the same info object but not through this method, so the next cycle tick is what
 * announces them.</p>
 */
@Mixin(WorldProviderPlanet.class)
public abstract class MixinPlanetWeatherCycleEvents {

    private static final String INSTRUMENT = "planet_weather_cycle_events";

    /** What this provider last announced: {@code null} until its first tick. */
    @Unique
    private Boolean arTest$announcedRaining = null;

    @Inject(method = "updateWeather", at = @At("RETURN"))
    private void arTest$weatherCycled(CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        World world = ((WorldProviderPlanet) (Object) this).world;
        if (world == null || world.getWorldInfo() == null) {
            return;
        }
        boolean raining = world.getWorldInfo().isRaining();
        if (arTest$announcedRaining != null && arTest$announcedRaining == raining) {
            return; // EDGE-ONLY: this runs every tick of every planet
        }
        boolean first = arTest$announcedRaining == null;
        arTest$announcedRaining = raining;
        TestTrace.record(world, "planet_weather_changed",
                "\"dim\":" + world.provider.getDimension()
                        + ",\"raining\":" + raining
                        + ",\"thundering\":" + world.getWorldInfo().isThundering()
                        + ",\"first\":" + first);
    }
}
