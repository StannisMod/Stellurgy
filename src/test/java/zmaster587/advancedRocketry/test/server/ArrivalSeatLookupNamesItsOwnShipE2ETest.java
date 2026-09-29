package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.ShipReadiness;
import org.junit.After;

import org.junit.Test;

import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * An arrival's seat lookup must answer for the ship it is ASKING ABOUT, however close another craft
 * is parked to the point it asks at.
 *
 * <p>The lookup that seats a crew after a crossing takes a world point and scans the shipyard of the
 * ship it finds there. Resolving that ship by POSITION has no distance bound: it returns the nearest
 * registered craft in the world, which is the right one exactly while there is only one. A player's
 * entry to orbit has already failed this way — the destination cell held a second ship, the arrival
 * scanned that ship's shipyard, found no pilot seat in it (there was none to find, ever) and gave up
 * after 200 attempts with his own seat 51,200 blocks away in the same world.</p>
 *
 * <p><b>Both legs are load-bearing.</b> The BY-POSITION leg is the defect itself, run as the control:
 * it must come back with zero seats, because that is what proves this arrangement can exhibit the
 * bug at all. Only then does the BY-IDENTITY leg's non-zero answer mean anything — without the
 * control it would also pass in a world where every lookup trivially found the seat.</p>
 *
 * <p>Arrangement: a seated craft and a seatless one, assembled 64 blocks apart, and both lookups
 * asked at the SEATLESS craft's position. Gated on the server's real VS presence; skips cleanly
 * otherwise.</p>
 */
public class ArrivalSeatLookupNamesItsOwnShipE2ETest extends AbstractSharedServerTest {

    /**
     * How many ships must be loaded for a lookup to be able to pick the WRONG one.
     *
     * <p>The TEST'S OWN, and it is the whole arrangement: with one ship in the world every lookup
     * is right by accident, so this leg cannot exhibit the defect it exists for.</p>
     */
    private static final int SHIPS_FOR_AMBIGUITY = 2;

    /** World a ship is given to become loadable - the old 40 x 250 ms. */

    /** The craft that HAS a pilot seat — the one an arrival would be asking about. */
    private static final int SEATED_X = 5800, SEATED_Y = FixtureSite.OPEN_AIR_Y, SEATED_Z = 5800;
    /** A second craft with a flight computer but NO pilot seat, parked far enough to be a separate
     *  ship and near enough to win every position lookup made at its own position. */
    private static final int SEATLESS_X = 5864, SEATLESS_Y = FixtureSite.OPEN_AIR_Y, SEATLESS_Z = 5800;

    @Test
    public void theSeatLookupFindsItsOwnShipsSeatWithAnotherCraftNearer() throws Exception {

        // Headless: nobody is near a ship to hold it loaded, and an unloaded ship reads as a missing
        // one. Reset in @After (shared-harness contract).


        String seatedAsm = exec("artest rocket assemble 0 "
                + placeFixture(FixtureSite.openAir(0, SEATED_X, SEATED_Z), "with-pilot-seat"));
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + seatedAsm,
                (Reply.of(seatedAsm).integer("rocketCount") == 0));
        String seatlessAsm = exec("artest rocket assemble 0 "
                + placeFixture(FixtureSite.openAir(0, SEATLESS_X, SEATLESS_Z), "with-nav-computer"));
        assertTrue("the seatless craft did not become a ship either: " + seatlessAsm,
                (Reply.of(seatlessAsm).integer("rocketCount") == 0));
        assertTrue("the ships never loaded", loadedShips(0) >= SHIPS_FOR_AMBIGUITY);

        // ARRANGEMENT CHECK, before either leg: the two crafts must be two REGISTERED ships, or the
        // whole question ("which one does the lookup answer for") does not exist in this world.
        String all = exec("artest vs ship-count-all 0");
        assertTrue("fewer than two ships are registered, so no lookup can pick the wrong one: " + all,
                extractInt(all, "count") >= SHIPS_FOR_AMBIGUITY);

        // THE IDENTITY COMES FROM THE ASSEMBLY THAT MINTED IT, not from a lookup at a position.
        //
        // It was read out of `seat-yard`'s `nearest` field until 2026-09-14, and that field is now
        // GONE along with the positional lookup behind it: a ship's blocks live in its subspace, so
        // in the world it has a pose and no extent for a distance to be measured to. This class is
        // ABOUT that defect — its subject leg proves an arrival asks by identity — and it was
        // getting the identity it asks with from the very lookup under test.
        String seatedShip = zmaster587.advancedRocketry.test.ShipIdentity.nameFromAssembly(seatedAsm);
        assertNotNull("could not read the seated craft's ship identity — without it the subject leg "
                + "cannot ask about that ship at all", seatedShip);

        // CONTROL — the defect, asked the old way. At the SEATLESS craft's position a position
        // lookup resolves that craft, and its shipyard holds no pilot seat.
        String byPosition = exec("artest vs seat-yard 0 "
                + SEATLESS_X + " " + (SEATLESS_Y + 2) + " " + SEATLESS_Z);
        assertEquals("control leg: a position lookup at the seatless craft must find NO seat. It "
                        + "found one, so this arrangement cannot exhibit a wrong-ship arrival and the "
                        + "subject leg below would pass for the wrong reason: " + byPosition,
                0, extractInt(byPosition, "seats"));

        // SUBJECT — the same point, asked about the seated ship BY IDENTITY.
        String byIdentity = exec("artest vs seat-yard 0 "
                + SEATLESS_X + " " + (SEATLESS_Y + 2) + " " + SEATLESS_Z + " " + seatedShip);
        assertTrue("asked about its own ship, the arrival's seat lookup still scanned whichever "
                        + "shipyard was nearest and found no seat: " + byIdentity,
                extractInt(byIdentity, "seats") >= 1);
    }

    @After
    public void restoreSharedServerState() throws Exception {
    }

    // --- helpers (mirror VSShipEntryE2ETest) --------------------------------------------------

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
