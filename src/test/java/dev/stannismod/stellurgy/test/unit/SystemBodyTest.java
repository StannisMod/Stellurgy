package dev.stannismod.stellurgy.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.space.AbsolutePos;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.universe.BodyEphemeris;
import dev.stannismod.stellurgy.universe.CellFrame;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.SystemBodyKind;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Contract tests for {@link SystemBody}: the split between a body's durable NAME — an identifier no
 * tick changes, and one a moon shares with its parent — and where it actually is, which rides the
 * body its cell is the neighbourhood of; its NBT round-trip (used by the universe registry's POI and
 * pinned stores) and the descend-target rule. Pure-JUnit.
 */
public class SystemBodyTest {

    /** An orbit big enough that a tick apart is a different place, with a short period so it moves. */
    private static BodyEphemeris orbit(double distUnits, long unitBlocks) {
        return BodyEphemeris.orbit(distUnits, 0.0, 0.0, false, 1000d, unitBlocks);
    }

    @Test
    public void nbtRoundTripPreservesEveryField() {
        SystemBody body = SystemBody.fixedAt(GalacticCoord.ofSectorLocal(4, -5, 6, 123_456, -7_890, 42),
                SystemBodyKind.STATION_SLOT, 815, -12345);
        NBTTagCompound tag = new NBTTagCompound();
        body.writeToNBT(tag);
        SystemBody round = SystemBody.readFromNBT(tag);
        assertEquals(body, round);
        assertEquals(body.name(), round.name());
        assertEquals(body.addressAt(0L), round.addressAt(0L));
        assertEquals(SystemBodyKind.STATION_SLOT, round.kind());
        assertEquals(815, round.dimId());
        assertEquals(-12345, round.starId());
    }

    /**
     * A name is not a place. The whole point of the model: a cell name is an identifier, so no amount
     * of time changes it. The second half is the control — without it a body that never moves would
     * pass.
     * Pins ADDR-1 (a body's cell name is the same at every tick while its place is not).
     */
    @Test
    public void aNameIsTheSameAtEveryTickWhileThePlaceIsNot() {
        GalacticCoord name = GalacticCoord.ofSectorLocal(57, 0, 5, 0, 0, 0);
        SystemBody planet = new SystemBody(name,
                CellFrame.of(AbsolutePos.ofCellName(name), orbit(200d, 1_000_000L)),
                BodyEphemeris.STATIC, SystemBodyKind.PLANET, 3, 0);

        assertEquals(name, planet.name());
        // (`planet.name()` was compared with itself here, at one instant, under the message "a
        // name is not a function of time". The loop below is what varies the time.)
        for (long tick : new long[]{0L, 137L, 250L, 500_000L}) {
            assertEquals("tick " + tick + " renamed the cell", name.cellKey(),
                    planet.addressAt(tick).cellKey());
        }
        assertNotEquals("the fixture's body never moves, so nothing above was tested",
                planet.absoluteAt(0L), planet.absoluteAt(250L));
    }

    /**
     * A cell's primary body is what its frame is centred on, so its in-cell offset is zero.
     * Pins ADDR-6 (the cell of a primary rides its body while a void cell stands still).
     */
    @Test
    public void aPrimarySitsAtItsOwnFramesOrigin() {
        GalacticCoord name = GalacticCoord.ofSectorLocal(1, 2, 3, 0, 0, 0);
        CellFrame frame = CellFrame.of(AbsolutePos.ofCellName(name), orbit(200d, 1_000_000L));
        SystemBody planet = new SystemBody(name, frame, BodyEphemeris.STATIC,
                SystemBodyKind.PLANET, 4, 0);

        for (long tick : new long[]{0L, 300L, 999_999L}) {
            assertTrue("a primary drifted off its own frame origin at tick " + tick,
                    planet.inCellOffsetAt(tick).isZero());
            assertEquals(frame.originAt(tick), planet.absoluteAt(tick));
        }
    }

    /**
     * A moon shares its parent's cell NAME and rides its parent's frame, while keeping its own live
     * offset inside it — that is what makes a planet-and-its-moons one destination.
     *
     * <p>red-witnessed: 2026-09-30, with {@code SystemBody#inCellOffsetAt} at {@code offsetLaw.offsetAt(tick)} (then bound to a local
     * {@code raw}, since returned directly) reading the offset law at
     * tick 0 instead of the tick asked, this fails with "a moon's offset inside its parent's cell is
     * live. Actual: BlockDelta[120000,0,0]" — 300 units at 400 blocks each, the fixture printed.</p>
     */
    @Test
    public void aMoonKeepsItsParentsNameAndMovesInsideIt() {
        GalacticCoord name = GalacticCoord.ofSectorLocal(9, 0, 0, 0, 0, 0);
        CellFrame parentFrame = CellFrame.of(AbsolutePos.ofCellName(name), orbit(200d, 1_000_000L));
        SystemBody moon = new SystemBody(name, parentFrame, orbit(300d,
                dev.stannismod.stellurgy.util.AstronomicalBodyHelper.BLOCKS_PER_DISTANCE_UNIT),
                SystemBodyKind.MOON, 11, 0);

        assertEquals(name, moon.name());
        assertEquals(name.cellKey(), moon.addressAt(400L).cellKey());
        assertNotEquals("a moon's offset inside its parent's cell is live",
                moon.inCellOffsetAt(0L), moon.inCellOffsetAt(300L));
        assertFalse("a moon is not at its parent's centre", moon.inCellOffsetAt(0L).isZero());
    }

