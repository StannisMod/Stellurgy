package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.api.satellite.SatelliteOrbit;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Regions;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import org.junit.Test;

import java.util.List;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Satellite Control Center as the server decides it: what each try comes to (`satellite` INV-SAT-07),
 * when a terminal receives (INV-SAT-38) and when it is out of range (INV-SAT-39).
 *
 * <p>Every verdict is read off {@code terminal_try}, the record the terminal's own settle writes
 * ({@code MixinTileSatelliteTerminalEvents}), named by the terminal's place; the terminal tries by its own
 * tick, a probe never makes it try. A released satellite is released by the production per-hatch deploy
 * from a rocket a probe arranged. What it does NOT see: the client's screen — the readout's pages are drawn
 * from the terminal's copy, which this class never reads — and the player's Download button (a try the poll
 * makes is the same try).</p>
 *
 * <p>What each scenario leaves: the rocket it arranged is removed by its id, a released satellite is marked
 * dead and the chunks it forced are let go — each in its {@code finally}; a terminal block stays in its
 * plot, which is never reused, and a station stays registered on its own grid cell, which no other
 * station is given.</p>
 */
public class SatelliteTerminalChipRecognitionTest extends AbstractSharedServerTest {

    private static final String TRY = "terminal_try";

    /** The terminal's poll period, ticks — {@code TileSatelliteTerminal#POLL_PERIOD_TICKS}, the threshold a try's tick is placed against. */
    private static final int POLL_TICKS = 16;
    /** A link's deadline for a terminal's next tries: a few poll periods. Loosened only. */
    private static final int TRIES_BUDGET = 200;
    /** The power a terminal is given: far more tries than a scenario makes. */
    private static final int TERMINAL_POWER = 5000;

    /**
     * A chip whose satellite was erased names no satellite: every try after it says so (INV-SAT-07,
     * `no satellite`). Read over the next two records, so a try that came to anything else is seen.
     */
    @Pins("INV-SAT-07")
    @Regions(of = "INV-SAT-07", value = {"no satellite"})
    @RedWitness(verdict = "no satellite", member = "TileSatelliteTerminal#tryContact",
            at = "return Outcome.NO_SATELLITE;", replacedBy = "return Outcome.OUT_OF_SIGHT;",
            fails = "INV-SAT-07: no satellite", on = "2026-10-21")
    @Test
    public void aTerminalWhoseChipNamesNoSatelliteSaysSo() throws Exception {
        FixtureSite site = site();
        Events events = events();
        int[] at = {site.x, site.y, site.z};
        try {
            placeTerminal(0, at, true);
            loadChip(0, at, builtSatellite());
            long mark = events.markInstrumented();
            act("stellurgytest satellite-terminal press-erase 0 " + at[0] + " " + at[1] + " " + at[2]).requireOk("erase");
            List<String> tries = awaitTries(events, mark, 0, at, 3, "INV-SAT-07: no satellite — the terminal's tries after the erase");
            for (String one : tries) {
                assertEquals("INV-SAT-07: no satellite — a try after the erase: " + one, "NO_SATELLITE",
                        Events.text(one, "outcome"));
            }
        } finally {
            letGo(0, at);
        }
    }

    /** A chip and no power: the terminal says no power (INV-SAT-07, `no power`). */
    @Pins("INV-SAT-07")
    @Regions(of = "INV-SAT-07", value = {"no power"})
    @RedWitness(verdict = "no power", member = "TileSatelliteTerminal#notEnoughEnergyForFunction",
            at = "settle(Outcome.NO_POWER);", replacedBy = "settle(tryContact());",
            fails = "INV-SAT-07: no power", on = "2026-10-21")
    @Test
    public void aTerminalWithoutPowerSaysSo() throws Exception {
        FixtureSite site = site();
        Events events = events();
        int[] at = {site.x, site.y, site.z};
        try {
            placeTerminal(0, at, false);
            long mark = events.markInstrumented();
            loadChip(0, at, builtSatellite());
            List<String> tries = awaitTries(events, mark, 0, at, 3, "INV-SAT-07: no power — the unpowered terminal's tries");
            assertEquals("INV-SAT-07: no power — the last try of a terminal with no power: " + tries, "NO_POWER",
                    Events.text(tries.get(tries.size() - 1), "outcome"));
        } finally {
            letGo(0, at);
        }
    }

