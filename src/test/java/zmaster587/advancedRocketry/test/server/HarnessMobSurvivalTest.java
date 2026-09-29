package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.DeckCapture;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.GameTicks;

import org.junit.Test;


import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A living entity spawned in the harness world used to be removed within a few ticks, which blocked
 * any client e2e needing a live render subject (e.g. the remote-body model-roll gate).
 *
 * <p>Root cause (fixed): the test server runs with {@code spawn-animals} off, and vanilla
 * {@code WorldServer.updateEntityWithOptionalForce} {@code setDead()}s every {@code EntityAnimal}
 * each tick while {@code canSpawnAnimals()} is false — persistence does not exempt it, so a spawned
 * cow vanished on its first update tick. The {@code drop-living} probe now flips the server's
 * {@code canSpawnAnimals} flag on, and {@code canSpawnAnimals()} reads it live. This test pins that:
 * a persistent, no-AI cow spawned on a laid floor (so a fall is not a confound) must still be alive
 * after ~6 s of real server ticks.</p>
 */
public class HarnessMobSurvivalTest extends AbstractSharedServerTest {

    /** How much world the subject is given to survive, in ticks — the old "~6 s = 120 real ticks". */
    private static final int SURVIVAL_TICKS = 120;

    private static final String ENTITY_ID = "entityId";

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    @Test
    public void aPersistentNoAiMobSurvivesInTheHarnessWorld() throws Exception {
        final int x = 7930, y = FixtureSite.OPEN_AIR_Y, z = 7930;

        // Warm the chunk and lay ONE floor block so the subject is supported the moment it spawns —
        // a fall from an unsupported spawn would kill a 10-HP cow and masquerade as the bug.
        exec("artest chunk warmup 0 " + (x >> 4) + " " + (z >> 4) + " " + (x >> 4) + " " + (z >> 4));
        // Force-loaded, so the world keeps UPDATING the subject: the server tier has no player in
        // it, and a world with neither players nor persistent chunks stops updating its entities
        // after 300 idle ticks — which would let the cow survive a cull that never ran.
        exec("artest chunk forceload 0 " + (x >> 4) + " " + (z >> 4));
        exec("artest fill 0 " + x + " " + (y - 1) + " " + z + " " + x + " " + (y - 1) + " " + z
                + " minecraft:stone");

        String spawned = exec("artest vs drop-living 0 minecraft:cow "
                + (x + 0.5) + " " + y + " " + (z + 0.5));
        System.out.println("[mobsurv] spawn: " + spawned.replace('\n', ' '));
        assertTrue("subject must spawn: " + spawned, Reply.of(spawned).ok());
        Reply mReply = Reply.of(spawned);
        assertTrue("spawn must report an entity id: " + spawned, mReply.has(ENTITY_ID));
        int id = Integer.parseInt(mReply.text(ENTITY_ID));
        int ageBefore = Reply.of(exec("artest entity info 0 " + id)).integer(TICKS_EXISTED);

        // EXPERIMENT: the dose is SURVIVAL_TICKS server ticks, and the claim below is that the cow
        // outlived them. The defect culled it on its FIRST update, so any dose past a handful of
        // ticks separates the two builds; overshoot only lengthens the ordeal, which can turn a
        // green red and never the reverse.
        GameTicks.advance(client(), GameTicks.server(), SURVIVAL_TICKS);

        // deck-capture answers "entity not found" once the subject has been removed from the world.
        String after = exec("artest vs deck-capture 0 " + id);
        System.out.println("[mobsurv] after: " + after.replace('\n', ' '));

        // Asked of the reader: a `contains` over the whole reply would also answer true for a reply
        // that merely MENTIONS the phrase, and false for any other way of saying the mob is gone.
        assertFalse("a persistent, no-AI mob must survive >=100 ticks in the harness world "
                        + "(WorldServer culls every EntityAnimal while canSpawnAnimals() is false); "
                        + "after=" + after,
                DeckCapture.entityMissing(after));
        // AND IT MUST HAVE BEEN UPDATED. The cull runs inside the entity's update, so a world that
        // never updated the cow (an idle world stops updating entities once nobody is in it and
        // nothing is force-loaded) keeps it alive for the wrong reason — the pass above would then
        // be a statement about the world's idleness.
        int ageAfter = Reply.of(exec("artest entity info 0 " + id)).integer(TICKS_EXISTED);
        assertTrue("the surviving mob must have been UPDATED across the dose, or its survival says"
                        + " nothing about the cull: ticksExisted " + ageBefore + " -> " + ageAfter,
                ageAfter > ageBefore);
    }

    private static final String TICKS_EXISTED = "ticksExisted";
}
