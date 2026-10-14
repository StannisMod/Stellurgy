package dev.stannismod.stellurgy.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.junit.AbstractMechanicsTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.github.stannismod.forge.testing.server.ServerRoot;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.client.GameDirSeed;
import dev.stannismod.stellurgy.test.contract.OwnBoot;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.fail;

/**
 * The thermal materials table a pack edits is read when the server STARTS, and a table the pack broke
 * stops the start instead of being replaced by the shipped one.
 *
 * <p>The two boots are a matched pair: the same directory, the same world, and the only difference
 * between them is the table file. The first boot is the control — it must come up, and run on the
 * pack's own value — so a second boot that never becomes ready is told apart from a server that would
 * not have started anyway. That the refusal NAMES the file is pinned where the decision is made,
 * {@code unit/ThermalTableFileTest}; this class pins that the decision is taken at startup.</p>
 */
@OwnBoot(reason = OwnBoot.Reason.STARTUP_FILE)
public class ThermalTableAtStartupTest extends AbstractMechanicsTest {

    /**
     * Above any terrain the fixed seed raises around spawn, in a chunk the probe's fill loads itself.
     */
    private static final int X = 0, Y = 200, Z = 0;

    /** A ceiling nobody ships for iron, so "the pack's row is in force" cannot be a coincidence. */
    private static final int PACK_IRON_CEILING = 1237;

    /** The pack's table: one iron row, with the ceiling above. */
    private static final String PACK_TABLE = "{\"materials\":{\"iron\":{\"density\":7874,\"specificHeat\":449,"
            + "\"ceilingKelvin\":" + PACK_IRON_CEILING + "}}}";

    private ServerRoot root;

    @Before
    public void prepareRoot() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -D"
                        + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        root = ServerRoot.fresh("forge-server-thermal-table-");
    }

    @After
    public void removeRoot() throws Exception {
        if (root != null) {
            root.delete();
        }
    }

    @Pins("MECH-HEAT-27d")
    @RedWitness(member = "Stellurgy#postInit",
            at = "dev.stannismod.stellurgy.subsystem.heat.ThermalMaterials.INSTANCE.all();", replacedBy = "",
            fails = "a server whose thermal table the pack broke must not start; it started on another table",
            on = "2026-10-05")
    @Test
    public void aTableThePackBrokeStopsTheStart() throws Exception {
        root.seed(new GameDirSeed().thermalTable(PACK_TABLE, ThermalTableAtStartupTest.class));

        RealDedicatedServerHarness control = RealDedicatedServerHarness.startWith(harnessBoot(), root, false);
        try {
            arrange(control, "stellurgytest fill 0 " + X + " " + Y + " " + Z + " " + X + " " + Y + " " + Z
                    + " minecraft:iron_block");
            Reply iron = read(control, "stellurgytest heat material 0 " + X + " " + Y + " " + Z)
                    .requireOk("read the iron block's material");
            requireArranged("the block must resolve to the table's iron row: " + iron,
                    "iron".equals(iron.text("material")));
            requireArranged("the control boot must run on the pack's own iron, read from its file: "
                    + iron, iron.integer("ceilingKelvin") == PACK_IRON_CEILING);
        } finally {
            control.close();
        }

        // A trailing comma and a missing brace: what a hand edit leaves, and not a table.
        root.editWhileDown(SavedWorldEdit.thermalTable(saved ->
                "{\"materials\":{\"iron\":{\"density\":7874,\"specificHeat\":449,"
                        + "\"ceilingKelvin\":" + PACK_IRON_CEILING + ",}"));

        RealDedicatedServerHarness broken;
        try {
            broken = RealDedicatedServerHarness.startWith(harnessBoot(), root, false);
        } catch (AssertionError notReady) {
            // The harness answers a boot that never became ready in two ways; only the process
            // ending is the start being refused. A boot that hung is a different finding.
            if (String.valueOf(notReady.getMessage()).startsWith("Server process exited")) {
                return;
            }
            throw notReady;
        }
        broken.close();
        fail("a server whose thermal table the pack broke must not start; it started on another table");
    }

    /** A verb that OBSERVES, sent in its role. */
    private static Reply read(RealDedicatedServerHarness server, String command) throws Exception {
        return Reply.of(command, String.join("\n", server.client().read(command)));
    }

    /** A step of the arrangement, sent in its role and refused unless it reported ok. */
    private static Reply arrange(RealDedicatedServerHarness server, String command) throws Exception {
        return Reply.of(command, String.join("\n", server.client().arrange(command))).requireOk(command);
    }
}
