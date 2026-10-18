package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.contract.RedWitness;

import dev.stannismod.stellurgy.test.contract.Pins;

import org.junit.Test;

import dev.stannismod.stellurgy.test.Disposal;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShieldTile;
import dev.stannismod.stellurgy.test.ShipReadiness;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the blocks of a hull decide while the hull has NO PHYSICS OBJECT — nobody is near enough for
 * it to be simulated, while its chunks are loaded and its tiles tick.
 *
 * <p>NEW-GROUP: a hull that is named but not simulated. The physics substrate builds a ship's physics
 * object only while a player stands within its load distance; everywhere else the hull is a registry
 * record, a set of loaded chunks and a crowd of ticking tiles. Every decision those tiles make about
 * WHICH ship they belong to must come from the record, and every decision about where the ship IS
 * must wait for the physics object. No existing group stages this state: the ship scenarios on this
 * tier run under the test server's default, which holds every ship loaded.</p>
 *
 * <p>The state is reached the way a real game reaches it — the hold is released and the substrate's
 * own loading controller unloads the physics object of a ship no player is near — and it is MEASURED
 * before any verdict is read: the registry still names the block's ship, and the substrate reports no
 * physics object for it.</p>
 *
 * <p>What this group does NOT see: a client. A hull nobody is near is by definition one no client is
 * rendering, so nothing here can be observed from that side.</p>
 */
public class UnsimulatedHullGroupTest extends AbstractSharedServerTest {

    private static final int DIM = 0;

    /**
     * Ceiling on the assembly's two links (the registry record, then the emitter's first resolution
     * in the shipyard). The spawn is drained on the next world tick ({@code VSBridge#assembleTier2Ship}
     * says so and treats anything longer as a failure); this is a deadline far past that, not an
     * estimate of it.
     */
    private static final int ASSEMBLY_LINK_TICKS = 200;

    /**
     * Ceiling on the unload's links: the substrate's loading controller queues the unload of a ship
     * no player is near on its next tick, and drains it the same tick; the emitter re-resolves on its
     * next tick or when its chunk is next read. A deadline, not an estimate.
     */
    private static final int UNLOAD_LINK_TICKS = 200;

    /** This class's reader of the server's ordered event log, stepped on the hull's own world. */
    private final Events events = new Events(c -> read(c).raw(),
            ticks -> GameTicks.advance(client(), GameTicks.world(DIM), ticks), evictionReports());