    /** A satellite built and never launched is on no orbit (INV-SAT-07, `not in orbit`). */
    @Pins("INV-SAT-07")
    @Regions(of = "INV-SAT-07", value = {"not in orbit"})
    @RedWitness(verdict = "not in orbit", member = "TileSatelliteTerminal#tryContact",
            at = "return Outcome.NOT_IN_ORBIT;", replacedBy = "return Outcome.OUT_OF_SIGHT;",
            fails = "INV-SAT-07: not in orbit", on = "2026-10-21")
    @Test
    public void aSatelliteStillOnTheGroundIsNotInOrbit() throws Exception {
        FixtureSite site = site();
        Events events = events();
        int[] at = {site.x, site.y, site.z};
        try {
            placeTerminal(0, at, true);
            long mark = events.markInstrumented();
            loadChip(0, at, builtSatellite());
            List<String> tries = awaitTries(events, mark, 0, at, 2, "INV-SAT-07: not in orbit — the terminal's tries");
            assertEquals("INV-SAT-07: not in orbit — a satellite fresh from the builder: " + tries, "NOT_IN_ORBIT",
                    Events.text(tries.get(tries.size() - 1), "outcome"));
        } finally {
            letGo(0, at);
        }
    }

    /**
     * A terminal in the Nether — a world Stellurgy registers no body for, so it lies in no planetary system —
     * with an Earth satellite released on its chip is out of range (INV-SAT-39). The satellite is on an
     * orbit, so a gate that read the Nether as Earth would carry the try on to the question of place.
     */
    @Pins("INV-SAT-39")
    @RedWitness(member = "TileSatelliteTerminal#tryContact",
            at = "DimensionProperties here = DimensionManager.getEffectiveBodyOrNull(world, pos);",
            replacedBy = "DimensionProperties here = DimensionManager.getEffectiveDimId(world, pos);",
            fails = "INV-SAT-39: out of range", on = "2026-10-22")
    @Test
    public void aTerminalInAWorldOutsideEverySystemIsOutOfRange() throws Exception {
        FixtureSite site = site();
        Events events = events();
        int[] nether = {site.x, site.y, site.z};
        int[] pad = {site.x, site.y, site.z};
        Released sat = null;
        try {
            placeTerminal(-1, nether, true);
            sat = release(pad);
            long mark = events.markInstrumented();
            loadChip(-1, nether, sat.id);
            List<String> tries = awaitTries(events, mark, -1, nether, 2, "INV-SAT-39: out of range — the Nether terminal's tries");
            for (String one : tries) {
                assertEquals("INV-SAT-39: out of range — a try of a terminal in the Nether: " + one, "OUT_OF_RANGE",
                        Events.text(one, "outcome"));
            }
        } finally {
            dispose(sat);
            letGo(-1, nether);
        }
    }

    /**
     * A terminal on a station orbiting Earth, with an Earth satellite released on its chip, has no place to
     * see from (INV-SAT-07, `no place`): the station puts it inside Earth's planetary system, so the range
     * gate lets the try through, and the station's world is no body's surface.
     */
    @Pins("INV-SAT-07")
    @Regions(of = "INV-SAT-07", value = {"no place"})
    @RedWitness(verdict = "no place", member = "TileSatelliteTerminal#tryContact",
            at = "return Outcome.NO_PLACE;", replacedBy = "return Outcome.OUT_OF_SIGHT;",
            fails = "INV-SAT-07: no place", on = "2026-10-22")
    @Test
    public void aTerminalOnAStationHasNoPlaceToSeeFrom() throws Exception {
        FixtureSite site = site();
        Events events = events();
        int[] pad = {site.x, site.y, site.z};
        int spaceDim = stationWorld();
        int[] aboard = stationOverEarth();
        Released sat = null;
        try {
            placeTerminal(spaceDim, aboard, true);
            sat = release(pad);
            long mark = events.markInstrumented();
            loadChip(spaceDim, aboard, sat.id);
            List<String> tries = awaitTries(events, mark, spaceDim, aboard, 2, "INV-SAT-07: no place — the station terminal's tries");
            for (String one : tries) {
                assertEquals("INV-SAT-07: no place — a try of a terminal on a station: " + one, "NO_PLACE",
                        Events.text(one, "outcome"));
            }
        } finally {
            dispose(sat);
            letGo(spaceDim, aboard);
        }
    }

