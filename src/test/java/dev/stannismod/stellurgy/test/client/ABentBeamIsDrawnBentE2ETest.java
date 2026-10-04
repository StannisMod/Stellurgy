package dev.stannismod.stellurgy.test.client;

import dev.stannismod.stellurgy.test.EvictionReports;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A beam a mirror turned is DRAWN turned, and not straight through the mirror that turned it.
 *
 * <p>The server resolves a bent beam as a path with a corner in it. Everything about that is
 * invisible from the client's side unless the corner crosses the wire: a beam sent as two ends is
 * still one beam, still drawn, still counted — and drawn as a laser passing clean through a plate
 * that is, in fact, reflecting it. So "a beam is drawn" cannot see this; what is read is the client's
 * own record of what it drew, {@code client_beam_drawn}, whose {@code points} and {@code bent} come
 * off the path the renderer holds.</p>
 *
 * <p>The control leg is the ordinary beam. A gun burning into an iron wall must be drawn with NO
 * corner: without that half, a bug that reported every beam as bent would pass this file.</p>
 */
public class ABentBeamIsDrawnBentE2ETest extends AbstractClientE2ETest {

    /** What the logs this test reads have already announced about evictions. */
    private final EvictionReports evictions = new EvictionReports();

    private static final int DIM = 0;
    private static final int Y = 84, Z = 420;
    private static final int GUN_X = 700;
    private static final int TARGET_X = GUN_X + 20;
    /** Controller + three emitters + two cooling jackets; the controller is not a part. */
    private static final int PARTS = 5;
    /** How often the gun is fed while a wait runs, in ticks — arrangement, not subject. */
    private static final int FEED_EVERY_TICKS = 20;

    /**
     * red-witnessed: with {@code PacketBeamState.of} ({@code PacketBeamState#of} at {@code for (Vec3d point : path)}) made to
     * carry only the path's two ends, this fails at "with a mirror in the beam's way the client is
     * still drawing a straight line" while the server reads {@code beamLit:true} (2026-09-29).
     */
    @Test
    public void aBeamTurnedByAMirrorReachesTheClientWithItsCornerInIt() throws Exception {
        Events server = new Events(this::exec, bot()::waitTicks, evictions);
        Events client = ClientEvents.of(bot(), evictions);
        serverClient().execute("tp @a " + (GUN_X + 4) + ".5 " + (Y + 1) + " " + (Z + 0.5D));

        long built = server.markInstrumented();
        buildBeamGun();
        Weapons.awaitAssembled(server, built, GUN_X, Y, Z, PARTS, "the beam gun never assembled");

        // Leg one, the control: plain iron. A beam that meets it is a straight line to its end.
        ask("stellurgytest fill " + DIM + " " + TARGET_X + " " + Y + " " + Z + " " + (TARGET_X + 5) + " "
                + Y + " " + Z + " minecraft:iron_block").requireOk("build the iron wall");
        long straight = client.mark();
        aimAtTarget();
        String drawn = client.awaitMatching(straight, "client_beam_drawn",
                reply -> !Events.recordsWhere(reply, "pos", Weapons.at(GUN_X, Y, Z)).isEmpty(),
                "for this gun", "the beam never reached the client at all, so nothing here measured"
                        + " how it is drawn: " + read(), Weapons.SUBJECT_TICKS, this::charge,
                FEED_EVERY_TICKS);
        String first = Events.recordsWhere(drawn, "pos", Weapons.at(GUN_X, Y, Z)).get(0);
        assertEquals("a beam burning into a plain iron wall was drawn with a corner in it. Nothing "
                + "turned it, so either the server invented a bend or the client is calling every "
                + "beam bent — and this file's real assertion would then pass for that reason "
                + "rather than for the mirror: " + first, "false", Events.text(first, "bent"));

        // Leg two: swap the iron the beam is standing on for a mirror. Same gun, same aim, same
        // distance — the ONE thing that changes is what the beam meets.
        long mirrored = client.mark();
        ask("stellurgytest fill " + DIM + " " + TARGET_X + " " + Y + " " + Z + " " + (TARGET_X + 5) + " "
                + Y + " " + Z + " minecraft:air").requireOk("take the iron away");
        place("stellurgy:mirrorPlatingGold", TARGET_X, Y, Z);
        place("minecraft:iron_block", TARGET_X + 1, Y, Z);

        client.awaitMatching(mirrored, "client_beam_drawn",
                reply -> !Events.recordsWhereAll(reply, "pos", Weapons.at(GUN_X, Y, Z), "bent", "true")
                        .isEmpty(),
                "for this gun, bent", "with a mirror in the beam's way the client is still drawing a"
                        + " straight line: the corner never crossed the wire, so a player watching"
                        + " this sees a laser going clean through a plate that is reflecting it. "
                        + read(), Weapons.SUBJECT_TICKS, this::charge, FEED_EVERY_TICKS);
    }

    // ---- building

    private void buildBeamGun() throws Exception {
        ask("stellurgytest chunk warmup " + DIM + " " + ((GUN_X - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((GUN_X + 48) >> 4) + " " + ((Z + 16) >> 4)).requireOk("warm the site's chunks");
        ask("stellurgytest fill " + DIM + " " + (GUN_X - 8) + " " + (Y - 2) + " " + (Z - 4) + " "
                + (GUN_X + 40) + " " + (Y + 12) + " " + (Z + 4) + " minecraft:air").requireOk("clear the site");
        for (int cx = ((GUN_X - 16) >> 4); cx <= ((GUN_X + 40) >> 4); cx++) {
            ask("stellurgytest chunk forceload " + DIM + " " + cx + " " + (Z >> 4)).requireOk("hold a chunk");
        }
        place("stellurgy:turret", GUN_X, Y, Z);
        for (int i = 1; i <= 3; i++) {
            place("stellurgy:gunBeamEmitter", GUN_X, Y + i, Z);
        }
        place("stellurgy:gunCooling", GUN_X, Y, Z + 1);
        place("stellurgy:gunCooling", GUN_X, Y, Z - 1);
    }

    private void aimAtTarget() throws Exception {
        ask("stellurgytest turret target " + DIM + " " + GUN_X + " " + Y + " " + Z + " "
                + (TARGET_X + 0.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D)).requireOk("aim the gun");
    }

    /**
     * Feed the gun, taking whatever the probe answers. Not refused on a missing gun: square-on to the
     * mirror the beam comes back down its own line and wears the gun away (the subject of
     * {@code AMirrorSendsTheBeamBackE2ETest}), so a gun gone by the end of a long wait is this
     * arrangement working — and the wait's own expiry, not the feed, is what should say the corner
     * never arrived.
     */
    private void charge() throws Exception {
        exec("stellurgytest turret charge " + DIM + " " + GUN_X + " " + Y + " " + Z);
    }

    // ---- reading

    private Reply read() throws Exception {
        return ask("stellurgytest turret read " + DIM + " " + GUN_X + " " + Y + " " + Z);
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
