package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShipIdentity;
import dev.stannismod.stellurgy.test.ShipInfo;
import dev.stannismod.stellurgy.test.ShipLift;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Flight Assist is the unmanned mode switch: with it ON an unpiloted craft holds, with it OFF the
 * craft is released and falls.
 *
 * <h2>Why this needs saying at all</h2>
 *
 * <p>A ship left at altitude with nobody at the controls used not to fall — and not because anything
 * was holding it up. The solver steps only bodies whose physics has been switched on, and that switch
 * was thrown by the flight computer only after a craft had been FLOWN once. A newly built ship
 * therefore hung in the air, and the state read from outside as station-keeping while in fact the
 * craft was not being simulated at all. An assembled craft is simulated from its assembly now, and
 * Flight Assist alone decides whether it holds.</p>
 *
 * <h2>Both directions, in one run</h2>
 *
 * <p>"It fell" alone would pass on a build where a craft can no longer hold at all — which would be a
 * worse defect than the one being fixed, and invisible to a one-sided test. So the same craft is held
 * with Flight Assist ON first and asserted NOT to move, then released and asserted to fall. The
 * control comes first deliberately: if the hold is already broken, the test says so instead of
 * reporting a successful fall.</p>
 *
 * <p>red-witnessed: with the unmanned branch's {@code !flightAssistEnabled} release in
 * {@code TileAdvancedFlightComputer} ({@code :734}) never taken, the fall verdict fails — "sank only
 * 0.0 blocks in 61 ticks" — after the hold control passed, 2026-09-29.</p>
 */
public class AnUnpilotedShipFallsWithoutFlightAssistE2ETest extends AbstractHeadlessServerTest {

    private static final int BASE_X = 11000, BASE_Z = 11000;

    /**
     * A hundred blocks above the open-air band. The launchpad stays behind as WORLD blocks directly
     * under the lift, so the room below the craft is the room above its own pad — and a released
     * craft covers ~45 blocks in this window.
     */
    private static final int SKY_Y = FixtureSite.OPEN_AIR_Y + 100;

    /**
     * How far the craft must sink to count as falling, in blocks. Far above the drift of a hold and
     * far below what free fall covers in the window — at the configured field a released craft covers
     * about 110 blocks in 60 ticks (measured 2026-08-19; 88.7 to 128.3 in 61 ticks from a standing
     * release across four runs on 2026-09-29 — a spread whose cause is not established, and which
     * this bound does not care about) — so the assertion is about WHETHER the craft is released, not how fast; the rate is
     * a balance number and not pinned here.
     */
    private static final double FELL = 4.0;

    /**
     * How far a HELD craft may drift either way, same units. A hold that leaks this much is not one;
     * measured 0.0 over 61 ticks (2026-09-29).
     */
    private static final double HELD_TOLERANCE = 2.0;

    /** The window each leg is watched over, in server ticks. */
    private static final int SAMPLE_TICKS = 60;

    /** A LINK budget for the craft to become usable after its assembly, in server ticks. */
    private static final int WAIT_TICKS = 200;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    @Test
    public void flightAssistDecidesWhetherAnUnpilotedCraftHoldsOrFalls() throws Exception {

        long buildMark = events.markInstrumented();
        String asm = RocketFixture.assembleAt(FixtureSite.openAir(0, BASE_X, BASE_Z), this::exec,
                "with-pilot-seat", 4, 12, "the craft that is held and then released is built here");
        assertEquals("with the physics mod an AFC-bearing build must become a ship, not a rocket: " + asm,
                0, Reply.of(asm).integer("rocketCount"));
        String shipId = ShipIdentity.awaitPhysicsIdOf(this::exec, events, 0,
                ShipIdentity.nameFromAssembly(asm), WAIT_TICKS);
        // Usable, not merely named: an unsimulated craft passes the HOLD control below for free.
        ShipIdentity.awaitUsable(events, buildMark, shipId, 0,
                "ARRANGEMENT: the craft must be simulated before its hold can be measured", WAIT_TICKS);
        ShipLift.toAltitude(this::exec, client(), 0, shipId, SKY_Y,
                "a craft released on its pad lands on the pad at once, which reads as holding");

        // --- control: Flight Assist ON must HOLD -------------------------------------------------
        assertTrue("could not reach this ship's flight computer to arm the control leg",
                Reply.of(exec("stellurgytest vs fa-by-id 0 " + shipId + " true")).bool("afcResolved"));
        double heldFrom = ShipInfo.byId(this::exec, 0, shipId).y;
        // WINDOW: the altitude is read before and after this stretch and the claim is an UPPER bound
        // on the difference, so a longer stretch than asked can only make a leaking hold show.
        long heldTicks = GameTicks.advanceObserved(client(), GameTicks.server(), SAMPLE_TICKS);
        double heldTo = ShipInfo.byId(this::exec, 0, shipId).y;
        assertTrue("ARRANGEMENT/CONTROL: with Flight Assist ON an unpiloted craft must keep station,"
                        + " and this one moved from " + heldFrom + " to " + heldTo + " in " + heldTicks
                        + " ticks. Without a working hold, the fall asserted below would say nothing - a"
                        + " craft that cannot hold falls whatever the mode switch does.",
                Math.abs(heldFrom - heldTo) <= HELD_TOLERANCE);

        // --- the subject: Flight Assist OFF must RELEASE ------------------------------------------
        assertTrue("could not reach this ship's flight computer to release it",
                Reply.of(exec("stellurgytest vs fa-by-id 0 " + shipId + " false")).bool("afcResolved"));
        double releasedFrom = ShipInfo.byId(this::exec, 0, shipId).y;
        // EXPERIMENT: the dose is SAMPLE_TICKS of release. The claim is a LOWER bound, which extra
        // ticks make easier — but only for a craft that is falling at all: a held one does not sink
        // further for being watched longer, and a released one clears FELL many times over.
        long fallTicks = GameTicks.advanceObserved(client(), GameTicks.server(), SAMPLE_TICKS);
        double fellTo = ShipInfo.byId(this::exec, 0, shipId).y;
        System.out.println("[fall witness] held drift " + (heldFrom - heldTo) + " in " + heldTicks
                + " ticks; released fall " + (releasedFrom - fellTo) + " in " + fallTicks + " ticks");

        assertTrue("with Flight Assist OFF and nobody at the controls the craft must be RELEASED and"
                        + " fall, but it sank only " + (releasedFrom - fellTo) + " blocks in "
                        + fallTicks + " ticks (from " + releasedFrom + " to " + fellTo + "). Either the"
                        + " controller is still commanding a hover with the assist off, or the craft's"
                        + " physics was never switched on - the two produce the same reading from here"
                        + " and both mean the mode switch is not the mode switch.",
                releasedFrom - fellTo >= FELL);
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }
}
