package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.PlanetAir;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Poisoned air, in the open: a world whose air carries a poison past its limit is SAID to be
 * poisonous, and a living thing breathing it is hurt by a poison dose — while the same thing, in the
 * same place, breathing the world's clean air for the same time, is not.
 *
 * <p>The poison is carbon monoxide put into the overworld's own air through the planet's gas exchange,
 * so the arrangement supplies gas and never the reading. The subject is an iron golem held in the open
 * air: it breathes, it does not heal, and a hundred health outlasts any honest exposure here.</p>
 *
 * <p>NEW-GROUP: poisoning, a dose a living thing carries out of poisoned air. No existing group holds
 * it — the detector group is about redstone, the life-support groups about sealed rooms — and it
 * happens to any living thing in a planet's open air.</p>
 *
 * <p>Does NOT see: a player (server-tier players have no connection, and atmosphere effects skip
 * them), a suit keeping poison out, the dose clearing once the air is clean, or a sealed room's air.</p>
 */
public class PoisonedAirTest extends AbstractSharedServerTest {

    private static final int OVERWORLD = 0;

    /** Carbon monoxide at ten times its limit: harm from the third second, by the dose law. */
    private static final long MONOXIDE = 1_000L * 1_000L;

    /** EXPERIMENT: how long the golem breathes each air — the dose is this time. Ten seconds. */
    private static final int EXPOSURE_TICKS = 200;

    /**
     * red-witnessed: with {@code Poisoning#tick} at {@code if (toxic && damage > 0.0F)} made unreachable
     * (the dose kept, the harm never dealt): "health 100.0 -> 100.0" — taken before 2026-10-06, when the
     * line had no {@code toxic &&}, which {@code enableToxicity}'s default leaves true; with {@code AtmosphereAssertions#holdsAt}
     * at {@code return around != null && around.isToxic();} reading the zone's air only: "air with carbon
     * monoxide past its limit is said to be poisonous, outdoors too". Run apart: the statement is
     * asserted first and would have hidden the harm.
     */
    @Test
    public void aLivingThingBreathingPoisonedAirIsHurtByTheDoseAndNotInCleanAir() throws Exception {
        PlanetAir.Probe probe = this::exec;
        FixtureSite site = clearedSite(1, 4, "the golem is held in this air");
        int x = site.x, y = site.y + 1, z = site.z;
        Reply.of(exec("stellurgytest chunk forceload " + OVERWORLD + " " + (x >> 4) + " " + (z >> 4)))
                .requireOk("keep the golem's chunk ticking");

        String spawn = "stellurgytest entity spawn " + OVERWORLD + " " + x + " " + y + " " + z
                + " minecraft:villager_golem";
        int golem = Reply.of(spawn, exec(spawn)).requireOk(spawn).integer("entityId");
        Reply.of(exec("stellurgytest entity set-no-gravity " + OVERWORLD + " " + golem + " true"))
                .requireOk("hold the golem in the open air");

        PlanetAir before = PlanetAir.snapshot(probe, OVERWORLD);
        try {
            // CONTROL: the world's own air, for the same time.
            assertFalse("CONTROL: the world's own air is not said to be poisonous",
                    statements(x, y, z).contains("TOXIC"));
            Reply start = info(golem);
            GameTicks.advance(client(), GameTicks.world(OVERWORLD), EXPOSURE_TICKS);
            Reply clean = info(golem);
            assertTrue("CONTROL: the golem was updated while it breathed (ticksExisted "
                            + start.integer("ticksExisted") + " -> " + clean.integer("ticksExisted") + ")",
                    clean.integer("ticksExisted") - start.integer("ticksExisted") >= EXPOSURE_TICKS);
            assertEquals("CONTROL: clean air does not hurt it", start.number("health"),
                    clean.number("health"), 0.0D);

            Reply.of(exec("stellurgytest planet add-gas " + OVERWORLD + " carbonmonoxide " + MONOXIDE))
                    .requireOk("poison the world's air");
            assertTrue("air with carbon monoxide past its limit is said to be poisonous, outdoors too",
                    statements(x, y, z).contains("TOXIC"));

            GameTicks.advance(client(), GameTicks.world(OVERWORLD), EXPOSURE_TICKS);
            Reply poisoned = info(golem);
            assertTrue("the same golem, the same time, in poisoned air is hurt: health "
                            + clean.number("health") + " -> " + poisoned.number("health"),
                    poisoned.number("health") < clean.number("health"));
            assertEquals("and what hurt it last was the poison", "Poison", poisoned.text("lastDamageType"));
        } finally {
            before.restore(probe);
            exec("stellurgytest chunk release " + OVERWORLD + " " + (x >> 4) + " " + (z >> 4));
        }
    }

    private Reply info(int golem) throws Exception {
        String command = "stellurgytest entity info " + OVERWORLD + " " + golem;
        Reply reply = Reply.of(command, exec(command));
        assertTrue("the golem must be alive to be read: " + reply, reply.bool("isAlive"));
        return reply;
    }

    private java.util.List<String> statements(int x, int y, int z) throws Exception {
        String command = "stellurgytest atmosphere get " + OVERWORLD + " " + x + " " + y + " " + z;
        return Arrays.asList(Reply.of(command, exec(command)).textArray("statements"));
    }
}
