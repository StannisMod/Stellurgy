package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ShipIdentity;
import dev.stannismod.stellurgy.test.ShipInfo;
import dev.stannismod.stellurgy.test.TransitSetup;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertTrue;

/**
 * A space cell has no gravity, so a craft released in one does not fall.
 *
 * <h2>The third case, and the only one nothing watched</h2>
 *
 * <p>The per-world gravity field answers three ways: a registered body scales the configured vector by
 * its own multiplier, a foreign or vanilla world gets that vector unchanged, and a space cell gets
 * ZERO. The first is witnessed by a craft that falls a quarter as far over a quarter-gravity body; the
 * second is the untouched path every other world already flew. The cell was pinned by nothing at all —
 * and it is the case a player spends the whole tier-2 game inside.</p>
 *
 * <h2>Why "it did not move" needs a control, here more than anywhere</h2>
 *
 * <p>On a planet, a craft that does not fall is doing something: holding. In a cell there is nothing
 * to hold against, and <b>a craft in zero gravity is indistinguishable from a craft nobody is
 * simulating</b> — both sit exactly still, both report zero velocity, and the second is a defect this
 * mod has actually shipped: physics used to be switched on only after a craft had been flown once, so
 * a newly built ship hung in the air and read as station-keeping.</p>
 *
 * <p>So stillness is believed only once the same craft is shown to be under the solver's hand: it is
 * DRIVEN through its own flight computer, and it must translate. The drive is the computer's
 * realized-force channel ({@code force-vel-by-id}) and not a raw velocity write, which the substrate
 * overwrites every step and which moves nothing ({@code VSShipMotionServerTest} pins exactly that).</p>
 *
 * <h2>Why this is a server test after all</h2>
 *
 * <p>It sat {@code @Ignore}d on the premise that a ship on a headless server never becomes loaded,
 * with a client-tier port beside it that was never run. The premise is false in this tree: a test
 * server holds its ships loaded, and the crossing and motion tests on this tier drive craft that way.
 * Its control was the other half of why it could not pass — it pushed with the raw velocity write.
 * Both are fixed here, and the client port is gone: one contract, one home, on the cheapest tier that
 * can see a craft's pose.</p>
 *
 * <p>red-witnessed: with {@code StellurgyWorldGravity:68} answering the configured vector for a space
 * slot instead of zero, the stillness verdict fails — "moved 74.2 blocks vertically in 41 ticks" —
 * on a craft that was {@code ready:true} when released, 2026-09-29. (The first attempt at this
 * witness stayed GREEN: the window had opened on a craft not yet simulated. That is what the
 * {@code ship_usable} link above now rules out.)</p>
 */
public class AShipInASpaceCellDoesNotFallE2ETest extends AbstractSharedServerTest {

    /**
     * How far the craft may drift vertically and still count as not falling, in blocks. At the
     * configured field a released craft covers about 110 blocks in 60 ticks (measured 2026-08-19), so
     * a cell handed a planet's gravity sinks tens of blocks in this window, not two. Measured in a cell
     * 2026-09-29: 0.27 over 41 ticks.
     */
    private static final double STILL_TOLERANCE = 2.0;

    /** The velocity the flight computer is commanded to realize for the control, in blocks/second. */
    private static final double DRIVE_VZ = 4.0;

    /**
     * How far it must travel under that command before the stillness means anything, in blocks. At
     * the commanded rate the window covers {@code DRIVE_VZ x SAMPLE_TICKS / 20} = 8 blocks; the bar is
     * well under that so the controller's ramp-up does not decide it, and far above the drift of a
     * craft nobody simulates. Measured 2026-09-29: 7.9 over 41 ticks.
     */
    private static final double DRIVE_MIN_TRAVEL = 3.0;

    /** The window each leg is watched over, in server ticks. */
    private static final int SAMPLE_TICKS = 40;

