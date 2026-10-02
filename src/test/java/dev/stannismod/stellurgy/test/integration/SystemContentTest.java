package dev.stannismod.stellurgy.test.integration;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.space.BlockDelta;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import dev.stannismod.stellurgy.universe.ClusteredGalaxyGenerator;
import dev.stannismod.stellurgy.universe.GalaxyGenConfig;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.SystemBodyKind;
import dev.stannismod.stellurgy.universe.SystemContent;
import dev.stannismod.stellurgy.universe.UniverseRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Authored system content, where a system is an anchored NEIGHBOURHOOD of cells rather than one cell:
 * a catalogued {@link StellarBody} with planets resolves to addressable {@link SystemBody} data — star
 * at the anchor, each planet in its OWN cell (snapped to the cell centre) inside the anchor's super-cell
 * box — and a planet dim resolves to its own cell through the registry.
 * Needs {@link MinecraftBootstrap} for {@link DimensionProperties} construction.
 * Scale constants are {@code tunable} and never pinned; only the placement SHAPE is.
 */
public class SystemContentTest {

    /** The universe this test arranges; one per test, so nothing reaches the next. */
    private final dev.stannismod.stellurgy.test.TestUniverse testUniverse = new dev.stannismod.stellurgy.test.TestUniverse();

    /** The two dimension ids this scenario's fixture authors. Not thresholds: the arrangement's
     *  own numbers, read back from the body it produced. */
    private static final int AUTHORED_DIM_A = 700;
    /** @see #AUTHORED_DIM_A */
    private static final int AUTHORED_DIM_B = 701;

    /**
     * A moon's orbit for these fixtures, in DISTANCE UNITS — the unit {@code orbitalDist} is stored in.
     *
     * <p>The fixtures' moon has always been 25 400 chart blocks from its planet (the literal was
     * {@code 127} units of 200 blocks). That is 63.5 of today's 400-block units, so the nearest whole
     * number is taken: 64 units, 25 600 blocks.</p>
     *
     * <p><b>Why not through {@link #planet}</b>: its parameter is hundredths of an AU, and one
     * hundredth of an AU is 14 959 units — about 5.98 million blocks — so 25 400 blocks is not
     * expressible in it at all. This constant was that expression until 2026-09-29, clamped by
     * {@code Math.max(1, …)} from zero to 1: every moon here sat 5.98 million blocks out while this
     * comment said 25 400.</p>
     */
    private static final int MOON_DISTANCE_UNITS =
            (int) Math.round(25_400d / AstronomicalBodyHelper.BLOCKS_PER_DISTANCE_UNIT);

    /** A moon of these fixtures: {@link #planet}'s body, at the fixtures' moon distance. */
    private static DimensionProperties moon(int dimId) {
        DimensionProperties m = planet(dimId, 0, 0.9);
        m.orbitalDist = MOON_DISTANCE_UNITS;
        return m;
    }

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    /**
     * A body authored at orbital angle {@code theta}, and CURRENTLY somewhere else on that orbit.
     *
     * <p>The two angle fields are set to different values on purpose. {@code baseOrbitTheta} is the
     * authored angle a durable cell name is derived from; {@code orbitTheta} is the live angle the
     * world rewrites every tick. Setting them equal — the physically honest state at tick zero, and
     * the first thing one reaches for — makes every test in this file blind to the defect the file
     * exists to guard: with the two identical, reading the live field and reading the authored one
     * produce the same answer, so a derivation that went back to the live field would keep the whole
     * suite green while restoring exactly the bug that took planets out of a parked ship's sky. A
     * unit test never ticks the world, so the drift has to be authored in.</p>
     */
    private static DimensionProperties planet(int dimId, int orbitCentiAu, double theta) {
        DimensionProperties p = new DimensionProperties(dimId);
        // The argument is HUNDREDTHS OF AN AU, which is what these cases were written in
        // when a distance unit WAS one. A distance unit is a length now (100 km), so the
        // conversion happens here rather than at twenty call sites — and stating it once
        // is what keeps the cases readable as "one AU", "two AU", "a quarter further out".
        p.orbitalDist = (int) ((long) orbitCentiAu
                * AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU / 100L);
        p.baseOrbitTheta = theta;
        p.orbitTheta = theta + 1.0; // the body has moved since; a NAME must not notice
        p.orbitalPhi = 0;
        return p;
    }

