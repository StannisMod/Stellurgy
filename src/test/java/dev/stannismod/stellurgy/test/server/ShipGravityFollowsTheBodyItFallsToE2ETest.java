package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.DimList;
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

import java.util.HashSet;
import java.util.Set;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertTrue;

/**
 * Gravity is a property of the world a craft is in, and its MAGNITUDE is that world's own
 * gravitational multiplier: a released ship over a quarter-gravity body sinks a quarter as far in the
 * same time as one released over Earth.
 *
 * <h2>What was already witnessed, and what was not</h2>
 *
 * <p>{@code AnUnpilotedShipFallsWithoutFlightAssistE2ETest} pins that a released craft falls at all —
 * on Earth. That leaves the whole point of a per-world field unobserved: every craft could be falling
 * at one standard gravity everywhere and that test would be just as green. A field that does not vary
 * is a constant, and thrust-to-weight, hover cost and stopping distance are all statements ABOUT a
 * varying field.</p>
 *
 * <h2>Two worlds at once, not one world twice</h2>
 *
 * <p>The two craft are released in the same window, in two worlds, and read separately. Doing it as
 * two sequential runs in one world — flip the multiplier, drop again — would pass equally well on a
 * build where gravity is a single global that the last write wins, which is precisely the defect this
 * mechanic exists to rule out. Two ships falling simultaneously at different rates cannot be produced
 * by any global.</p>
 *
 * <p>The measurement is a RATIO, and that is what makes it a contract test rather than a pin on
 * today's numbers. The solver adds {@code gravity x mass x dt} and then scales velocity by a drag
 * factor, so the fall is linear in the field and mass-invariant: measured 2026-09-29 over 41 ticks,
 * Earth 59.43 blocks and the quarter-gravity body 14.86, a ratio of 0.2500. The two releases and the
 * two reads are each one probe call apart and taken in the same order, so each craft is watched over
 * the same number of ticks to within a call.</p>
 *
 * <h2>The premises are gated before the subject is measured</h2>
 *
 * <p>In order: the low-gravity body really carries the multiplier asked for; both craft became ships;
 * both HOLD with Flight Assist on; and the Earth craft, once released, really falls. Only then is the
 * low-gravity craft's fall compared. Without the last two, "it barely moved" is the reading a craft
 * gives when it was never simulated, when it was never released, and when the field is genuinely
 * small — three different states with one appearance.</p>
 *
 * <p>The hold is asserted as an ABSOLUTE drift, in both directions, on purpose. The solver and the
 * flight computer's feed-forward ask the same function precisely so that they agree; if the
 * feed-forward were left on Earth's field while the solver used the body's, a held craft over a
 * quarter-gravity body would CLIMB by the difference — which is most of the field — and a one-sided
 * "did it sink" gate would wave that through.</p>
 *
 * <p>red-witnessed: with {@code StellurgyWorldGravity:85} answering the configured vector instead of
 * scaling it by the body's multiplier, the ratio verdict fails at 1.015 ("outside [0.125, 0.5]"),
 * 2026-09-29.</p>
 */
public class ShipGravityFollowsTheBodyItFallsToE2ETest extends AbstractHeadlessServerTest {

    private static final int BASE_X = 11000, BASE_Z = 11000;

    /**
     * A hundred blocks above the open-air band. The launchpad stays behind as WORLD blocks directly
     * under the lift, and at fifty the Earth craft landed on it — measured 2026-09-29: 42.85 blocks
     * in one run against 59.43 in the next, the difference being the pad.
     */
    private static final int SKY_Y = FixtureSite.OPEN_AIR_Y + 100;

    /**
     * The gravity the authored body is given, as a fraction of the configured field. A quarter is far
     * enough from one that no plausible tolerance can hide it, and far enough from zero that the
     * craft must still visibly fall — a body a test cannot tell apart from a void would witness
     * nothing about magnitude.
     */
    private static final double LOW_GRAVITY = 0.25;

    /**
     * How wide a ratio band counts as agreement, as a factor either side of {@link #LOW_GRAVITY}. It
     * refuses everything this test exists to catch: an unscaled field lands at 1.0, twice the upper
     * bound, and a multiplier applied twice lands at 0.0625, half the lower one. The measured ratio is
     * the multiplier itself (0.2500, 2026-09-29); the slack is for the call-apart reads, not for the
     * physics.
     */
    private static final double RATIO_SLACK = 2.0;

