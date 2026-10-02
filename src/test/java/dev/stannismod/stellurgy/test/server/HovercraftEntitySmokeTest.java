package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.EntityState;
import dev.stannismod.stellurgy.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;

import dev.stannismod.stellurgy.test.FixtureSite;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Hovercraft entity smoke (lifecycle only).
 *
 * <p>{@code EntityHoverCraft} is registered via
 * {@code EntityRegistry.registerModEntity(new ResourceLocation(modId, "StellurgyHoverCraft"), ...)}
 * with the runtime registry name {@code stellurgy:StellurgyHoverCraft}.
 * We cover the server-side
 * lifecycle: spawn &rarr; entity alive &rarr; tick &rarr; still alive (no NPE during the
 * physics update path).</p>
 *
 * <p>Real player-riding gameplay (mount, throttle, fuel burn, fan
 * orientation) requires a client harness with a real player — covered by the
 * deferred @Ignore client E2E tests, not here.</p>
 */
public class HovercraftEntitySmokeTest extends AbstractHeadlessServerTest {

    /** The world's build ceiling, in blocks — vanilla's own, cited so the range check reads as the
     *  world bound it is rather than as a tuned number. */
    private static final double WORLD_CEILING_Y = 256;

    private static final String ENTITY_ID = "entityId";

    @Test
    public void hovercraftSpawnsAndTicksWithoutCrash() throws Exception {
        int px = 2300, py = FixtureSite.OPEN_AIR_Y, pz = 2300;

        // Solid floor so the hovercraft falls onto stone, not into a cave.
        client().execute("stellurgytest fill 0 " + (px - 1) + " " + (py - 1) + " " + (pz - 1)
                + " " + (px + 1) + " " + (py - 1) + " " + (pz + 1) + " minecraft:stone");

        String spawn = String.join("\n", client().execute(
                "stellurgytest entity spawn 0 " + px + ".5 " + py + " " + pz + ".5"
                        + " stellurgy:StellurgyHoverCraft"));
        assertTrue("hovercraft spawn failed: " + spawn,
                Reply.of(spawn).ok() && Reply.of(spawn).bool("spawned"));

        // `entity spawn` is its own producer and refuses on its own: `integer` names the field and
        // throws when it is absent, which is what the has-check stood for.
        int entityId = Reply.of("stellurgytest entity spawn", spawn).integer(ENTITY_ID);

        EntityState info1 = entity(entityId);
        // Asked of the `entityClass` FIELD: the old `contains` over the whole reply would also have
        // been satisfied by the class name turning up in any other field of it.
        assertTrue("entity class must be EntityHoverCraft: " + info1.raw(),
                info1.entityClass().contains("EntityHoverCraft"));
    }

    /** What the server says about one entity in the overworld. */
    private EntityState entity(int entityId) throws Exception {
        return EntityState.byId(cmd -> String.join("\n", client().execute(cmd)), 0, entityId);
    }
}
