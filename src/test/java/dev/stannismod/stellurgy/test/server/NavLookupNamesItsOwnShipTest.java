package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertTrue;

/**
 * A ship's navigation lookup must answer for the ship it was ASKED ABOUT, in a world holding more
 * than one craft.
 *
 * <p><b>What this pins, and why one ship could never show it.</b> The lookup behind
 * {@code ShipNavigation.hasNavComputer()} used to pre-filter its candidates against a shipyard claim
 * resolved by "which registered ship is nearest this point". That ranking is done on each ship's
 * TRANSFORM POSITION — where its hull floats in the WORLD — while the point handed to it is the
 * flight computer's SUBSPACE block, which lives in the shipyard region. Two frames, no relation
 * between them: the ship it picked was whichever hull happened to be flying nearest a coordinate in
 * a completely different coordinate space. With ONE craft in the world that is always the right
 * ship and the mistake cannot be seen at all, which is why every existing navigation scenario passed
 * over it. With two it is arbitrary, and the claim it then measures against can pre-filter away the
 * console of the very ship being asked about.</p>
 *
 * <p><b>What the player loses.</b> A ship that HAS a navigation computer reports it has none, and
 * the jump gate refuses the jump — with no message that could tell the pilot the refusal is about a
 * neighbouring craft's shipyard.</p>
 *
 * <p><b>The assertion is that BOTH ships answer for themselves</b>, not that one does. A test that
 * asked only about the first would pass whenever the frame-confused lookup happened to pick that
 * ship's claim, which it does half the time by construction.</p>
 *
 * <p>Gated on the server's real VS presence; skips cleanly otherwise.</p>
 */
public class NavLookupNamesItsOwnShipTest extends AbstractSharedServerTest {

    /**
     * Two craft, each carrying a flight computer and a navigation computer, far enough apart to be
     * two registered ships and near enough that neither is obviously "the" nearest to anything.
     */
    private static final int SHIP_A_X = 6100, SHIP_A_Y = FixtureSite.OPEN_AIR_Y, SHIP_A_Z = 6100;
    private static final int SHIP_B_X = 6164, SHIP_B_Y = FixtureSite.OPEN_AIR_Y, SHIP_B_Z = 6100;

    @Test
    public void eachShipsNavigationLookupAnswersForItsOwnShip() throws Exception {

        // Headless: nobody is near a ship to hold it loaded, and an unloaded ship reads as a missing
        // one. Reset in @After (shared-harness contract).


        ShipBirth birthA = assemble(FixtureSite.openAir(0, SHIP_A_X, SHIP_A_Z), "ship A");
        ShipBirth birthB = assemble(FixtureSite.openAir(0, SHIP_B_X, SHIP_B_Z), "ship B");

        // ARRANGEMENT CHECK, before anything is asked: the two presses must have made TWO ships —
        // one ASSEMBLED naming each, under two different physics ids — or the question this test
        // exists to ask ("which one does the lookup answer for") does not exist in this world and
        // both assertions below would pass vacuously. Each craft is then asked for by the id ITS
        // OWN assembly named.
        String shipA = ArrangementFailure.arranged(
                () -> birthA.awaitUsable(c -> read(c).raw(), events, NAMING_LINK_TICKS));
        String shipB = ArrangementFailure.arranged(
                () -> birthB.awaitUsable(c -> read(c).raw(), events, NAMING_LINK_TICKS));
        requireArranged("both presses named the SAME ship (" + shipA + "), so the two craft did not"
                + " become two ships and there is nothing to confuse", !shipA.equals(shipB));

        assertNavigationIsFound("A", shipA, shipB);
        assertNavigationIsFound("B", shipB, shipA);
    }

    /** The physics ids of the crafts this scenario's presses named; disposed of when it ends. */
    private final java.util.List<String> built = new java.util.ArrayList<>();

    @org.junit.After
    public void disposeOfTheCrafts() throws Exception {
        for (String ship : built) {
            Disposal.ship(c -> arrange(c).raw(), events, 0, ship);
        }
        built.clear();
    }

    /** Server ticks for a press's naming of its ship to be recorded: the substrate names it on its next tick. */
    private static final int NAMING_LINK_TICKS = 200;

    private final Events events =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /** Lay a with-nav-computer craft at {@code site}, press its assembler, and answer the ship that press made. */
    private ShipBirth assemble(FixtureSite site, String what) throws Exception {
        int[] bp = placeFixture(site, "with-nav-computer");
        long mark = events.mark();
        String asm = act("stellurgytest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]).raw();
        ShipBirth ship = ShipBirth.ofPress(events, mark, 0, bp, asm, NAMING_LINK_TICKS, what);
        built.add(ship.physicsId);
        requireArranged(what + ": with VS an AFC-bearing build must route to a ship (no rocket): " + ship,
                ship.rocketsBorn.isEmpty());
        return ship;
    }

    /**
     * The named ship's jump gate must SEE its navigation computer. The other ship's id is carried
     * only so a failure can say which craft was standing beside the one that answered wrongly.
     */
    private void assertNavigationIsFound(String label, String shipId, String otherShipId)
            throws Exception {
        int[] afc = flightComputerOf(shipId);
        requireArranged("ship " + label + " (" + shipId + ") has no resolvable flight "
                + "computer, so its navigation cannot be asked about at all", afc != null);
        String gate = arrange("stellurgytest nav gate 0 " + afc[0] + " " + afc[1] + " " + afc[2]).raw();
        assertTrue("ship " + label + " carries a navigation computer built into it, and its own "
                        + "jump gate reports it has none — the lookup answered about some other "
                        + "craft's shipyard. ship=" + shipId + " neighbour=" + otherShipId
                        + " afc=(" + afc[0] + "," + afc[1] + "," + afc[2] + ") gate=" + gate,
                Reply.of(gate).bool("navComputer"));
    }

    /**
     * The SUBSPACE position of the named ship's flight computer, or {@code null}. Read off
     * {@code seat-input-by-id}, which resolves the computer from the ship's identity and reports
     * where it landed even when that ship carries no pilot seat — which these two do not.
     */
    private int[] flightComputerOf(String shipId) throws Exception {
        Reply reply = Reply.of("stellurgytest vs seat-input-by-id",
                arrange("stellurgytest vs seat-input-by-id 0 " + shipId + " 0 0 0 0 0 0").raw());
        if (!reply.has("afcX")) {
            return null;
        }
        return new int[]{reply.integer("afcX"), reply.integer("afcY"), reply.integer("afcZ")};
    }


    /**
     * WHERE this scenario's craft stands, and the first link that says the volume is empty.
     *
     * <p>What stood here was a pair: a {@code clearArea} that ran a chunk warmup and an air fill
     * over {@code y-2 .. y+12}, and a {@code placeFixture} that laid the blocks. The fill DUG
     * rather than asked, and threw away its own answer — {@code placed}, the count of blocks that
     * were standing in the volume. The shared builder asks instead, and on an open-air site
     * anything found is an arrangement failure that names itself. The warmup went with it: the
     * fill force-loads every chunk in its own box, so the first link was already doing that job.</p>
     *
     * <p>HALO 4 and HEIGHT 12 are the old volume's own numbers, kept rather than re-derived:
     * they are what this scenario's green runs were taken over.</p>
     */
    private int[] placeFixture(FixtureSite site, String variant) throws Exception {
        return RocketFixture.placeAt(site, c -> arrange(c).raw(), variant, 4, 12,
                "the craft this scenario builds stands in this volume");
    }
}