    /**
     * red-witnessed: 2026-09-30, with {@code SystemContent#orbitLawOf} at {@code planet.isRetrograde, periodTicks, ORBIT_UNIT_BLOCKS)} building the authored law at
     * {@code ORBIT_UNIT_BLOCKS / 1000} (zero in long arithmetic — an orbit with no length), this fails
     * with "a planet sits in its OWN cell, not in the star's anchor cell".
     */
    @Test
    public void authoredPlanetsGetTheirOwnCellsInsideTheSuperCellBox() {
        StellarBody star = new StellarBody();
        star.setId(4242);
        star.setName("TestStar");
        planet(700, 100, 0.0).setStar(star);       // setStar back-adds the planet to the star
        planet(701, 200, Math.PI / 2).setStar(star);

        GalacticCoord anchor = GalacticCoord.ofSectorLocal(10, 20, 30, 0, 0, 0);
        long s = GalaxyGenConfig.DEFAULT_MIN_SPACING;
        List<SystemBody> bodies = SystemContent.bodiesOf(star, anchor, new dev.stannismod.stellurgy.universe.ReportOnce());

        assertEquals("first body is the star at the anchor cell", SystemBodyKind.STAR, bodies.get(0).kind());
        assertTrue(bodies.get(0).name().sameCell(anchor));
        assertEquals(0, bodies.get(0).name().localX());

        int planets = 0;
        SystemBody aPlanet = null;
        for (SystemBody b : bodies) {
            assertEquals("every body belongs to the star", 4242, b.starId());
            // Snapped to its own cell's centre: a cell names a body's whole orbital ZONE, not a point.
            assertEquals(0, b.name().localX());
            assertEquals(0, b.name().localY());
            assertEquals(0, b.name().localZ());
            // Inside the anchor's super-cell box, so member attribution stays exact.
            assertEquals(Math.floorDiv(anchor.sectorX(), s), Math.floorDiv(b.name().sectorX(), s));
            assertEquals(Math.floorDiv(anchor.sectorY(), s), Math.floorDiv(b.name().sectorY(), s));
            assertEquals(Math.floorDiv(anchor.sectorZ(), s), Math.floorDiv(b.name().sectorZ(), s));
            if (b.kind() == SystemBodyKind.PLANET) {
                planets++;
                aPlanet = b;
                assertFalse("a planet sits in its OWN cell, not in the star's anchor cell",
                        b.name().sameCell(anchor));
            }
        }
        assertEquals("both authored planets become bodies", 2, planets);
        assertNotNull(aPlanet);
        assertTrue("an authored planet body is a descend target (real dim)", aPlanet.isDescendTarget());
        assertTrue(aPlanet.dimId() == AUTHORED_DIM_A || aPlanet.dimId() == AUTHORED_DIM_B);

        // Distinct orbits land in distinct cells (per-body cells are real, not a shared one).
        SystemBody first = null;
        for (SystemBody b : bodies) {
            if (b.kind() != SystemBodyKind.PLANET) {
                continue;
            }
            if (first == null) {
                first = b;
            } else {
                assertFalse("planets on different orbits sit in different cells",
                        b.name().sameCell(first.name()));
            }
        }
    }

