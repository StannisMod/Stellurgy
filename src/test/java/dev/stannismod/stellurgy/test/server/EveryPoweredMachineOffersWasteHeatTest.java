package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.arrange;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.ask;

/**
 * A powered machine offers a coolant loop the heat its work leaves behind — and not only the one
 * machine an older scenario happened to use.
 *
 * <p><b>Why this exists when a test that heats a loop by running a machine already did.</b>
 * {@link HeatLoopTest#aMachineOnACoolantLoopWarmsIt} powers a life-support plant beside a pipe run
 * and asserts the pipes warm: no injected charge, a real machine, a real loop, a sound assertion. It
 * passed for the whole period in which {@code IHeatEmitter} had exactly ONE implementor in the mod —
 * and that implementor was the life-support plant. The scenario's SUBJECT was the single instance
 * that worked, so it could not have revealed that twenty-one other machines emitted nothing at all.
 *
 * <p>The lesson is not "the assertion was weak". It is that a scenario is only as good as the case
 * it is run ON, and picking the case that is easiest to build tends to pick the one already wired.
 * These run the machines it never reached.
 *
 * <p>They ask about WIRING rather than about thermodynamics — does this machine offer the capability
 * at all — which is a question that has to be asked in a world: the capability object is populated by
 * the mod's own load, so in a unit context it is null, {@code hasCapability} answers true by
 * accident, and the cast throws.
 */
public class EveryPoweredMachineOffersWasteHeatTest extends AbstractSharedServerTest {

    /**
     * A recipe machine — the branch of the hierarchy under {@code TileWasteHeatMachine}.
     *
     * <p>red-witnessed: with {@code TileWasteHeatMachine:41} no longer answering the heat
     * capability: "stellurgy:electrolyser must offer a coolant loop its waste heat … \"present\":false",
     * 2026-09-30. The tile premise is an arrangement and is not witnessed.</p>
     */
    @Test
    public void anElectrolyserOffersItsWasteHeat() throws Exception {
        assertOffersWasteHeat("stellurgy:electrolyser");
    }

    /**
     * red-witnessed: with {@code TileWasteHeatMachine:41} no longer answering the heat capability:
     * "stellurgy:arcfurnace must offer a coolant loop its waste heat … \"present\":false", 2026-09-30.
     * The tile premise is an arrangement and is not witnessed.
     */
    @Test
    public void anArcFurnaceOffersItsWasteHeat() throws Exception {
        assertOffersWasteHeat("stellurgy:arcfurnace");
    }

    /**
     * A plain power consumer — the other branch, under {@code TileWasteHeatPowerConsumer}.
     *
     * <p>red-witnessed: with {@code TileWasteHeatPowerConsumer:45} no longer answering the heat
     * capability: "stellurgy:observatory must offer a coolant loop its waste heat …
     * \"present\":false", 2026-09-30. The tile premise is an arrangement and is not witnessed.</p>
     */
    @Test
    public void anObservatoryOffersItsWasteHeat() throws Exception {
        assertOffersWasteHeat("stellurgy:observatory");
    }

    /**
     * red-witnessed: with {@code TileWasteHeatPowerConsumer:45} no longer answering the heat
     * capability: "stellurgy:railgun must offer a coolant loop its waste heat … \"present\":false",
     * 2026-09-30. The tile premise is an arrangement and is not witnessed.
     */
    @Test
    public void aRailgunOffersItsWasteHeat() throws Exception {
        assertOffersWasteHeat("stellurgy:railgun");
    }

    /**
     * The machine that was already the subject of the loop-warming scenario, asserted here beside the
     * others so that all three cases — both bases and the hand-written one — answer to one rule
     * rather than to one rule and one habit.
     *
     * <p>red-witnessed: with {@code TileLifeSupportPlant:164} no longer answering the heat
     * capability: "stellurgy:lifeSupportPlant must offer a coolant loop its waste heat …
     * \"present\":false", 2026-09-30. The tile premise is an arrangement and is not witnessed.</p>
     */
    @Test
    public void theLifeSupportPlantOffersItsWasteHeatToo() throws Exception {
        assertOffersWasteHeat("stellurgy:lifeSupportPlant");
    }

    /** One block, one above this scenario's own site, after the site is proved empty. */
    private String standAlone(String block) throws Exception {
        FixtureSite site = clearedSite(1, 2, block + " standing alone in open air");
        return site.dim + " " + site.x + " " + (site.y + 1) + " " + site.z;
    }

    private void assertOffersWasteHeat(String block) throws Exception {
        String at = standAlone(block);
        arrange("stellurgytest place " + at + " " + block);

        // The probe answers `present:false, tile:"none"` for an empty position, so "the probe found a
        // tile" is the `tile` field and not `ok` — `ok` is true for both.
        Reply emitter = arrange("stellurgytest heat emitter " + at);
        requireArranged("premise: the probe must find a tile there: " + emitter,
                !"none".equals(emitter.text("tile")));
        assertTrue(block + " must offer a coolant loop its waste heat, and every powered machine "
                        + "must, or a ship full of running machinery heats nothing: " + emitter,
                emitter.bool("present"));
    }

    /**
     * A block that is not a machine must NOT claim to make waste heat — the planted negative.
     *
     * <p>It has to HAVE a tile, or production is never asked: for an empty position the probe answers
     * {@code present:false} from its own no-tile branch without consulting the capability at all. A
     * chest is a tile entity that does no work, so its {@code false} is the capability's answer.</p>
     *
     * <p>red-witnessed: with {@code CapabilityHeatEmitter:35} handing a default emitter to a tile
     * that offers none: "a chest does no work and so has no waste heat to offer: … \"present\":true",
     * 2026-09-30. The tile premise is an arrangement and is not witnessed.</p>
     */
    @Test
    public void aTileThatIsNotAMachineOffersNothing() throws Exception {
        String at = standAlone("minecraft:chest");
        arrange("stellurgytest place " + at + " minecraft:chest");

        Reply emitter = arrange("stellurgytest heat emitter " + at);
        requireArranged("premise: the probe must find the chest's tile, or the capability was never"
                + " asked: " + emitter, !"none".equals(emitter.text("tile")));
        assertFalse("a chest does no work and so has no waste heat to offer: " + emitter,
                emitter.bool("present"));
    }
}
