package dev.stannismod.stellurgy.universe;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.stannismod.stellurgy.space.GalacticCoord;

/**
 * The addon-replaceable galaxy-generation seam (universe-model.md &sect;3). Given the world seed and an
 * absolute {@link GalacticCoord}, it answers "is there a procedural system here, and what" plus a region
 * enumeration used as the telescope-scan backend.
 *
 * <p>Both methods MUST be a pure, deterministic function of {@code (seed, coord)} — the same arguments
 * always yield the same system — so a scan and a later jump agree, and a re-materialised cell regenerates
 * identically. The registry supplies the RAW world seed (it does not pre-mix per cell), leaving the mixing
 * policy to the generator so a clustered sampler can correlate neighbouring cells.</p>
 *
 * <p>Two implementations ship: {@link ClusteredGalaxyGenerator}, which {@code <galaxyGen>} configures
 * and which a pack that states none is given with the shipped configuration, and
 * {@link EmptyGalaxyGenerator}, the void between authored anchors that {@code procedural="false"} asks
 * for. Which one answers a configuration is the world-model version's choice. An addon installs its own
 * via {@link UniverseRegistry#attachGenerator}.</p>
 *
 * <p><b>A generator is never told where the AUTHORED systems stand</b>, and seats its field as if they
 * did not exist. Keeping procedural systems out of an authored neighbourhood is the
 * registry's job: it asks {@link #neighbourhoodOf} of every seat it is handed and drops the ones that
 * reach an authored neighbourhood, so the derivation stays a function of {@code (seed, config)}.</p>
 */
public interface IGalaxyGenerator {

    /**
     * @param seed  the world seed (the generator owns any per-cell mixing)
     * @param coord an absolute galactic coordinate; implementations should treat it at cell granularity
     * @return the procedural system at {@code coord}'s cell, or empty for void space
     */
    Optional<PlanetarySystem> systemAt(long seed, GalacticCoord coord);

    /**
     * Enumerate every procedural system whose cell falls within the inclusive sector box {@code [min, max]}.
     *
     * @return a map from each occupied cell-centre coordinate to its system (empty when the region is void)
     */
    Map<GalacticCoord, PlanetarySystem> systemsInRegion(long seed, GalacticCoord min, GalacticCoord max);

    /**
     * The procedural CONTENT of the system at {@code systemCoord}'s cell — its star plus planets/moons/POIs
     * as addressable {@link SystemBody} data (universe-model.md &sect;4). Must be deterministic in
     * {@code (seed, systemCoord)}. The default is empty (a system with no derivable content); a real
     * generator overrides it. Implementations should accept ANY cell of the system's neighbourhood
     * (resolving the anchor via {@link #anchorAt}) and return the FULL body list.
     */
    default List<SystemBody> bodiesFor(long seed, GalacticCoord systemCoord) {
        return Collections.emptyList();
    }

    /**
     * The ANCHOR cell of the system whose neighbourhood contains {@code cell}, or empty for void space.
     * Under amendment A#1a a system spans many cells (star at the anchor, each planet in its own cell);
     * this is how a member cell is attributed back to its owning system. Must be deterministic in
     * {@code (seed, cell)}. The default treats a system as single-cell (pre-A#1a behaviour).
     */
    default Optional<GalacticCoord> anchorAt(long seed, GalacticCoord cell) {
        return systemAt(seed, cell).isPresent() ? Optional.of(cell.cellCentre()) : Optional.<GalacticCoord>empty();
    }

    /**
     * The nebulae seated within {@code radiusLy} light years of {@code cell} — what a sky asks, because
     * a cloud is meant to be seen from OUTSIDE it.
     *
     * <p>A DIRECTION-and-size query, never a placement one: a nebula has no cell name and is not a
     * body, so nothing here can be flown to. The default is empty, which is the correct answer for a
     * generator with no clusters rather than a stub — no clusters means no gas.</p>
     */
    default List<Nebula> nebulaeAround(long seed, GalacticCoord cell, double radiusLy) {
        return Collections.emptyList();
    }

    /**
     * How much diffuse matter lies between two cells, in density-light-years — the column an
     * observer at {@code from} looks THROUGH to see {@code to}.
     *
     * <p>The one query every looking-consequence of a cloud is written against, in both directions:
     * what a survey loses to a cloud in the way, and what a ship inside one loses looking out, are
     * this integral with the endpoints moved. Zero for a generator with no clouds, which is the
     * correct answer for clear space and not a stub.</p>
     */
    default double columnDensityBetween(long seed, GalacticCoord from, GalacticCoord to) {
        return 0d;
    }

    /**
     * The DERIVED retinue an AUTHORED system asks for — {@code count} major bodies from
     * {@code (seed, anchor)}, avoiding {@code takenCells}.
     *
     * <p>Default: none. A generator with no procedural content has no retinue to lend, and an
     * authored system then holds exactly what its pack authored — which is the honest answer rather
     * than a stub, and is what the {@code EmptyGalaxyGenerator} means.</p>
     */
    default java.util.List<SystemBody> authoredRetinueFor(long seed,
            dev.stannismod.stellurgy.space.GalacticCoord anchor,
            dev.stannismod.stellurgy.api.dimension.solar.StellarBody star, int starId, int count,
            java.util.Set<String> takenCells) {
        return java.util.Collections.emptyList();
    }