    /**
     * How far the Earth craft must sink for the comparison to mean anything, in blocks. Measured
     * 2026-09-29: 59.43 in 41 ticks; a craft that is held, parked or unsimulated covers none of it.
     */
    private static final double EARTH_FELL_MIN = 10.0;

    /**
     * How much a HELD craft may drift either way, same units. A hold that leaks this much is not one;
     * the sibling fall test measured a held craft's drift at 0.0 over 61 ticks (2026-09-29).
     */
    private static final double HELD_TOLERANCE = 2.0;

    /**
     * How long each craft is watched, in server ticks — sized against the MEASURED fall rate. A craft
     * released at one standard gravity covers about 110 blocks in 60 ticks here (measured
     * 2026-08-19), which from the release altitude would reach the ground and turn the Earth reading
     * into a landing rather than a fall. Forty leaves it around fifty blocks down with clear air
     * beneath it, and still drops the low-gravity craft far enough that its fall cannot be confused
     * with a hold's drift.
     */
    private static final int SAMPLE_TICKS = 40;

    /** A LINK budget for each craft to become usable after its assembly, in server ticks. */
    private static final int WAIT_TICKS = 200;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    @Test
    public void aCraftOverALowGravityBodyFallsInProportionToThatBodysGravity() throws Exception {

        int lowGravityDim = authorLowGravityBody();

        Craft earth = buildAndLift(0);
        Craft low = buildAndLift(lowGravityDim);

        // --- premise: both craft are simulated, controllable, and at rest ---------------------------
        hold(earth);
        hold(low);
        double earthFrom = shipY(earth), lowFrom = shipY(low);
        // WINDOW: both altitudes are read before and after this stretch and the claim is an UPPER
        // bound on each difference, so a longer stretch than asked can only make a leaking hold show.
        GameTicks.advanceObserved(client(), GameTicks.server(), SAMPLE_TICKS);
        double earthHeldY = shipY(earth), lowHeldY = shipY(low);
        assertHeld(earth, earthFrom, earthHeldY);
        assertHeld(low, lowFrom, lowHeldY);
        // From here the held reading is the release point: it is the last one taken while the craft
        // was demonstrably at rest, so the distance measured below is a fall and not the tail of
        // whatever the craft was doing when it arrived.

        // --- release both in the same window --------------------------------------------------------
        release(earth);
        release(low);
        // EXPERIMENT: the dose is SAMPLE_TICKS of release for both craft at once. The subject is a
        // RATIO of two falls over the same ticks, so the box delivering more of them changes both
        // numbers together and not the verdict.
        long fallTicks = GameTicks.advanceObserved(client(), GameTicks.server(), SAMPLE_TICKS);
        double earthFell = earthHeldY - shipY(earth);
        double lowFell = lowHeldY - shipY(low);

        System.out.println("[gravity witness] earth fell " + earthFell + " blocks, dim "
                + low.dim + " (gravity " + LOW_GRAVITY + ") fell " + lowFell + " blocks in "
                + fallTicks + " ticks - ratio " + (lowFell / earthFell));

        // --- control: the Earth craft must fall, or the comparison below is between two non-events --
        requireArranged("CONTROL: released over Earth the craft must fall, and this one moved "
                        + earthFell + " blocks in " + fallTicks + " ticks. Until a released craft"
                        + " demonstrably falls here, the low-gravity craft holding still would say"
                        + " nothing about gravity - it is what a craft that was never released, or"
                        + " never simulated, looks like too.",
                earthFell >= EARTH_FELL_MIN);

        // --- the subject: the same release over a quarter-gravity body ------------------------------
        double ratio = lowFell / earthFell;
        assertTrue("a craft released over a body of gravity " + LOW_GRAVITY + " must fall that"
                        + " fraction of what the same release covers over Earth, but it fell "
                        + lowFell + " blocks against Earth's " + earthFell + " - a ratio of " + ratio
                        + ", outside [" + (LOW_GRAVITY / RATIO_SLACK) + ", "
                        + (LOW_GRAVITY * RATIO_SLACK) + "]. A ratio near 1 means the body's"
                        + " multiplier never reached the solver and every world is still one standard"
                        + " gravity; a ratio near 0 means the craft over the low-gravity body is not"
                        + " falling at all, which is a different defect from a weak field.",
                ratio >= LOW_GRAVITY / RATIO_SLACK && ratio <= LOW_GRAVITY * RATIO_SLACK);
    }

    // --- arrangement --------------------------------------------------------------------------------