    /**
     * red-witnessed: 2026-09-30, with {@code SystemContent#orbitLawOf} at {@code planet.isRetrograde, periodTicks, ORBIT_UNIT_BLOCKS)} building the AUTHORED law at
     * {@code ORBIT_UNIT_BLOCKS / 1000} while the procedural one is untouched, this fails with "one
     * orbit unit must be one distance in both families expected:&lt;0.0&gt; but
     * was:&lt;400.00000338384956&gt;" — 400 blocks per unit, the procedural family's measured scale.
     */
    @Test
    public void oneOrbitalDistanceMeansOneDistanceInBothFamilies() {
        // The acceptance the scale rework exists for. An authored planet and a procedural one at the
        // same orbital distance must stand the same distance from their stars — the field is
        // documented in one unit, and every derived number (insolation, temperature, period) is
        // computed from it and never from where the body was placed. They used to be turned into
        // positions by two different laws: authored linear and absolute, procedural logarithmic and
        // normalised to whatever neighbourhood the system had been given. Order survived; proportion
        // did not, and the science and the flight time disagreed.
        StellarBody star = new StellarBody();
        star.setId(4244);
        star.setName("ScaleStar");
        planet(720, 300, 0.0).setStar(star);

        GalacticCoord anchor = GalacticCoord.ofSectorLocal(11, -4, 6, 0, 0, 0);
        SystemBody authored = null;
        for (SystemBody b : SystemContent.bodiesOf(star, anchor, new dev.stannismod.stellurgy.universe.ReportOnce())) {
            if (b.dimId() == 720) {
                authored = b;
            }
        }
        assertNotNull(authored);
        double authoredPerUnit = authored.absoluteAt(0L).distanceTo(
                dev.stannismod.stellurgy.space.AbsolutePos.ofCellName(anchor))
                / authored.orbitalDistance();

        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(new dev.stannismod.stellurgy.universe.ReportOnce(),
                new GalaxyGenConfig(GalaxyGenConfig.DEFAULT_MIN_SPACING, 1.0d,
                        GalaxyGenConfig.DEFAULT_GALAXY_SPACING, GalaxyGenConfig.DEFAULT_GALAXY_DENSITY,
                        null, null));
        long spacing = GalaxyGenConfig.DEFAULT_MIN_SPACING;
        // SWEEP for an occupied super-cell rather than demanding one particular cube. Occupancy is a
        // draw scaled by the galaxy's profile, so any single cube is a coin toss and a fixture that
        // insists on one is testing the coin.
        // It must be a seat with a STAR: the comparison is between one authored planet's orbit and one
        // procedural planet's, and a starless system has no orbits at all to compare with.
        // Asked what each TERRITORY holds, never what its corner point resolves to: the lattice is
        // divided uniformly, so a point probe samples one seat in k-cubed and a sweep built on it
        // reads a populated field as an almost empty one.
        Optional<GalacticCoord> seat = Optional.empty();
        for (long i = 1; i <= 16 && !seat.isPresent(); i++) {
            for (GalacticCoord candidate : gen.anchorsInTerritory(0xBEEFL,
                    GalacticCoord.ofSectorLocal(i * spacing, spacing, spacing, 0L, 0L, 0L), 64)) {
                if (gen.systemAt(0xBEEFL, candidate).get().star().isPresent()) {
                    seat = Optional.of(candidate);
                    break;
                }
            }
        }
        assertTrue("the fixture needs an occupied super-cell with a star in it", seat.isPresent());
        int compared = 0;
        for (SystemBody b : gen.bodiesFor(0xBEEFL, seat.get())) {
            if (b.kind() != SystemBodyKind.PLANET && b.kind() != SystemBodyKind.GAS_GIANT) {
                continue;
            }
            double proceduralPerUnit = b.absoluteAt(0L).distanceTo(
                    dev.stannismod.stellurgy.space.AbsolutePos.ofCellName(seat.get()))
                    / b.orbitalDistance();
            assertEquals("one orbit unit must be one distance in both families",
                    authoredPerUnit, proceduralPerUnit, authoredPerUnit * 1e-6d);
            compared++;
        }
        assertTrue("the procedural system must have bodies to compare against", compared > 0);
    }

