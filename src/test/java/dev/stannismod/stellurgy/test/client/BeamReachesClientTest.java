package dev.stannismod.stellurgy.test.client;

import dev.stannismod.stellurgy.test.EvictionReports;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import static org.junit.Assert.assertTrue;

/**
 * Whether a burning beam is visible to the player standing next to the gun.
 *
 * <h3>Why this has to be a client test</h3>
 * <p>A held beam is not an entity, not a block and not a particle: it is a line the server resolves
 * every tick and forgets. Vanilla replicates none of that, so "you can see the gun burning" is
 * entirely a claim about a packet channel, and the only place that claim can be checked is a real
 * client. What is asserted is what the renderer draws FROM — the client's own tracker of burning
 * beams, as its {@code client_beam_drawn} record — because a renderer's output cannot be read from a
 * test. Whether it LOOKS like a laser stays a human's judgement.</p>
 *
 * <h3>Why the far half is evidence</h3>
 * <p>The control is the half that makes it worth running: a beam burning four kilometres away must
 * NOT arrive, or the filter that keeps every battery in the world off every connection is doing
 * nothing. "Nothing arrived" is only a reading once two things are established. The far gun really
 * burned — its own {@code turret_beam} edge says it lit, and a gun announces the tick it lights. And
 * that announcement had time to arrive: the far gun lit BEFORE the near one (the server log orders
 * the two edges), and every packet the server sends this player travels one ordered connection, so a
 * far packet sent at the far gun's lighting would have been drawn before the near gun's first
 * packet — which the client DID draw. The near half is therefore the far half's instrument check as
 * well as its own claim.</p>
 *
 * <p>Gated by {@code forge.test.client.enabled=true}; auto-skips on headless CI.</p>
 *
 * <p>NEW-GROUP: weapons as a real client sees and drives them -- beam, shot and aim replication, the
 * weapon GUIs, the linker, the hand repair. No existing client group holds the weapon cluster; this
 * class and the eight others on {@code AbstractClientE2ETest} that name this cluster are its members
 * until they are folded into one group class. A mechanics test, not an e2e: it arranges the
 * weapon's state by probe.</p>
 */
public class BeamReachesClientTest extends AbstractClientE2ETest {

    /** What the logs this test reads have already announced about evictions. */
    private final EvictionReports evictions = new EvictionReports();

    private static final int DIM = 0;
    private static final int Y = 84, Z = 300;
    /** Where the player stands, and where the gun they should be able to see is built. */
    private static final int NEAR_X = 300;
    /** Comfortably outside the default 256-block visibility radius. */
    private static final int FAR_X = NEAR_X + 4_000;
    /** Controller + three emitters + two cooling jackets; the controller is not a part. */
    private static final int PARTS = 5;
    /** How often both guns are fed while the wait runs, in ticks — arrangement, not subject. */
    private static final int FEED_EVERY_TICKS = 20;

