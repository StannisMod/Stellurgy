package zmaster587.advancedRocketry.test.server;

import org.junit.Test;


import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;

import static org.junit.Assert.assertTrue;

/**
 * One craft — one command authority, enforced at the assembly SCAN. A tier-2 build carrying more
 * than one Advanced Flight Computer (or more than one pilot seat) must be rejected with its own
 * error code before anything can assemble:
 *
 * <ul>
 *   <li><b>Two flight computers</b>: every AFC on a physics ship ticks AND is a force controller,
 *       so a second one — e.g. a scavenged block whose NBT still says "hold station" — fights the
 *       linked computer for the ship every tick. Unbuildable is the only safe state.</li>
 *   <li><b>Two pilot seats</b>: only the last-scanned seat is linked at assembly; a pilot in any
 *       other seat has silently dead controls. Passenger seats (the plain seat block) stay
 *       unrestricted.</li>
 * </ul>
 *
 * <p>Both tests drive the REAL scan (fixture + one extra control block + {@code rocket assemble})
 * and read the scan status the builder GUI shows the player. The restriction
 * exists for the ship path; without VS the blocks are inert cargo.</p>
 */
public class VSShipMultiControlScanTest extends AbstractSharedServerTest {


    @Test
    public void aSecondFlightComputerIsRejectedAtScan() throws Exception {

        final FixtureSite site = FixtureSite.openAir(0, 2400, 2400);
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        String coords = placeFixture(site, "with-pilot-seat");
        // A second AFC in the free drill cell (rocketX+1, rocketY+3) — inside the scanned build.
        String fill = String.join("\n", client().execute("artest fill 0 "
                + (baseX + 4) + " " + (baseY + 4) + " " + (baseZ + 3) + " "
                + (baseX + 4) + " " + (baseY + 4) + " " + (baseZ + 3)
                + " advancedrocketry:advancedFlightComputer"));
        assertTrue("placing the second flight computer failed: " + fill, Reply.of(fill).ok());

        String assemble = String.join("\n", client().execute("artest rocket assemble 0 " + coords));
        assertTrue("a build with TWO flight computers must be rejected at scan with its own error "
                        + "code: " + assemble,
                "MULTIPLEFLIGHTCOMPUTERS".equals(Reply.of(assemble).text("status")));
    }

    @Test
    public void aSecondPilotSeatIsRejectedAtScan() throws Exception {

        final FixtureSite site = FixtureSite.openAir(0, 2600, 2400);
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        String coords = placeFixture(site, "with-pilot-seat");
        // A second pilot seat in the free cell beside the linked one (rocketX+1, rocketY+4).
        String fill = String.join("\n", client().execute("artest fill 0 "
                + (baseX + 4) + " " + (baseY + 5) + " " + (baseZ + 3) + " "
                + (baseX + 4) + " " + (baseY + 5) + " " + (baseZ + 3)
                + " advancedrocketry:pilotSeat"));
        assertTrue("placing the second pilot seat failed: " + fill, Reply.of(fill).ok());

        String assemble = String.join("\n", client().execute("artest rocket assemble 0 " + coords));
        assertTrue("a build with TWO pilot seats must be rejected at scan with its own error code: "
                        + assemble,
                "MULTIPLEPILOTSEATS".equals(Reply.of(assemble).text("status")));
    }

    /** Place the fixture on a pad WITHOUT assembling; returns the builder pos as "bx by bz". */
    private String placeFixture(FixtureSite site, String variant) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this build occupies is EMPTY. The site stands in open air, so this
        // ASSERTS rather than digs, and its fill force-loads every chunk in the box — so the warmup
        // it replaces lost nothing. Nothing here ever flies: the subject is the assembly SCAN, and
        // the height covers the tower the scan reads.
        int[] bp = RocketFixture.placeAt(site, cmd -> String.join("\n", client().execute(cmd)),
                variant, 2, 10,
                "the build the assembly scan is about stands in this volume");
        return bp[0] + " " + bp[1] + " " + bp[2];
    }
}
