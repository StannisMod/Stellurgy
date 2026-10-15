package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Disposal;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShipBirth;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * An arrival's seat lookup must answer for the ship it is ASKING ABOUT, however close another craft
 * is parked to the point it asks at.
 *
 * <p>The lookup that seats a crew after a crossing takes a world point and the ship it means, and
 * scans that ship's shipyard. Resolving the ship by POSITION instead has no distance bound: it returns
 * the nearest registered craft in the world, which is the right one exactly while there is only one. A
 * player's entry to orbit has already failed this way — the destination cell held a second ship, the
 * arrival scanned that ship's shipyard, found no pilot seat in it (there was none to find, ever) and
 * gave up after 200 attempts with his own seat 51,200 blocks away in the same world.</p>
 *
 * <p><b>Both legs are load-bearing, and both are asked at ONE point and differ only in the ship they
 * name.</b> The CONTROL names the seatless craft and must come back with zero seats: that proves a
 * lookup landing on the wrong yard at this point finds nothing, so this arrangement can exhibit a
 * wrong-ship arrival. The SUBJECT names the seated craft at the same point and must find its seat. A
 * lookup that ignored the name — answering from the point alone — gives both legs the same answer, so
 * it cannot pass both.</p>
 *
 * <p>Arrangement: a seated craft and a seatless one, assembled 64 blocks apart, each held by the id
 * its own assembly named, and both lookups asked at the SEATLESS craft's position.</p>
 */
public class ArrivalSeatLookupNamesItsOwnShipTest extends AbstractSharedServerTest {

    /** The craft that HAS a pilot seat — the one an arrival would be asking about. */
    private static final int SEATED_X = 5800, SEATED_Z = 5800;
    /** A second craft with a flight computer but NO pilot seat, parked far enough to be a separate
     *  ship and near enough to win every position lookup made at its own position. */
    private static final int SEATLESS_X = 5864, SEATLESS_Y = FixtureSite.OPEN_AIR_Y, SEATLESS_Z = 5800;

    /** Server ticks for a press's naming of its ship to be recorded: the substrate names it on its next tick. */
    private static final int NAMING_LINK_TICKS = 200;

    private final Events events =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    @Test
    public void theSeatLookupFindsItsOwnShipsSeatWithAnotherCraftNearer() throws Exception {
        ShipBirth seated = assemble(FixtureSite.openAir(0, SEATED_X, SEATED_Z), "with-pilot-seat",
                "the seated craft");
        ShipBirth seatless = assemble(FixtureSite.openAir(0, SEATLESS_X, SEATLESS_Z), "with-nav-computer",
                "the seatless craft");

        String point = SEATLESS_X + " " + (SEATLESS_Y + 2) + " " + SEATLESS_Z;

        // CONTROL — the wrong ship's yard, asked at this point, holds no seat.
        String wrongShip = read("stellurgytest vs seat-yard 0 " + point + " " + seatless.physicsId).raw();
        assertEquals("control leg: the seatless craft's yard, asked at its own position, must hold NO"
                        + " seat. It holds one, so a wrong-ship arrival here would still find a seat and"
                        + " the subject leg below could pass for the wrong reason: " + wrongShip,
                0, Reply.of(wrongShip).integer("seats"));

        // SUBJECT — the same point, asked about the seated ship BY IDENTITY.
        String byIdentity = read("stellurgytest vs seat-yard 0 " + point + " " + seated.physicsId).raw();
        assertTrue("asked about its own ship, the arrival's seat lookup scanned another yard and"
                        + " found no seat: " + byIdentity,
                Reply.of(byIdentity).integer("seats") >= 1);
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

    /**
     * Lay {@code variant} at {@code site}, press its assembler, and answer the ship that press made.
     *
     * <p>HALO 4 and HEIGHT 12 contain the fixture's pad, builder and tower.</p>
     */
    private ShipBirth assemble(FixtureSite site, String variant, String what) throws Exception {
        int[] bp = RocketFixture.placeAt(site, c -> arrange(c).raw(), variant, 4, 12,
                "the craft this scenario builds stands in this volume");
        long mark = events.mark();
        String asm = act("stellurgytest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]).raw();
        ShipBirth ship = ShipBirth.ofPress(events, mark, 0, bp, asm, NAMING_LINK_TICKS, what);
        built.add(ship.physicsId);
        requireArranged("with VS an AFC-bearing build must route to a ship (no rocket): " + ship,
                ship.rocketsBorn.isEmpty());
        return ship;
    }
}
