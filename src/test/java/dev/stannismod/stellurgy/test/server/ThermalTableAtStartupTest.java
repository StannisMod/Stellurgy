package dev.stannismod.stellurgy.test.server;

import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.fail;

/**
 * The thermal materials table a pack edits is read when the server STARTS, and a table the pack broke
 * stops the start instead of being replaced by the shipped one.
 *
 * <p>SEPARATE-BOOT: a two-boot sequence on one work directory, and what the server does at start with
 * a file written before it boots is the subject — a shared server has already started.</p>
 *
 * <p>The two boots are a matched pair: the same directory, the same world, and the only difference
 * between them is the table file. The first boot is the control — it must come up, and run on the
 * pack's own value — so a second boot that never becomes ready is told apart from a server that would
 * not have started anyway. That the refusal NAMES the file is pinned where the decision is made,
 * {@code unit/ThermalTableFileTest}; this class pins that the decision is taken at startup.</p>
 */
public class ThermalTableAtStartupTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * Above any terrain the fixed seed raises around spawn, in a chunk the probe's fill loads itself.
     */
    private static final int X = 0, Y = 200, Z = 0;

    /** A ceiling nobody ships for iron, so "the pack's row is in force" cannot be a coincidence. */
    private static final int PACK_IRON_CEILING = 1237;

    /**
     * <p>red-witnessed: with {@code Stellurgy#postInit} at {@code
     * dev.stannismod.stellurgy.subsystem.heat.ThermalMaterials.INSTANCE.all();} removed, so the
     * table is first read when something needs it: "a server whose thermal table the pack broke must
     * not start; it started on another table", 2026-10-05. The control leg is an arrangement and is
     * not witnessed.</p>
     */
    @Test
    public void aTableThePackBrokeStopsTheStart() throws Exception {
        Path root = folder.newFolder("server").toPath();
        Path table = root.resolve("config").resolve("advRocketry").resolve("thermalMaterials.json");
        Files.createDirectories(table.getParent());
        Files.write(table, ("{\"materials\":{\"iron\":{\"density\":7874,\"specificHeat\":449,"
                + "\"ceilingKelvin\":" + PACK_IRON_CEILING + "}}}").getBytes(StandardCharsets.UTF_8));

        RealDedicatedServerHarness control = RealDedicatedServerHarness.startWith(root, false);
        try {
            ask(control, "stellurgytest fill 0 " + X + " " + Y + " " + Z + " " + X + " " + Y + " " + Z
                    + " minecraft:iron_block").requireOk("place an iron block");
            Reply iron = ask(control, "stellurgytest heat material 0 " + X + " " + Y + " " + Z)
                    .requireOk("read the iron block's material");
            requireArranged("the block must resolve to the table's iron row: " + iron,
                    "iron".equals(iron.text("material")));
            requireArranged("the control boot must run on the pack's own iron, read from its file: "
                    + iron, iron.integer("ceilingKelvin") == PACK_IRON_CEILING);
        } finally {
            control.close();
        }

        Files.write(table, ("{\"materials\":{\"iron\":{\"density\":7874,\"specificHeat\":449,"
                + "\"ceilingKelvin\":" + PACK_IRON_CEILING + ",}").getBytes(StandardCharsets.UTF_8));

        RealDedicatedServerHarness broken;
        try {
            broken = RealDedicatedServerHarness.startWith(root, false);
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

    private static Reply ask(RealDedicatedServerHarness server, String command) throws Exception {
        return Reply.of(command, String.join("\n", server.client().execute(command)));
    }
}