    /**
     * A shield emitter aboard a hull nobody is near still belongs to THAT hull: it is in the hull's
     * shield domain, and it is framed on the hull — a frame that reports not-ready because the hull
     * has no pose to map through, so the emitter projects nothing.
     *
     * <p>The emitter rides in the craft as built (the fixture's {@code with-shield-emitter} variant),
     * so its subspace position is the one the assembly gave it, read off its own frame record. Before
     * the unload the same emitter, read the same way, is in the hull's domain and framed READY: that
     * is the positive half of both verdicts — the instruments can say "this hull" and "ready", and
     * what changes across the unload is only that the hull lost its physics object.</p>
     *
     * <p>Three waits are links of the arrangement: the hull's spawn, the substrate's unload of it, and
     * the emitter's frame record after the unload. That last record is written by any tile instance
     * on its first resolution, and the read before it reloads the chunk, so it appears whatever the
     * frame is; its fields are the verdicts.</p>
     */
    @RedWitness(verdict = "READY",
            member = "ShipFieldFrame#isReady",
            at = "return VSIntegration.toWorldFrameFor(world, shipId, 0.0D, 0.0D, 0.0D) != null;",
            replacedBy = "return true;",
            fails = "an emitter aboard a hull with no physics object reports its frame ready",
            on = "2026-10-07")
    @RedWitness(verdict = "DOMAIN while simulated",
            member = "ShieldDomains#forBlock",
            at = "String shipId = VSIntegration.shipIdOfBlock(world, pos);",
            replacedBy = "String shipId = null;",
            fails = "while the hull is simulated, its emitter must be in the hull's shield domain",
            on = "2026-10-07")
    @RedWitness(verdict = "the aboard-and-ready link",
            member = "FieldFrames#forBlock",
            at = "String shipId = VSIntegration.shipIdOfBlock(world, pos);",
            replacedBy = "String shipId = null;",
            fails = "the emitter never resolved its frame onto hull",
            on = "2026-10-07")
    @RedWitness(verdict = "FRAME",
            member = "FieldFrames#forBlock",
            at = "String shipId = VSIntegration.shipIdOfBlock(world, pos);",
            replacedBy = "String shipId = VSIntegration.shipIdOfBlock(world, pos); if (shipId != null"
                + " && VSIntegration.toWorldFrameFor(world, shipId, 0.0D, 0.0D, 0.0D) == null) { shipId = null; }",
            fails = "an emitter aboard a hull nobody is near was not framed on its own hull",
            on = "2026-10-15")
    @RedWitness(verdict = "DOMAIN after the unload",
            member = "ShieldDomains#forBlock",
            at = "String shipId = VSIntegration.shipIdOfBlock(world, pos);",
            replacedBy = "String shipId = VSIntegration.shipIdOfBlock(world, pos); if (shipId != null"
                + " && VSIntegration.toWorldFrameFor(world, shipId, 0.0D, 0.0D, 0.0D) == null) { shipId = null; }",
            fails = "an emitter aboard a hull nobody is near left the hull's shield domain",
            on = "2026-10-15")
    @Pins("PORT-16")
    @Test
    public void anEmitterAboardAHullNobodyIsNearIsStillThatHullsShield() throws Exception {
        // The rocket fixture lays a 6x6 pad at the site, its structure tower one block outside the
        // pad's -X edge rising six above it, and its builder (with an input plug above it) one block
        // outside the -Z edge: a halo of 1 and a height of 7 contain all of it.
        FixtureSite site = clearedSite(1, 7, "the craft whose emitter is the subject");
        long mark = events.markInstrumented();
        Reply fixture = arrange("stellurgytest fixture rocket " + DIM + " " + site.x + " " + site.y + " "
                + site.z + " with-shield-emitter").requireOk("lay the craft with an emitter in it");
        int[] builder = fixture.blockPos("builderPos");
        Reply assembled = act("stellurgytest rocket assemble " + DIM + " " + builder[0] + " "
                + builder[1] + " " + builder[2]).requireOk("assemble the craft");
        java.util.List<String> rockets = RocketFixture.rocketsBornAt(events, mark, builder);
        requireArranged("the build did not become one ship (rockets " + rockets
                        + ", flight computers " + assembled.integer("afcCount") + "): " + assembled,
                rockets.isEmpty() && assembled.integer("afcCount") == 1);
        String durable = assembled.text("shipId");
        String spawned = dev.stannismod.stellurgy.test.ArrangementFailure.arranged(() ->
                events.awaitRecordWithField(mark, "ship_spawned", "stellurgyShip", durable,
                        "the assembly never registered a hull named " + durable, ASSEMBLY_LINK_TICKS));
        String hull = Events.text(spawned, "vsShip");
        try {
            observeTheEmitterAcrossTheUnload(mark, hull);
        } finally {
            Disposal.ship(c -> arrange(c).raw(), events, DIM, hull);
        }
    }

