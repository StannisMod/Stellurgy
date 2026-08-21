package zmaster587.advancedRocketry.test.server;

import org.junit.After;
import org.junit.Test;

import zmaster587.advancedRocketry.test.GameTicks;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.SHIP_CAPTURE_RADIUS_BLOCKS;

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
    private static final int LOAD_TICKS = 200;

    /**
     * Two craft, each carrying a flight computer and a navigation computer, far enough apart to be
     * two registered ships and near enough that neither is obviously "the" nearest to anything.
     */
    private static final int SHIP_A_X = 6100, SHIP_A_Y = 80, SHIP_A_Z = 6100;
    private static final int SHIP_B_X = 6164, SHIP_B_Y = 80, SHIP_B_Z = 6100;

    /** How far a ship-info answer may be from a freshly assembled craft's own base, in blocks. */
    private static final int SHIP_QUERY_RADIUS = SHIP_CAPTURE_RADIUS_BLOCKS;

    @Test
    public void eachShipsNavigationLookupAnswersForItsOwnShip() throws Exception {

        // Headless: nobody is near a ship to hold it loaded, and an unloaded ship reads as a missing
        // one. Reset in @After (shared-harness contract).
        exec("artest vs permaload true");

        clearArea(SHIP_A_X, SHIP_A_Z);
        clearArea(SHIP_B_X, SHIP_B_Z);

        String asmA = exec("artest rocket assemble 0 "
                + placeFixture(SHIP_A_X, SHIP_A_Y, SHIP_A_Z, "with-nav-computer"));
        assertTrue("ARRANGEMENT: with VS an AFC-bearing build must route to a ship (no rocket): "
                + asmA, asmA.contains("\"rocketCount\":0"));
        String asmB = exec("artest rocket assemble 0 "
                + placeFixture(SHIP_B_X, SHIP_B_Y, SHIP_B_Z, "with-nav-computer"));
        assertTrue("ARRANGEMENT: the second craft did not become a ship either: " + asmB,
                asmB.contains("\"rocketCount\":0"));
        assertTrue("ARRANGEMENT: the ships never loaded", waitForLoadedShips(0, 2) >= 2);

        // ARRANGEMENT CHECK, before anything is asked: there must be TWO registered ships, or the
        // question this test exists to ask ("which one does the lookup answer for") does not exist
        // in this world and both assertions below would pass vacuously.
        String all = exec("artest vs ship-count-all 0");
        assertTrue("ARRANGEMENT: fewer than two ships are registered, so no lookup can pick the "
                + "wrong one and this run cannot exhibit the defect: " + all,
                extractInt(all, "count") >= 2);

        String shipA = shipIdAt(SHIP_A_X, SHIP_A_Y, SHIP_A_Z);
        String shipB = shipIdAt(SHIP_B_X, SHIP_B_Y, SHIP_B_Z);
        assertNotNull("ARRANGEMENT: craft A never named itself at its own base", shipA);
        assertNotNull("ARRANGEMENT: craft B never named itself at its own base", shipB);
        assertTrue("ARRANGEMENT: both bases resolved to the SAME ship (" + shipA + "), so the two "
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
        assertNotNull("ARRANGEMENT: ship " + label + " (" + shipId + ") has no resolvable flight "
                + "computer, so its navigation cannot be asked about at all", afc);
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

    /**
     * The identity of the ship freshly assembled at {@code (x,y,z)}. The one positional lookup this
     * test spends, at the only moment it is defensible: nothing has moved yet and the bound cannot
     * admit the other craft, which is 64 blocks away.
     */
    private String shipIdAt(int x, int y, int z) throws Exception {
        String info = exec("artest vs ship-info 0 " + x + " " + y + " " + z
                + " " + SHIP_QUERY_RADIUS);
        if (!info.contains("\"managed\":true")) {
            return null;
        }
        String id = extractString(info, "id");
        return id == null || id.isEmpty() ? null : id;
    }

    @After
    public void restoreSharedServerState() throws Exception {
        exec("artest vs permaload false");
    }

    // --- helpers (mirror ArrivalSeatLookupNamesItsOwnShipE2ETest) -------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private int waitForLoadedShips(int dim, int want) throws Exception {
        final int[] loaded = {0};
        GameTicks.until(client(), GameTicks.server(), LOAD_TICKS, () -> {
            if (extractInt(exec("artest vs ship-count-all " + dim), "count") < want) {
                return false;
            }
            exec("artest vs load-ships " + dim);
            loaded[0] = extractInt(exec("artest vs ship-count " + dim), "count");
            return loaded[0] >= want;
        });
        return loaded[0];
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
