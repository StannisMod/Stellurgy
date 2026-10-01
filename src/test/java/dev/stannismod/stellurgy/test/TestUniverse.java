package dev.stannismod.stellurgy.test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.universe.IGalaxyGenerator;
import dev.stannismod.stellurgy.universe.UniverseRegistry;

/**
 * The universe ONE test arranges: the generator and the star lookup it wants in force, applied to
 * every registry it builds through {@link #newRegistry()} — the ones built before an arrangement as
 * well as after it, the way a whole save has one model. A test holds its own instance, so nothing
 * it arranges reaches another test.
 */
public final class TestUniverse {

    private final List<UniverseRegistry> registries = new ArrayList<>();
    private IGalaxyGenerator generator;
    private IntFunction<StellarBody> starLookup;

    /** A registry under this test's arrangement. */
    public UniverseRegistry newRegistry() {
        UniverseRegistry registry = new UniverseRegistry();
        if (generator != null) {
            registry.attachGenerator(generator);
        }
        if (starLookup != null) {
            registry.setStarLookup(starLookup);
        }
        registries.add(registry);
        return registry;
    }

    public void attachGenerator(IGalaxyGenerator g) {
        generator = g;
        for (UniverseRegistry registry : registries) {
            registry.attachGenerator(g);
        }
    }

    /** Back to the shipped default: void between authored anchors. */
    public void detachGenerator() {
        generator = null;
        for (UniverseRegistry registry : registries) {
            registry.detachGenerator();
        }
    }

    /** {@code null}: the catalogue, as production resolves stars. */
    public void setStarLookup(IntFunction<StellarBody> lookup) {
        starLookup = lookup;
        for (UniverseRegistry registry : registries) {
            registry.setStarLookup(lookup);
        }
    }
}
