package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.client.GameDirSeed;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.Test;

import java.util.List;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;

/**
 * A satellite terminal and a satellite of ANOTHER planetary system (`satellite` INV-SAT-39): the range gate's
 * system check, reached with both places registered bodies.
 *
 * <p>NEW-GROUP: its premise is a galaxy of two systems declared in {@code planetDefs.xml} — Sol holding Earth
 * on dimension 0, and a second star holding one planet — and a class's galaxy is its seed, so the premise is the
 * class. The default galaxy every other satellite group stands in is one system (Earth and Luna), where no
 * satellite outside the terminal's system can exist.</p>
 *
 * <p>The verdict is read off {@code terminal_try}, the record the terminal's own settle writes, named by the
 * terminal's place; the try is the one production makes as the chip goes in. What it does NOT see: a satellite
 * of a MOON of another system, and the client's screen.</p>
 *
 * <p>Leaves: the terminal block in its plot, and the satellite, marked dead.</p>
 */
public class SatelliteOutsideItsSystemTest extends AbstractSharedServerTest {

    /** The planet of the second system; no stock id, so nothing else in the file can claim it. */
    private static final int FAR_PLANET = 30;
    private static final String FAR_NAME = "Farhold";

    /** A link's deadline for the terminal's first try: the chip is tried as it goes in. Loosened only. */
    private static final int TRIES_BUDGET = 200;
    /** The power a terminal is given: more tries than the scenario makes. */
    private static final int TERMINAL_POWER = 5000;

    /**
     * Two systems, as a pack would author them. Every planet states its mass and radius, which a planet in this
     * file must; {@code numPlanets="0"} keeps each system to what is written here.
     */
    @Override
    protected void seedGameDirectory(GameDirSeed seed) throws Exception {
        seed.planetDefs("<galaxy>"
                + "<star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" numPlanets=\"0\">"
                + "<planet name=\"Earth\" DIMID=\"0\"><mass>1</mass><radius>1</radius></planet>"
                + "</star>"
                + "<star name=\"Farstar\" temp=\"80\" x=\"400\" y=\"400\" numPlanets=\"0\">"
                + "<planet name=\"" + FAR_NAME + "\" DIMID=\"" + FAR_PLANET + "\"><mass>1</mass><radius>1</radius></planet>"
                + "</star>"
                + "</galaxy>", getClass());
    }

    /**
     * A terminal on Earth with a chip naming a satellite of a planet of another star says out of range
     * (INV-SAT-39), before anything is asked of the satellite's orbit — it has none.
     */
    @Pins("INV-SAT-39")
    @RedWitness(member = "TileSatelliteTerminal#tryContact",
            at = "if (here == null || !PlanetaryTravelHelper.isTravelAnywhereInPlanetarySystem(sat.getDimensionId(), here.getId())) {",
            replacedBy = "if (here == null) {", fails = "INV-SAT-39: out of range", on = "2026-10-23")
    @Test
    public void aSatelliteOfAnotherSystemIsOutOfRange() throws Exception {
        Reply earth = read("stellurgytest planet info 0");
        Reply far = read("stellurgytest planet info " + FAR_PLANET);
        requireArranged("the seeded galaxy must hold Earth and " + FAR_NAME + " under two stars: " + earth.raw()
                + " | " + far.raw(), FAR_NAME.equals(far.text("name")) && "Earth".equals(earth.text("name"))
                && far.integer("starId") != earth.integer("starId"));

        FixtureSite site = site();
        int[] at = {site.x, site.y, site.z};
        String place = "0 " + at[0] + " " + at[1] + " " + at[2];
        Events events = new Events(c -> read(c).raw(), ticks -> GameTicks.advance(client(), GameTicks.server(), ticks),
                evictionReports());
        long satId = -1L;
        try {
            arrange("stellurgytest chunk forceload 0 " + (at[0] >> 4) + " " + (at[2] >> 4)).requireOk("forceload");
            Reply placed = arrange("stellurgytest place " + place + " stellurgy:satelliteControlCenter");
            requireArranged("the terminal must be placed: " + placed.raw(), placed.bool("placed"));
            arrange("stellurgytest energy inject " + place + " " + TERMINAL_POWER).requireOk("power");
            Reply created = arrange("stellurgytest satellite create " + FAR_PLANET + " optical");
            requireArranged("the satellite must be added to " + FAR_NAME + ": " + created.raw(),
                    created.has("id") && created.integer("dim") == FAR_PLANET);
            satId = created.longInteger("id");

            long mark = events.markInstrumented();
            arrange("stellurgytest satellite-terminal load-chip " + place + " " + satId).requireOk("load chip");
            String reply = events.awaitMatching(mark, "terminal_try", r -> !triesAt(r, at).isEmpty(),
                    "a try of this terminal", "INV-SAT-39: out of range — the terminal's try", TRIES_BUDGET);
            Events.assertInstrumentRan(reply, "terminal_try", "INV-SAT-39: out of range — the terminal's try");
            String first = triesAt(reply, at).get(0);
            assertEquals("INV-SAT-39: out of range — a satellite of " + FAR_NAME + " on a terminal on Earth: " + first,
                    "OUT_OF_RANGE", Events.text(first, "outcome"));
        } finally {
            if (satId >= 0) {
                arrange("stellurgytest satellite set-dead " + FAR_PLANET + " " + satId);
            }
            arrange("stellurgytest chunk release 0 " + (at[0] >> 4) + " " + (at[2] >> 4));
        }
    }

    private static List<String> triesAt(String reply, int[] at) {
        return Events.recordsWhereAll(reply, "dim", "0", "x", String.valueOf(at[0]), "y", String.valueOf(at[1]),
                "z", String.valueOf(at[2]));
    }
}
