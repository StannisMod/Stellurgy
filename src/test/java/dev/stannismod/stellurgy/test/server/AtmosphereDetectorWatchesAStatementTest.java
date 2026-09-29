package dev.stannismod.stellurgy.test.server;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;

import static dev.stannismod.stellurgy.test.StellurgyTestConstants.ppm;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.exec;

/**
 * The atmosphere detector watches a STATEMENT about the air, not a name.
 *
 * <p>It used to compare the air against one of fourteen named atmospheres and emit redstone when they
 * were the same object, which made a player's wiring a function of the mod's vocabulary: "is this
 * Superheated with no oxygen" could be wired and "is this poisonous" could not, because somebody had
 * written a class for the first and not the second.</p>
 *
 * <p>Two scenarios, and the second is the one that could not exist before. The first shows the
 * detector TRACKING its statement as the air changes — a detector that simply latched on would pass a
 * one-shot check. The second wires a condition no named atmosphere expressed at all.</p>
 */
public class AtmosphereDetectorWatchesAStatementTest extends AbstractSharedServerTest {

    private static final Pattern POWERED = Pattern.compile("\"powered\":(true|false)");

    private static final int CY = 64;
    private static final int CZ = 2600;
    private static final int CX_TRACKS = 3600;
    private static final int CX_POISON = 3800;

    /**
     * The DOSE of vent ticks a room is given to finish becoming a zone.
     *
     * <p>Not a budget: a force-ticked tile does not move the world clock, so these ticks are the
     * only thing driving the fill here and the same dose does the same work on any box.</p>
     */
    private static final int SEAL_TICKS = 200;

    /** The air inside the room, one cell above the vent. */
    private static int airY() {
        return CY + 1;
    }

    @Test
    public void theDetectorFollowsItsStatementAsTheAirChanges() throws Exception {
        buildRoom(CX_TRACKS);
        placeDetector(CX_TRACKS);
        sealRoom(CX_TRACKS);
        setMode(CX_TRACKS, "NOT_BREATHABLE");

        // Ordinary air: the statement is false, so nothing is emitted. This is the control — a
        // detector stuck on would satisfy the interesting half below without watching anything.
        setAir(CX_TRACKS, 790_000, 210_000, 0);
        assertPowered(CX_TRACKS, false, "breathable air must not satisfy \"not breathable\"");

        // Breathe most of it away. Now the statement holds, and the detector must say so.
        setAir(CX_TRACKS, 790_000, 50_000, 160_000);
        assertPowered(CX_TRACKS, true, "air nobody can breathe must satisfy it");

        // And it must come back DOWN: a latch would pass everything above and still be broken.
        setAir(CX_TRACKS, 790_000, 210_000, 0);
        assertPowered(CX_TRACKS, false, "and refilling the room must switch it off again");
    }

    @Test
    public void theDetectorCanWatchForSomethingNoNamedAtmosphereEverSaid() throws Exception {
        buildRoom(CX_POISON);
        placeDetector(CX_POISON);
        sealRoom(CX_POISON);
        setMode(CX_POISON, "TOXIC");

        // Perfectly breathable air — every named atmosphere in the old list would have called this
        // room fine, because none of them had anything to say about poison.
        setAir(CX_POISON, 790_000, 210_000, 0);
        assertPowered(CX_POISON, false, "clean air must not read as poisonous");

        // Now put carbon monoxide in it, well past its own exposure limit, and change nothing else.
        // The room is still breathable by the oxygen band; it is simply also poisonous.
        setAir(CX_POISON, 790_000, 210_000, 0, "carbonmonoxide=" + ppm(1_000));
        assertPowered(CX_POISON, true, "a room can be breathable and poisonous at once, and the"
                + " detector must be able to wire the second");
    }

    // ─── the rig ───────────────────────────────────────────────────────

    private void buildRoom(int cx) throws Exception {
        int by = CY;
        // Fresh territory: the seal is a flood fill over real blocks, and an unloaded chunk is not
        // one. Warmed first so that a room failing to seal means something about the room.
        String warm = exec("stellurgytest chunk warmup 0 " + ((cx - 4) >> 4) + " " + ((CZ - 4) >> 4)
                + " " + ((cx + 4) >> 4) + " " + ((CZ + 4) >> 4));
        assertTrue("chunk warmup failed: " + warm, warm.contains("\"ok\":true"));
        exec("stellurgytest fill 0 " + (cx - 2) + " " + (by - 1) + " " + (CZ - 2)
                + " " + (cx + 2) + " " + by + " " + (CZ + 2) + " minecraft:stone");
        for (int yy = by + 1; yy <= by + 2; yy++) {
            exec("stellurgytest fill 0 " + (cx - 2) + " " + yy + " " + (CZ - 2)
                    + " " + (cx + 2) + " " + yy + " " + (CZ + 2) + " minecraft:stone");
            exec("stellurgytest fill 0 " + (cx - 1) + " " + yy + " " + (CZ - 1)
                    + " " + (cx + 1) + " " + yy + " " + (CZ + 1) + " minecraft:air");
        }
        exec("stellurgytest fill 0 " + (cx - 2) + " " + (by + 3) + " " + (CZ - 2)
                + " " + (cx + 2) + " " + (by + 3) + " " + (CZ + 2) + " minecraft:stone");
    }