    /**
     * An authored orbit is in RADIANS, and a body must land where that orbit puts it. A quarter turn
     * is a quarter turn: the body belongs on the anchor's +Z axis, with its +X offset gone. Running
     * the angle through a degrees&rarr;radians conversion a second time collapsed every orbit into a
     * 6&deg; wedge, which parked every body in a system on the {@code x ≈ orbitalDist} line — one
     * cell apart, each against a cell boundary, so their addresses flipped under the slightest motion
     * and two bodies could share one.
     *
     * <p>red-witnessed: 2026-09-30, with {@code SystemContent#orbitLawOf} at {@code planet.isRetrograde, periodTicks, ORBIT_UNIT_BLOCKS)} building the law at
     * {@code ORBIT_UNIT_BLOCKS / 1000} (zero), this fails with "...and puts the whole orbital radius
     * along +Z".</p>
     */
    @Test
    public void aQuarterTurnPutsTheBodyAQuarterTurnRound() {
        StellarBody star = new StellarBody();
        star.setId(4244);
        planet(720, 400, Math.PI / 2).setStar(star);

        GalacticCoord anchor = GalacticCoord.ORIGIN;
        SystemBody body = null;
        for (SystemBody b : SystemContent.bodiesOf(star, anchor, new dev.stannismod.stellurgy.universe.ReportOnce())) {
            if (b.dimId() == 720) {
                body = b;
            }
        }
        assertNotNull(body);
        assertEquals("a quarter turn leaves no offset along +X", 0L, body.name().sectorX());
        assertTrue("...and puts the whole orbital radius along +Z", body.name().sectorZ() > 0L);
    }

    /**
     * A body with no surface is not somewhere a ship can put down, so it must not be advertised as
     * one. It stays a real, addressable destination — it owns a cell and keeps its dimension, which
     * is what a pilot flies to and what a survey reads — but the descent trigger, the nav GUI and the
     * render channel all read {@code isDescendTarget()} and must be told the truth by the one place
     * bodies are made. Advertised as landable, it sent a ship's descent into a dimension with no
     * terrain to find.
     *
     * <p>red-witnessed: 2026-09-30, with {@code SystemContent#kindOf} at {@code return body.hasSurface() ? ifWalkable : SystemBodyKind.GAS_GIANT} answering the walkable kind
     * whatever the surface, this fails with "a surface-less body is not somewhere a ship can land".</p>
     */
    @Test
    public void aBodyWithNoSurfaceIsNotADescendTarget() {
        StellarBody star = new StellarBody();
        star.setId(4245);
        DimensionProperties gasGiant = planet(730, 250, 1.0);
        gasGiant.setGasGiant(true);
        gasGiant.setStar(star);
        planet(731, 120, 2.0).setStar(star);

        SystemBody giantBody = null;
        SystemBody planetBody = null;
        for (SystemBody b : SystemContent.bodiesOf(star, GalacticCoord.ORIGIN, new dev.stannismod.stellurgy.universe.ReportOnce())) {
            if (b.dimId() == 730) {
                giantBody = b;
            } else if (b.dimId() == 731) {
                planetBody = b;
            }
        }
        assertNotNull(giantBody);
        assertNotNull(planetBody);
        assertFalse("a surface-less body is not somewhere a ship can land",
                giantBody.isDescendTarget());
        assertEquals("...but it is still a body, with its own dimension to fly to and survey",
                730, giantBody.dimId());
        assertTrue("a body with a surface is still landable", planetBody.isDescendTarget());
    }