    /**
     * A released satellite whose primary function was taken out is dead in orbit: in its window, beneath it
     * right after its release, the terminal says silent — in sight, no answer — never out of sight
     * (INV-SAT-07, `silent`).
     */
    @Pins("INV-SAT-07")
    @Regions(of = "INV-SAT-07", value = {"silent"})
    @RedWitness(verdict = "silent", member = "TileSatelliteTerminal#tryContact",
            at = "if (!SatelliteLife.functioning(sat)) {", replacedBy = "if (false) {",
            fails = "INV-SAT-07: silent", on = "2026-10-21")
    @Test
    public void aDeadSatelliteIsSilentInItsWindow() throws Exception {
        FixtureSite site = site();
        Events events = events();
        int[] at = {site.x + 3, site.y, site.z};
        int[] pad = {site.x, site.y, site.z};
        Released sat = null;
        try {
            placeTerminal(0, at, true);
            sat = release(pad);
            Reply emptied = act("stellurgytest satellite repair " + sat.id + " 0 empty").requireOk("take the primary out");
            requireArranged("the primary function's slot must be empty: " + emptied.raw(), !emptied.has("item")
                    || "null".equals(emptied.textOr("item", "null")));
            long mark = events.markInstrumented();
            loadChip(0, at, sat.id);
            Geometry g = geometry(sat.id, at);
            long loss = g.firstTick(false, g.epoch, g.epoch + 20_000L);
            List<String> tries = awaitTries(events, mark, 0, at, 2, "INV-SAT-07: silent — the terminal's tries in the window");
            String first = tries.get(0);
            requireArranged("the first try must fall inside the window the geometry gives (before space tick " + loss
                    + "): " + first, (long) Events.number(first, "spaceTick") < loss);
            assertEquals("INV-SAT-07: silent — a dead satellite in sight: " + first, "SILENT", Events.text(first, "outcome"));
        } finally {
            dispose(sat);
            letGo(0, at);
        }
    }

