package dev.stannismod.stellurgy.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;

/**
 * What a restart must give back — split by who owns it.
 *
 * <p>A subsystem network is deliberately not saved: it has no durable name, being "the blocks that
 * happen to be connected right now", so it is rebuilt from the world every time. That decision is
 * only sound if the rebuild actually returns the same network, and until now nothing checked it.
 * The settings are the opposite case: they live on BLOCKS precisely because a block has a name, and
 * so they are the things that must survive byte-for-byte.</p>
 *
 * <p>So this pins both halves against one restart: the ventilation graph comes back with the same
 * shape (nothing persisted it — chunk load re-registers the nodes), while the vent's zone priority
 * and the shield console's resistance bias come back with the same VALUES (their tiles persisted
 * them). A regression in either direction is invisible in a single-boot test.</p>
 */
public class SubsystemNetworkRestartTest {

    /** One chunk, so a single probe pulls every node of both networks back into memory. */
    private static final int Y = 64;
    private static final int Z = 2608;
    private static final int VENT = 2608;
    private static final int DUCT_A = VENT + 1;
    private static final int DUCT_B = VENT + 2;
    private static final int PLANT = VENT + 3;
    private static final int SHIELD_SOURCE = VENT + 5;
    private static final int SHIELD_SINK = VENT + 6;
    private static final int SHIELD_CONSOLE = VENT + 7;

    /** The coolant loop sits in the same chunk, past the shield run. */
    private static final int PIPE_A = VENT + 9;
    private static final int PIPE_B = VENT + 10;
    private static final int ACCUMULATOR = VENT + 11;

    private static final int ZONE_PRIORITY = 1;
    private static final String RESISTANCE_BIAS = "0.75";
    /** Not a round number, so a value that came back by coincidence would not look like a pass. */
    private static final long STORED_HEAT = 4531L;

    private Path workDir;
    private RealDedicatedServerHarness firstBoot;
    private RealDedicatedServerHarness secondBoot;