    /**
     * The edge, in cells, of the star TERRITORY this generator partitions space by. It is NOT a bound
     * on how many systems a territory holds: a generator may divide one further (see
     * {@link #anchorsInTerritory}), and {@link ClusteredGalaxyGenerator} does, into as many as
     * {@code k³} seats. The registry uses it for the AUTHORED neighbourhood — the box
     * {@code minSpacingCells/2} to each side of an authored anchor that member cells attribute to and
     * that authored bodies are clamped into.
     */
    default int minSpacingCells() {
        return GalaxyGenConfig.DEFAULT_MIN_SPACING;
    }

    /**
     * The NEIGHBOURHOOD of the system seated at {@code seat}: every cell {@link #anchorAt} attributes to
     * that seat, and so every cell its bodies may be named in. Empty when no system is seated at
     * {@code seat}'s cell.
     *
     * <p>Every seat this generator hands out — through {@link #anchorAt} or {@link #anchorsInTerritory}
     * — must have one. The registry asks it to keep the procedural field out of an authored
     * neighbourhood: a seat whose neighbourhood reaches an authored one is not a system.</p>
     *
     * <p>Default: the seat's own cell, which is the twin of the default {@link #anchorAt} — a
     * generator that never overrode that one treats a system as a single cell, so a single cell is all
     * its system owns.</p>
     */
    default Optional<SectorBox> neighbourhoodOf(long seed, GalacticCoord seat) {
        GalacticCoord cell = seat.galacticCell().cellCentre();
        Optional<GalacticCoord> anchor = anchorAt(seed, cell);
        if (!anchor.isPresent() || !anchor.get().sameCell(cell)) {
            return Optional.empty();
        }
        return Optional.of(SectorBox.around(cell, 0L));
    }

    /**
     * Every anchor seated inside the star TERRITORY that {@code cell} falls in — what one look of a
     * survey owes the direction it is pointed in.
     *
     * <p>A survey strides by the territory, because walking finer would spend a whole sweep re-reading
     * one system's own neighbourhood. But a generator is free to divide that cube further, and then a stride that samples ONE point of it
     * reports a fraction of the sky and calls it the sky. So a look asks for the territory's
     * contents rather than for the point's, and the resolution of the answer is the generator's own
     * business rather than the surveyor's.</p>
     *
     * <p><b>{@code limit} is a refusal, not a truncation.</b> A generator that would return more than
     * {@code limit} anchors returns the single anchor at {@code cell} instead — the sampling a
     * survey has always done inside a star cluster, where one look is a find and not a census.
     * Returning the first {@code limit} of them would be worse than sampling: it would be a biased
     * corner of the territory presented as its whole.</p>
     *
     * <p>Default: whatever {@link #anchorAt} answers, which is exactly right for a generator whose
     * lattice has one seat per territory.</p>
     */
    default List<dev.stannismod.stellurgy.space.GalacticCoord> anchorsInTerritory(long seed,
            dev.stannismod.stellurgy.space.GalacticCoord cell, int limit) {
        Optional<dev.stannismod.stellurgy.space.GalacticCoord> anchor = anchorAt(seed, cell);
        return anchor.isPresent() ? Collections.singletonList(anchor.get()) : Collections
                .<dev.stannismod.stellurgy.space.GalacticCoord>emptyList();
    }

    /**
     * The tunables this generator was built from, when it has any — what a {@code <galaxyGen>} element
     * would have to say to reproduce it, and what the save fingerprints so a later load can tell that
     * the pack has been retuned underneath it.
     *
     * <p>Empty is a real answer and not a stub: a generator with no parameters (the one that answers
     * {@code procedural="false"}, or one an addon fabricates from something other than this config) has nothing to write
     * back, and a pack file that carried a {@code <galaxyGen>} section for it would describe a generator
     * nobody installed.
     */
    default Optional<GalaxyGenConfig> tuning() {
        return Optional.empty();
    }

    /**
     * How this generator's bodies are derived — the half of a world model that says WHAT a body is,
     * where {@link #systemAt} says where it is.
     *
     * <p>It hangs here rather than on the schema because the generator is what a schema selects, so a
     * version picks a derivation by picking a generator, and anything outside the universe layer that
     * needs a body's physics asks the generator that produced the body. The default is version 1's,
     * which is the right answer for a generator that does not derive anything of its own.
     */
    default IBodyDerivation derivation() {
        return BodyDerivationV0.INSTANCE;
    }

    /**
     * The metric and expansion this generator measures with — how many cells a light year is, and how
     * the whole thing grows.
     *
     * <p>Beside {@link #derivation()} and for the same reason: a schema selects the laws by selecting a
     * generator, and anything outside this package that must convert a length in THIS world's terms
     * asks the world's generator rather than a global. The default is version 1's.
     */
    default IUniverseLaws laws() {
        return UniverseLawsV0.INSTANCE;
    }

    /**
     * The cell an authored anchor declared against {@code key} is measured FROM, or empty when this
     * generator has no galaxies.
     *
     * <p>What an authored {@link GalacticAnchor} is resolved against. The default is empty, and that
     * is the right answer rather than a stub: a generator with no galaxy tier has nothing for a
     * declaration to be LOCAL to, so a declared position is already absolute — which is exactly the
     * behaviour an authored-only universe had before galaxies existed.</p>
     */
    default Optional<GalacticCoord> declarationOriginOf(long seed, GalaxyKey key) {
        return Optional.empty();
    }

    /**
     * How far from its DECLARATION ORIGIN authored content is guaranteed to stay inside its galaxy,
     * in light years, or {@code 0} when this generator has no galaxies (and therefore no wall).
     */
    default double guaranteedAuthoredReachLy() {
        return 0d;
    }
}