    /**
     * Sealed LAST, after the detector is already part of the wall. Sealing first and then punching a
     * block into the volume leaves the zone covering a different set of cells than the test thinks —
     * which is how this test first failed, silently, by sampling a position in no zone at all.
     */
    private void sealRoom(int cx) throws Exception {
        String place = "stellurgytest place 0 " + cx + " " + CY + " " + CZ + " stellurgy:oxygenVent";
        Reply.of(place, exec(place)).requireOk("vent placement");
        exec("stellurgytest energy inject 0 " + cx + " " + CY + " " + CZ + " 1000000");
        // EXPERIMENT: SEAL_TICKS is a DOSE of the SERVER'S OWN clock, not a budget for this box to
        // be quick. The fill that makes a room a zone is paced by world time, and a force-ticked
        // tile does not move world time — which is why ticking the vent alone never seals it, and
        // why an earlier version of this that force-ticked in a poll loop only worked by accident,
        // on the real seconds that elapsed between its polls. The clock is advanced explicitly
        // instead, so the same dose does the same work everywhere, and the state is read ONCE.
        GameTicks.advance(client(), GameTicks.server(), SEAL_TICKS);
        String air = exec("stellurgytest atmosphere get 0 " + cx + " " + airY() + " " + CZ);
        assertTrue("premise: the room must actually be a sealed zone with gases in it: " + air,
                Reply.of(air).has("gases"));
    }

    /** Part of the room's shell, with air on the inward side for it to read. */
    private void placeDetector(int cx) throws Exception {
        String resp = exec("stellurgytest place 0 " + (cx + 1) + " " + airY() + " " + CZ
                + " stellurgy:oxygenDetection");
        assertTrue("detector placement failed: " + resp, resp.contains("\"ok\":true"));
    }

    private void setMode(int cx, String assertion) throws Exception {
        String resp = exec("stellurgytest atmosphere detector-set-mode 0 " + (cx + 1) + " " + airY()
                + " " + CZ + " " + assertion);
        assertTrue("detector mode " + assertion + " refused: " + resp, resp.contains("\"ok\":true"));
    }

    private void setAir(int cx, int n2Ppm, int o2Ppm, int co2Ppm, String... extraGases)
            throws Exception {
        StringBuilder cmd = new StringBuilder("stellurgytest vent setair 0 " + cx + " " + CY + " " + CZ
                + " " + ppm(n2Ppm) + " " + ppm(o2Ppm) + " " + ppm(co2Ppm));
        for (String gas : extraGases) {
            cmd.append(' ').append(gas);
        }
        String resp = exec(cmd.toString());
        assertTrue("setair failed: " + resp, resp.contains("\"ok\":true"));
    }

    private String output(int cx) throws Exception {
        return exec("stellurgytest atmosphere detector-output 0 " + (cx + 1) + " " + airY() + " " + CZ);
    }

    /**
     * The detector reads the air on its own schedule, so the answer is asserted after a stated
     * amount of the detector's OWN world — never the instant the air changed. An immediate reading
     * passes or fails on how long the previous command happened to take, which is how this test
     * first went green by accident: two diagnostic calls added to a failure message ran BEFORE the
     * comparison and supplied exactly the delay it needed.
     */
    private void assertPowered(int cx, boolean expected, String why) throws Exception {
        // MADE to sample, not waited for. Production gates the detector's sample on
        // `world.getWorldTime() % 10`, so what it reports is whatever it decided the last time that
        // gate happened to fire — which, right after the air was changed, is about the OLD air.
        // `detector-force-sample` runs the same sample loop and the same setState production runs,
        // once, on demand. One call, one read, and no budget anywhere: the answer does not depend
        // on how fast this box is.
        String cmd = "stellurgytest atmosphere detector-force-sample 0 "
                + (cx + 1) + " " + airY() + " " + CZ;
        Reply.of(cmd, exec(cmd)).requireOk("the detector must be made to sample before it is read");
        assertEquals(why + " (reading " + powered(cx) + " after a forced sample): " + output(cx),
                String.valueOf(expected), powered(cx));
    }

    private String powered(int cx) throws Exception {
        String out = output(cx);
        Matcher m = POWERED.matcher(out);
        assertTrue("detector reported no power state: " + out, m.find());
        return m.group(1);
    }
}
