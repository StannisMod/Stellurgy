package dev.stannismod.stellurgy.universe;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import dev.stannismod.stellurgy.space.GalacticCoord;

/**
 * Void space everywhere: the answer to a pack's {@code <galaxyGen procedural="false"/>}, and what a
 * registry holds before a world model is in force. Trivially deterministic — no seed or RNG is
 * consulted, so {@code (seed, coord)} always resolves to empty.
 */
public final class EmptyGalaxyGenerator implements IGalaxyGenerator {

    @Override
    public Optional<PlanetarySystem> systemAt(long seed, GalacticCoord coord) {
        return Optional.empty();
    }

    @Override
    public Map<GalacticCoord, PlanetarySystem> systemsInRegion(long seed, GalacticCoord min, GalacticCoord max) {
        return Collections.emptyMap();
    }
}