    /**
     * What "a cell rides the body in it" demands of the pinned store, and the reason a body carries a
     * LAW rather than a position: a pin freezes the ELEMENTS. Pin-on-touch fires the first time a
     * player builds a station in a system, so a pin that froze positions would stop that system for
     * the rest of the save — and nothing would ever say so.
     * Pins ADDR-6 (the cell of a primary rides its body while a void cell stands still).
     */
    @Test
    public void aBodyStillMovesAfterAnNbtRoundTrip() {
        GalacticCoord name = GalacticCoord.ofSectorLocal(20, 0, 0, 0, 0, 0);
        SystemBody planet = new SystemBody(name,
                CellFrame.of(AbsolutePos.ofCellName(name), orbit(200d, 1_000_000L)),
                BodyEphemeris.STATIC, SystemBodyKind.PLANET, 6, 2);

        NBTTagCompound tag = new NBTTagCompound();
        planet.writeToNBT(tag);
        SystemBody round = SystemBody.readFromNBT(tag);

        assertEquals(planet.absoluteAt(0L), round.absoluteAt(0L));
        assertEquals(planet.absoluteAt(250L), round.absoluteAt(250L));
        assertNotEquals("a round-tripped body was frozen where it stood", round.absoluteAt(0L),
                round.absoluteAt(250L));
    }

    /** A POI is re-bound to its cell's frame when served, so a station rides the planet it orbits. */
    @Test
    public void aBodyRebindsToTheFrameOfTheCellItIsServedFrom() {
        GalacticCoord name = GalacticCoord.ofSectorLocal(3, 0, 0, 5_000, 0, 0);
        SystemBody station = SystemBody.fixedAt(name, SystemBodyKind.STATION_SLOT,
                Constants.INVALID_PLANET, 0);
        assertEquals("a bare POI stands still", station.absoluteAt(0L), station.absoluteAt(500L));

        CellFrame moving = CellFrame.of(AbsolutePos.ofCellName(name), orbit(200d, 1_000_000L));
        SystemBody carried = station.withFrame(moving);
        assertEquals("re-binding a frame may not move the station inside its cell",
                station.inCellOffsetAt(0L), carried.inCellOffsetAt(0L));
        assertNotEquals("a station in a body's cell must travel with it",
                carried.absoluteAt(0L), carried.absoluteAt(500L));
    }

    /**
     * A body with MASS defines a frame; content without any rides the frame of the cell it stands in.
     *
     * <p>A MOON is on the first side of that line: it has a cell of its own inside its parent's zone,
     * and that cell rides the moon. While it shared its parent's name
     * it could not define one — two bodies cannot both be the primary of one cell — and that is what
     * left a craft parked beside a moon riding the PLANET and drifting away from the moon.</p>
     * Pins ADDR-17 (a body with mass has a zone and its name is its cell in its parent's zone, and a massless body defines none).
     */
    @Test
    public void onlyBodiesWithMassDefineACellsFrame() {
        GalacticCoord at = GalacticCoord.ORIGIN;
        assertTrue(SystemBody.fixedAt(at, SystemBodyKind.STAR, Constants.INVALID_PLANET, 0).definesFrame());
        assertTrue(SystemBody.fixedAt(at, SystemBodyKind.PLANET, 1, 0).definesFrame());
        assertTrue(SystemBody.fixedAt(at, SystemBodyKind.GAS_GIANT, 2, 0).definesFrame());
        assertTrue(SystemBody.fixedAt(at, SystemBodyKind.ASTEROID_BELT,
                Constants.INVALID_PLANET, 0).definesFrame());
        assertTrue("a moon owns its own cell and that cell rides it",
                SystemBody.fixedAt(at, SystemBodyKind.MOON, 3, 0).definesFrame());
        assertFalse("a station has no mass, so it rides whatever its cell rides",
                SystemBody.fixedAt(at, SystemBodyKind.STATION_SLOT,
                        Constants.INVALID_PLANET, 0).definesFrame());
    }

