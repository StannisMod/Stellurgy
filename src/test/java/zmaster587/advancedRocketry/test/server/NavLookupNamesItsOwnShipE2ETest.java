package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.ShipReadiness;
import org.junit.After;
import org.junit.Test;

import zmaster587.advancedRocketry.test.ShipIdentity;

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

    /** World a ship is given to become loadable — the same budget the sibling two-ship test uses. */

    /**
     * Two craft, each carrying a flight computer and a navigation computer, far enough apart to be
     * two registered ships and near enough that neither is obviously "the" nearest to anything.
     */
    private static final int SHIP_A_X = 6100, SHIP_A_Y = 80, SHIP_A_Z = 6100;
    private static final int SHIP_B_X = 6164, SHIP_B_Y = 80, SHIP_B_Z = 6100;

    @Test
    public void eachShipsNavigationLookupAnswersForItsOwnShip() throws Exception {

        // Headless: nobody is near a ship to hold it loaded, and an unloaded ship reads as a missing
        // one. Reset in @After (shared-harness contract).
        exec("artest vs permaload true");

        clearArea(SHIP_A_X, SHIP_A_Z);
        clearArea(SHIP_B_X, SHIP_B_Z);

        String asmA = exec("artest rocket assemble 0 "
                + placeFixture(SHIP_A_X, SHIP_A_Y, SHIP_A_Z, "with-nav-computer"));
        requireArranged("with VS an AFC-bearing build must route to a ship (no rocket): "
                + asmA, asmA.contains("\"rocketCount\":0"));
        String asmB = exec("artest rocket assemble 0 "
                + placeFixture(SHIP_B_X, SHIP_B_Y, SHIP_B_Z, "with-nav-computer"));
        requireArranged("the second craft did not become a ship either: " + asmB,
                asmB.contains("\"rocketCount\":0"));
        requireArranged("the ships never loaded", loadedShips(0) >= 2);

        // ARRANGEMENT CHECK, before anything is asked: there must be TWO registered ships, or the
        // question this test exists to ask ("which one does the lookup answer for") does not exist
        // in this world and both assertions below would pass vacuously.
        String all = exec("artest vs ship-count-all 0");
        requireArranged("fewer than two ships are registered, so no lookup can pick the "
                + "wrong one and this run cannot exhibit the defect: " + all,
                extractInt(all, "count") >= 2);

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
                gate.contains("\"navComputer\":true"));
    }

    /**
     * The SUBSPACE position of the named ship's flight computer, or {@code null}. Read off
     * {@code seat-input-by-id}, which resolves the computer from the ship's identity and reports
     * where it landed even when that ship carries no pilot seat — which these two do not.
     */
    private int[] flightComputerOf(String shipId) throws Exception {
        String reply = exec("artest vs seat-input-by-id 0 " + shipId + " 0 0 0 0 0 0");
        if (!reply.contains("\"afcX\"")) {
            return null;
        }
        return new int[]{extractInt(reply, "afcX"), extractInt(reply, "afcY"),
                extractInt(reply, "afcZ")};
    }

    @After
    public void restoreSharedServerState() throws Exception {
        exec("artest vs permaload false");
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

    private void clearArea(int baseX, int baseZ) throws Exception {
        int cx1 = (baseX - 4) >> 4, cz1 = (baseZ - 4) >> 4;
        int cx2 = (baseX + 20) >> 4, cz2 = (baseZ + 20) >> 4;
        assertTrue("chunk warmup failed", exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " "
                + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (baseX - 4) + " " + (SHIP_A_Y - 2)
                + " " + (baseZ - 4) + " " + (baseX + 20) + " " + (SHIP_A_Y + 12) + " " + (baseZ + 20)
                + " minecraft:air").contains("\"ok\":true"));
    }

    private String placeFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ
                + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        java.util.regex.Matcher bp = java.util.regex.Pattern
                .compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]").matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp.find());
        return bp.group(1) + " " + bp.group(2) + " " + bp.group(3);
    }

    private static int extractInt(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static String extractString(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