    /**
     * INV-SAT-38 over one pass and a half of a satellite released over its terminal: in sight first; out of
     * sight at the first try after the tick the orbit's geometry first puts the satellite below the
     * terminal's horizon; nothing reaching the terminal while hidden; and in sight again, with what its
     * buffer gathered, at the first try after the tick it next rises. Every tick is the test's own spherical
     * arithmetic on the orbit's elements as production keeps them, the body's radius, mass and rotation
     * (MECH-SAT-14, MECH-SAT-31).
     */
    @Pins("INV-SAT-38")
    @Regions(of = "INV-SAT-38", value = {"in sight", "loses sight", "nothing while hidden", "regains sight"})
    @RedWitness(verdict = "in sight", member = "GroundContact#sightOf",
            at = "return answer(Downlink.sees(terminal, fromItsBody, new Vector3d(), 0d));",
            replacedBy = "return answer(false);", fails = "INV-SAT-38: in sight", on = "2026-10-21")
    @RedWitness(verdict = "loses sight", member = "GroundContact#sightOf",
            at = "return answer(Downlink.sees(terminal, fromItsBody, new Vector3d(), 0d));",
            replacedBy = "return answer(true);", fails = "INV-SAT-38: loses sight", on = "2026-10-21")
    @RedWitness(verdict = "nothing while hidden", member = "TileSatelliteTerminal#tryContact",
            at = "return Outcome.OUT_OF_SIGHT;",
            replacedBy = "{ ((SatelliteData) sat).performAction(null, world, pos); return Outcome.OUT_OF_SIGHT; }",
            fails = "INV-SAT-38: nothing while hidden", on = "2026-10-21")
    @RedWitness(verdict = "regains sight", member = "GroundContact#sightOf",
            at = "SatellitePlacement.rotationOf(here), tick);", replacedBy = "SatellitePlacement.rotationOf(here), 0L);",
            fails = "INV-SAT-38: regains sight", on = "2026-10-21")
    @Test
    public void aTerminalReceivesOnlyWhileTheDishSeesIt() throws Exception {
        FixtureSite site = site();
        Events events = events();
        int[] at = {site.x + 3, site.y, site.z};
        int[] pad = {site.x, site.y, site.z};
        Released sat = null;
        try {
            placeTerminal(0, at, true);
            sat = release(pad);
            long mark = events.markInstrumented();
            loadChip(0, at, sat.id);
            Geometry g = geometry(sat.id, at);
            long loss = g.firstTick(false, g.epoch, g.epoch + 20_000L);
            long gain = g.firstTick(true, loss, loss + 40_000L);
            requireArranged("the geometry must give a window of several tries, a hidden spell and a return: epoch "
                    + g.epoch + ", loss " + loss + ", gain " + gain, loss > g.epoch + POLL_TICKS && gain > loss);
            int budget = (int) (gain - g.epoch) + 4 * TRIES_BUDGET;
            String untilLost = events.awaitMatching(mark, TRY,
                    r -> triesOf(r, 0, at).stream().anyMatch(one -> !inContact(one)), "a try out of contact",
                    "INV-SAT-38: loses sight — no try went out of contact", budget);
            String inSight = null;
            String lost = null;
            for (String one : triesOf(untilLost, 0, at)) {
                if (inContact(one)) {
                    inSight = one;
                } else {
                    lost = one;
                    break;
                }
            }
            assertTrue("INV-SAT-38: in sight — no try in contact before the satellite set (space tick " + loss + "): "
                    + untilLost, inSight != null && (long) Events.number(inSight, "spaceTick") < loss);
            assertTrue("INV-SAT-38: loses sight — the first try out of contact is not the first try after space tick "
                    + loss + ": " + lost, "OUT_OF_SIGHT".equals(Events.text(lost, "outcome"))
                    && within((long) Events.number(lost, "spaceTick"), loss));

            String reply = events.awaitMatching(mark, TRY,
                    r -> regained(r, at, loss), "a try in contact after one out of sight",
                    "INV-SAT-38: regains sight — no try came back into contact", budget);
            List<String> tries = triesOf(reply, 0, at);
            String back = null;
            for (String one : tries) {
                if ((long) Events.number(one, "spaceTick") > (long) Events.number(lost, "spaceTick") && inContact(one)) {
                    back = one;
                    break;
                }
            }
            int dataWhenLost = (int) Events.number(lost, "terminalData");
            for (String one : tries) {
                long tick = (long) Events.number(one, "spaceTick");
                if (tick > (long) Events.number(lost, "spaceTick") && tick < (long) Events.number(back, "spaceTick")) {
                    assertEquals("INV-SAT-38: nothing while hidden — a try out of sight: " + one, "OUT_OF_SIGHT",
                            Events.text(one, "outcome"));
                    assertEquals("INV-SAT-38: nothing while hidden — points reached the terminal while hidden: " + one,
                            dataWhenLost, (int) Events.number(one, "terminalData"));
                }
            }
            assertTrue("INV-SAT-38: regains sight — the first try back in contact is not the first try after space tick "
                    + gain + ": " + back, within((long) Events.number(back, "spaceTick"), gain));
            assertEquals("INV-SAT-38: regains sight — the return is a reception: " + back, "RECEIVED",
                    Events.text(back, "outcome"));
            assertTrue("INV-SAT-38: regains sight — the return brought none of what the buffer gathered while hidden: "
                    + back, Events.number(back, "received") > 0d);
        } finally {
            dispose(sat);
            letGo(0, at);
        }
    }

    /** Erase removes the chip's satellite from its world's list and blanks the chip (INV-SAT-07, `erase`). */
    @Pins("INV-SAT-07")
    @Regions(of = "INV-SAT-07", value = {"erase"})
    @RedWitness(verdict = "erase", member = "TileSatelliteTerminal#erase", at = "idchip.erase(stack);",
            replacedBy = "", fails = "INV-SAT-07: erase", on = "2026-10-21")
    @Test
    public void pressEraseRemovesSatelliteFromDimAndBlanksChip() throws Exception {
        FixtureSite site = site();
        int[] at = {site.x, site.y, site.z};
        try {
            placeTerminal(0, at, false);
            loadChip(0, at, builtSatellite());
            Reply result = act("stellurgytest satellite-terminal press-erase 0 " + at[0] + " " + at[1] + " " + at[2]);
            requireArranged("the chip's satellite must be registered before the erase: " + result.raw(),
                    result.bool("preSatRegistered"));
            assertEquals("INV-SAT-07: erase — the satellite is still in its world's list: " + result.raw(), false,
                    result.bool("postSatRegistered"));
            assertEquals("INV-SAT-07: erase — the chip is not blank: " + result.raw(), true, result.bool("postNbtNull"));
        } finally {
            letGo(0, at);
        }
    }

    // --- instruments of this class ------------------------------------------------------------------