    @Test
    public void descendTargetOnlyForPlanetOrMoonWithARealDimension() {
        GalacticCoord at = GalacticCoord.ofSectorLocal(1, 1, 1, 10, 20, 30);
        assertTrue(SystemBody.fixedAt(at, SystemBodyKind.PLANET, 7, 1).isDescendTarget());
        assertTrue(SystemBody.fixedAt(at, SystemBodyKind.MOON, 8, 1).isDescendTarget());
        assertFalse("a planet with no realized dim is not yet a descent target",
                SystemBody.fixedAt(at, SystemBodyKind.PLANET, Constants.INVALID_PLANET, 1).isDescendTarget());
        assertFalse(SystemBody.fixedAt(at, SystemBodyKind.STAR, Constants.INVALID_PLANET, 1).isDescendTarget());
        assertFalse(SystemBody.fixedAt(at, SystemBodyKind.STATION_SLOT, Constants.INVALID_PLANET, 1).isDescendTarget());
        assertFalse(SystemBody.fixedAt(at, SystemBodyKind.ASTEROID_BELT, Constants.INVALID_PLANET, 1).isDescendTarget());
    }

    @Test
    public void unknownKindDecodesToAnInertPoiRatherThanCrashing() {
        NBTTagCompound tag = new NBTTagCompound();
        SystemBody.fixedAt(GalacticCoord.ORIGIN, SystemBodyKind.PLANET, 5, 1).writeToNBT(tag);
        tag.setString("kind", "SOME_FUTURE_KIND"); // a kind this version doesn't know
        SystemBody round = SystemBody.readFromNBT(tag);
        assertEquals(SystemBodyKind.STATION_SLOT, round.kind());
        assertFalse(round.isDescendTarget());
    }

    @Test
    public void kindDescendCapability() {
        assertTrue(SystemBodyKind.PLANET.canDescend());
        assertTrue(SystemBodyKind.MOON.canDescend());
        assertFalse(SystemBodyKind.STAR.canDescend());
        assertFalse(SystemBodyKind.ASTEROID_BELT.canDescend());
        assertFalse(SystemBodyKind.STATION_SLOT.canDescend());
    }

    @Test
    public void aBodyCarriesItsOwnRadiusThroughNbt() {
        // A body's SIZE travels with it: nothing downstream can recover it (a procedural world has no
        // dimension until a descent mints one, and the render feed reaches a client with no registry).
        SystemBody sized = SystemBody.fixedAt(GalacticCoord.ORIGIN, SystemBodyKind.GAS_GIANT,
                Constants.INVALID_PLANET, 4).withRadius(11.2d);
        NBTTagCompound tag = new NBTTagCompound();
        sized.writeToNBT(tag);
        assertEquals(11.2d, SystemBody.readFromNBT(tag).radiusEarths(), 1e-9);

        // A body that is not a sphere says so, and says it the same way after a round trip — the
        // renderer draws that as a marker rather than inventing a size for it.
        SystemBody belt = SystemBody.fixedAt(GalacticCoord.ORIGIN, SystemBodyKind.ASTEROID_BELT,
                Constants.INVALID_PLANET, 4);
        NBTTagCompound beltTag = new NBTTagCompound();
        belt.writeToNBT(beltTag);
        assertEquals(SystemBody.RADIUS_UNKNOWN, SystemBody.readFromNBT(beltTag).radiusEarths(), 0d);
        assertFalse("an unstated radius writes no key at all", beltTag.hasKey("radiusEarths"));
    }

    /**
     * A body whose in-cell offset would reach past its own cell is a fault of whatever derived it,
     * so it is refused when built rather than flattened onto the cell face — where every point of
     * the orbit beyond the face lands on one spot and the body looks as if it stopped moving.
     *
     * <p>red-witnessed: with {@code SystemBody#requireInsideOwnCell} at
     * {@code if (!offsetLaw.staysWithin(-half, half - 1L))} inverted to never throw, this fails with
     * "a moon whose orbit is wider than the cell that names it must be refused" (2026-10-02).</p>
     */
    @Test
    public void aBodyWhoseOffsetLeavesItsOwnCellIsRefusedWhenBuilt() {
        GalacticCoord giantCell = GalacticCoord.ofSectorLocal(9, 0, -3, 0, 0, 0);
        CellFrame frame = CellFrame.staticAt(giantCell);

        SystemBody inner = new SystemBody(giantCell, frame, orbit(2d, 100_000L),
                SystemBodyKind.MOON, Constants.INVALID_PLANET, 1);
        assertEquals("a moon that fits stays in the giant's cell", giantCell.cellCentre(), inner.name());

        try {
            new SystemBody(giantCell, frame, orbit(4d, GalacticCoord.HALF_CELL), SystemBodyKind.MOON,
                    Constants.INVALID_PLANET, 1);
            fail("a moon whose orbit is wider than the cell that names it must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue("the refusal names the cell it overflowed: " + expected.getMessage(),
                    expected.getMessage().contains("outside its own cell"));
        }
    }
}
