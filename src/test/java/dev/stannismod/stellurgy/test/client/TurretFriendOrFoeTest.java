package dev.stannismod.stellurgy.test.client;

import dev.stannismod.stellurgy.test.EvictionReports;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Whether a gun can tell a friend from a target.
 *
 * <h3>Why this is a client test</h3>
 * <p>The credential is CARRIED, not held about somebody: an entity is friendly for exactly as long
 * as it has the installation's code on it, and the only entity that can carry one is a player. A
 * dedicated-server test has no players, so the whole mechanic is unreachable there — which is
 * precisely why it stayed unbuilt while everything around it was pinned.</p>
 *
 * <h3>Both halves, or neither means anything</h3>
 * <p>A gun that never fires passes "does not shoot friendlies" trivially. So the refusal is read off
 * the gun's own fire decision — taken on target, charged, and answered "friendly" — and the same gun,
 * pointed at the same player, is then seen to fire once the code stops matching.</p>
 *
 * <p>Gated by {@code forge.test.client.enabled=true}; auto-skips on headless CI.</p>
 *
 * <p>NEW-GROUP: weapons as a real client sees and drives them -- beam, shot and aim replication, the
 * weapon GUIs, the linker, the hand repair. No existing client group holds the weapon cluster; this
 * class and the eight others on {@code AbstractClientE2ETest} that name this cluster are its members
 * until they are folded into one group class. A mechanics test, not an e2e: it arranges the
 * weapon's state by probe.</p>
 */
public class TurretFriendOrFoeTest extends AbstractClientE2ETest {

    /** What the logs this test reads have already announced about evictions. */
    private final EvictionReports evictions = new EvictionReports();

    /** The harness's single client always joins under this name. */
    private static final String PLAYER = "ForgeTestClient";

    /** Far enough that the gun is not firing into the player's own block, near enough to track. */
    private static final int GUN_OFFSET = 20;

    /** Controller + four barrels + two cooling jackets; the controller is not a part. */
    private static final int PARTS = 6;

    /**
     * red-witnessed: with {@code !targetIsFriendly()} taken out of {@code TileTurret.canFireNow}
     * ({@code TileTurret#canFireNow} at {@code return !targetIsFriendly()}), this fails at "the gun was PERMITTED to fire on a player carrying
     * its own access code" on a decision reading {@code permitted:true, friendly:true} (2026-10-04).
     */
    @Test
    public void aGunHoldsFireOnAPlayerCarryingItsCodeAndFiresOnOneWhoIsNot() throws Exception {
        Events server = new Events(this::server,
                GameTicks.serverAndClient(serverClient(), GameTicks.server(), bot()::waitWorldTicks), evictions);
        // Build the gun AROUND the player rather than teleporting the player to the gun. A tp into
        // a freshly cleared site drops him, and a gun tracking a falling target pins its elevation
        // arc and stops firing — which is indistinguishable from the refusal this test is about.
        Reply player = ask("stellurgytest player position-of " + PLAYER);
        int px = (int) Math.floor(player.number("playerPosX"));
        int py = (int) Math.floor(player.number("playerPosY"));
        int pz = (int) Math.floor(player.number("playerPosZ"));
        int gx = px + GUN_OFFSET;

        ask("stellurgytest chunk warmup 0 " + ((px - 16) >> 4) + " " + ((pz - 16) >> 4) + " "
                + ((gx + 16) >> 4) + " " + ((pz + 16) >> 4)).requireOk("warm the site's chunks");
        // Clear the whole corridor between the player and the gun, not just the gun's own footprint.
        // The muzzle sits `reach + 1.5` blocks along the aim — about five and a half blocks towards
        // the player — and the line-of-fire check refuses a shot into terrain.
        ask("stellurgytest fill 0 " + (px - 2) + " " + py + " " + (pz - 2) + " " + (gx + 4) + " "
                + (py + 8) + " " + (pz + 2) + " minecraft:air").requireOk("clear the corridor");
        ask("stellurgytest chunk forceload 0 " + (gx >> 4) + " " + (pz >> 4)).requireOk("hold the chunk");
        long built = server.markInstrumented();
        buildGun(gx, py, pz);
        Weapons.awaitAssembled(server, built, gx, py, pz, PARTS, "the gun never assembled");
        ask("stellurgytest turret charge 0 " + gx + " " + py + " " + pz).requireOk("charge the gun");
        ask("stellurgytest turret code 0 " + gx + " " + py + " " + pz + " ALPHA").requireOk("set the code");

        // The player carries the installation's own code, and is therefore a friend.
        server("clear " + PLAYER);
        server("give " + PLAYER + " affs:code_device 1 0 {affs_code:\"ALPHA\"}");
        long friend = server.mark();
        ask("stellurgytest turret target-player 0 " + gx + " " + py + " " + pz + " " + PLAYER)
                .requireOk("point the gun at the player");

        // The fire decision is asked only once the mount is on its target: the record of it taken
        // with the player carrying the code is where the gun answers.
        String held = server.awaitRecordWithFields(friend, "turret_fire_decided",
                "the gun never took a fire decision on the player — it never got onto him, so its"
                        + " silence would be about geometry rather than the credential",
                Weapons.ARRANGEMENT_TICKS, "pos", Weapons.at(gx, py, pz), "caller", "auto",
                "friendly", "true");
        assertEquals("the gun was PERMITTED to fire on a player carrying its own access code: " + held,
                "false", Events.text(held, "permitted"));
        assertTrue("the gun shot a player carrying its own access code: " + read(gx, py, pz),
                Weapons.firedSince(server, friend, gx, py, pz).isEmpty());

        // Same gun, same player, same target — only the credential changes.
        long foe = server.mark();
        server("clear " + PLAYER);
        server("give " + PLAYER + " affs:code_device 1 0 {affs_code:\"BRAVO\"}");
        ask("stellurgytest turret charge 0 " + gx + " " + py + " " + pz).requireOk("recharge the gun");
        Weapons.awaitFired(server, foe, gx, py, pz,
                "the gun would not fire on a player carrying somebody else's code either — the hold"
                        + " was not about the credential: " + read(gx, py, pz));
    }

    private void buildGun(int gx, int gy, int gz) throws Exception {
        place("stellurgy:turret", gx, gy, gz);
        for (int i = 1; i <= 4; i++) {
            place("stellurgy:gunBarrel", gx, gy + i, gz);
        }
        place("stellurgy:gunCooling", gx, gy, gz + 1);
        place("stellurgy:gunCooling", gx, gy, gz - 1);
    }

    private Reply read(int gx, int gy, int gz) throws Exception {
        return ask("stellurgytest turret read 0 " + gx + " " + gy + " " + gz);
    }

    private void place(String block, int x, int y, int z) throws Exception {
        Reply placed = ask("stellurgytest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + ": " + placed, placed.bool("placed"));
    }

    /** A vanilla command, whose reply is chat rather than JSON and is not read. */
    private String server(String command) throws Exception {
        return String.join("\n", serverClient().execute(command));
    }

    private Reply ask(String command) throws Exception {
        return Reply.of(command, server(command));
    }
}
