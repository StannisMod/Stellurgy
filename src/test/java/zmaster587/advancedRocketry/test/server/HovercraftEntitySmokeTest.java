package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.EntityState;
import zmaster587.advancedRocketry.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Hovercraft entity smoke (lifecycle only).
 *
 * <p>{@code EntityHoverCraft} is registered via
 * {@code EntityRegistry.registerModEntity(new ResourceLocation(modId, "ARHoverCraft"), ...)}
 * with the runtime registry name {@code advancedrocketry:ARHoverCraft}.
 * We cover the server-side
 * lifecycle: spawn &rarr; entity alive &rarr; tick &rarr; still alive (no NPE during the
 * physics update path).</p>
 *
 * <p>Real player-riding gameplay (mount, throttle, fuel burn, fan
 * orientation) requires a client harness with a real player — covered by the
 * deferred @Ignore client E2E tests, not here.</p>
 */
public class HovercraftEntitySmokeTest extends AbstractHeadlessServerTest {

    private static final String ENTITY_ID = "entityId";

    @Test
    public void hovercraftSpawnsAndTicksWithoutCrash() throws Exception {
        int px = 2300, py = FixtureSite.OPEN_AIR_Y, pz = 2300;

        // Solid floor so the hovercraft falls onto stone, not into a cave.
        client().execute("artest fill 0 " + (px - 1) + " " + (py - 1) + " " + (pz - 1)
                + " " + (px + 1) + " " + (py - 1) + " " + (pz + 1) + " minecraft:stone");

        String spawn = String.join("\n", client().execute(
                "artest entity spawn 0 " + px + ".5 " + py + " " + pz + ".5"
                        + " advancedrocketry:ARHoverCraft"));
        assertTrue("hovercraft spawn failed: " + spawn,
                Reply.of(spawn).ok() && Reply.of(spawn).bool("spawned"));

        // `entity spawn` is its own producer and refuses on its own: `integer` names the field and
        // throws when it is absent, which is what the has-check stood for.
        int entityId = Reply.of("artest entity spawn", spawn).integer(ENTITY_ID);

        // Verify entity registered and alive.
        EntityState info1 = entity(entityId);
        assertTrue("entity must be alive immediately after spawn: " + info1.raw(), info1.alive);
        // Asked of the `entityClass` FIELD: the old `contains` over the whole reply would also have
        // been satisfied by the class name turning up in any other field of it.
        assertTrue("entity class must be EntityHoverCraft: " + info1.raw(),
                info1.entityClass().contains("EntityHoverCraft"));
        assertFalse("entity must NOT be dead-flagged after spawn: " + info1.raw(), info1.dead());

        // The hovercraft uses ITickable-equivalent World.tick path, not a tile
        // entity tick — we exercise stability by querying state across server
        // ticks. We can't force entity.onUpdate() directly via /artest tile
        // force-tick, but the server's own tick loop runs the entity update on
        // each /artest invocation indirectly (each command runs on the server
        // thread between game ticks; subsequent calls observe the post-tick
        // state). Spam a series of state queries to give the server's update
        // loop room to fire.
        for (int i = 0; i < 10; i++) {
            EntityState poll = entity(entityId);
            assertTrue("entity must stay alive across poll " + i + ": " + poll.raw(), poll.alive);
            assertFalse("entity must not crash with isDead=true: " + poll.raw(), poll.dead());
        }

        // Confirm posY is within sane bounds (gravity / hover physics applied
        // without NaN / underflow). The reader refuses a gone entity, which is what the
        // "posY must be readable" check stood for.
        double finalY = entity(entityId).requireAlive("the hovercraft must still exist to be"
                + " measured").posY();
        assertTrue("hovercraft must not fall below world floor (got " + finalY + ")",
                finalY > 0 && finalY < 256);
    }

    /** What the server says about one entity in the overworld. */
    private EntityState entity(int entityId) throws Exception {
        return EntityState.byId(cmd -> String.join("\n", client().execute(cmd)), 0, entityId);
    }
}
