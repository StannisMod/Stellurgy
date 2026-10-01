package dev.stannismod.stellurgy.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Whether a fired round reaches the person it is fired near — its flight AND its end.
 *
 * <p>A shot is a server-side record: no entity, no chunk, nothing vanilla replicates on its own. So
 * "you can see the gun firing" is entirely a claim about a packet, and it is checkable on the real
 * client — the client's own tracker records every round it is handed to draw
 * ({@code client_shot_drawn}) and every end it is told of ({@code client_shot_ended}), on the client
 * thread, from the packets themselves. The end matters as much as the spawn: a round whose end never
 * reaches the client is drawn flying on through the wall it stopped in, with no flash where it hit.</p>
 *
 * <h3>Why the far round is evidence</h3>
 * <p>The control is the half that makes it worth running: a round fired far away must NOT arrive, or
 * the filter that keeps a battery off every connection in the world is not doing anything. The far
 * round is fired FIRST and the near one second, down the one ordered connection this player has: a
 * spawn packet for the far round would therefore have been drawn before the near round's — which the
 * client did draw — so the near round is the far round's instrument check as well as its own
 * claim.</p>
 *
 * <p>Gated by {@code forge.test.client.enabled=true}; auto-skips on headless CI.</p>
 */
public class ShotReachesClientE2ETest extends AbstractClientE2ETest {

    /** Where the player stands for both halves. */
    private static final double PX = 8.5D, PY = 79.0D, PZ = 8.5D;

    /** Comfortably inside the default 256-block visibility radius. */
    private static final double NEAR = 40.0D;

    /** Comfortably outside it, and travelling further away. */
    private static final double FAR = 4_000.0D;

    /**
     * red-witnessed: with the radius test in {@code ProximityBroadcast.sendNearSegment}
     * ({@code ProximityBroadcast#sendNearSegment} at {@code if (distanceSqToSegment(player.posX, player.posY, player.posZ, from, to) > radiusSq)}) disabled, this fails at "a round fired four kilometres away
     * was replicated to this client anyway", on a {@code client_shot_drawn} at {@code x=4008.5}
     * (2026-09-29).
     */
    @Test
    public void aRoundFiredNearbyIsDrawnByTheClientAndOneFiredFarAwayIsNot() throws Exception {
        Events client = ClientEvents.of(bot());
        serverClient().execute("tp @a " + PX + " " + PY + " " + PZ);

        long mark = client.mark();
        // The control first. Without it the test would pass just as well against a replication
        // layer that told everybody about everything.
        long distant = fire((PX + FAR) + " " + PY + " " + (PZ + FAR) + " 4 0 4 2000 200");
        // Fired 40 blocks away, across the player's view. The launch is production's own entry
        // point — the same call a turret makes.
        long near = fire((PX + NEAR) + " " + PY + " " + PZ + " 0 0 4 2000 200");

        client.awaitRecordWithFields(mark, "client_shot_drawn",
                "a round fired 40 blocks from the player never reached the client: a shot is a server"
                        + " record, so a client that is not told about one cannot draw it and the"
                        + " turret fires invisibly", Weapons.SUBJECT_TICKS, "shot", String.valueOf(near));

        String drawn = client.since(mark, "client_shot_drawn");
        Events.assertInstrumentRan(drawn, "client_shot_events", "the far round was never drawn");
        assertTrue("a round fired four kilometres away was replicated to this client anyway — the"
                + " visibility filter is not filtering: " + drawn,
                Events.recordsWhere(drawn, "shot", String.valueOf(distant)).isEmpty());
    }

    /**
     * A round that stops in a wall in front of the player is told to the client as ENDED, for the
     * reason it ended — so the drawing stops at the wall and the flash is drawn where it hit.
     *
     * <p>red-witnessed: with {@code ShotReplication.announceEnd} ({@code ShotReplication#announceEnd} at {@code if (world == null || world.isRemote || point == null)})
     * made to return before sending, this fails at "a round that stopped in a wall beside the player
     * was never told to the client as over" (2026-09-29).</p>
     *
     * <p>red-witnessed: with both of {@code ShotSubstrate.step}'s come-to-rest returns
     * ({@code ShotSubstrate#step} at {@code return ShotEndReason.STRUCTURE_IMPACT;} and {@code :320}) answering EXPIRED, this fails at "the client was
     * told the round ended, but not that it hit structure: {...client_shot_ended...reason:EXPIRED...}"
     * (2026-09-30). That inversion is in the server's decision, so it shows the verdict reads the reason
     * the client was handed; a replication that dropped or rewrote the reason on the way was not
     * separately inverted.</p>
     */
    @Test
    public void aRoundThatStopsNearbyIsToldToTheClientAsEnded() throws Exception {
        Events client = ClientEvents.of(bot());
        serverClient().execute("tp @a " + PX + " " + PY + " " + PZ);
        int wallX = (int) Math.floor(PX + NEAR);
        int wallY = (int) Math.floor(PY), wallZ = (int) Math.floor(PZ) + 6;
        ask("stellurgytest fill 0 " + (wallX - 6) + " " + (wallY - 1) + " " + (wallZ - 1) + " "
                + (wallX + 2) + " " + (wallY + 1) + " " + (wallZ + 1) + " minecraft:air").requireOk("clear the lane");
        ask("stellurgytest fill 0 " + wallX + " " + (wallY - 1) + " " + (wallZ - 1) + " " + (wallX + 2)
                + " " + (wallY + 1) + " " + (wallZ + 1) + " minecraft:stone").requireOk("build the wall");

        // A block and a half of this wall's own price, read off the wall: enough to bore in and run
        // out inside a three-block wall. Not less — a round that cannot buy a single stage LODGES in
        // the face and lives out its lifetime there, ending EXPIRED (measured on this scenario's
        // first run, 2026-09-29), which is a different ending from the one this is about.
        Reply wall = ask("stellurgytest damage stage 0 " + wallX + " " + wallY + " " + wallZ);
        int budget = (int) Math.round(wall.integer("stageCost") * Math.max(1, wall.integer("maxStage")) * 1.5D);
        assertTrue("the wall has no price, so no budget here means anything: " + wall, budget > 0);

        long mark = client.mark();
        // Four blocks short of the wall, flying at it at a block a tick.
        long id = fire((wallX - 4.0D) + " " + (wallY + 0.5D) + " " + (wallZ + 0.5D) + " 1 0 0 " + budget
                + " 200");

        client.awaitRecordWithFields(mark, "client_shot_drawn",
                "the round fired beside the player was never drawn, so its end below would be about"
                        + " nothing", Weapons.SUBJECT_TICKS, "shot", String.valueOf(id));
        String ended = client.awaitRecordWithFields(mark, "client_shot_ended",
                "a round that stopped in a wall beside the player was never told to the client as"
                        + " over: it goes on being drawn in flight through the wall it stopped in",
                Weapons.SUBJECT_TICKS, "shot", String.valueOf(id));
        assertEquals("the client was told the round ended, but not that it hit structure: " + ended,
                "STRUCTURE_IMPACT", Events.text(ended, "reason"));
    }

    /** One round through production's own entry point; the id, or a failed arrangement. */
    private long fire(String spec) throws Exception {
        Reply fired = ask("stellurgytest shot fire 0 " + spec).requireOk("fire a round");
        long id = fired.longInteger("id");
        assertTrue("the launch was refused, so nothing else here means anything: " + fired, id >= 0);
        return id;
    }

    private Reply ask(String command) throws Exception {
        return Reply.of(command, String.join("\n", serverClient().execute(command)));
    }
}
