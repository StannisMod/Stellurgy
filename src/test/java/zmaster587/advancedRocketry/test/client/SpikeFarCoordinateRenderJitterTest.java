package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;

import org.junit.Test;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.Reply;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * SPIKE — is the render actually quantized far from the origin, the thing the 4M cell was sized for?
 *
 * <p>The cell used to be 4,000,000 blocks, justified with "entity doubles / chunks / lighting degrade
 * past ~±2M blocks in 1.12.2". The server half of that (chunk generation, block storage) measured
 * CLEAN out to 28M. This measures the visual half.
 *
 * <h2>The stimulus, and why it is motion rather than a still frame</h2>
 * A float quantum does not produce a shimmer in a static scene — the error is CONSTANT, so a still
 * camera at 16M renders a still (if slightly displaced) image. Quantization shows up when the camera
 * moves by LESS than the quantum: the frame then refuses to change until the accumulated motion
 * crosses one step. So the camera is walked in {@value #STEP_BLOCKS}-block increments and the metric
 * is <b>how many consecutive frames come back byte-identical</b>.
 *
 * <p>Expected, if the render path carried absolute coordinates in float: at ±2M the quantum is 0.25
 * block, so ~5 frames repeat per step; at ±16M it is 2 blocks, so ~40 repeat. Expected, if the path
 * subtracts the viewer position in double before casting (which is what
 * {@code RenderManager.renderEntityStatic} and Valkyrien Skies' {@code PhysObjectRenderManager} both
 * appear to do): zero repeats at every coordinate.
 *
 * <h2>Two controls, because this instrument has a known way of lying</h2>
 * <ol>
 *   <li><b>The capture must contain a scene.</b> A framebuffer enabled at RUNTIME receives the HUD
 *       pass and not the world pass, so every capture comes back as the clear colour — which reads
 *       exactly like "the renderer drew nothing". This test therefore declares
 *       {@code requiresFramebufferAtLaunch()}, so its own client starts with the FBO on whatever the
 *       invocation passes, and the first frame is still checked for being more than one flat
 *       colour.</li>
 *   <li><b>The scene must be STATIC.</b> Two captures with no motion between them must be identical.
 *       If they are not, something in the frame is animating and "frames differ" can no longer mean
 *       "the camera moved" — the run is inconclusive and says so rather than producing a number.</li>
 * </ol>
 *
 * <h2>Why the first run of this class stopped at 4M, and why that was not the render</h2>
 * It delivered with plain {@code /tp} into an arena at {@code Z = 0}. The physics mod cancels,
 * silently, any teleport into its reserved shipyard quadrant — {@code chunkX >= 318401 && chunkZ >=
 * -1599}, i.e. <b>X ≥ 5,094,416 and Z ≥ -25,584</b> — while the command still reports success. Every
 * rung from 8M up was therefore refused by a mod constant, and the camera never left the previous
 * coordinate. The arena now sits at {@code Z = }{@value #ARENA_Z}, below the quadrant's Z edge, and
 * the long jump between rungs goes through {@code /artest player far-tp} (vanilla's own
 * dimension-change path, which is how a long jump escapes the speed check). The sub-block camera
 * steps stay on plain {@code /tp}: they are not long jumps, and they are outside the quadrant.
 *
 * <p>Designed to come back NO: if every coordinate shows zero repeats, the render is not the ceiling
 * and the cell bound has to be justified by something else or dropped.</p>
 */
@org.junit.Ignore("RETIRED 2026-09-16, answered. The render does not quantize out to 24M (measured"
        + " 2026-08-12: frames compared clean at every rung). Kept rather than deleted"
        + " because that table cites this class as its evidence. Un-ignoring is removing this"
        + " annotation and nothing else: do it if the cell bound moves or the render path changes.")
public class SpikeFarCoordinateRenderJitterTest extends AbstractClientE2ETest {

    // RETIRED 2026-09-16 — maintainer: "Он же отработал, теперь пусть игнорируется. Он не проверяет
    // механики." And on why the file stays: "Ну да, и поэтому мы его не удаляем" — `space-model.md`
    // cites this class as the evidence for its far-coordinate table, and a citation needs a target.
    //
    // IT ANSWERED ITS QUESTION. Measured 2026-08-12: the camera walked 0.05 blocks a step and frames
    // compared CLEAN at every rung out to 24M: the render does not quantize, and neither does the
    // wire. That measurement stands; this class re-establishing it
    // every run buys nothing and costs a client boot and five screenshots.
    //
    // NOT converted into a contract test, which is the usual first ending for a spike that has
    // answered its question, because it proved a NEGATIVE about the renderer at coordinates the game does not put players
    // at yet. There is no mechanic to pin. The second ending — @Ignore — is honest here for the one
    // condition that makes it honest: un-ignoring is removing the annotation and nothing else. The
    // instrument is complete, both its controls included.

    /**
     * This spike measures WORLD pixels, so its client is launched with the framebuffer already on.
     *
     * <p><b>Declared here rather than asked of the operator</b>, which is what the base class's hook
     * exists for and what this test did not use until 2026-09-16. It relied on {@code -PclientFbo=true}
     * being remembered at the command line; a full-tier run does not pass it, so on every such run
     * this spike reported its own control as INCONCLUSIVE and had to be re-run alone and its result
     * merged in by hand. Measured that day: 184 passed, 2 failed, and this was one of the two — a red
     * that said nothing about the subject and everything about the invocation.</p>
     *
     * <p>Zero tests overrode this hook before this one, and its javadoc already said why it is there.</p>
     */
    @Override
    protected boolean requiresFramebufferAtLaunch() {
        return true;
    }

    /**
     * The origin is carried as the CONTROL in the same run: "zero repeats at 16M" means nothing until
     * the same instrument has shown zero repeats where no one suspects a quantum. Then today's
     * half-cell, the ratified half-cell (16M) and the measured margin (24M).
     */
    private static final int[] X_LADDER = {0, 2_000_000, 8_000_000, 16_000_000, 24_000_000};

    /**
     * The arena's Z. The physics mod's reserved quadrant starts at {@code chunkZ >= -1599}
     * (Z ≥ -25,584); this sits well below it, so its teleport veto never fires and the only thing
     * under test is the coordinate's own magnitude.
     */
    private static final int ARENA_Z = -100_000;

    /** Sub-block camera step. Smaller than every quantum in the table, so a quantum shows as repeats. */
    private static final double STEP_BLOCKS = 0.05d;
    private static final int STEPS = 12;
    /** How many 20-tick waits the frame gets to stop changing on its own before a teleport. */
    private static final int SETTLE_ATTEMPTS = 15;
    /** How many (deliver, settle) rounds a rung gets before it is called undeliverable. */
    private static final int DELIVERY_ATTEMPTS = 4;

    private static final int OVERWORLD = 0;
    private static final int FLOOR_Y = FixtureSite.OPEN_AIR_Y;
    private static final int EYE_Y = FLOOR_Y + 1;

    private Path outDir;
    private String botName;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void howFarFromTheOriginDoesTheRenderStartToQuantize() throws Exception {
        outDir = Paths.get(System.getProperty("forge.test.client.screenshotDir", "build/test-screenshots"))
                .toAbsolutePath();
        Files.createDirectories(outDir);

        bot().waitForWorld();
        exec("gamerule sendCommandFeedback false");
        exec("gamerule logAdminCommands false");
        // Freeze everything that could change a pixel for a reason this spike did not cause.
        exec("gamerule doDaylightCycle false");
        exec("gamerule doMobSpawning false");
        exec("gamerule doWeatherCycle false");
        exec("weather clear");
        exec("time set 6000");

        String health = exec("artest player health");
        Reply healthReply = Reply.of("artest player health", health);
        assertTrue("player health must echo the player name: " + health, healthReply.has("player"));
        botName = healthReply.text("player");

        JsonObject fb = bot().setFramebuffer(true);
        assertTrue("this client's GL must support the framebuffer capture path: " + fb,
                fb.get("supported").getAsBoolean());
        bot().setHudHidden(true);
        bot().setRenderDistance(4);

        List<String> report = new ArrayList<>();
        List<String> inconclusive = new ArrayList<>();
        // Per rung: the longest run of byte-identical frames, i.e. the quantum in camera steps.
        java.util.Map<Integer, Integer> longestRunByX = new java.util.LinkedHashMap<>();

        for (int x : X_LADDER) {
            // A sealed stone box: the only thing in frame is a wall a few blocks away, so nothing in
            // the picture can move on its own (no sky, no sun, no clouds, no weather).
            exec("artest chunk forceload " + OVERWORLD + " " + (x >> 4) + " " + (ARENA_Z >> 4));
            // Put the player there FIRST so the chunks around him are live, and build the box only
            // then. The first two runs built into unloaded chunks, the player fell into an ocean, and
            // every frame was animated water — the controls caught it, but the arrangement had to be
            // read off a captured frame to see WHY. FLOOR_Y is well above sea level for the same
            // reason: 2M and 16M are both ocean.
            deliver(x, FLOOR_Y + 20);
            exec("artest fill " + OVERWORLD + " " + (x - 6) + " " + FLOOR_Y + " " + (ARENA_Z - 6) + " "
                    + (x + 6) + " " + (FLOOR_Y + 5) + " " + (ARENA_Z + 6) + " minecraft:stone");
            exec("artest fill " + OVERWORLD + " " + (x - 5) + " " + (FLOOR_Y + 1) + " " + (ARENA_Z - 5)
                    + " " + (x + 5) + " " + (FLOOR_Y + 4) + " " + (ARENA_Z + 5) + " minecraft:air");
            // A patterned wall: a flat surface gives a frame whose pixels barely move, and a
            // sub-block shift in a flat texture is exactly the change this must be able to see.
            exec("artest fill " + OVERWORLD + " " + (x - 5) + " " + (FLOOR_Y + 1) + " " + (ARENA_Z + 5)
                    + " " + (x + 5) + " " + (FLOOR_Y + 4) + " " + (ARENA_Z + 5) + " minecraft:bookshelf");

            // ARRANGEMENT CHECK, ON THE AXIS THAT CARRIES THE CONDITION. This used to test posY, and
            // posY is right whenever the player stands on ANY floor — so it passed while he was still
            // in the previous coordinate's box, and three separate readings were taken of a player who
            // was not there. Delivery is RETRIED until the server's own posX says he arrived, and
            // abandoned loudly if it never does.
            double actualX = deliver(x, EYE_Y);
            bot().setLook(0f, 0f); // face +Z, straight at the bookshelf wall
            bot().waitTicks(40);
            if (!(Math.abs(actualX - (x + 0.5d)) < 2d)) {
                inconclusive.add("x=" + x + " the player never arrived (posX=" + actualX
                        + ", wanted " + (x + 0.5d) + ") - delivery, not the render");
                continue;
            }

            BufferedImage first = capture("jitter_" + x + "_ctrl_a");
            if (isFlat(first)) {
                inconclusive.add("x=" + x + " capture is one flat colour " + describe(first)
                        + " - the framebuffer is not receiving the world pass. This class declares"
                        + " requiresFramebufferAtLaunch(), so the FBO is not the operator's to"
                        + " remember: if this fires, the launch-time enable itself did not take"
                        + " (a driver that refuses the FBO path, or the hook not reaching this"
                        + " client) and the GL support check above is the next thing to read.");
                continue;
            }
            // SETTLE. The first run said the scene was not static and it was right: chunk streaming,
            // lighting propagation and the client's own catch-up keep changing pixels for a while
            // after a teleport. Wait for the frame to stop moving ON ITS OWN before asking whether
            // MOTION moves it — an unsettled scene answers "the frame changed" to every question.
            BufferedImage second = null;
            int settleAttempts = 0;
            int lastDelta = Integer.MAX_VALUE;
            BufferedImage previousSettle = first;
            // STAYS A LOOP: the exit is the DIFFERENCE between two captured frames falling to zero
            // — a value converging, where nothing decides. No record could carry it, because the
            // quantity only exists between a PAIR of observations this loop takes itself. What it
            // cannot see: a scene that stopped moving for one pair and resumed after.
            while (settleAttempts < SETTLE_ATTEMPTS) {
                settleAttempts++;
                bot().waitTicks(20);
                BufferedImage now = capture("jitter_" + x + "_settle" + settleAttempts);
                lastDelta = differingPixels(previousSettle, now);
                previousSettle = now;
                if (lastDelta == 0) {
                    second = now;
                    break;
                }
            }
            if (second == null) {
                inconclusive.add("x=" + x + " the frame never stopped changing on its own after "
                        + settleAttempts + " attempts (last delta " + lastDelta + "px) - the scene is "
                        + "not static, so frame differences cannot be attributed to camera motion");
                continue;
            }

            List<Double> movedOnServer = new ArrayList<>();
            int repeats = 0;
            int maxRun = 0;
            int run = 0;
            BufferedImage previous = second;
            // A STIMULUS WINDOW: each step teleports the body further out and compares the frame
            // with the one before, so the loop is what produces the motion being measured. Its
            // results — how many steps repeated a frame, and the longest RUN of repeats — are
            // statistics over the sweep, which no record could carry. What it cannot see: a frame
            // between two steps.
            for (int step = 1; step <= STEPS; step++) {
                double px = x + 0.5d + step * STEP_BLOCKS;
                exec("tp " + botName + " " + fmt(px) + " " + EYE_Y + " " + fmt(ARENA_Z + 0.5d));
                bot().waitTicks(8);
                // THE MISSING CONTROL. "The frame did not change" and "the player did not move" are
                // the same observation until the position is read back. The first version of this
                // spike read only the frame and concluded the RENDER quantizes — a conclusion its own
                // data could not support.
                double serverX = posXOf(exec("artest player health"));
                movedOnServer.add(serverX);
                BufferedImage now = capture("jitter_" + x + "_step" + step);
                if (identical(previous, now)) {
                    repeats++;
                    run++;
                    maxRun = Math.max(maxRun, run);
                } else {
                    run = 0;
                }
                previous = now;
            }
            double serverSpan = movedOnServer.isEmpty() ? 0d
                    : movedOnServer.get(movedOnServer.size() - 1) - movedOnServer.get(0);
            int distinctServerPositions = new java.util.HashSet<>(movedOnServer).size();
            double impliedQuantum = maxRun == 0 ? 0d : (maxRun + 1) * STEP_BLOCKS;
            report.add("x=" + x + " steps=" + STEPS
                    + " serverMoved=" + fmt(serverSpan) + "blk/" + distinctServerPositions + "distinct"
                    + " identicalFrames=" + repeats
                    + " longestRun=" + maxRun + (maxRun >= STEPS ? " quantum>=" + fmt(STEPS * STEP_BLOCKS) : " quantum~" + fmt(impliedQuantum))
                    + " blocks "
                    + describe(previous));
            // A repeat means "the render did not change". That is only about the RENDER if the camera
            // actually moved, so a rung whose stimulus did not land is inconclusive, never a finding.
            if (distinctServerPositions < STEPS) {
                inconclusive.add("x=" + x + " only " + distinctServerPositions + " of " + STEPS
                        + " camera steps landed on the server - the stimulus, not the render");
            } else {
                longestRunByX.put(x, maxRun);
            }
        }

        StringBuilder out = new StringBuilder(
                "[SPIKE far-coordinate render jitter] step=" + STEP_BLOCKS + " blocks\n");
        for (String line : report) {
            out.append("  ").append(line).append('\n');
        }
        for (String line : inconclusive) {
            out.append("  INCONCLUSIVE ").append(line).append('\n');
        }
        System.out.println(out);
        writeReport("far-coordinate-render-jitter.txt", out.toString());

        // The control decides whether anything else here is evidence: at the origin nobody suspects a
        // quantum, so if the instrument reports repeats THERE it cannot tell a quantized render from a
        // camera that did not move. Asserted first, and separately.
        Integer control = longestRunByX.get(0);
        assertTrue("the x=0 control produced no usable measurement, so no far rung is evidence:\n" + out,
                control != null);
        assertTrue("the x=0 control repeated " + control + " frames in a row - a sub-block camera step "
                + "does not change the picture even at the origin, so this instrument cannot see the "
                + "thing it was built to see:\n" + out, control == 0);

        List<String> quantized = new ArrayList<>();
        for (java.util.Map.Entry<Integer, Integer> e : longestRunByX.entrySet()) {
            if (e.getKey() != 0 && e.getValue() > control) {
                quantized.add("x=" + e.getKey() + " longestRun=" + e.getValue()
                        + " (~" + fmt((e.getValue() + 1) * STEP_BLOCKS) + " blocks)");
            }
        }
        assertTrue("the render quantizes further out than at the origin: " + quantized + "\n" + out,
                quantized.isEmpty());
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    /**
     * Puts the camera at {@code (x + 0.5, y, ARENA_Z + 0.5)} through the long-jump path and returns
     * the server's own reading of where he ended up. Retried, because the chunks are force-loaded on
     * the SERVER while the client has not received them yet — the first delivery of a rung routinely
     * lands in a world the client cannot see.
     */
    private double deliver(int x, int y) throws Exception {
        double actualX = Double.NaN;
        // STAYS A LOOP, and the refusal names the link. The re-issued far-tp IS the stimulus — a
        // delivery that did not take is not recoverable by reading longer — and the exit is a
        // CONVERGENCE on where the server holds him. The link that looks right is `pos_jump`, and
        // it does not answer: it fires only on a VERTICAL write past a threshold and carries
        // `from`/`to` in Y alone, so it cannot say he arrived at this X. What this cannot see: a
        // delivery that landed and was undone between two attempts.
        for (int attempt = 1; attempt <= DELIVERY_ATTEMPTS; attempt++) {
            exec("artest player far-tp " + fmt(x + 0.5d) + " " + y + " " + fmt(ARENA_Z + 0.5d));
            GameTicks.advanceWorld(serverClient(), OVERWORLD, 60);
            bot().waitTicks(20);
            actualX = posXOf(exec("artest player health"));
            if (Math.abs(actualX - (x + 0.5d)) < 2d) {
                break;
            }
        }
        return actualX;
    }

    private BufferedImage capture(String name) throws Exception {
        bot().setHudHidden(true);
        // `screenshot` captures at the end of the next frame the client renders, which is drawn
        // after the HUD was hidden: nothing to advance for.
        JsonObject shot = bot().screenshot(name);
        assertTrue("screenshot must land on disk: " + shot, shot.get("exists").getAsBoolean());
        Path dst = outDir.resolve(name + ".png");
        Files.copy(Paths.get(shot.get("path").getAsString()), dst, StandardCopyOption.REPLACE_EXISTING);
        BufferedImage image = ImageIO.read(new File(dst.toString()));
        assertTrue("screenshot must decode: " + dst, image != null);
        return image;
    }

    private static boolean identical(BufferedImage a, BufferedImage b) {
        return differingPixels(a, b) == 0;
    }

    private static int differingPixels(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return Integer.MAX_VALUE;
        }
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if ((a.getRGB(x, y) & 0xFFFFFF) != (b.getRGB(x, y) & 0xFFFFFF)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** One flat colour = the framebuffer never received the world pass. */
    private static boolean isFlat(BufferedImage img) {
        int first = img.getRGB(0, 0) & 0xFFFFFF;
        for (int y = 0; y < img.getHeight(); y += 3) {
            for (int x = 0; x < img.getWidth(); x += 3) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != first) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String describe(BufferedImage img) {
        return "[" + img.getWidth() + "x" + img.getHeight() + "]";
    }

    /** The server's own reading of where the player is, so the stimulus can be shown to have landed. */
    private static double posXOf(String healthJson) {
        // NaN on absence is deliberate and is CHECKED by every caller: this reading is used to show
        // that a stimulus landed, and "the probe did not report posX" is not a position. It is not a
        // plausible substitute for one either, which is the property a zero would not have had.
        return Reply.of("artest player health", healthJson).number("posX");
    }

    /** The report is the deliverable, so it also lands on disk and survives a truncated console. */
    private static void writeReport(String name, String text) {
        try {
            Path dir = Paths.get("build", "spike-reports").toAbsolutePath();
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), text.getBytes("UTF-8"));
        } catch (Exception e) {
            System.out.println("[SPIKE] could not write the report file: " + e);
        }
    }

    private static String oneLine(String s) {
        return s.replace((char) 10, ' ').replace((char) 13, ' ').trim();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }
}