    /**
     * A freshly generated planet, given a known gravity through the ordinary planet command. The
     * generator rolls a multiplier at random, so the body is authored rather than searched for: a
     * test whose discriminating power depends on what the world generator happened to produce is
     * not a test.
     */
    private int authorLowGravityBody() throws Exception {
        Set<Integer> fresh = registeredDims();
        exec("ar planet generate 0 LowGravityWitness");
        Set<Integer> after = registeredDims();
        after.removeAll(fresh);
        requireArranged("planet generate must add exactly one dim - got " + after, after.size() == 1);
        int dim = after.iterator().next();

        String load = exec("stellurgytest dim load " + dim);
        requireArranged("the authored body never loaded: " + load, Reply.of(load).bool("loaded"));

        // The SHIPPED command, not a test-only setter: it is what an operator would use, it refuses
        // a dimension that is not a registered body instead of silently writing to Earth's
        // properties, and it publishes the change the way production does.
        exec("ar planet set " + dim + " gravitationalMultiplier " + LOW_GRAVITY);
        // Read back off the planet registry rather than trusting the command's own reply: what the
        // solver will ask is the registry, and this is the one moment the arrangement can be checked
        // against the same source.
        double gravity = Reply.of(exec("stellurgytest planet info " + dim)).number("gravity");
        // Exact: the multiplier is stored as a float, and 0.25 is exact in one.
        requireArranged("the authored body does not carry the gravity it was given (" + gravity + ")",
                gravity == LOW_GRAVITY);
        return dim;
    }

    private Set<Integer> registeredDims() throws Exception {
        Set<Integer> ids = new HashSet<>();
        for (int dim : DimList.from(this::exec).registered()) {
            ids.add(dim);
        }
        return ids;
    }

    /** Build the craft in {@code dim}, then lift it into clear sky and hand it to physics. */
    private Craft buildAndLift(int dim) throws Exception {
        long buildMark = events.markInstrumented();
        String asm = RocketFixture.assembleAt(FixtureSite.openAir(dim, BASE_X, BASE_Z), this::exec,
                "with-pilot-seat", 4, 12, "the craft released over this world is built here");
        requireArranged("with the physics mod an AFC-bearing build must become a ship, not a rocket (dim "
                + dim + "): " + asm, Reply.of(asm).integer("rocketCount") == 0);
        String id = ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(this::exec, events,
                dim, ShipIdentity.nameFromAssembly(asm), WAIT_TICKS));
        // Usable, not merely named: an unsimulated craft passes the HOLD control for free.
        ShipIdentity.awaitUsable(events, buildMark, id, dim,
                "the craft in dim " + dim + " must be simulated before its hold is measured", WAIT_TICKS);
        ShipLift.toAltitude(this::exec, events, client(), dim, id, SKY_Y,
                "a craft released on its pad lands on the pad at once, which reads as not falling");
        return new Craft(dim, id);
    }

    private void hold(Craft craft) throws Exception {
        requireArranged("could not reach the flight computer of the craft in dim " + craft.dim,
                Reply.of(exec("stellurgytest vs fa-by-id " + craft.dim + " " + craft.id + " true"))
                        .bool("afcResolved"));
    }

    private void release(Craft craft) throws Exception {
        requireArranged("could not release the craft in dim " + craft.dim,
                Reply.of(exec("stellurgytest vs fa-by-id " + craft.dim + " " + craft.id + " false"))
                        .bool("afcResolved"));
    }

    private void assertHeld(Craft craft, double fromY, double heldY) {
        requireArranged("PREMISE: with Flight Assist on the craft in dim " + craft.dim
                        + " must keep station, and this one moved to " + heldY + " from " + fromY
                        + ". Drift DOWN means it is not being held at all, so the fall measured"
                        + " afterwards would not be caused by the release; drift UP means the flight"
                        + " computer is cancelling a field larger than the one the solver applies,"
                        + " which is exactly the disagreement the shared gravity function exists to"
                        + " prevent.",
                Math.abs(fromY - heldY) <= HELD_TOLERANCE);
    }

    /** The craft's own Y, by identity — never "whichever ship is nearest", which a fall would outrun. */
    private double shipY(Craft craft) throws Exception {
        return ShipInfo.byId(this::exec, craft.dim, craft.id).y;
    }

    /** One craft under test: its world and its identity, so no reading can be about the other one. */
    private static final class Craft {
        final int dim;
        final String id;

        Craft(int dim, String id) {
            this.dim = dim;
            this.id = id;
        }
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }
}
