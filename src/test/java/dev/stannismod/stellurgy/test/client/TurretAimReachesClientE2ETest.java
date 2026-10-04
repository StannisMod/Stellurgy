package dev.stannismod.stellurgy.test.client;

import dev.stannismod.stellurgy.test.EvictionReports;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;
import dev.stannismod.stellurgy.weapon.TurretMechanism;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Whether a player can see which way a turret is pointing.
 *
 * <h3>Why this is a client test and not a unit one</h3>
 * <p>A block cannot be turned — it sits in a grid cell at one of a handful of fixed orientations — so
 * a turret's bearing exists only as numbers on the server and as a drawing on the client. The whole
 * question is therefore whether those numbers arrive, and that is answerable only on a real client.
 * What is asserted is the state the renderer draws FROM — the client tile's own mount, as its
 * {@code turret_aim} record on the CLIENT log — because a renderer's output cannot be read from a
 * test; what is NOT asserted is that the barrel looks right, which stays a human's judgement.</p>
 *
 * <h3>The command travels, not the pose</h3>
 * <p>The client runs the same traverse the server does, from the command it was sent. So the test
 * waits for the client's own mount to report itself ON its command — the arrival, not a sample of
 * the travel — and then compares the command the client holds with the one the server gave. A
 * static target is commanded once and sent once, so the two are the same number exactly.</p>
 *
 * <p>Gated by {@code forge.test.client.enabled=true}; auto-skips on headless CI.</p>
 */
public class TurretAimReachesClientE2ETest extends AbstractClientE2ETest {

    /** What the logs this test reads have already announced about evictions. */
    private final EvictionReports evictions = new EvictionReports();

    private static final int X = 120, Y = 79, Z = 120;

    /**
     * red-witnessed: with {@code TileTurret.getUpdateTag} ({@code TileTurret#getUpdateTag} at {@code NBTTagCompound mount = new NBTTagCompound();}) no longer
     * writing the mount, this fails at "the client's turret never turned onto its command: the server
     * commanded a bearing -90.0 and the client is still where it started (0.0)" (2026-09-29).
     *
     * <p>red-witnessed: with {@code TurretMechanism.isOnTarget} ({@code TurretMechanism#isOnTarget} at {@code return dYaw <= AIM_TOLERANCE_DEGREES && dPitch <= AIM_TOLERANCE_DEGREES;})
     * answering true for any commanded mount, this fails at "the client reports itself on target yet
     * points elsewhere than its command: {...onTarget:true...yaw:-4.0...commandedYaw:-90.0...remote:true}"
     * (2026-09-30).</p>
     */
    @Test
    public void theBearingTheServerCommandsArrivesAtTheClient() throws Exception {
        Events server = new Events(this::exec, bot()::waitTicks, evictions);
        Events client = ClientEvents.of(bot(), evictions);
        ask("stellurgytest chunk warmup 0 " + ((X - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((X + 16) >> 4) + " " + ((Z + 16) >> 4)).requireOk("warm the site's chunks");
        ask("stellurgytest fill 0 " + (X - 3) + " " + (Y - 1) + " " + (Z - 3) + " " + (X + 3) + " "
                + (Y + 6) + " " + (Z + 3) + " minecraft:air").requireOk("clear the site");
        // Stand next to it, so the client is tracking this chunk and its tile.
        serverClient().execute("tp @a " + (X + 4) + ".5 " + Y + " " + (Z + 0.5D));

        long placed = client.mark();
        long built = server.markInstrumented();
        place("stellurgy:turret", X, Y, Z);
        for (int i = 1; i <= 4; i++) {
            place("stellurgy:gunBarrel", X, Y + i, Z);
        }
        Weapons.awaitAssembled(server, built, X, Y, Z, 4, "the gun never assembled");
        // The client's own copy of the tile ticks its mount; its first record is where it starts.
        String start = client.awaitRecordWithFields(placed, "turret_aim",
                "the client never ticked a turret tile here, so it has nothing to draw",
                Weapons.ARRANGEMENT_TICKS, "pos", Weapons.at(X, Y, Z));
        double startYaw = Events.number(start, "yaw");

        // Point it hard to one side: a bearing the mount has to travel to, not one it is already at.
        long aimed = client.mark();
        long ordered = server.mark();
        ask("stellurgytest turret target 0 " + X + " " + Y + " " + Z + " " + (X + 40.5D) + " "
                + (Y + 0.5D) + " " + (Z + 0.5D)).requireOk("aim the gun");

        String serverArrived = Weapons.awaitOnTarget(server, ordered, X, Y, Z,
                "the server's own mount never reached the bearing it was given, so there is nothing"
                        + " for the client to have been told");
        double commanded = Events.number(serverArrived, "commandedYaw");
        // "Has to travel" is production's own line: within the aim tolerance the mount already
        // reports itself on target without moving.
        requireArranged("the command is not a bearing the mount has to travel to (start " + startYaw
                + ", commanded " + commanded + ", tolerance " + TurretMechanism.AIM_TOLERANCE_DEGREES
                + "), so arriving at it would prove nothing",
                Math.abs(wrap(commanded - startYaw)) > TurretMechanism.AIM_TOLERANCE_DEGREES);

        String clientArrived = Weapons.awaitOnTarget(client, aimed, X, Y, Z,
                "the client's turret never turned onto its command: the server commanded a bearing "
                        + commanded + " and the client is still where it started (" + startYaw + ")."
                        + " A gun whose barrel does not move is a gun a player cannot read");
        assertEquals("the client turned, but onto a command that is not the one the server gave: "
                + clientArrived + " | server: " + serverArrived,
                commanded, Events.number(clientArrived, "commandedYaw"), 1.0E-9D);
        assertEquals("the client reports itself on target yet points elsewhere than its command: "
                + clientArrived, commanded, Events.number(clientArrived, "yaw"),
                TurretMechanism.AIM_TOLERANCE_DEGREES);
    }

    private static double wrap(double degrees) {
        double wrapped = degrees % 360.0D;
        return wrapped <= -180.0D ? wrapped + 360.0D : (wrapped > 180.0D ? wrapped - 360.0D : wrapped);
    }

    private void place(String block, int x, int y, int z) throws Exception {
        Reply placed = ask("stellurgytest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + ": " + placed, placed.bool("placed"));
    }

    private String exec(String command) throws Exception {
        return String.join("\n", serverClient().execute(command));
    }

    private Reply ask(String command) throws Exception {
        return Reply.of(command, exec(command));
    }
}