    /** The scenario after the assembly: {@code mark} is the one taken before the build. */
    private void observeTheEmitterAcrossTheUnload(long mark, String hull) throws Exception {

        // The emitter's subspace position, off its own frame record: the first resolution that puts
        // it on THIS hull, with the hull simulated (the test server holds every ship loaded).
        String aboard = events.awaitRecordWithFields(mark, "field_frame_resolved",
                "the emitter never resolved its frame onto hull " + hull + " while it was simulated",
                ASSEMBLY_LINK_TICKS, "shipId", hull, "shipFramed", "true", "ready", "true");
        String[] at = Events.text(aboard, "pos").split(",");
        int ex = Integer.parseInt(at[0]);
        int ey = Integer.parseInt(at[1]);
        int ez = Integer.parseInt(at[2]);
        ShieldTile simulated = ShieldTile.at(c -> read(c).raw(), DIM, ex, ey, ez)
                .require(ShieldTile.EMITTER, "the block the frame record named");
        assertEquals("while the hull is simulated, its emitter must be in the hull's shield domain — or"
                + " the verdict after the unload compares against nothing: " + simulated,
                "ship:" + hull, simulated.domainId());

        // Let the substrate do what it does to a hull nobody is near: drop its physics object.
        long unloadMark = events.markInstrumented();
        ShipReadiness.letShipsUnload(c -> arrange(c).raw(), "the subject is a hull with no physics object");
        try {
            observeTheUnsimulatedEmitter(unloadMark, hull, ex, ey, ez);
        } finally {
            ShipReadiness.holdShipsLoaded(c -> arrange(c).raw(), "this scenario's opt-out ends with it");
        }
    }

    /** The verdicts on the emitter once its hull has no physics object; {@code unloadMark} precedes it. */
    private void observeTheUnsimulatedEmitter(long unloadMark, String hull, int ex, int ey, int ez)
            throws Exception {
        dev.stannismod.stellurgy.test.ArrangementFailure.arranged(() -> events.awaitRecordWithField(unloadMark,
                "ship_unloaded", "vsShip", hull,
                "the substrate never dropped the physics object of hull " + hull + " with nobody near it",
                UNLOAD_LINK_TICKS));
        // A read of the emitter: if the substrate's unload let its chunk go, this loads it again (and
        // the tile resolves its frame on load); if not, the tile resolves on its next tick.
        ShieldTile.at(c -> read(c).raw(), DIM, ex, ey, ez).require(ShieldTile.EMITTER, "the emitter after the unload");
        String unsimulated = dev.stannismod.stellurgy.test.ArrangementFailure.arranged(() ->
                events.awaitRecordWithField(unloadMark, "field_frame_resolved", "pos",
                        ex + "," + ey + "," + ez, "the emitter never resolved its frame after hull " + hull
                                + " lost its physics object", UNLOAD_LINK_TICKS));

        // THE STATE, measured before any verdict: the record still claims the emitter's block, and
        // the substrate has no physics object for the hull.
        Reply owner = read("stellurgytest vs managed-by " + DIM + " " + ex + " " + ey + " " + ez)
                .requireOk("ask which ship's record claims the emitter");
        requireArranged("the registry no longer names hull " + hull + " for the emitter's block, so"
                + " this is a remnant and not an unsimulated hull: " + owner,
                hull.equals(owner.text("shipId")));
        Reply physics = read("stellurgytest vs phys-diag " + DIM + " " + hull + " " + ex + " " + ey + " "
                + ez);
        requireArranged("hull " + hull + " still has a physics object, so it is simulated and the"
                + " verdicts below would be about a hull somebody is near: " + physics,
                !physics.bool("shipFound"));

        // FRAME: the emitter is framed on its own hull, not on the world.
        assertTrue("an emitter aboard a hull nobody is near was not framed on its own hull — given the"
                        + " WORLD frame, its shell stands at its shipyard address as though that were a"
                        + " place in the world: " + unsimulated,
                Boolean.parseBoolean(Events.text(unsimulated, "shipFramed"))
                        && hull.equals(Events.text(unsimulated, "shipId")));
        assertFalse("an emitter aboard a hull with no physics object reports its frame ready, so it"
                        + " would project a shell from a pose that does not exist: " + unsimulated,
                Boolean.parseBoolean(Events.text(unsimulated, "ready")));

        // DOMAIN: the emitter is still the hull's shield, not the dimension's.
        ShieldTile after = ShieldTile.at(c -> read(c).raw(), DIM, ex, ey, ez)
                .require(ShieldTile.EMITTER, "the emitter, read for its domain");
        assertEquals("an emitter aboard a hull nobody is near left the hull's shield domain: " + after,
                "ship:" + hull, after.domainId());
    }
}