    /** A satellite released from a rocket a probe arranged; {@link #dispose} undoes it. */
    private static final class Released {
        final long id;

        Released(long id) {
            this.id = id;
        }
    }

    /**
     * An optical satellite with a power source and a data unit — without one it holds no point to send — on
     * the orbit line, equatorial, released over {@code pad} by the production deploy; the rocket is removed
     * at once.
     */
    private Released release(int[] pad) throws Exception {
        Reply rocket = arrange("stellurgytest satellite rocket-with 0 " + pad[0] + " " + pad[1] + " " + pad[2]
                + " line 0 0 0 data").requireOk("rocket-with");
        int rocketId = rocket.integer("rocketId");
        long id = rocket.longInteger("satId");
        try {
            act("stellurgytest satellite deploy " + rocketId).requireOk("deploy");
        } finally {
            arrange("stellurgytest rocket remove " + rocketId);
        }
        Reply place = read("stellurgytest satellite place " + id);
        requireArranged("the deploy must put the satellite on an orbit: " + place.raw(), place.bool("hasOrbit"));
        Reply life = read("stellurgytest satellite life " + id);
        requireArranged("the satellite must have room for data: " + life.raw(), life.integer("maxData") > 0);
        return new Released(id);
    }

    private void dispose(Released sat) throws Exception {
        if (sat != null) {
            arrange("stellurgytest satellite set-dead 0 " + sat.id);
        }
    }

    /** An optical satellite from the builder, never launched; its id. */
    private long builtSatellite() throws Exception {
        return arrange("stellurgytest satellite-builder build 0 optical").requireOk("build").longInteger("id");
    }

    /** A terminal at {@code at} of {@code dim}, its chunk forced so it ticks, optionally with power. */
    private void placeTerminal(int dim, int[] at, boolean powered) throws Exception {
        arrange("stellurgytest chunk forceload " + dim + " " + (at[0] >> 4) + " " + (at[2] >> 4)).requireOk("forceload");
        Reply place = arrange("stellurgytest place " + dim + " " + at[0] + " " + at[1] + " " + at[2]
                + " stellurgy:satelliteControlCenter");
        requireArranged("the terminal must be placed: " + place.raw(), place.bool("placed"));
        if (powered) {
            arrange("stellurgytest energy inject " + dim + " " + at[0] + " " + at[1] + " " + at[2] + " " + TERMINAL_POWER)
                    .requireOk("power");
        }
    }

    /** The world stations stand in, as the server's configuration names it. */
    private int stationWorld() throws Exception {
        return read("stellurgytest client space-dim").requireOk("space dim").integer("spaceDimId");
    }

    /** A new station orbiting Earth; the place in its world the station's spawn names, which the station's own lookup maps back to it. */
    private int[] stationOverEarth() throws Exception {
        long id = arrange("stellurgytest station create 0").requireOk("station create").longInteger("id");
        Reply info = read("stellurgytest station info " + id);
        requireArranged("the station must orbit Earth and have a place: " + info.raw(),
                info.integer("orbitingPlanetId") == 0 && info.has("spawnX"));
        return new int[]{info.integer("spawnX"), info.integer("spawnY"), info.integer("spawnZ")};
    }

    private void loadChip(int dim, int[] at, long satId) throws Exception {
        arrange("stellurgytest satellite-terminal load-chip " + dim + " " + at[0] + " " + at[1] + " " + at[2] + " " + satId)
                .requireOk("load chip");
    }

    private void letGo(int dim, int[] at) throws Exception {
        arrange("stellurgytest chunk release " + dim + " " + (at[0] >> 4) + " " + (at[2] >> 4));
    }

    private Events events() {
        return new Events(c -> read(c).raw(), ticks -> GameTicks.advance(client(), GameTicks.server(), ticks),
                evictionReports());
    }

    /** The first {@code count} tries of the terminal at {@code at} since {@code mark}, waited for as a link. */
    private List<String> awaitTries(Events events, long mark, int dim, int[] at, int count, String what) throws Exception {
        String reply = events.awaitMatching(mark, TRY, r -> triesOf(r, dim, at).size() >= count,
                count + " tries of this terminal", what, TRIES_BUDGET);
        Events.assertInstrumentRan(reply, TRY, what);
        return triesOf(reply, dim, at).subList(0, count);
    }