    /** A LINK budget for the setup's craft to become usable, in server ticks. */
    private static final int WAIT_TICKS = 200;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    @Test
    public void aReleasedCraftInACellKeepsItsAltitude() throws Exception {

        long setupMark = events.markInstrumented();
        TransitSetup setup = TransitSetup.piloted(this::exec);
        int cellDim = setup.originDim;
        String shipId = ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(this::exec, events,
                cellDim, setup.requireDurableId(), WAIT_TICKS));
        // USABLE, as its own link, and not merely named. The id above answers as soon as the craft
        // is REGISTERED; a registered craft whose physics has not started yet sits exactly as still
        // as a weightless one. Measured 2026-09-29: the subject window opened on `ready:false`, and
        // under load a planet's gravity in the cell then sank the craft 1.48 blocks in 76 ticks —
        // green, because it was not being simulated for most of the window.
        ShipIdentity.awaitUsable(events, setupMark, shipId, cellDim,
                "the craft must be USABLE before its stillness can mean anything", WAIT_TICKS);

        // A craft placed by a paste is rigid until something hands it back, and a rigid craft is
        // exactly as still as a weightless one.
        String unparked = exec("stellurgytest vs unpark-by-id " + cellDim + " " + shipId);
        requireArranged("the pasted craft must be handed back to physics: " + unparked,
                Reply.of(unparked).ok());

        // Release it: Flight Assist off is what hands an unpiloted craft to the field, whatever the
        // field turns out to be. Over a planet this is what makes it fall.
        requireArranged("could not reach the flight computer to release the craft",
                Reply.of(exec("stellurgytest vs fa-by-id " + cellDim + " " + shipId + " false"))
                        .bool("afcResolved"));

        // --- the subject: released, over nothing, it must keep its altitude ------------------------
        ShipInfo atRelease = ShipInfo.byId(this::exec, cellDim, shipId);
        double before = atRelease.y;
        // WINDOW: the altitude is read before and after this stretch and the claim is an UPPER bound
        // on the difference, so a longer stretch than asked can only make a real field show.
        long stillTicks = GameTicks.advanceObserved(client(), GameTicks.server(), SAMPLE_TICKS);
        ShipInfo afterStill = ShipInfo.byId(this::exec, cellDim, shipId);
        double after = afterStill.y;
        double sank = before - after;

        // --- the control, taken AFTER the reading and asserted BEFORE it ---------------------------
        // Taken second so the drive cannot disturb the altitude it is vouching for, and asserted first
        // so a craft nobody is simulating fails HERE, on a leg that says so.
        double zBefore = ShipInfo.byId(this::exec, cellDim, shipId).z;
        requireArranged("could not command the craft's flight computer: the control leg cannot run",
                Reply.of(exec("stellurgytest vs force-vel-by-id " + cellDim + " " + shipId + " 0 0 "
                        + DRIVE_VZ)).bool("afcResolved"));
        // EXPERIMENT: the dose is SAMPLE_TICKS of commanded drive, and the claim is a LOWER bound —
        // so the bar is scaled by the ticks this box delivered and the rate it demands is fixed.
        long driven = GameTicks.advanceObserved(client(), GameTicks.server(), SAMPLE_TICKS);
        double travelled = Math.abs(ShipInfo.byId(this::exec, cellDim, shipId).z - zBefore);
        double required = DRIVE_MIN_TRAVEL * driven / SAMPLE_TICKS;
        System.out.println("[cell witness] sank " + sank + " in " + stillTicks + " ticks; driven "
                + travelled + " in " + driven + " ticks; at release " + atRelease.raw()
                + " after " + afterStill.raw());

        requireArranged("CONTROL: the craft must be under the solver's hand for its stillness to"
                        + " mean anything. Commanded at " + DRIVE_VZ + " blocks/s it moved " + travelled
                        + " blocks in " + driven + " ticks, needing " + required + ". In zero gravity a"
                        + " craft nobody simulates sits exactly as still as one that is weightless, so"
                        + " the stillness measured above would be true for the wrong reason.",
                travelled >= required);

        assertTrue("a released craft in a space cell must keep its altitude: there is nothing for it to"
                        + " fall towards, and the field a cell supplies is zero. This one moved " + sank
                        + " blocks vertically in " + stillTicks + " ticks (from " + before + " to "
                        + after + "). Tens of blocks is what the configured field would produce, i.e."
                        + " the cell being handed a planet's gravity.",
                Math.abs(sank) <= STILL_TOLERANCE);
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }
}
