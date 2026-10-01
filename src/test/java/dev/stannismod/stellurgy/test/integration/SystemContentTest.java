package dev.stannismod.stellurgy.test.integration;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.dimension.DimensionManager;
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
import static org.junit.Assert.assertNotEquals;
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

    @After
    public void resetSeams() {
        UniverseRegistry.setStarLookup(null);
        UniverseRegistry.detachGenerator();
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
        List<SystemBody> bodies = SystemContent.bodiesOf(star, anchor);

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
        for (SystemBody b : SystemContent.bodiesOf(star, anchor)) {
            if (b.dimId() == 720) {
                authored = b;
            }
        }
        assertNotNull(authored);
        double authoredPerUnit = authored.absoluteAt(0L).distanceTo(
                dev.stannismod.stellurgy.space.AbsolutePos.ofCellName(anchor))
                / authored.orbitalDistance();

        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(
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
     * red-witnessed: 2026-09-30, with {@code SystemContent#orbitLawOf} at {@code planet.isRetrograde, periodTicks, ORBIT_UNIT_BLOCKS)} building the law at
     * {@code ORBIT_UNIT_BLOCKS / 1000} (zero), this fails with "the planet's coord is its own zone
     * cell, NOT the system's anchor cell".
     */
    @Test
    public void planetResolvesToItsOwnCellThroughTheRegistry() {
        StellarBody star = new StellarBody();
        star.setId(4243);
        DimensionProperties p = planet(710, 120, 0.0);
        p.setStar(star);

        UniverseRegistry reg = new UniverseRegistry();
        GalacticCoord anchor = GalacticCoord.ofSectorLocal(5, 5, 5, 0, 0, 0);
        reg.place(anchor, 4243);

        // Without content resolution (star not in the catalogue) the body has no cell to be addressed
        // by, and the seam says so. It used to answer with the system ANCHOR — a coordinate a caller
        // cannot tell from a real one, and one that denotes the star rather than the world: a first
        // memory crystal seeded from it carried a planet's name at its star's address.
        assertFalse("a body its own system cannot account for has no address, and the seam must not"
                + " substitute the star's", reg.coordForPlanet(p).isPresent());

        // With content resolvable, the planet resolves to its OWN cell, which is where its body sits.
        UniverseRegistry.setStarLookup(id -> id == 4243 ? star : null);
        Optional<GalacticCoord> resolved = reg.coordForPlanet(p);
        assertTrue(resolved.isPresent());
        assertFalse("the planet's coord is its own zone cell, NOT the system's anchor cell",
                resolved.get().sameCell(anchor));

        GalacticCoord bodyCell = null;
        for (SystemBody b : reg.systemBodiesAt(anchor)) {
            if (b.dimId() == 710) {
                bodyCell = b.name();
            }
        }
        assertNotNull(bodyCell);
        assertEquals("coordForPlanet agrees with the body's own cell", bodyCell, resolved.get());
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
        for (SystemBody b : SystemContent.bodiesOf(star, anchor)) {
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
        for (SystemBody b : SystemContent.bodiesOf(star, GalacticCoord.ORIGIN)) {
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

        SystemBody body = bodyOf(SystemContent.bodiesOf(star, GalacticCoord.ORIGIN), 740);
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

        GalacticCoord first = cellOf(SystemContent.bodiesOf(star, GalacticCoord.ORIGIN), 745);
        GalacticCoord second = cellOf(SystemContent.bodiesOf(star, GalacticCoord.ORIGIN), 746);

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
                });

        GalacticCoord actual = cellOf(bodies, 747);
        assertNotNull(actual);
        assertTrue("the store's name is the body's name, whatever the derivation would have said",
                recorded.sameCell(actual));
    }

    /**
     * A moon's ADDRESS is the moon's OWN cell, and that cell is not its parent's.
     *
     * <p>This pin used to assert the opposite — that a moon is addressed INSIDE its parent's cell, tens of thousands of blocks off its centre, so a jump had to aim at the body
     * rather than at the cell or the pilot who picked the moon arrived at the planet. The defect was
     * real; the fix is that the moon has a cell of its own, in its parent's zone. Aiming at the cell
     * and aiming at the body are now the same act, which is what "a moon is a destination in its own
     * right" means — and the two answers coinciding is the assertion, not a coincidence to shrug at.</p>
     *
     * <p>red-witnessed: 2026-09-30, with {@code SystemBody#addressAt} at {@code BlockDelta offset = inCellOffsetAt(tick)} answering the galactic cell of
     * the body's live position instead of its name, this fails with "a moon's cell rides the moon,
     * so aiming at the cell IS aiming at the body".</p>
     */
    @Test
    public void aMoonIsAddressedByItsOwnCellInsideItsParentsZone() {
        StellarBody star = new StellarBody();
        star.setId(4247);
        star.setSize(1f);
        DimensionProperties parent = planet(750, 200, 0.5);
        parent.gravitationalMultiplier = 1f;
        DimensionProperties moon = moon(751);
        DimensionManager.getInstance().setDimProperties(750, parent);
        DimensionManager.getInstance().setDimProperties(751, moon);
        parent.setStar(star);
        moon.setParentPlanet(parent);

        UniverseRegistry reg = new UniverseRegistry();
        reg.place(GalacticCoord.ORIGIN, 4247);
        UniverseRegistry.setStarLookup(id -> id == 4247 ? star : null);

        Optional<GalacticCoord> parentCell = reg.coordForPlanet(parent);
        Optional<GalacticCoord> cell = reg.coordForPlanet(moon);
        Optional<GalacticCoord> aim = reg.addressForPlanet(moon, 0L);
        assertTrue(parentCell.isPresent());
        assertTrue(cell.isPresent());
        assertTrue(aim.isPresent());

        assertEquals("the moon's cell is named inside its PARENT's zone",
                parentCell.get().cellKey(), cell.get().zone());
        assertFalse("...and it is not the parent's own cell",
                cell.get().galacticCell().sameCell(cell.get()));
        assertTrue("a moon's cell rides the moon, so aiming at the cell IS aiming at the body",
                aim.get().sameCell(cell.get()));
        // Both endpoints are in ONE cell, so they share a frame and its motion cancels: the in-cell
        // delta IS the distance, with no tick and no frame lookup needed. It is ZERO, because a
        // moon sits at its own frame's origin exactly as a planet does.
        assertEquals("a ship dropped at that cell's centre arrives at the moon", 0d,
                aim.get().staticFrameDistanceTo(cell.get()), 0d);
    }

    /**
     * <b>The query the descent scan uses can see a moon from its PARENT's cell; the cell read
     * cannot, and both halves are the assertion.</b>
     *
     * <p>This test fails if production breaks the contract that <b>a craft can find a body it is
     * able to reach without already being in that body's cell.</b> A moon has a cell of its own
     * inside its parent's zone, so a craft flying at one is in a different cell for the whole
     * approach — and the descent trigger's candidate list was built from {@code bodiesAt}, the CELL
     * read, which by construction can never hold it. The trigger went dead for every moon in the
     * game, silently, and every tier stayed green because they all ask the registry where bodies
     * are rather than what a craft near one can see.</p>
     *
     * <p>The negative half is not decoration. {@code bodiesAt} answering "no moon" is CORRECT — it
     * is the cell read and the moon is not in that cell — so a later "fix" that widened it would
     * make the positive leg pass while quietly breaking every consumer that asks it a question
     * about one cell (attribution, the wells query, entry placement).</p>
     *
     * <p><b>What this does NOT pin: that the descent scan CALLS the sky read.</b> The call is
     * {@code TileAdvancedFlightComputer.descendTargetsIn}, a private step of the flight computer's
     * tick, and reverting it to {@code bodiesAt} — the defect as it shipped — leaves this test green.
     * Nothing pins that call yet: the computer is not ticking on the server tier by the time a craft
     * could be flown near a moon, so it needs a client e2e with a pilot aboard.</p>
     *
     * <p>red-witnessed: 2026-09-29, with {@code UniverseRegistry#skyBodiesAt} at
     * {@code List<SystemBody> out = systemBodiesAt(cell)} answering the cell read ({@code bodiesAt})
     * instead — the defect as it shipped — this fails with "but the SKY read must hold the
     * moon, or a scan built on it can never find one" (re-run after the message was narrowed).</p>
     */
    @Test
    public void aMoonIsReachableFromItsParentsCellThroughTheSkyReadButNotTheCellRead() {
        StellarBody star = new StellarBody();
        star.setId(4261);
        star.setSize(1f);
        DimensionProperties parent = planet(790, 200, 0.5);
        parent.gravitationalMultiplier = 1f;
        DimensionProperties moon = moon(791);
        DimensionManager.getInstance().setDimProperties(790, parent);
        DimensionManager.getInstance().setDimProperties(791, moon);
        parent.setStar(star);
        moon.setParentPlanet(parent);

        UniverseRegistry reg = new UniverseRegistry();
        reg.place(GalacticCoord.ORIGIN, 4261);
        UniverseRegistry.setStarLookup(id -> id == 4261 ? star : null);

        GalacticCoord parentCell = reg.coordForPlanet(parent).orElse(null);
        assertNotNull("arrangement: the planet must have a cell", parentCell);
        GalacticCoord moonCell = reg.coordForPlanet(moon).orElse(null);
        assertNotNull("arrangement: and so must the moon", moonCell);
        assertFalse("arrangement: they are different cells, which is what makes this a question",
                moonCell.sameCell(parentCell));

        // The POSITIVE half of the cell read, in this method (STEP 7): it holds the planet, so the
        // "no moon" below is the read answering about its one cell and not an empty list.
        assertTrue("arrangement: the CELL read of the planet's cell must hold the planet itself",
                holdsDim(reg.bodiesAt(parentCell), 790));
        assertFalse("the CELL read of the planet's cell must not hold the moon — it is not in it",
                holdsDim(reg.bodiesAt(parentCell), 791));
        assertTrue("but the SKY read must hold the moon, or a scan built on it can never find one",
                holdsDim(reg.skyBodiesAt(parentCell), 791));
        assertTrue("...and it must still hold the planet itself",
                holdsDim(reg.skyBodiesAt(parentCell), 790));
    }

    /** Whether {@code bodies} holds the body of dimension {@code dimId}. */
    private static boolean holdsDim(List<SystemBody> bodies, int dimId) {
        for (SystemBody b : bodies) {
            if (b.dimId() == dimId) {
                return true;
            }
        }
        return false;
    }

    /**
     * The live half of the moon rule, one level down. A moon's NAME is fixed forever; what moves is
     * its CELL, which rides it — so the moon sits at its own frame's origin at every tick while its
     * absolute position goes round its planet.
     *
     * <p>This used to assert the opposite of its own second clause: that a moon's offset INSIDE its
     * parent's cell is live. It was, and that motion was exactly what nothing carried a parked craft
     * through. A nav computer no longer has to lead its aim at a moon, because the address it aims
     * at moves with the body.</p>
     *
     * <p>red-witnessed: 2026-09-30, with {@code SystemBody#addressAt} at {@code BlockDelta offset = inCellOffsetAt(tick)} answering the galactic cell of
     * the body's live position instead of its name, this fails with "...and it is the same name at
     * every tick expected:&lt;33_0_18.1_0_1&gt; but was:&lt;33_0_18&gt;".</p>
     */
    @Test
    public void aMoonsCellRidesItSoItsOffsetIsZeroWhileItsPositionIsLive() {
        StellarBody star = new StellarBody();
        star.setId(4249);
        star.setSize(1f);
        DimensionProperties parent = planet(770, 200, 0.5);
        parent.gravitationalMultiplier = 1f;
        DimensionProperties moon = moon(771);
        DimensionManager.getInstance().setDimProperties(770, parent);
        DimensionManager.getInstance().setDimProperties(771, moon);
        parent.setStar(star);
        moon.setParentPlanet(parent);

        List<SystemBody> bodies = SystemContent.bodiesOf(star, GalacticCoord.ORIGIN);
        SystemBody moonBody = bodyOf(bodies, 771);
        SystemBody planetBody = bodyOf(bodies, 770);
        assertNotNull(moonBody);
        assertNotNull(planetBody);

        // A quarter of the orbit the moon is actually ON — its own law's distance about this parent's
        // mass. It was the period at 127 units round a mass of 1, which were this fixture's numbers
        // before a distance unit became 100 km; the moon now stands at 64, so that "quarter" was a
        // tick with no relation to the orbit, and the samples below were a quarter turn apart only by
        // luck of the draw.
        long quarterPeriod = (long) (24000d * AstronomicalBodyHelper.getMoonOrbitalPeriod(
                (float) moonBody.frame().law().distUnits(), (float) parent.getOrbitalMass()) / 4d);

        assertEquals("a moon's name is its OWN cell, in its parent's zone",
                planetBody.name().cellKey(), moonBody.name().zone());
        assertNotEquals("which is not its parent's cell", planetBody.name(), moonBody.name());
        assertEquals("...and it is the same name at every tick", moonBody.name().cellKey(),
                moonBody.addressAt(quarterPeriod).cellKey());
        assertTrue("a moon is at its own cell's frame origin, so it has no offset to move",
                moonBody.inCellOffsetAt(0L).isZero()
                        && moonBody.inCellOffsetAt(quarterPeriod).isZero());
        assertFalse("what is LIVE is where that cell IS: the moon goes round its planet",
                moonBody.absoluteAt(0L).equals(moonBody.absoluteAt(quarterPeriod)));
        assertTrue("and a planet is at its own cell's frame origin for the same reason",
                planetBody.inCellOffsetAt(quarterPeriod).isZero());
    }

    /**
     * A moon's period is set by its parent's MASS, not by the gravity you would feel standing on it.
     *
     * <p>The two are the same number only at one Earth radius — {@code g = M/R²} — and every orbital
     * law here used to be handed gravity. Exact for Earth; for a Jupiter (318 Earth masses, 2.53 g)
     * wrong by {@code sqrt(318/2.53)}, so a giant's moons crawled round it 11 times too slowly. The
     * fixture below is that Jupiter, and the two readings are 11× apart, so a run cannot satisfy this
     * test by accident.</p>
     *
     * <p>red-witnessed: 2026-09-30, with {@code SystemContent#moonLawOf} at {@code orbit, (float) parent.getOrbitalMass())} taking the period from the
     * AUTHORED distance instead of the lifted one, this fails with "one mass-derived period must bring
     * it back (was 1372907.1155518861, orbit radius 714400.0)". The fixture, printed: the moon is
     * lifted to 1 786 units, 714 400 blocks.</p>
     */
    @Test
    public void aMoonsPeriodFollowsItsParentsMassNotItsSurfaceGravity() {
        StellarBody star = new StellarBody();
        star.setId(4251);
        star.setSize(1f);
        DimensionProperties parent = planet(780, 200, 0.5);
        parent.setBulk(318d, 11.2d); // a Jupiter: gravity falls out as M/R² = 2.53
        DimensionProperties moon = moon(781);
        DimensionManager.getInstance().setDimProperties(780, parent);
        DimensionManager.getInstance().setDimProperties(781, moon);
        parent.setStar(star);
        moon.setParentPlanet(parent);

        assertEquals("the fixture must be a giant, or the two readings coincide and prove nothing",
                2.535d, parent.gravitationalMultiplier, 0.01d);

        SystemBody moonBody = bodyOf(SystemContent.bodiesOf(star, GalacticCoord.ORIGIN), 781);
        assertNotNull(moonBody);

        // THE PERIOD OF THE ORBIT THE MOON IS ACTUALLY ON, not of the one it was authored with.
        //
        // This used to compute both readings at the authored 127 units, and the two disagreed with
        // the body all along: a moon this close to an 11.2-radius parent is below the 2.5-parent-radii
        // floor, so `moonLawOf` lifts it — to 1 786 units (714 400 blocks) here, printed below — and
        // it orbits at the lifted distance
        // while the expectation was built from the authored one. The mismatch was a near-miss the old
        // period law happened to keep inside the tolerance (3 815 blocks against a 500-block bar once
        // the law was re-anchored on the real Moon), so the tolerance, not the arrangement, was doing
        // the work. Asking the body for its own distance removes the disagreement entirely.
        // `frame().law()`, and it is now the moon's OWN turn about its parent: a moon's cell rides
        // the moon, so the cell's frame is its parent's displaced by exactly this orbit, and the
        // moon's `offsetLaw` is STATIC. The comment here used to say the opposite for the same
        // reason — the frame was the PARENT's then, and reading it gave the parent's 200 units and a
        // period for an orbit the moon is not on. One level down, the same sentence picks the other
        // accessor. `frame().parent().law()` is what now gives the parent's orbit round the star.
        double actualUnits = moonBody.frame().law().distUnits();
        long massPeriodTicks = (long) (24000d * AstronomicalBodyHelper.getMoonOrbitalPeriod(
                (float) actualUnits, (float) parent.getOrbitalMass()));
        long gravityPeriodTicks = (long) (24000d * AstronomicalBodyHelper.getMoonOrbitalPeriod(
                (float) actualUnits, parent.gravitationalMultiplier));
        assertTrue("mass and gravity must give periods far enough apart to tell apart: "
                        + massPeriodTicks + " vs " + gravityPeriodTicks,
                gravityPeriodTicks > massPeriodTicks * 5);

        // Sampled off the same law, which is where the moon's displacement from its parent lives now.
        BlockDelta start = moonBody.frame().law().offsetAt(0L);
        BlockDelta afterOnePeriod = moonBody.frame().law().offsetAt(massPeriodTicks);
        BlockDelta afterHalf = moonBody.frame().law().offsetAt(massPeriodTicks / 2L);

        // Both bounds are functions of the orbit the moon is ON: half a turn carries it to the far
        // side (about two radii away) and a full turn brings it back to where it started. Stated as
        // fractions of the radius rather than as block counts, so neither can quietly become the
        // thing that passes the test when the layout scale moves again.
        double radiusBlocks = actualUnits * AstronomicalBodyHelper.BLOCKS_PER_DISTANCE_UNIT;
        System.out.println("[moon-period] actualUnits=" + actualUnits + " radiusBlocks=" + radiusBlocks
                + " massPeriodTicks=" + massPeriodTicks + " gravityPeriodTicks=" + gravityPeriodTicks);
        double halfTurn = separation(start, afterHalf);
        double fullTurn = separation(start, afterOnePeriod);
        assertTrue("half a mass-derived period must carry the moon to the far side (was " + halfTurn
                        + ", orbit radius " + radiusBlocks + ")",
                halfTurn > radiusBlocks * 1.5d);
        assertTrue("one mass-derived period must bring it back (was " + fullTurn
                        + ", orbit radius " + radiusBlocks + ")",
                fullTurn < radiusBlocks * 0.02d);
    }

    private static double separation(BlockDelta a, BlockDelta b) {
        double dx = a.dx() - b.dx();
        double dy = a.dy() - b.dy();
        double dz = a.dz() - b.dz();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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

        UniverseRegistry reg = new UniverseRegistry();
        reg.place(GalacticCoord.ORIGIN, 4248);
        UniverseRegistry.setStarLookup(id -> id == 4248 ? star : null);

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