    @Before
    public void prepareWorkDir() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -D"
                        + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = Files.createTempDirectory("forge-server-subnet-restart-");
    }

    @After
    public void closeAll() throws Exception {
        if (firstBoot != null) firstBoot.close();
        if (secondBoot != null) secondBoot.close();
    }

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. CABLES — {@code TileVentilationDuct#onLoad} at {@code SubsystemNetworkRegistry.register(this);}
     * not re-registering a duct restored from the save: "the ventilation graph must come back with the
     * same cable count … \"cables\":0". SOURCES — the same in {@code TileLifeSupportPlant#onLoad} at
     * {@code SubsystemNetworkRegistry.register(this);}: "and the same source count: … \"sources\":0".
     * SINKS — the same in {@code TileVentilationPort#onLoad} at {@code SubsystemNetworkRegistry.register(this);}:
     * "and the same sink count: … \"sinks\":0". PRIORITY — {@code TileVentilationPort#readFromNBT} at {@code zonePriority = Math.max(PRIORITY_MIN, Math.min(PRIORITY_MAX, nbt.getInteger("zonePriority")));} not reading the priority back: "the vent's zone priority must survive the
     * restart … expected:&lt;1&gt; but was:&lt;0&gt;". (SINKS and PRIORITY were taken while the zone's
     * sink was the oxygen vent; both lines moved unchanged to the port on 2026-10-05.) BIAS — {@code TileEntityShieldConsole#readFromNBT} at {@code shieldEnergyResistanceBias = compound.hasKey("shieldEnergyResistanceBias")} not
     * reading the bias back: "the console's resistance bias must survive the restart … expected:&lt;0.75&gt;
     * but was:&lt;0.5&gt;". The first boot's premises are arrangements and are not witnessed. (CABLES,
     * SOURCES and SINKS were recorded against each {@code onLoad}'s declaration line; the quoted call
     * is the one their prose names, supplied when the records were converted to symbols on 2026-09-30.)</p>
     *
     * <p>Not asserted: that the ventilation network's membership comes back the same. The verdict
     * compares two builds by the same code, and the member set is the positions of the component's
     * role maps — so it can only differ across the restart if some node came back with a different
     * set of roles, which moves one of the three counts above. (A fault that drops membership outright
     * would drop it before the restart too and leave the comparison equal; that is not this test's
     * contract.)</p>
     */
    @Test
    public void theNetworkIsRebuiltFromTheWorldWhileItsSettingsAreRestoredFromTheirBlocks()
            throws Exception {
        // ─────── Boot 1: build both networks, set both settings ───────
        firstBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);

        place(firstBoot, "stellurgy:ventilationPort", VENT);
        place(firstBoot, "stellurgy:ventilationDuct", DUCT_A);
        place(firstBoot, "stellurgy:ventilationDuct", DUCT_B);
        place(firstBoot, "stellurgy:lifeSupportPlant", PLANT);
        place(firstBoot, "affs:shield_generator", SHIELD_SOURCE);
        place(firstBoot, "affs:field_generator", SHIELD_SINK);
        place(firstBoot, "affs:shield_console", SHIELD_CONSOLE);

        Reply priorityWrite = arrange(firstBoot,
                "stellurgytest vent priority 0 " + VENT + " " + Y + " " + Z + " " + ZONE_PRIORITY);
        assertEquals("priority write failed: " + priorityWrite,
                ZONE_PRIORITY, priorityWrite.integer("priority"));

        arrange(firstBoot, "stellurgytest shield console-bias 0 " + SHIELD_CONSOLE + " "
                + Y + " " + Z + " " + RESISTANCE_BIAS);

        arrange(firstBoot, "stellurgytest subnet solve lifesupport 0 2");
        Reply ventilationBefore = subnet(firstBoot, DUCT_A);
        int cablesBefore = ventilationBefore.integer("cables");
        int sourcesBefore = ventilationBefore.integer("sources");
        int sinksBefore = ventilationBefore.integer("sinks");
        assertEquals("premise: two ducts must be in the network before the restart: "
                + ventilationBefore, 2, cablesBefore);
        assertEquals("premise: the plant must be its source: " + ventilationBefore, 1, sourcesBefore);
        assertEquals("premise: the port must be its sink: " + ventilationBefore, 1, sinksBefore);

        firstBoot.close();
        firstBoot = null;

        // ─────── Boot 2: same world, nothing saved the network itself ───────
        secondBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);

        // Bring the chunk in FIRST. Nothing about the network is restored by loading a save: the
        // tiles re-register from onLoad and the graph is rebuilt from their adjacency, so with the
        // chunk still on disk there is legitimately no network to report. In play a walking player
        // does this; here it is explicit, because a probe that quietly loaded the chunk would be
        // changing the thing it measures.
        arrange(secondBoot, "stellurgytest chunk forceload 0 " + (VENT >> 4) + " " + (Z >> 4));
        arrange(secondBoot, "stellurgytest subnet solve lifesupport 0 2");
        Reply ventilationAfter = subnet(secondBoot, DUCT_A);
        assertEquals("the ventilation graph must come back with the same cable count — it is "
                + "rebuilt from the world, and the world did not change: " + ventilationAfter,
                cablesBefore, ventilationAfter.integer("cables"));
        assertEquals("and the same source count: " + ventilationAfter,
                sourcesBefore, ventilationAfter.integer("sources"));
        assertEquals("and the same sink count: " + ventilationAfter,
                sinksBefore, ventilationAfter.integer("sinks"));

        // The settings, by contrast, are only here because their own tiles wrote them to NBT.
        Reply priorityAfter = arrange(secondBoot, "stellurgytest vent priority 0 " + VENT + " " + Y + " " + Z);
        assertEquals("the port's zone priority must survive the restart — it is the port's own "
                        + "setting, not the network's: " + priorityAfter,
                ZONE_PRIORITY, priorityAfter.integer("priority"));

        Reply consoleAfter = ask(secondBoot, "stellurgytest shield console-info 0 " + SHIELD_CONSOLE
                + " " + Y + " " + Z);
        assertEquals("the console's resistance bias must survive the restart, and it is the only "
                        + "thing that re-seeds the rebuilt shield network: " + consoleAfter,
                Double.parseDouble(RESISTANCE_BIAS), consoleAfter.number("resistanceBias"), 1.0e-6);
    }

    /**
     * The third case, and the one that is neither of the other two: a coolant loop's ENERGY.
     *
     * <p>It is not a setting — nobody typed it — and it is not derivable from the blocks either, so
     * neither half of the test above covers it. It is written down per block for exactly the reason
     * the settings are: a loop has no durable name and is rebuilt from the world, while a block has
     * its position. This pins that decision, which is otherwise only an argument.</p>
     *
     * <p>Read per BLOCK first and per LOOP second, deliberately. The loop's figure alone could not
     * tell energy that came back from the blocks from energy that was never gone.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. OFF THE BLOCKS — {@code TileHeatLoopBlock#readFromNBT} at {@code storedHeat = nbt.getLong(NBT_STORED_HEAT);} not reading the stored heat back: "every heat unit must come back off the
     * blocks that were holding it … expected:&lt;4531&gt; but was:&lt;0&gt;". REBUILT —
     * {@code TileHeatLoopBlock#onLoad} at {@code SubsystemNetworkRegistry.register(this);} not
     * re-registering a block restored from the save: "the loop must be REBUILT with the same membership
     * — nothing persisted it: … \"members\":0". The first boot's premises are arrangements and are not
     * witnessed. (REBUILT was recorded against {@code onLoad}'s declaration line; the quoted call is the
     * one its prose names, supplied when the record was converted to symbols on 2026-09-30.)</p>
     *
     * <p>Not asserted: the loop's capacity, stored heat and temperature after the restart, because
     * the loop re-sums capacity and heat from its member blocks on every solve and derives its
     * temperature from those two, so none of them could differ while the per-block energy and the
     * membership verdicts hold.</p>
     */
    @Test
    public void aCoolantLoopsEnergyComesBackFromItsBlocks() throws Exception {
        // ─────── Boot 1: a loop, and some energy in it ───────
        firstBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);

        place(firstBoot, "stellurgy:heatPipe", PIPE_A);
        place(firstBoot, "stellurgy:heatPipe", PIPE_B);
        place(firstBoot, "stellurgy:heatAccumulator", ACCUMULATOR);

        Reply written = arrange(firstBoot,
                "stellurgytest heat set 0 " + PIPE_A + " " + Y + " " + Z + " " + STORED_HEAT);
        requireArranged("premise: the position must hold a loop block: " + written,
                written.bool("isLoopBlock"));
        assertEquals("premise: the block must accept the energy: " + written,
                STORED_HEAT, written.longInteger("heatStored"));

        // One tick, so the loop finds itself and spreads the energy over its members at one
        // temperature — which is the state a real ship would be saved in, not a lump in one pipe.
        arrange(firstBoot, "stellurgytest subnet solve heat 0 1");
        Reply loopBefore = subnetHeat(firstBoot, PIPE_A);
        long loopHeatBefore = loopBefore.longInteger("heatStored");
        assertEquals("premise: all three blocks must be one loop: " + loopBefore,
                3, loopBefore.integer("members"));
        assertEquals("premise: the loop must hold exactly what was put in it: " + loopBefore,
                STORED_HEAT, loopHeatBefore);

        firstBoot.close();
        firstBoot = null;

        // ─────── Boot 2: same world, and the loop is a fresh object ───────
        secondBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);

        arrange(secondBoot, "stellurgytest chunk forceload 0 " + (PIPE_A >> 4) + " " + (Z >> 4));

        // The blocks first: this is where the energy actually was, and the only place it could have
        // come from — nothing saved the loop.
        long fromBlocks = 0L;
        for (int x : new int[]{PIPE_A, PIPE_B, ACCUMULATOR}) {
            Reply block = arrange(secondBoot, "stellurgytest heat read 0 " + x + " " + Y + " " + Z);
            assertTrue("the loop block at " + x + " must be back: " + block, block.bool("isLoopBlock"));
            fromBlocks += block.longInteger("heatStored");
        }
        assertEquals("every heat unit must come back off the blocks that were holding it — this is "
                        + "the whole reason the energy lives on blocks and not in the network",
                STORED_HEAT, fromBlocks);

        arrange(secondBoot, "stellurgytest subnet solve heat 0 1");
        Reply loopAfter = subnetHeat(secondBoot, PIPE_A);
        assertEquals("the loop must be REBUILT with the same membership — nothing persisted it: "
                + loopAfter, 3, loopAfter.integer("members"));
    }

    private Reply subnetHeat(RealDedicatedServerHarness harness, int x) throws Exception {
        return ask(harness, "stellurgytest subnet info heat 0 " + x + " " + Y + " " + Z);
    }

    private void place(RealDedicatedServerHarness harness, String block, int x) throws Exception {
        Reply resp = arrange(harness, "stellurgytest place 0 " + x + " " + Y + " " + Z + " " + block);
        assertTrue(block + " place failed at " + x + ": " + resp, resp.bool("placed"));
    }

    private Reply subnet(RealDedicatedServerHarness harness, int x) throws Exception {
        return ask(harness, "stellurgytest subnet info lifesupport 0 " + x + " " + Y + " " + Z);
    }

    private static Reply ask(RealDedicatedServerHarness harness, String command) throws Exception {
        return Reply.of(command, String.join("\n", harness.client().execute(command)));
    }

    private static Reply arrange(RealDedicatedServerHarness harness, String command) throws Exception {
        return ask(harness, command).requireOk(command);
    }
}
