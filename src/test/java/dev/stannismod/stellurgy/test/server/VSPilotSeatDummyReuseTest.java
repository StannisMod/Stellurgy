package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.SeatMount;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;


import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.PilotSeat;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShipIdentity;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * One seat — one mount dummy, at EVERY dummy-producing path. The right-click mount recipe reuses
 * the seat's existing bound dummy; the {@code vs seat-mount} probe (the harness's stand-in for a
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
        String warmup = String.join("\n", client().execute(
                "stellurgytest chunk warmup 0 " + (x >> 4) + " " + (z >> 4) + " " + (x >> 4) + " " + (z >> 4)));
        assertTrue("chunk warmup failed: " + warmup, Reply.of(warmup).ok());
        String place = String.join("\n", client().execute("stellurgytest fill 0 "
                + x + " " + y + " " + z + " " + x + " " + y + " " + z
                + " stellurgy:pilotSeat"));
        assertTrue("placing the pilot seat failed: " + place, Reply.of(place).ok());

        // The bare form is right here and nowhere else: this test PLACES the only pilot seat in the
        // world two statements above, and the reader's seat count is what says so in a failure.
        SeatMount first = SeatMount.firstLoadedSeat(
                cmd -> String.join("\n", client().execute(cmd)), 0);
        first.requireSeatFound("seat-mount must find the pilot seat just placed");

        SeatMount second = SeatMount.firstLoadedSeat(
                cmd -> String.join("\n", client().execute(cmd)), 0);
        second.requireSeatFound("seat-mount must find that same pilot seat again");

        assertEquals("a second mount on the same seat must REUSE its bound dummy, not spawn a "
                        + "twin (first=" + first.raw() + " second=" + second.raw() + ")",
                first.requireDummyId(), second.requireDummyId());
        assertTrue("the second response must say the dummy was reused: " + second.raw(),
                second.reused);
    }

    /**
     * How long the seat's mount is given to go once its ship is marked dead, in server ticks. The
     * mount reads its ship's record on its own next update; measured 2026-10-07: the mount was removed
     * in the same server tick as the ship's record ({@code entity_removed} and {@code ship_removed}
     * both at one tick). The budget only bounds how long the broken case is waited on.
     */
    private static final int MOUNT_RETIRED_BUDGET_TICKS = 100;

    /**
     * A ship's seat mount goes with the ship. A ship is collected without its seat block being
     * broken, so nothing but the mount itself can notice; one that stayed would be bound to a
     * shipyard position the next ship handed the same claim is given, and be taken for that ship's
     * seat.
     *
     * <p>Contract: fails if {@code EntityDummy#onUpdate} stops deciding that a riderless mount whose
     * ship ({@code EntityDummy#outlivedItsShip}, the ship it was bound under) is gone from the registry
     * retires. The ship is retired by the probe's {@code vs destroy-ship}, which marks it dead exactly
     * as production does; what the test reads is production's own removal of the mount
     * ({@code entity_removed}).</p>
     * <p>red-witnessed: with {@code EntityDummy#onUpdate} at {@code if (getPassengers().isEmpty() &&
     * outlivedItsShip())} reading false, fails: "the seat's mount must go with its ship, not outlive it
     * bound to a shipyard position the next ship on the claim is handed — no `entity_removed` carrying
     * e = 1357 was recorded within 100 ticks. What DID happen since the mark: … ship_removed …"
     * (2026-10-07).</p>
     */
    @Test
    public void aShipsSeatMountGoesWithTheShip() throws Exception {
        FixtureSite site = site();
        int[] builder = RocketFixture.placeAt(site, this::exec, "with-pilot-seat", 1, 8,
                "the craft this scenario builds and destroys");
        String asm = RocketFixture.assembleBuilt(site, this::exec, builder);
        requireArranged("the pilot-seat build must route to a ship: " + asm,
                Reply.of(asm).integer("rocketCount") == 0);
        String shipId = ShipIdentity.physicsIdOf(this::exec, 0, ShipIdentity.nameFromAssembly(asm));
        PilotSeat seat = PilotSeat.byId(this::exec, 0, shipId)
                .requireFound("the assembled ship's pilot seat must be found");
        String at = seat.seatX + " " + seat.seatY + " " + seat.seatZ;

        Reply mounted = Reply.of(exec("stellurgytest vs seat-mount-at 0 " + at));
        requireArranged("the seat's mount must spawn: " + mounted, mounted.ok());
        int dummyId = mounted.integer("dummyId");
        Reply bound = Reply.of(exec("stellurgytest vs seat-status 0 " + at));
        requireArranged("the seat must hold exactly that one mount before its ship goes: " + bound,
                bound.integer("boundCount") == 1);

        Events events = new Events(this::exec,
                ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());
        long mark = events.markInstrumented();
        Reply destroyed = Reply.of(exec("stellurgytest vs destroy-ship 0 " + shipId));
        requireArranged("the ship must be marked dead: " + destroyed, destroyed.bool("marked"));
        String shipGone = events.awaitRecordWithField(mark, "ship_removed", "vsShip", shipId,
                "the destroyed ship must leave the registry, or its mount has nothing to notice",
                MOUNT_RETIRED_BUDGET_TICKS);

        String mountGone = events.awaitRecordWithFields(mark, "entity_removed",
                "the seat's mount must go with its ship, not outlive it bound to a shipyard position the"
                        + " next ship on the claim is handed", MOUNT_RETIRED_BUDGET_TICKS,
                "e", String.valueOf(dummyId));
        System.out.println("[seat-mount] ship " + shipGone + " | mount " + mountGone);
    }

}