    /**
     * red-witnessed: with the radius test in {@code ProximityBroadcast.sendNearSegment}
     * ({@code ProximityBroadcast#sendNearSegment} at {@code if (distanceSqToSegment(player.posX, player.posY, player.posZ, from, to) > radiusSq)}) disabled, this fails at "a beam burning four kilometres away
     * was replicated to this client anyway", on a {@code client_beam_drawn} for {@code 4300,84,300}
     * recorded before the near gun's (2026-09-29).
     */
    @Test
    public void aBeamBurningNearbyIsDrawnByTheClientAndOneFourKilometresAwayIsNot() throws Exception {
        Events server = new Events(this::exec, GameTicks.serverAndClient(serverClient(), GameTicks.server(), bot()::waitWorldTicks), evictions);
        Events client = ClientEvents.of(bot(), GameTicks.serverAndClient(serverClient(), GameTicks.server(), bot()::waitWorldTicks), evictions);

        // The player stands beside the near gun for the whole scenario, so which gun is in range
        // never changes while anything burns.
        serverClient().execute("tp @a " + (NEAR_X + 4) + ".5 " + (Y + 1) + " " + (Z + 0.5D));

        long built = server.markInstrumented();
        buildBeamGun(FAR_X);
        buildBeamGun(NEAR_X);
        Weapons.awaitAssembled(server, built, FAR_X, Y, Z, PARTS, "the far beam gun never assembled");
        Weapons.awaitAssembled(server, built, NEAR_X, Y, Z, PARTS, "the near beam gun never assembled");

        long clientMark = client.mark();
        long burning = server.mark();
        aimAlongTheWall(FAR_X);
        charge(FAR_X);
        String farLit = server.awaitMatching(burning, "turret_beam",
                reply -> !Events.recordsWhereAll(reply, "pos", Weapons.at(FAR_X, Y, Z), "lit", "true")
                        .isEmpty(),
                "at the far gun, lit", "the far gun never burned at all, so this run proves nothing"
                        + " about the filter", Weapons.SUBJECT_TICKS, () -> charge(FAR_X),
                FEED_EVERY_TICKS);

        // Now the one the player is standing beside. Both are kept fed: a gun with no supply burns
        // its buffer down and goes dark to save up, and the point here is the packet, not the duty
        // cycle.
        aimAlongTheWall(NEAR_X);
        String nearLit = server.awaitMatching(burning, "turret_beam",
                reply -> !Events.recordsWhereAll(reply, "pos", Weapons.at(NEAR_X, Y, Z), "lit", "true")
                        .isEmpty(),
                "at the near gun, lit", "the near gun never burned, so nothing below is about"
                        + " replication", Weapons.SUBJECT_TICKS, () -> {
                    charge(FAR_X);
                    charge(NEAR_X);
                }, FEED_EVERY_TICKS);
        long farSeq = (long) Events.number(
                Events.recordsWhereAll(farLit, "pos", Weapons.at(FAR_X, Y, Z), "lit", "true").get(0), "seq");
        long nearSeq = (long) Events.number(
                Events.recordsWhereAll(nearLit, "pos", Weapons.at(NEAR_X, Y, Z), "lit", "true").get(0), "seq");
        assertTrue("the far gun did not light before the near one (far seq " + farSeq + ", near seq "
                + nearSeq + "), so its announcement is not guaranteed to precede the near gun's on the"
                + " connection and the silence below would be about timing", farSeq < nearSeq);

        client.awaitRecordWithFields(clientMark, "client_beam_drawn",
                "a beam burning twelve blocks from the player never reached the client: a held beam is"
                        + " a server-side line, so a client that is not told about one cannot draw it"
                        + " and the weapon fires invisibly. gun=" + read(NEAR_X),
                Weapons.SUBJECT_TICKS, "pos", Weapons.at(NEAR_X, Y, Z));

        String drawn = client.since(clientMark, "client_beam_drawn");
        Events.assertInstrumentRan(drawn, "client_beam_events", "no far beam was drawn");
        assertTrue("a beam burning four kilometres away was replicated to this client anyway — the"
                + " visibility filter is not filtering, and every gun in the world would be drawn on"
                + " every connection: " + drawn,
                Events.recordsWhere(drawn, "pos", Weapons.at(FAR_X, Y, Z)).isEmpty());
    }

    // ---- building

    /** The reference beam gun: a controller with emitters on it and cooling around it. */
    private void buildBeamGun(int bx) throws Exception {
        ask("stellurgytest chunk warmup " + DIM + " " + ((bx - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((bx + 48) >> 4) + " " + ((Z + 16) >> 4)).requireOk("warm the gun's chunks");
        ask("stellurgytest fill " + DIM + " " + (bx - 4) + " " + (Y - 2) + " " + (Z - 4) + " "
                + (bx + 40) + " " + (Y + 12) + " " + (Z + 4) + " minecraft:air").requireOk("clear the site");
        for (int cx = ((bx - 16) >> 4); cx <= ((bx + 40) >> 4); cx++) {
            ask("stellurgytest chunk forceload " + DIM + " " + cx + " " + (Z >> 4)).requireOk("hold a chunk");
        }
        place("stellurgy:turret", bx, Y, Z);
        for (int i = 1; i <= 3; i++) {
            place("stellurgy:gunBeamEmitter", bx, Y + i, Z);
        }
        place("stellurgy:gunCooling", bx, Y, Z + 1);
        place("stellurgy:gunCooling", bx, Y, Z - 1);
    }

    /** Something to burn into, and an order to burn into it. */
    private void aimAlongTheWall(int bx) throws Exception {
        int wallX = bx + 20;
        ask("stellurgytest fill " + DIM + " " + wallX + " " + Y + " " + Z + " " + (wallX + 5) + " " + Y
                + " " + Z + " minecraft:iron_block").requireOk("build the wall");
        ask("stellurgytest turret target " + DIM + " " + bx + " " + Y + " " + Z + " " + (wallX + 0.5D)
                + " " + (Y + 0.5D) + " " + (Z + 0.5D)).requireOk("aim the gun at its wall");
    }

    // ---- reading

    private Reply read(int bx) throws Exception {
        return ask("stellurgytest turret read " + DIM + " " + bx + " " + Y + " " + Z);
    }

    private void charge(int bx) throws Exception {
        ask("stellurgytest turret charge " + DIM + " " + bx + " " + Y + " " + Z).requireOk("charge the gun");
    }

    private void place(String block, int x, int y, int z) throws Exception {
        Reply placed = ask("stellurgytest place " + DIM + " " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + ": " + placed, placed.bool("placed"));
    }

    private String exec(String command) throws Exception {
        return String.join("\n", serverClient().execute(command));
    }

    private Reply ask(String command) throws Exception {
        return Reply.of(command, exec(command));
    }
}
