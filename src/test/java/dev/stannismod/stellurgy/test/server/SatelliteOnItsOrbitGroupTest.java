package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.Disposal;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A satellite on its orbit, as the server holds it: the orbit a release puts it on, its presence where its
 * cell is live, and its destruction (`satellite` MECH-SAT-16, -17, -21; INV-SAT-16, -18, -19).
 *
 * <p>NEW-GROUP: the satellite as an object on its orbit. The other satellite classes hold the builder, the
 * terminal and a satellite's tick — none of them releases a satellite onto an orbit or stands one in a cell.</p>
 *
 * <p>Every satellite here is released by the production per-hatch deploy a rocket's orbit reach runs
 * ({@code satellite deploy}), from a rocket whose guidance computer and hatch a probe arranged. What it does
 * NOT see: the rocket's flight to orbit (the deploy is called where the orbit reach would call it), the
 * client — the panel, the render and the attitude a client receives are the client group's — and the dose
 * a satellite takes, which is not built yet.</p>
 *
 * <p>What each scenario leaves: the rocket it arranged is removed by its id, a satellite that survives is
 * marked dead, a cell it held live is released and a chunk it forced is let go — each in its
 * {@code finally}.</p>
 */
public class SatelliteOnItsOrbitGroupTest extends AbstractSharedServerTest {

    /** The overworld: the body the arranged rockets stand on and release about. */
    private static final int HOME_DIM = 0;
    /** {@code itemSatellitePrimaryFunction} metadata 1: a composition survey — any type is placed the same way. */
    private static final int COMPOSITION = 1;
    /** {@code itemSatellitePowerSource} metadata 0: the small panel; its generation is what a repair swaps. */
    private static final int SMALL_PANEL = 0;
    /**
     * Ticks between a chunk's forced load and its satellite standing up: the materializer acts inside the
     * load itself, so the budget is only the server's catch-up. A deadline, not a verdict: expiry says the
     * entity never stood up.
     */
    private static final int STAND_UP_TICKS = 100;
    /**
     * Ticks between the two sky reads that bracket a satellite's attitude reading: two of the sky feed's
     * and the attitude's 20-tick cadences, so each side's age is covered on either side of the reading.
     */
    private static final int STAR_MOTION_TICKS = 40;
    /** Ticks a primed TNT burns before it detonates (vanilla's fuse, 80) plus the server's catch-up. */
    private static final int FUSE_TICKS = 200;