    /**
     * A body's cell does NOT move with time. Half an orbit later — the moment its position is as far
     * from where it started as that body ever gets — it is still addressed by the same cell.
     *
     * <p>This test used to assert the opposite, in as many words: "half an orbit later the body is
     * somewhere else — an address is a moment". That was the model, and it was the bug. An address is
     * how a pilot names a destination, how a ship's arrival is recorded and what the sky of a cell is
     * built from; a name that expires while its owner is still there took planets out of a parked
     * ship's sky every few minutes and sent jumps to cells their target had left.</p>
     *
     * <p>What is still a function of time — a moon's position INSIDE its parent's cell, which is what
     * a navigation computer leads its aim by — is pinned by
     * {@link #aMoonIsAimedAtWhereItIsNotAtItsParentsCellCentre}.</p>
     *
     * <p>red-witnessed: 2026-09-30, with {@code SystemBody#addressAt} at {@code BlockDelta offset = inCellOffsetAt(tick)} answering the galactic cell of
     * the body's LIVE position instead of its name — the defect this test used to assert as the
     * model — this fails with "half an orbit later the body is still addressed by the same cell
     * expected:&lt;19_0_0&gt; but was:&lt;-19_0_0&gt;".</p>
     */
    @Test
    public void aBodysCellIsTheSameCellHalfAnOrbitLater() {
        StellarBody star = new StellarBody();
        star.setId(4246);
        star.setSize(1f);
        DimensionProperties p = planet(740, 100, 0.0);
        p.setStar(star);

        // Half an orbital period: the far side of the star, i.e. the largest displacement this body
        // ever has from where it began. Which tick that is comes from the body's own orbit, so this
        // pins the DURABILITY of a name, never a particular period.
        long halfPeriodTicks = (long) (24000d
                * AstronomicalBodyHelper.getOrbitalPeriod(
                        AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU, 1f) / 2d);

        SystemBody body = bodyOf(SystemContent.bodiesOf(star, GalacticCoord.ORIGIN, new dev.stannismod.stellurgy.universe.ReportOnce()), 740);
        assertNotNull(body);

        assertEquals("half an orbit later the body is still addressed by the same cell",
                body.name().cellKey(), body.addressAt(halfPeriodTicks).cellKey());
        // The control. Without it this passes against a body that never went anywhere, and "the name
        // is durable" would be a statement about the fixture rather than about the derivation.
        assertFalse("the fixture's planet must actually travel over half an orbit",
                body.absoluteAt(0L).equals(body.absoluteAt(halfPeriodTicks)));
        assertTrue("...and travel FAR - more than one galactic cell's width",
                body.absoluteAt(0L).distanceTo(body.absoluteAt(halfPeriodTicks))
                        > GalacticCoord.CELL);
    }

    /**
     * The negative leg of the clause above, and the reason it is not satisfiable by a constant: a
     * durable name is derived from the body's AUTHORED orbit, so authoring a different orbit gives a
     * different name. Without this, "the name never changes" would be passed by a derivation that
     * returned the same cell for every body in the universe.
     *
     * <p>red-witnessed: 2026-09-30, with {@code SystemContent#orbitLawOf} at {@code planet.isRetrograde, periodTicks, ORBIT_UNIT_BLOCKS)} building the law at
     * {@code ORBIT_UNIT_BLOCKS / 1000} (zero — every orbit collapses onto the anchor), this fails with
     * "two bodies authored on opposite sides of one star are not one address".</p>
     */
    @Test
    public void aDifferentAuthoredOrbitIsADifferentCell() {
        StellarBody star = new StellarBody();
        star.setId(4256);
        star.setSize(1f);
        planet(745, 100, 0.0).setStar(star);
        planet(746, 100, Math.PI).setStar(star);

        GalacticCoord first = cellOf(SystemContent.bodiesOf(star, GalacticCoord.ORIGIN, new dev.stannismod.stellurgy.universe.ReportOnce()), 745);
        GalacticCoord second = cellOf(SystemContent.bodiesOf(star, GalacticCoord.ORIGIN, new dev.stannismod.stellurgy.universe.ReportOnce()), 746);

        assertNotNull(first);
        assertNotNull(second);
        assertFalse("two bodies authored on opposite sides of one star are not one address",
                first.sameCell(second));
    }