    private static List<String> triesOf(String reply, int dim, int[] at) {
        return Events.recordsWhereAll(reply, "dim", String.valueOf(dim), "x", String.valueOf(at[0]),
                "y", String.valueOf(at[1]), "z", String.valueOf(at[2]));
    }

    private static boolean inContact(String record) {
        String outcome = Events.text(record, "outcome");
        return "RECEIVED".equals(outcome) || "NOTHING_NEW".equals(outcome);
    }

    /** Whether, after a try out of contact past {@code loss}, a try came back into contact. */
    private static boolean regained(String reply, int[] at, long loss) {
        boolean hidden = false;
        for (String one : triesOf(reply, 0, at)) {
            long tick = (long) Events.number(one, "spaceTick");
            if (!inContact(one) && tick >= loss) {
                hidden = true;
            } else if (hidden && inContact(one)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a try at {@code tick} is the first a terminal polling every {@link #POLL_TICKS} makes at or after {@code moment}. */
    private static boolean within(long tick, long moment) {
        return tick >= moment && tick < moment + POLL_TICKS;
    }

    /** The satellite's orbit and the terminal's body as production keeps them, read once. */
    private Geometry geometry(long satId, int[] at) throws Exception {
        Reply place = read("stellurgytest satellite place " + satId);
        requireArranged("the satellite's body must be named with a size and a mass: " + place.raw(),
                place.has("bodyRadiusEarths") && place.number("bodyRadiusEarths") > 0d && place.number("bodyMassEarths") > 0d);
        int period = planetIntField(0, "rotationalPeriod");
        requireArranged("the body must turn: rotational period " + period, period > 0);
        return new Geometry(place.number("radiusM"), place.number("inclinationRad"), place.longInteger("epoch"),
                SatelliteOrbit.EARTH_MU_M3_S2 * place.number("bodyMassEarths"),
                place.number("bodyRadiusEarths") * AstronomicalBodyHelper.EARTH_RADIUS_METRES, period, at[0] + 0.5d,
                at[2] + 0.5d);
    }

    /**
     * The contract's geometry, computed here: a circular orbit whose node lies under meridian 0 at release
     * and which starts at its node (MECH-SAT-14), a terminal where its block falls on the turning body and
     * a satellite seen while not below its horizon (MECH-SAT-31).
     */
    private static final class Geometry {
        final double radiusM;
        final double inclination;
        final long epoch;
        final double mu;
        final double bodyRadiusM;
        final long rotation;
        final double x;
        final double z;

        Geometry(double radiusM, double inclination, long epoch, double mu, double bodyRadiusM, long rotation,
                 double x, double z) {
            this.radiusM = radiusM;
            this.inclination = inclination;
            this.epoch = epoch;
            this.mu = mu;
            this.bodyRadiusM = bodyRadiusM;
            this.rotation = rotation;
            this.x = x;
            this.z = z;
        }

        double turn(long tick) {
            return 2d * Math.PI * Math.floorMod(tick, rotation) / rotation;
        }

        boolean seen(long tick) {
            double u = Math.sqrt(mu / (radiusM * radiusM * radiusM)) * AstronomicalBodyHelper.SECONDS_PER_TICK
                    * (tick - epoch);
            double node = turn(epoch);
            double sx = radiusM * (Math.cos(node) * Math.cos(u) - Math.sin(node) * Math.sin(u) * Math.cos(inclination));
            double sy = radiusM * (Math.sin(node) * Math.cos(u) + Math.cos(node) * Math.sin(u) * Math.cos(inclination));
            double sz = radiusM * Math.sin(u) * Math.sin(inclination);
            double longitude = turn(tick) + x / bodyRadiusM;
            double north = -z / bodyRadiusM;
            double tx = bodyRadiusM * Math.cos(north) * Math.cos(longitude);
            double ty = bodyRadiusM * Math.cos(north) * Math.sin(longitude);
            double tz = bodyRadiusM * Math.sin(north);
            return (sx - tx) * tx + (sy - ty) * ty + (sz - tz) * tz >= 0d;
        }

        /** The first tick from {@code from} at which the satellite is {@code wanted}; arrangement failure if none before {@code limit}. */
        long firstTick(boolean wanted, long from, long limit) {
            for (long t = from; t < limit; t++) {
                if (seen(t) == wanted) {
                    return t;
                }
            }
            requireArranged("the orbit never becomes " + (wanted ? "seen" : "hidden") + " between " + from + " and " + limit,
                    false);
            return -1L;
        }
    }
}