    private final Events events = new Events(c -> read(c).raw(),
            ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /** A satellite this scenario released, and the rocket that carried it. */
    private static final class Released {
        final int rocketId;
        final long satId;

        Released(int rocketId, long satId) {
            this.rocketId = rocketId;
            this.satId = satId;
        }
    }

    /**
     * Arrange a rocket at {@code site} whose guidance computer is set to {@code altitude} ({@code line} for
     * none) and {@code inclinationDeg}, holding one composition satellite with a small panel; then release it
     * through the production deploy. Answers the rocket and the satellite it held.
     */
    private Released release(FixtureSite site, String altitude, double inclinationDeg) throws Exception {
        Reply rocket = arrange("stellurgytest satellite rocket-with " + site.dim + " " + site.x + " " + site.y + " "
                + site.z + " " + altitude + " " + inclinationDeg + " " + COMPOSITION + " " + SMALL_PANEL)
                .requireOk("arranging a rocket holding one satellite");
        Released released = new Released(rocket.integer("rocketId"), rocket.longInteger("satId"));
        act("stellurgytest satellite deploy " + released.rocketId).requireOk("deploying the rocket's hatch");
        return released;
    }

    /** Give back what {@link #release} made: the rocket by its id, and the satellite if it is still recorded. */
    private void dispose(Released released) throws Exception {
        Disposal.rocket(c -> arrange(c).raw(), released.rocketId);
        Reply place = read("stellurgytest satellite place " + released.satId);
        if (place.bool("present")) {
            arrange("stellurgytest satellite set-dead " + place.integer("dim") + " " + released.satId)
                    .requireOk("marking the satellite dead");
        }
    }

    /**
     * INV-SAT-19: a release goes to the altitude and inclination its guidance computer sets; with no altitude
     * set, to the body's orbit line; and where the body admits no orbit at the altitude set, the satellite
     * stays in the hatch and nothing is released.
     *
     * <p>Expected radii are the body's radius plus the altitude asked for: the radius and the orbit line are
     * parameters of the decision, read from production ({@code planet orbit-line}); the altitudes and the
     * inclination are this experiment's inputs. One kilometre is below every body's descent shell (100 km on
     * an Earth-sized body), so no orbit exists there.</p>
     */
    @Pins("INV-SAT-19")
    @RedWitness(verdict = "altitude", member = "TileGuidanceComputer#statedReleaseAltitudeM",
            at = "releaseAltitudeStated ? OptionalDouble.of(releaseAltitudeM) : OptionalDouble.empty()",
            replacedBy = "OptionalDouble.empty()", fails = "INV-SAT-19: the 500 km release", on = "2026-10-21")
    @RedWitness(verdict = "stays aboard", member = "EntityRocket#deploySatelliteFromHatch",
            at = "satellite.setOrbit(orbit.get());", replacedBy = "", fails = "INV-SAT-19: the line release",
            on = "2026-10-21")
    @Test
    public void aSatelliteGoesToTheOrbitItsGuidanceComputerSets() throws Exception {
        Reply line = read("stellurgytest planet orbit-line " + HOME_DIM);
        ArrangementFailure.requireArranged("the home body must state its radius and orbit line: " + line,
                line.has("radius") && line.has("line"));
        double bodyRadiusM = line.number("radius") * AstronomicalBodyHelper.EARTH_RADIUS_METRES;
        double lineAltitudeM = line.number("line");

        FixtureSite site = clearedSite(1, 3, "a rocket holding a guidance computer and one satellite hatch");
        Released atLine = release(site, "line", 0d);
        Released inclined = release(site, "500", 30d);
        Released tooLow = release(site, "1", 0d);
        try {
            Reply lineSat = read("stellurgytest satellite place " + atLine.satId);
            assertTrue("INV-SAT-19: the line release — the satellite with no altitude set was not put on an orbit: "
                    + lineSat, lineSat.bool("present") && lineSat.bool("hasOrbit"));
            assertEquals("INV-SAT-19: the line release — not at the body's orbit line: " + lineSat,
                    bodyRadiusM + lineAltitudeM, lineSat.number("radiusM"), 1d);
            assertEquals("INV-SAT-19: the line release — not equatorial: " + lineSat,
                    0d, lineSat.number("inclinationRad"), 1e-12d);

            Reply inclinedSat = read("stellurgytest satellite place " + inclined.satId);
            assertTrue("INV-SAT-19: the 500 km release — no orbit: " + inclinedSat,
                    inclinedSat.bool("present") && inclinedSat.bool("hasOrbit"));
            assertEquals("INV-SAT-19: the 500 km release — not at 500 km: " + inclinedSat,
                    bodyRadiusM + 500_000d, inclinedSat.number("radiusM"), 1d);
            assertEquals("INV-SAT-19: the 500 km release — not at 30°: " + inclinedSat,
                    Math.toRadians(30d), inclinedSat.number("inclinationRad"), 1e-12d);

            Reply hatches = read("stellurgytest satellite rocket-hatches " + tooLow.rocketId);
            Reply[] held = hatches.elements("hatches");
            assertTrue("INV-SAT-19: below the descent shell the satellite left the hatch: " + hatches,
                    held.length == 1 && held[0].bool("occupied") && held[0].longInteger("satId") == tooLow.satId);
            assertFalse("INV-SAT-19: below the descent shell a satellite was released anyway",
                    read("stellurgytest satellite place " + tooLow.satId).bool("present"));
        } finally {
            dispose(atLine);
            dispose(inclined);
            dispose(tooLow);
        }
    }

    /** Where a released satellite stands, as production keeps it: the reply of {@code satellite place}. */
    private Reply placeOf(long satId) throws Exception {
        return read("stellurgytest satellite place " + satId);
    }

    /**
     * Make the cell holding {@code point} live and force the chunk the point is in. Answers the slot world.
     * The cell must be a galactic one: the probe that holds a cell live names it by its sector triple.
     */
    private int makeLive(Reply point) throws Exception {
        String cell = point.text("cell");
        ArrangementFailure.requireArranged("the satellite's cell must be a galactic cell to be held live by its"
                + " sectors: " + cell, cell.indexOf('.') < 0);
        Reply occupied = arrange("stellurgytest space occupy " + point.longInteger("sx") + " "
                + point.longInteger("sy") + " " + point.longInteger("sz")).requireOk("holding the cell live");
        int slotDim = occupied.integer("slotDim");
        arrange("stellurgytest chunk forceload " + slotDim + " " + (point.integer("x") >> 4) + " "
                + (point.integer("z") >> 4)).requireOk("forcing the chunk the satellite stands in");
        return slotDim;
    }

    /** Let go of what {@link #makeLive} held. */
    private void letGo(Reply point, int slotDim) throws Exception {
        arrange("stellurgytest chunk release " + slotDim + " " + (point.integer("x") >> 4) + " "
                + (point.integer("z") >> 4));
        arrange("stellurgytest space release " + point.longInteger("sx") + " " + point.longInteger("sy") + " "
                + point.longInteger("sz"));
    }

    /** Whether {@code record} stands at {@code point}'s place. */
    private static boolean standsAt(String record, Reply point) {
        return Events.number(record, "x") == point.integer("x") && Events.number(record, "y") == point.integer("y")
                && Events.number(record, "z") == point.integer("z");
    }

    /**
     * INV-SAT-16: a released satellite is present nowhere while its cell is not live — and the moment the
     * chunk of its live cell that holds its point loads, it stands up there, at exactly that point.
     *
     * <p>The negative half is asserted on the same instrument as the positive: every satellite entity that
     * joined any world since the release is counted, and the only one is the one that joined after the chunk
     * loaded. The point is production's ({@code SatellitePlacement#standingPoint}, read through
     * {@code satellite place}); its law is the fast tier's (INV-SAT-11..13), this pins that the entity is put
     * where that law says.</p>
     */
    @Pins("INV-SAT-16")
    @RedWitness(verdict = "stands up", member = "SatelliteMaterializer#onChunkLoad",
            at = "standUp(world, satellite, pos);", replacedBy = "", fails = "the satellite never stood up",
            on = "2026-10-21")
    @RedWitness(verdict = "at its point", member = "SatelliteMaterializer#standUp",
            at = "new EntitySatellite(world, satellite.getId(), pos[0], pos[1], pos[2])",
            replacedBy = "new EntitySatellite(world, satellite.getId(), pos[0] + 1, pos[1], pos[2])",
            fails = "INV-SAT-16: it stood up somewhere other than its point", on = "2026-10-21")
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advance(client(), GameTicks.server(), STAND_UP_TICKS)",
            why = "the window in which nothing may join: its length is the dose of the negative half, and the"
                    + " positive half below is linked on the join record itself")
    @Test
    public void aSatelliteStandsAtItsPointOnlyWhereItsCellIsLive() throws Exception {
        FixtureSite site = clearedSite(1, 3, "a rocket holding a guidance computer and one satellite hatch");
        long sinceRelease = events.mark();
        Released released = release(site, "line", 51.6d);
        Reply placed = placeOf(released.satId);
        ArrangementFailure.requireArranged("the released satellite must have a point to stand at: " + placed,
                placed.bool("present") && placed.has("point"));
        Reply point = Reply.of(placed.object("point"));
        ArrangementFailure.requireArranged("its cell must not be live yet: " + placed, !placed.has("slotDim"));
        GameTicks.advance(client(), GameTicks.server(), STAND_UP_TICKS);

        long sinceLive = events.mark();
        int slotDim = makeLive(point);
        try {
            String joined = events.awaitRecordWithFields(sinceLive, "entity_joined_world",
                    "the satellite never stood up in its live cell", STAND_UP_TICKS,
                    "cls", "EntitySatellite", "dim", String.valueOf(slotDim));
            assertTrue("INV-SAT-16: it stood up somewhere other than its point " + point + ": " + joined,
                    standsAt(joined, point));

            List<String> satellites = Events.recordsWhere(events.since(sinceRelease, "entity_joined_world"),
                    "cls", "EntitySatellite");
            assertEquals("INV-SAT-16: a satellite stood up while its cell was not live: " + satellites,
                    1, satellites.size());
        } finally {
            letGo(point, slotDim);
            dispose(released);
        }
    }

