package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.ShipReadiness;
import org.junit.After;
import org.junit.Test;

import zmaster587.advancedRocketry.test.ShipIdentity;

import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.ArrangementFailure.requireArranged;

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
public class NavLookupNamesItsOwnShipE2ETest extends AbstractSharedServerTest {

    /**
     * How many ships must be loaded for a lookup to be able to pick the WRONG one.
     *
     * <p>The TEST'S OWN, and it is the whole arrangement: with one ship in the world every lookup
     * is right by accident.</p>
     */
    private static final int SHIPS_FOR_AMBIGUITY = 2;

    /** World a ship is given to become loadable — the same budget the sibling two-ship test uses. */

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


        String asmA = exec("artest rocket assemble 0 "
                + placeFixture(FixtureSite.openAir(0, SHIP_A_X, SHIP_A_Z), "with-nav-computer"));
        requireArranged("with VS an AFC-bearing build must route to a ship (no rocket): "
                + asmA, (Reply.of(asmA).integer("rocketCount") == 0));
        String asmB = exec("artest rocket assemble 0 "
                + placeFixture(FixtureSite.openAir(0, SHIP_B_X, SHIP_B_Z), "with-nav-computer"));
        requireArranged("the second craft did not become a ship either: " + asmB,
                (Reply.of(asmB).integer("rocketCount") == 0));
        requireArranged("the ships never loaded", loadedShips(0) >= SHIPS_FOR_AMBIGUITY);

        // ARRANGEMENT CHECK, before anything is asked: there must be TWO registered ships, or the
        // question this test exists to ask ("which one does the lookup answer for") does not exist
        // in this world and both assertions below would pass vacuously.
        String all = exec("artest vs ship-count-all 0");
        requireArranged("fewer than two ships are registered, so no lookup can pick the "
                + "wrong one and this run cannot exhibit the defect: " + all,
                extractInt(all, "count") >= SHIPS_FOR_AMBIGUITY);

        // Each craft asked for by the name ITS OWN assembler minted. This test is about a lookup
        // picking the wrong ship of two, so deriving the two ids from a lookup at two points was the
        // defect used to arrange its own demonstration: the bound was defended by "the other craft is
        // 64 blocks away", which is a statement about distance and not about how many hulls a box
        // holds.
        String shipA = ShipIdentity.physicsIdOf(this::exec, 0, ShipIdentity.nameFromAssembly(asmA));
        String shipB = ShipIdentity.physicsIdOf(this::exec, 0, ShipIdentity.nameFromAssembly(asmB));
        requireArranged("both bases resolved to the SAME ship (" + shipA + "), so the two "
                + "craft did not become two ships and there is nothing to confuse",
                !shipA.equals(shipB));

        assertNavigationIsFound("A", shipA, shipB);
        assertNavigationIsFound("B", shipB, shipA);
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
        String gate = exec("artest nav gate 0 " + afc[0] + " " + afc[1] + " " + afc[2]);
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
        Reply reply = Reply.of("artest vs seat-input-by-id",
                exec("artest vs seat-input-by-id 0 " + shipId + " 0 0 0 0 0 0"));
        if (!reply.has("afcX")) {
            return null;
        }
        return new int[]{reply.integer("afcX"), reply.integer("afcY"), reply.integer("afcZ")};
    }

    @After
    public void restoreSharedServerState() throws Exception {
    }

    // --- helpers (mirror ArrivalSeatLookupNamesItsOwnShipE2ETest) -------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    /** How many ships are LOADED in {@code dim} right now. A read, not a wait: measured across this
     *  tier at one and at six forks, the ship is already loaded whenever a scenario asks. */
    private int loadedShips(int dim) throws Exception {
        return ShipReadiness.loadedCount(this::exec, dim);
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
    private String placeFixture(FixtureSite site, String variant) throws Exception {
        int[] bp = RocketFixture.placeAt(site, this::exec, variant, 4, 12,
                "the craft this scenario builds stands in this volume");
        return bp[0] + " " + bp[1] + " " + bp[2];
    }

    private static int extractInt(String json, String key) {
        return Reply.of(json).integer(key);
    }
}
