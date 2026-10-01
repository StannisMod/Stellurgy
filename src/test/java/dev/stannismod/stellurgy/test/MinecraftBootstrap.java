package dev.stannismod.stellurgy.test;

import net.minecraft.init.Bootstrap;
import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.common.CommonProxy;
import dev.stannismod.stellurgy.dimension.DimensionManager;

import java.lang.reflect.Field;

/**
 * Idempotent helper that initializes:
 *   1. vanilla Minecraft static registries via {@link Bootstrap#register()};
 *   2. Stellurgy's {@code @SidedProxy} field with a plain {@link CommonProxy} instance, and its
 *      {@code @Instance} field with a mod object, as Forge would;
 *   3. one server lifetime on that mod object, opened by the same method the server-start hook
 *      calls, so {@code DimensionManager.getInstance()} answers with the "server's" galaxy.
 *
 * Mod-specific registries (Stellurgy blocks, items, tile entities, packets) and the Forge
 * lifecycle (preInit/init/postInit) are NOT initialized — those require a real
 * Forge mod loader and belong in headless scenario tests under
 * {@code src/test/java/dev/stannismod/stellurgy/test/scenario/}.
 *
 * <p>Usage:</p>
 * <pre>{@code
 *   @BeforeClass public static void bootstrap() { MinecraftBootstrap.ensure(); }
 * }</pre>
 *
 * <p>Calling {@link Bootstrap#register()} multiple times is harmless because MC
 * implementation guards with a flag, but we also de-duplicate here to keep the
 * intent obvious in tests.</p>
 */
public final class MinecraftBootstrap {

    private static volatile boolean done = false;

    private MinecraftBootstrap() {}

    public static void ensure() {
        if (done) return;
        synchronized (MinecraftBootstrap.class) {
            if (done) return;

            // 1. Vanilla MC registries.
            Bootstrap.register();

            // 2. Stellurgy proxy. Stellurgy.proxy is null in tests because
            // @SidedProxy is wired by the Forge classloader. Inject a plain
            // CommonProxy so anything that calls Stellurgy.proxy.getXxx()
            // (most notably DimensionProperties.readFromNBT -> DimensionManager
            // .getInstance()) has a working dispatch target.
            try {
                Field proxyField = Stellurgy.class.getDeclaredField("proxy");
                proxyField.setAccessible(true);
                if (proxyField.get(null) == null) {
                    proxyField.set(null, new CommonProxy());
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "Failed to inject CommonProxy into Stellurgy.proxy — "
                                + "Stellurgy test bootstrap broken; check field visibility.",
                        e);
            }

            // 2b. LibVulpes proxy. Satellite getName() (and other display-name
            // paths) dispatch through LibVulpes.proxy.getLocalizedString(). The
            // field is null in tests (wired by the Forge classloader in prod), so
            // inject a plain proxy. Headless it returns the translation KEY, which
            // is enough for non-null / distinct-name contracts.
            if (dev.stannismod.stellurgy.libvulpes.LibVulpes.proxy == null) {
                dev.stannismod.stellurgy.libvulpes.LibVulpes.proxy = new dev.stannismod.stellurgy.libvulpes.common.CommonProxy();
            }

            // 2c. The built-in atmospheres, which the mod's pre-init registers.
            dev.stannismod.stellurgy.atmosphere.AtmosphereType.registerBuiltIns();

            // 3. The mod object and one server lifetime on it.
            if (Stellurgy.instance == null) {
                Stellurgy.instance = new Stellurgy();
            }
            Stellurgy.instance.beginServerLifetime();
            registerSol();

            done = true;
        }
    }

    /**
     * Ends the open server lifetime and begins the next, as a server stop and a server start do.
     * The new galaxy is set up the way {@link #ensure()} sets up the first.
     */
    public static void restartServerLifetime() {
        ensure();
        Stellurgy.instance.endServerLifetime();
        Stellurgy.instance.beginServerLifetime();
        registerSol();
    }

    // A deterministic "Sol" star with id=0, so that DimensionProperties.readFromNBT can resolve
    // DimensionManager.getInstance().getStar(0). This mirrors the production world-load path, where
    // Sol is the first star registered.
    private static void registerSol() {
        StellarBody sol = new StellarBody();
        sol.setId(0);
        sol.setName("Sol");
        sol.setTemperature(100);
        DimensionManager.getInstance().addStar(sol);
    }
}
