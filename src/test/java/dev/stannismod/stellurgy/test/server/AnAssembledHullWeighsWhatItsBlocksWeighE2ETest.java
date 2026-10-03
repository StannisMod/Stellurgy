package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShipIdentity;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertTrue;

/**
 * A full pass over a ship's hull agrees with the running total the engine kept while building it.
 *
 * <h2>What is actually under test</h2>
 *
 * <p>A ship's mass is maintained two ways, and only one of them is cheap. The working path is
 * incremental — a delta applied as each block arrives or leaves — and it is correct exactly as long
 * as nothing is ever missed. The authority is a full walk over the hull, run at the few moments where
 * the incremental path cannot be trusted to have kept up: an assembly, a paste, a load from disk.</p>
 *
 * <p>This asserts they agree on a craft that was just assembled — the one case where they really
 * ought to, because the deltas were fed the whole hull moments earlier. A disagreement here is not a
 * rounding complaint: it means the two halves of the mass model are pricing the same ship
 * differently, and every derived number downstream (thrust-to-weight, turn rate, fuel per manoeuvre)
 * inherits whichever one it happened to read.</p>
 *
 * <h2>How it sees</h2>
 *
 * <p>The comparison's own verdict, as a record: {@code ship_mass_compared} is written where production
 * decides whether the two totals agree, and it carries that decision ({@code agrees}, beside the
 * production description of any drift). So the test waits for the comparison of THIS craft and reads its answer — there is no
 * silence to interpret. The record is keyed by the physics identity, which is named from the assembly
 * that made the craft rather than by anything near a place.</p>
 *
 * <p>The drift is never thrown by production — it runs inside the world tick, and a dead server would
 * report that the process exited rather than that a hull was 4% light — so the number arrives here in
 * the record and the failure can carry it.</p>
 *
 * <p>red-witnessed: one break per verdict, 2026-09-30 (the {@code Drift} form — with
 * {@code ShipMassTrigger#recompute} at {@code if (event.cause != ShipLifecycleEvent.Cause.LOADED)} comparing only on a LOAD, the wait for this craft's comparison fails —
 * "the assembly never compared the full hull pass against the running total"; with
 * {@code ShipInertiaWriter#compare} at {@code if (Math.abs(recorded - expected) / massScale <= MASS_TOLERANCE && centreError <= CENTRE_TOLERANCE)}'s agreement test inverted, the verdict fails on {@code "agrees":false}
 * ("disagree").</p>
 */
public class AnAssembledHullWeighsWhatItsBlocksWeighE2ETest extends AbstractHeadlessServerTest {

    private static final int BASE_X = 10600, BASE_Z = 10600;

    /**
     * Budget in SERVER TICKS for the assembly's comparison to be recorded — a LINK: its expiry means
     * the comparison never ran. The assembly queues a spawn that the ship manager serves on its next
     * tick and announces at the end of that tick, so the record is a handful of ticks away; 200 is the
     * link budget the ship-registry waits in this tier use.
     */
    private static final int WAIT_TICKS = 200;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    @Test
    public void theAuthoritativeRecomputeAgreesWithTheIncrementalTotalOnAFreshAssembly()
            throws Exception {

        // Marked before the assemble: the comparison is made on the tick the craft is first named,
        // and a mark taken after the assembly could land behind it.
        long mark = events.markInstrumented();
        String asm = RocketFixture.assembleAt(FixtureSite.openAir(0, BASE_X, BASE_Z), this::exec,
                "with-pilot-seat", 4, 12, "the craft whose hull is weighed stands in this volume");
        requireArranged("with the physics mod an AFC-bearing build must become a ship, not a rocket: "
                + asm, Reply.of(asm).integer("rocketCount") == 0);
        String shipId = ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(this::exec, events,
                0, ShipIdentity.nameFromAssembly(asm), WAIT_TICKS));

        String compared = events.awaitRecordWithFields(mark, "ship_mass_compared",
                "the assembly never compared the full hull pass against the running total, so nothing"
                        + " measures the incremental path at all", WAIT_TICKS, "ship", shipId);

        assertTrue("the full hull pass and the running total the engine kept while assembling this"
                        + " craft disagree. They price the same blocks from the same table, so a"
                        + " difference here means one of the two write paths is not seeing part of the"
                        + " ship - and every derived figure downstream inherits whichever it read: "
                        + compared,
                Reply.of("ship_mass_compared", compared).bool("agrees"));
    }

    /**
     * 25 iron deck blocks at the table's default for the IRON material, 5000 kg ({@code WeightEngine:417}).
     * Everything else on the fixture only adds.
     */
    private static final double IRON_DECK_KG = 25 * 5000.0;

    /**
     * The mass the physics record carries is Stellurgy's block table, not the physics engine's own
     * flat per-block default.
     *
     * <p>The witness the whole server suite could not provide for a long time: it stayed green across
     * the change that replaced how EVERY block's mass is decided, because nothing in it ever asked a
     * ship what it weighed. It lived on the client tier until 2026-09-29 on the premise that a ship
     * on a headless server never loads, which a test server holding its ships loaded has made false.</p>
     *
     * <p>The bound is one-sided on purpose: the decked fixture carries a 5x5 iron deck, which the
     * table denominates at 5000 kg a block, and the engine's default cannot reach that figure with the
     * whole fixture. So this separates the two models without pinning the table's exact numbers,
     * which are balance and may be tuned.</p>
     *
     * <p>red-witnessed: with {@code StellurgyBlockMass.of} ({@code StellurgyBlockMass#of} at {@code return WeightEngine.INSTANCE.getWeight(asItem);}, read by both the hull pass
     * and the per-block path) answering 1.0 for every block, the verdict fails — "recorded mass is
     * 35.0 kg, below the 125000.0 kg of iron deck" — 2026-09-29.</p>
     */
    @Test
    public void anAssembledShipWeighsWhatTheBlockTableSays() throws Exception {
        long mark = events.markInstrumented();
        String asm = RocketFixture.assembleAt(FixtureSite.openAir(0, BASE_X, BASE_Z), this::exec,
                "with-pilot-deck", 4, 12, "the decked craft that is weighed stands in this volume");
        requireArranged("with the physics mod an AFC-bearing build must become a ship, not a rocket: "
                + asm, Reply.of(asm).integer("rocketCount") == 0);
        String shipId = ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(this::exec, events,
                0, ShipIdentity.nameFromAssembly(asm), WAIT_TICKS));
        // The authoritative frame is written right after this record, on the same tick: the read
        // below is of the mass that settles the question, not of whatever the paste had accumulated.
        ArrangementFailure.arranged(() -> events.awaitRecordWithFields(mark, "ship_mass_measured",
                "the assembly's own measurement must be on the record before its mass is read",
                WAIT_TICKS, "ship", shipId, "path", "event"));

        String info = exec("stellurgytest vs ship-info 0 id " + shipId);
        double massKg = Reply.of("stellurgytest vs ship-info", info).number("massKg");
        assertTrue("this ship's recorded mass is " + massKg + " kg, below the " + IRON_DECK_KG
                + " kg of iron deck it carries. A mass that low is the physics engine's flat per-block"
                + " default, which means the block table Stellurgy owns is not the one deciding ship"
                + " mass: " + info, massKg >= IRON_DECK_KG);
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }
}