    /**
     * A recorded name WINS over the derivation, for every later query.
     *
     * <p>This is what makes a name durable in the only sense that matters to a player: a coordinate
     * he wrote down still denotes his planet after the authored data has been re-saved (the angles
     * round-trip through the world's XML), after a spacing change, and after any later edit to the
     * derivation itself. A name that is merely re-derived consistently is only as stable as its
     * inputs, and those inputs are known to move.</p>
     *
     * <p>red-witnessed: 2026-09-30, with {@code SystemContent#nameOf} at {@code return names == null ? derived} returning the derivation without
     * consulting the store, this fails with "the store's name is the body's name, whatever the
     * derivation would have said".</p>
     */
    @Test
    public void aRecordedNameBeatsAFreshDerivation() {
        StellarBody star = new StellarBody();
        star.setId(4257);
        star.setSize(1f);
        planet(747, 100, 0.0).setStar(star);

        final GalacticCoord recorded = GalacticCoord.ofSectorLocal(77L, -3L, 12L, 0L, 0L, 0L);
        List<SystemBody> bodies = SystemContent.bodiesOf(star, GalacticCoord.ORIGIN,
                GalaxyGenConfig.DEFAULT_MIN_SPACING,
                new SystemContent.CellNames() {
                    @Override
                    public GalacticCoord nameFor(int dimId, int starId, GalacticCoord anchor,
                                                 int minSpacingCells, GalacticCoord derived) {
                        return dimId == 747 ? recorded : derived;
                    }
                }, new dev.stannismod.stellurgy.universe.ReportOnce());

        GalacticCoord actual = cellOf(bodies, 747);
        assertNotNull(actual);
        assertTrue("the store's name is the body's name, whatever the derivation would have said",
                recorded.sameCell(actual));
    }

    /**
     * A body on the NEGATIVE side of its star belongs to that star's system, exactly like one on the
     * positive side.
     *
     * <p>A system's neighbourhood is the box centred on its anchor — that is where bodies are placed.
     * Attributing a cell back by looking it up in a fixed grid of super-cubes asks a different
     * question, and for the home system, whose anchor sits at sector 0, every negative-offset orbit
     * lands in the neighbouring cube and resolves to NO system: an address the console will happily
     * offer, with nothing at it, that a ship can fly to and never descend from.</p>
     *
     * <p>red-witnessed: 2026-09-30, with {@code UniverseRegistry#anchorForCell} at {@code GalacticCoord stored = storedAnchorNear(cell)} skipping the
     * neighbourhood lookup ({@code storedAnchorNear}), this fails with "its own cell must attribute
     * back to its system".</p>
     */
    @Test
    public void aBodyBehindItsStarStillBelongsToThatSystem() {
        StellarBody star = new StellarBody();
        star.setId(4248);
        // Half a turn round: straight down the anchor's NEGATIVE X axis.
        planet(760, 300, Math.PI).setStar(star);

        UniverseRegistry reg = testUniverse.newRegistry();
        reg.place(GalacticCoord.ORIGIN, 4248);
        testUniverse.setStarLookup(id -> id == 4248 ? star : null);

        GalacticCoord bodyCell = cellOf(reg.systemBodiesAt(GalacticCoord.ORIGIN), 760);
        assertNotNull(bodyCell);
        assertTrue("the fixture must actually put the body behind the star", bodyCell.sectorX() < 0L);

        assertTrue("its own cell must attribute back to its system",
                reg.anchorForCell(bodyCell).isPresent());
        assertEquals("...to THAT system's anchor", GalacticCoord.ORIGIN,
                reg.anchorForCell(bodyCell).get());
        assertFalse("...and the cell must report the body standing in it",
                reg.bodiesAt(bodyCell).isEmpty());
    }

    private static SystemBody bodyOf(List<SystemBody> bodies, int dimId) {
        for (SystemBody b : bodies) {
            if (b.dimId() == dimId) {
                return b;
            }
        }
        return null;
    }

    private static GalacticCoord cellOf(List<SystemBody> bodies, int dimId) {
        for (SystemBody b : bodies) {
            if (b.dimId() == dimId) {
                return b.name();
            }
        }
        return null;
    }
}