    /** The unit vector toward the star of {@code slotDim}'s sky and its distance, as the sky feed sends them. */
    private double[] skyStar(int slotDim) throws Exception {
        Reply sky = read("stellurgytest space bodies");
        for (Reply cell : sky.elements("feed")) {
            if (cell.integer("slotDim") != slotDim) {
                continue;
            }
            for (Reply body : cell.elements("bodies")) {
                if (body.integer("kind") == dev.stannismod.stellurgy.universe.SystemBodyKind.STAR.ordinal()) {
                    return new double[]{body.arrayNumber("dir", 0), body.arrayNumber("dir", 1),
                            body.arrayNumber("dir", 2), body.number("distance")};
                }
            }
        }
        throw new ArrangementFailure("the sky of slot " + slotDim + " draws no star: " + sky);
    }

    private static double angle(double[] a, double[] b) {
        double la = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        double lb = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
        double cos = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (la * lb);
        return Math.acos(Math.max(-1d, Math.min(1d, cos)));
    }

    /**
     * INV-SAT-20: the star a standing satellite's panels face is the star its cell's sky draws — the
     * satellite's attitude and the sky feed name one direction.
     *
     * <p>A joint: the entity's synced attitude against the sky feed's star vector, two productions read
     * separately. They may differ by two things only, both measured here: the sky is drawn from the cell's
     * observer and the satellite stands away from it (the angle that offset subtends at the star's
     * distance), and either side may be up to its 20-tick cadence old (the angle the star moves on the feed
     * between two reads that bracket the satellite's reading).</p>
     */
    @Pins("INV-SAT-20")
    @RedWitness(member = "EntitySatellite#onUpdate",
            at = "dataManager.set(TO_STAR, rotations(attitude.get().toStar()));",
            replacedBy = "dataManager.set(TO_STAR, rotations(attitude.get().toBody()));",
            fails = "INV-SAT-20", on = "2026-10-21")
    @Test
    public void theStarASatelliteFacesIsTheStarItsSkyDraws() throws Exception {
        FixtureSite site = clearedSite(1, 3, "a rocket holding a guidance computer and one satellite hatch");
        Released released = release(site, "line", 51.6d);
        Standing standing = null;
        try {
            standing = standingSatellite(released);
            ArrangementFailure.requireArranged("the sky must be seen from its cell's centre, where no ship stands: "
                    + standing.point, !read("stellurgytest space bodies").holdsElement("ships", "cell",
                    standing.point.text("cell")));
            double[] before = skyStar(standing.slotDim);
            advanceAcrossBothCadences();
            Reply placed = placeOf(released.satId);
            ArrangementFailure.requireArranged("the satellite must carry an attitude: " + placed,
                    placed.integer("entities") == 1);
            double[] faces = {placed.arrayNumber("toStar", 0), placed.arrayNumber("toStar", 1),
                    placed.arrayNumber("toStar", 2)};
            advanceAcrossBothCadences();
            double[] after = skyStar(standing.slotDim);

            Reply p = standing.point;
            double offset = Math.sqrt((double) p.integer("x") * p.integer("x")
                    + (double) p.integer("y") * p.integer("y") + (double) p.integer("z") * p.integer("z"));
            // The synced vector is three floats: well under a microradian.
            double allowed = angle(before, after) + Math.asin(Math.min(1d, offset / before[3])) + 1e-6d;
            double off = Math.max(angle(faces, before), angle(faces, after));
            assertTrue("INV-SAT-20: the panels face " + Math.toDegrees(off) + "° off the sky's star, beyond the "
                    + Math.toDegrees(allowed) + "° the cadence and the observer's offset allow", off <= allowed);
        } finally {
            if (standing != null) {
                letGo(standing.point, standing.slotDim);
            }
            dispose(released);
        }
    }

