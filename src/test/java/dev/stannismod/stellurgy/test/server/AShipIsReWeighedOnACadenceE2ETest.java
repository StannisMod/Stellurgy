package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShipIdentity;
import dev.stannismod.stellurgy.tile.TileAdvancedFlightComputer;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * A ship is re-weighed on a CADENCE, with no event to trigger it.
 *
 * <h2>Why a cadence has to exist at all</h2>
 *
 * <p>The authoritative recompute used to run only where the engine announced something: a craft
 * assembled, pasted, or loaded from disk. That covers every moment a hull's STRUCTURE changes
 * wholesale — and covers nothing else, because the other two halves of a ship's mass change with no
 * block ever changing. A tank empties over a burn. A crate is filled. Somebody steps aboard carrying
 * a stack of ore. There is no block event under any of it, so there is nothing to subscribe to, and a
 * mass model that only listens to events is one that reports what the craft weighed when it was
 * built.</p>
 *
 * <h2>What this pins, and what it deliberately does not</h2>
 *
 * <p>It pins the mechanism: once a craft exists and its assembly's own measurement is on the record,
 * a further measurement of THAT craft runs on the round path, with nothing at all happening to the
 * ship. The record names its path, so the assembly's measurement can never be mistaken for the
 * round's — and with the cadence removed no round record ever appears, which is precisely the state
 * this test was written against.</p>
 *
 * <p>It does NOT yet pin the consequence — that a filled tank makes its ship heavier. Assembly moves
 * a hull's blocks into the ship's own shipyard address space, so filling a tank aboard means
 * addressing it there rather than at the coordinates it was built at, and no probe answers in that
 * space today. Recorded rather than quietly skipped: the mechanism above is what the consequence rests
 * on, and it is the half that could silently not exist.</p>
 *
 * <p>red-witnessed: with {@code TileAdvancedFlightComputer.tickMassRound} returning before its
 * {@code backgroundRound} call ({@code :1229}), the round wait fails with "no `ship_mass_measured`
 * carrying ship = … and path = round was recorded within 200 ticks", 2026-09-29.</p>
 */
public class AShipIsReWeighedOnACadenceE2ETest extends AbstractHeadlessServerTest {

    private static final int BASE_X = 10800, BASE_Z = 10800;

    /** A LINK budget for the assembly's own records, in server ticks — see the sibling hull test. */
    private static final int WAIT_TICKS = 200;

    /**
     * How long a round may take to come, in server ticks: TWO of production's periods. A ship's round
     * falls on a phase of its own inside every {@link TileAdvancedFlightComputer#MASS_ROUND_TICKS},
     * so one period always contains one; the second is the slack for a mark that lands just after a
     * round and for the probe round trips between reads. Its expiry means no round ran.
     */
    private static final int ROUND_BUDGET_TICKS = 2 * TileAdvancedFlightComputer.MASS_ROUND_TICKS;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    @Test
    public void aSettledShipIsMeasuredAgainWithNothingHappeningToIt() throws Exception {

        long assemblyMark = events.markInstrumented();
        String asm = RocketFixture.assembleAt(FixtureSite.openAir(0, BASE_X, BASE_Z), this::exec,
                "with-pilot-seat", 4, 12, "the craft that is re-weighed stands in this volume");
        assertEquals("with the physics mod an AFC-bearing build must become a ship, not a rocket: "
                + asm, 0, Reply.of(asm).integer("rocketCount"));
        String shipId = ShipIdentity.awaitPhysicsIdOf(this::exec, events, 0,
                ShipIdentity.nameFromAssembly(asm), WAIT_TICKS);

        // The assembly's own measurement first, on the record. Marking the round window only after it
        // is what keeps the assembly's recompute out of the round's count — without this the test
        // would pass on a build with no cadence at all.
        events.awaitRecordWithFields(assemblyMark, "ship_mass_measured",
                "ARRANGEMENT: the craft's assembly must be measured before a round can be told apart"
                        + " from it", WAIT_TICKS, "ship", shipId, "path", "event");

        long roundMark = events.markInstrumented();
        events.awaitRecordWithFields(roundMark, "ship_mass_measured",
                "a settled ship must be re-measured on its own cadence, with no event and nothing"
                        + " happening to it, or content and crew can never reach its mass - a tank"
                        + " empties and a crew member boards without a single block changing, so no"
                        + " block event exists to catch either",
                ROUND_BUDGET_TICKS, "ship", shipId, "path", "round");
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }
}
