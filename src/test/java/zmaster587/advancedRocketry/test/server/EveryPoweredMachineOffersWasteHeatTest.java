package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

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

    private static final int CY = 100;
    private static final int CZ = 3600;

    /** A recipe machine — the branch of the hierarchy under {@code TileWasteHeatMachine}. */
    @Test
    public void anElectrolyserOffersItsWasteHeat() throws Exception {
        assertOffersWasteHeat(3800, "advancedrocketry:electrolyser");
    }

    @Test
    public void anArcFurnaceOffersItsWasteHeat() throws Exception {
        assertOffersWasteHeat(3810, "advancedrocketry:arcfurnace");
    }

    /** A plain power consumer — the other branch, under {@code TileWasteHeatPowerConsumer}. */
    @Test
    public void anObservatoryOffersItsWasteHeat() throws Exception {
        assertOffersWasteHeat(3820, "advancedrocketry:observatory");
    }

    @Test
    public void aRailgunOffersItsWasteHeat() throws Exception {
        assertOffersWasteHeat(3830, "advancedrocketry:railgun");
    }

    /**
     * The machine that was already the subject of the loop-warming scenario, asserted here beside the
     * others so that all three cases — both bases and the hand-written one — answer to one rule
     * rather than to one rule and one habit.
     */
    @Test
    public void theLifeSupportPlantOffersItsWasteHeatToo() throws Exception {
        assertOffersWasteHeat(3840, "advancedrocketry:lifeSupportPlant");
    }

    private void assertOffersWasteHeat(int cx, String block) throws Exception {
        String placed = exec("artest place 0 " + cx + " " + CY + " " + CZ + " " + block);
        assertTrue("premise: the machine must be placeable: " + placed, placed.contains("\"ok\":true"));

        String emitter = exec("artest heat emitter 0 " + cx + " " + CY + " " + CZ);
        assertTrue("premise: the probe must find a tile there: " + emitter,
                emitter.contains("\"ok\":true"));
        assertTrue(block + " must offer a coolant loop its waste heat, and every powered machine "
                        + "must, or a ship full of running machinery heats nothing: " + emitter,
                emitter.contains("\"present\":true"));
    }

    /** A block that is not a machine must NOT claim to make waste heat — the planted negative. */
    @Test
    public void aPlainBlockOffersNothing() throws Exception {
        String placed = exec("artest place 0 3850 " + CY + " " + CZ + " minecraft:stone");
        assertTrue("premise: the control block must be placeable: " + placed,
                placed.contains("\"ok\":true"));

        String emitter = exec("artest heat emitter 0 3850 " + CY + " " + CZ);
        assertTrue("a plain stone block has no tile and therefore nothing to offer: " + emitter,
                emitter.contains("\"present\":false"));
        assertEquals("and the probe must say so rather than erroring: " + emitter,
                true, emitter.contains("\"ok\":true"));
    }
}