    /** Advance two of the sky feed's and the attitude's 20-tick cadences, so each side is recomputed inside it. */
    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advance(client(), GameTicks.server(), STAR_MOTION_TICKS);",
            why = "the star moves on the feed; feed reads either side of the satellite's reading, each this far"
                    + " apart, measure how far it moves across both sides' cadence — the bound the comparison is"
                    + " allowed. No record names a recomputation of either")
    private void advanceAcrossBothCadences() throws Exception {
        GameTicks.advance(client(), GameTicks.server(), STAR_MOTION_TICKS);
    }

    /** A released satellite standing in its live cell: its entity, the slot world, and its point. */
    private static final class Standing {
        final int entityId;
        final int slotDim;
        final Reply point;

        Standing(int entityId, int slotDim, Reply point) {
            this.entityId = entityId;
            this.slotDim = slotDim;
            this.point = point;
        }

        String at() {
            return slotDim + " " + point.integer("x") + " " + point.integer("y") + " " + point.integer("z");
        }
    }

    /** Make a released satellite's point live and wait for it to stand up — where every destruction starts. */
    private Standing standingSatellite(Released released) throws Exception {
        Reply point = Reply.of(placeOf(released.satId).object("point"));
        long sinceLive = events.mark();
        int slotDim = makeLive(point);
        String joined = events.awaitRecordWithFields(sinceLive, "entity_joined_world",
                "the satellite must stand up before it can be destroyed", STAND_UP_TICKS,
                "cls", "EntitySatellite", "dim", String.valueOf(slotDim));
        return new Standing((int) Events.number(joined, "e"), slotDim, point);
    }

    /** Whether {@code record} is an explosion at the satellite's own place: centred on it, half a block up. */
    private static boolean explodedAt(String record, Standing standing) {
        return Events.number(record, "x") == standing.point.integer("x")
                && Events.number(record, "y") == standing.point.integer("y") + 0.5d
                && Events.number(record, "z") == standing.point.integer("z");
    }

    /**
     * INV-SAT-18: one blow from a player leaves a satellite standing; two in a row destroy it; so does an
     * explosion. Destroyed, it leaves nothing: its record is gone, no satellite stands at its point, and
     * nothing lies there — only its own explosion is seen.
     *
     * <p>The blows are a player's attack packets, handed to the server's own handler; both of the pair land in
     * one call, so they are one tick apart at most whatever the box's speed. The explosion is vanilla TNT's.</p>
     */
    @Pins("INV-SAT-18")
    @RedWitness(verdict = "two blows", member = "EntitySatellite#attackEntityFrom",
            at = "lastBlowTick >= 0L && now - lastBlowTick <= SECOND_BLOW_WINDOW_TICKS", replacedBy = "false",
            fails = "the satellite's own explosion after two blows", on = "2026-10-21")
    @RedWitness(verdict = "one blow", member = "EntitySatellite#attackEntityFrom",
            at = "lastBlowTick >= 0L && now - lastBlowTick <= SECOND_BLOW_WINDOW_TICKS", replacedBy = "true",
            fails = "INV-SAT-18: one blow destroyed it", on = "2026-10-21")
    @RedWitness(verdict = "leaves nothing", member = "EntitySatellite#destroy",
            at = "props.removeSatellite(sat.getId());", replacedBy = "",
            fails = "INV-SAT-18: two blows left its record", on = "2026-10-21")
    @Test
    public void aSatelliteDestroyedLeavesNothing() throws Exception {
        FixtureSite site = clearedSite(1, 3, "a rocket holding a guidance computer and one satellite hatch");
        Released struck = release(site, "line", 51.6d);
        Released bombed = release(site, "500", 51.6d);
        Standing standing = null;
        Standing target = null;
        try {
            standing = standingSatellite(struck);
            // A TRIGGER only for the dimension change it fires; the player is arrangement here.
            act("stellurgytest player ensure-fake " + standing.slotDim + " " + (standing.point.integer("x") + 2)
                    + " " + standing.point.integer("y") + " " + standing.point.integer("z"))
                    .requireOk("standing the test player beside the satellite");

            act("stellurgytest satellite blow " + standing.entityId + " 1").requireOk("one blow");
            Reply afterOne = placeOf(struck.satId);
            assertTrue("INV-SAT-18: one blow destroyed it: " + afterOne,
                    afterOne.bool("present") && afterOne.integer("entities") == 1);

            long sinceBlows = events.mark();
            act("stellurgytest satellite blow " + standing.entityId + " 2").requireOk("two blows");
            final Standing struckAt = standing;
            events.awaitMatching(sinceBlows, "explosion_detonated",
                    reply -> Events.records(reply).stream().anyMatch(r -> explodedAt(r, struckAt)),
                    "at the satellite's own place", "the satellite's own explosion after two blows", STAND_UP_TICKS);
            Reply afterTwo = placeOf(struck.satId);
            assertFalse("INV-SAT-18: two blows left its record: " + afterTwo, afterTwo.bool("present"));
            assertNothingIsLeft(standing);

            target = standingSatellite(bombed);
            long sinceFuse = events.mark();
            arrange("stellurgytest satellite prime-tnt " + target.at()).requireOk("priming TNT at the satellite");
            final Standing bombedAt = target;
            events.awaitMatching(sinceFuse, "explosion_detonated",
                    reply -> Events.records(reply).stream().anyMatch(r -> explodedAt(r, bombedAt)),
                    "at the satellite's own place", "the satellite's own explosion after the TNT's", FUSE_TICKS);
            Reply afterTnt = placeOf(bombed.satId);
            assertFalse("INV-SAT-18: the explosion left its record: " + afterTnt, afterTnt.bool("present"));
            assertNothingIsLeft(target);
        } finally {
            if (standing != null) {
                letGo(standing.point, standing.slotDim);
            }
            if (target != null) {
                letGo(target.point, target.slotDim);
            }
            dispose(struck);
            dispose(bombed);
        }
    }

    /** Nothing stands or lies where a destroyed satellite stood: no satellite entity, no loose item. */
    private void assertNothingIsLeft(Standing standing) throws Exception {
        Reply left = read("stellurgytest satellite leftovers " + standing.at());
        assertEquals("INV-SAT-18: a satellite still stands where it was destroyed: " + left, 0, left.integer("satellites"));
        assertEquals("INV-SAT-18: something lies where it was destroyed: " + left, 0, left.integer("items"));
    }
}
