package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Disposal;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.SeatMount;
import dev.stannismod.stellurgy.test.ShipBirth;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * One seat — one mount dummy, at EVERY dummy-producing path. The right-click mount recipe reuses
 * the seat's existing bound dummy; the {@code vs seat-mount-at} probe (the harness's stand-in for a
 * right-click on an assembled ship's subspace seat) must obey the same rule, or every test mount
 * leaks an extra dummy whose riderless twin clears the ship's pilot input each server tick and
 * fights the seated pilot's controls.
 *
 * <p>The reuse scenario runs on a bare, unassembled pilot seat block (the rule is about the
 * seat↔dummy binding, not about the ship). The other builds a ship, because its subject is a mount
 * whose ship is gone.</p>
 */
public class VSPilotSeatDummyReuseTest extends AbstractSharedServerTest {

    private static final String DUMMY_ID = "dummyId";

    @Test
    public void theSeatMountProbeReusesTheSeatsSingleDummy() throws Exception {
        int x = 3000, y = FixtureSite.OPEN_AIR_Y, z = 3000;
        String warmup = String.join("\n", client().arrange(
                "stellurgytest chunk warmup 0 " + (x >> 4) + " " + (z >> 4) + " " + (x >> 4) + " " + (z >> 4)));
        assertTrue("chunk warmup failed: " + warmup, Reply.of(warmup).ok());
        String place = String.join("\n", client().arrange("stellurgytest fill 0 "
                + x + " " + y + " " + z + " " + x + " " + y + " " + z
                + " stellurgy:pilotSeat"));
        assertTrue("placing the pilot seat failed: " + place, Reply.of(place).ok());

        // The seat is addressed where this test PLACED it, two statements above — never looked for
        // among the world's loaded seats.
        String mountAt = "stellurgytest vs seat-mount-at 0 " + x + " " + y + " " + z;
        Reply first = Reply.of(mountAt, String.join("\n", client().arrange(mountAt)))
                .requireOk("seat-mount-at must bind a dummy to the pilot seat just placed");
        Reply second = Reply.of(mountAt, String.join("\n", client().arrange(mountAt)))
                .requireOk("seat-mount-at must bind a dummy to that same pilot seat again");

        assertEquals("a second mount on the same seat must REUSE its bound dummy, not spawn a "
                        + "twin (first=" + first + " second=" + second + ")",
                first.integer(DUMMY_ID), second.integer(DUMMY_ID));
        assertTrue("the second response must say the dummy was reused: " + second,
                second.bool("reused"));
    }

    /** Ticks a test allows the server for one link on its own records. */
    private static final int LINK_TICKS = 200;

    private final Events events = new Events(c -> read(c).raw(),
            ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /**
     * A riderless dummy whose seat no longer stands removes itself — here the seat goes with its ship,
     * which a discard takes away by deleting the ship's chunks, so no block of it is ever broken.
     *
     * <p>The dummy is bound with nobody on it, then the craft is discarded by its own id and the
     * discard is linked on its {@code DESTROYED}; the verdict is the world's own removal of THAT dummy,
     * by its entity id.</p>
     */
    @Test
    @Pins("SHIPCTL-20")
    @RedWitness(member = "EntityDummy#onUpdate",
            at = "(outlivedItsShip() || (getPassengers().isEmpty() && !seatStands()))", replacedBy = "(false)",
            fails = "the riderless dummy of a seat that went with its discarded ship was never removed",
            on = "2026-10-17")
    public void aRiderlessDummyWhoseSeatWentWithItsShipRemovesItself() throws Exception {
        int[] builder = RocketFixture.placeAt(site(), c -> arrange(c).raw(), "with-pilot-seat", 4, 12,
                "the craft whose seat the dummy is bound to stands in this volume");
        long buildMark = events.mark();
        String press = act("stellurgytest rocket assemble 0 " + builder[0] + " " + builder[1] + " "
                + builder[2]).raw();
        ShipBirth birth = ShipBirth.ofPress(events, buildMark, 0, builder, press, LINK_TICKS,
                "the craft whose seat the dummy is bound to");
        String shipId = birth.awaitUsable(c -> read(c).raw(), events, LINK_TICKS);

        int dummy = SeatMount.onShip(c -> arrange(c).raw(), 0, shipId)
                .requireSeatFound("the dummy must be bound to the assembled craft's seat")
                .requireDummyId();

        long discardMark = events.mark();
        Disposal.ship(c -> arrange(c).raw(), events, 0, shipId);
        events.awaitRecordWithFields(discardMark, "entity_removed",
                "the riderless dummy of a seat that went with its discarded ship was never removed — it"
                        + " stays in the world bound to a seat that no longer exists", LINK_TICKS,
                "e", String.valueOf(dummy), "cls", "EntityDummy");
    }

}
