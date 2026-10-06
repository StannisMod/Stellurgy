package dev.stannismod.stellurgy.test.client;

import dev.stannismod.stellurgy.test.EvictionReports;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import java.util.List;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Where a friend is spared: <b>before there is a target, not at the trigger.</b>
 *
 * <p>A gun already declines to fire on somebody carrying the installation's code. This is the
 * stronger statement one level up — an ally never becomes a CONTACT, so their name is never written
 * anywhere a gun could read it, and no race, stale order or second console can talk the battery into
 * shooting them. The two are deliberately both there: the sensor keeps allies out of the list, and
 * the gun checks again at the trigger.</p>
 *
 * <h3>Why this is a client test</h3>
 * <p>The credential is CARRIED, and only a player can carry one. A dedicated server has no players,
 * so the whole mechanic is unreachable there.</p>
 *
 * <h3>Equal windows, counted in SWEEPS</h3>
 * <p>The sensor decides who is a contact once per sweep, and records each sweep's list
 * ({@code sensor_swept}, with the contacts' names). So the friend is watched for exactly
 * {@link #SWEEPS} sweeps taken while he carried the code, and the control — the same player, the
 * same place, only the code changed — must appear within the same number of sweeps. A control
 * allowed longer than the subject proves acquisition EVENTUALLY, not within the window the friend
 * was watched for.</p>
 *
 * <p>Gated by {@code forge.test.client.enabled=true}; auto-skips on headless CI.</p>
 */
public class SensorFriendIsNeverAcquiredE2ETest extends AbstractClientE2ETest {

    /** What the logs this test reads have already announced about evictions. */
    private final EvictionReports evictions = new EvictionReports();

    /** The harness's single client always joins under this name. */
    private static final String PLAYER = "ForgeTestClient";

    /** Near enough to be held well by listening alone, far enough not to be inside the battery. */
    private static final int BATTERY_OFFSET = 20;

    /** Controller + four barrels + two cooling jackets; the controller is not a part. */
    private static final int PARTS = 6;

    /**
     * How many sweeps each leg is watched for. More than one, so a single sweep that happened to
     * miss the player for an unrelated reason cannot pass the friend leg alone; the control leg is
     * held to the same count, which is what makes the two windows equal.
     */
    private static final int SWEEPS = 3;

    /**
     * red-witnessed: with the friend exclusion in {@code TacticalScan} ({@code TacticalScan#trackOf} at {@code if (CodeUtils.entityHasMatchingCode(candidate, friendlyCode))})
     * disabled, this fails at "a player carrying the installation's code entered the target list" on a
     * sweep listing {@code names:"ForgeTestClient"} (2026-09-29).
     *
     * <p>red-witnessed: with {@code TileFireControlSensor#publish} at {@code state.setAcquiredTrack(contacts.get(0), world.getTotalWorldTime(), hold);} publishing the contact as the
     * network's named target ({@code setTargetEntity}) instead of as an acquisition, this fails at "it
     * fired, but not on an acquisition: {...acquired:false...}" (2026-09-30).</p>
     *
     * <p>"The battery fired at a player carrying its own code" is DOWNSTREAM of the friend-leg sweep
     * verdict and is not reached red by any inversion: the gun's only source of a target here is the
     * acquisition, so a round at the friend needs the friend in a sweep, which that verdict reds first.
     * Measured, 2026-09-30: with BOTH friend tests off — {@code TacticalScan.java:93} and
     * {@code TileTurret.targetIsFriendly} ({@code TileTurret.java:593}) — the run fails at "a player
     * carrying the installation's code entered the target list: {...names:ForgeTestClient...}", the
     * same line the sensor's exclusion alone already reds.</p>
     */
    @Test
    public void aPlayerCarryingTheCodeNeverBecomesAContactAndOneWhoIsNotDoes() throws Exception {
        Events server = new Events(this::server,
                GameTicks.serverAndClient(serverClient(), GameTicks.server(), bot()::waitWorldTicks), evictions);
        // Built around the player rather than the player moved to it: a tp into a cleared site drops
        // him, and a battery tracking a falling target is a different experiment.
        Reply player = ask("stellurgytest player position-of " + PLAYER);
        int px = (int) Math.floor(player.number("playerPosX"));
        int py = (int) Math.floor(player.number("playerPosY"));
        int pz = (int) Math.floor(player.number("playerPosZ"));
        int bx = px + BATTERY_OFFSET;
        int sx = bx + 1;

        server("gamerule doMobSpawning false");
        ask("stellurgytest chunk warmup 0 " + ((px - 16) >> 4) + " " + ((pz - 16) >> 4) + " "
                + ((bx + 16) >> 4) + " " + ((pz + 16) >> 4)).requireOk("warm the site's chunks");
        // The whole corridor, not just the battery's footprint: the muzzle sits five and a half
        // blocks along the aim and the line-of-fire check refuses a shot into terrain.
        ask("stellurgytest fill 0 " + (px - 2) + " " + py + " " + (pz - 2) + " " + (bx + 4) + " "
                + (py + 8) + " " + (pz + 2) + " minecraft:air").requireOk("clear the corridor");
        ask("stellurgytest chunk forceload 0 " + (bx >> 4) + " " + (pz >> 4)).requireOk("hold the chunk");
        long built = server.markInstrumented();
        buildBattery(bx, py, pz);
        Weapons.awaitAssembled(server, built, bx, py, pz, PARTS,
                "the gun never assembled, so nothing below would mean anything");
        ask("stellurgytest turret charge 0 " + bx + " " + py + " " + pz).requireOk("charge the gun");
        ask("stellurgytest sensor charge 0 " + sx + " " + py + " " + pz).requireOk("charge the sensor");
        ask("stellurgytest turret code 0 " + bx + " " + py + " " + pz + " ALPHA").requireOk("code the gun");
        ask("stellurgytest sensor code 0 " + sx + " " + py + " " + pz + " ALPHA").requireOk("code the sensor");

        // The player carries the installation's own code, and is therefore not a target at all.
        server("clear " + PLAYER);
        server("give " + PLAYER + " affs:code_device 1 0 {affs_code:\"ALPHA\"}");
        long friend = server.mark();
        List<String> friendSweeps = sweeps(server, friend, sx, py, pz);
        for (String sweep : friendSweeps) {
            assertTrue("a player carrying the installation's code entered the target list: " + sweep,
                    !names(sweep).contains(PLAYER));
        }

        // Same player, same place, same battery. Only the code changes.
        server("clear " + PLAYER);
        server("give " + PLAYER + " affs:code_device 1 0 {affs_code:\"BRAVO\"}");
        // Marked AFTER the new code is in his hand, exactly as the friend leg was: every sweep of
        // this window is one taken while he carried somebody else's code.
        long foe = server.mark();
        List<String> foeSweeps = sweeps(server, foe, sx, py, pz);
        boolean acquired = false;
        for (String sweep : foeSweeps) {
            acquired |= names(sweep).contains(PLAYER);
        }
        assertTrue("the sensor did not acquire the player carrying somebody else's code within the "
                + SWEEPS + " sweeps the friend was watched for — then the exclusion above was not"
                + " about the credential: " + foeSweeps, acquired);

        String fired = Weapons.awaitFired(server, foe, bx, py, pz,
                "the battery never fired on a contact its own sensor had acquired");
        assertEquals("it fired, but not on an acquisition: " + fired, "true",
                Events.text(fired, "acquired"));

        // Only now can the friend leg's silence be read: a gun with nobody to shoot never asks the
        // fire question, so its fire recorder is first proved live by the round just fired.
        java.util.List<String> atFriend = Weapons.firedSince(server, friend, bx, py, pz);
        atFriend.removeIf(round -> Events.number(round, "seq") >= foe);
        assertTrue("the battery fired at a player carrying its own code: " + atFriend,
                atFriend.isEmpty());
    }

    // ---- scenario construction

    private void buildBattery(int bx, int by, int bz) throws Exception {
        place("stellurgy:turret", bx, by, bz);
        for (int i = 1; i <= 4; i++) {
            place("stellurgy:gunBarrel", bx, by + i, bz);
        }
        place("stellurgy:gunCooling", bx, by, bz + 1);
        place("stellurgy:gunCooling", bx, by, bz - 1);
        place("stellurgy:fireControlSensor", bx + 1, by, bz);
    }

    /**
     * The first {@link #SWEEPS} sweeps of the sensor at {@code (x,y,z)} recorded since {@code mark}
     * — the window a leg is judged over.
     */
    private List<String> sweeps(Events server, long mark, int x, int y, int z) throws Exception {
        String swept = server.awaitMatching(mark, "sensor_swept",
                reply -> Events.recordsWhere(reply, "pos", Weapons.at(x, y, z)).size() >= SWEEPS,
                "at the sensor, " + SWEEPS + " times", "the sensor did not sweep " + SWEEPS
                        + " times, so the list it keeps was never re-decided in this window",
                Weapons.SUBJECT_TICKS);
        return Events.recordsWhere(swept, "pos", Weapons.at(x, y, z)).subList(0, SWEEPS);
    }

    /** The names a sweep put in its contact list. */
    private static List<String> names(String sweep) {
        return java.util.Arrays.asList(String.valueOf(Events.text(sweep, "names")).split(","));
    }

    private Reply read(int bx, int by, int bz) throws Exception {
        return ask("stellurgytest turret read 0 " + bx + " " + by + " " + bz);
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
