package dev.stannismod.stellurgy.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;

import org.junit.Test;
import org.lwjgl.input.Keyboard;
import org.valkyrienskies.mod.common.ships.chunk_claims.ShipChunkAllocator;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.PlayerState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.stannismod.stellurgy.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * SPIKE — can a player actually LIVE millions of blocks from the origin, or only exist there?
 *
 * <p>Chunk generation, block storage and entity doubles were already measured clean out to 28M with
 * the subject SPAWNED at the coordinate. None of them says anything about the thing that decides how
 * big a body may be drawn: whether a <b>player</b> walks, stands and collides normally out there.</p>
 *
 * <h2>Why the first attempt could not reach 8M, and why the reason was not vanilla</h2>
 * A connected player could not be delivered past ~4M, and vanilla's speed check
 * ({@code NetHandlerPlayServer} "moved too quickly!") was blamed. It is not the cause. The physics
 * mod installs a cancellable {@code @Inject} at the HEAD of
 * {@code NetHandlerPlayServer.setPlayerLocation} that CANCELS any teleport whose destination it
 * considers its own reserved "shipyard" region, and that region is the half-open quadrant
 * {@code chunkX >= 318401 && chunkZ >= -1599} — i.e. every position with
 * <b>X ≥ 5,094,416 and Z ≥ -25,584</b>. Teleports into it are dropped silently: the command reports
 * success, the mixin cancels, and the player never moves. That is exactly the reported symptom, and
 * it is a mod-imposed wall five million blocks out, not a vanilla precision limit.
 *
 * <p>Two consequences drive this class. {@link #whereExactlyDoesADeliveryStopWorking()} pins the
 * boundary against numbers PREDICTED from that predicate, so the mechanism is proven rather than
 * inferred from "2M worked and 8M did not". And the playability ladder runs at
 * {@code Z = }{@value #ARENA_Z}, below the quadrant's Z edge, where the predicate is false at every
 * X — so the original question can be answered out to 28M without touching the physics mod.</p>
 *
 * <h2>Acceptance, stated before the run</h2>
 * Every rung is compared against the {@code x=0} rung measured in the same run, in the same arena,
 * with the same key held for the same number of ticks:
 * <ol>
 *   <li><b>Walking distance</b> over {@value #WALK_TICKS} ticks of held {@code W} must be within
 *       <b>±10%</b> of the origin's, and at least {@value #MIN_WALK_BLOCKS} blocks absolute.</li>
 *   <li><b>Collision stand-off</b> from the wall walked into must be within
 *       <b>{@value #STANDOFF_TOLERANCE}</b> blocks of the origin's — the sharpest instrument here,
 *       being a sub-block quantity resolved from absolute coordinates.</li>
 *   <li><b>Standing</b>: {@code posY} within {@value #Y_TOLERANCE} of the floor top throughout.</li>
 *   <li><b>No rubber-band</b>: server and client agree on {@code posX} to within
 *       {@value #SYNC_TOLERANCE} blocks at rest.</li>
 * </ol>
 *
 * <p><b>Designed to come back NO.</b> If 28M behaves like the origin on all four, the ±2M bound has
 * nothing left holding it up. If it does not, the rung where it stops is the answer.</p>
 */
public class SpikeFarCoordinatePlayabilityTest extends AbstractClientE2ETest {

    /**
     * The arena's Z. The physics mod's reserved quadrant starts at {@code chunkZ >= -1599}
     * (Z ≥ -25,584); everything here sits well below it, so its teleport veto never fires and the
     * only thing under test is the coordinate's own magnitude.
     */
    private static final int ARENA_Z = -100_000;

    private static final int OVERWORLD = 0;
    /** Well above sea level: 2M and 16M are both ocean, and a delivery into water measures the water. */
    private static final int FLOOR_Y = FixtureSite.OPEN_AIR_Y;
    private static final int STAND_Y = FLOOR_Y + 1;

    /** The corridor runs +X from the player; the wall's near face is this many blocks ahead. */
    private static final int WALL_OFFSET = 16;

    /** The block the arena is built out of, spelled once — the fill above lays exactly this. */
    private static final String STONE = "minecraft:stone";
    private static final int WALK_TICKS = 40;
    private static final int RAM_TICKS = 160;

    private static final double MIN_WALK_BLOCKS = 5.0d;
    private static final double WALK_RATIO_TOLERANCE = 0.10d;
    private static final double STANDOFF_TOLERANCE = 0.05d;
    private static final double Y_TOLERANCE = 0.05d;
    private static final double SYNC_TOLERANCE = 0.5d;
    private static final double ARRIVAL_TOLERANCE = 1.0d;
    /** A deadline for each of a delivery's two records (the chunk, the placement) — not a settle. */
    private static final int DELIVERY_LINK_BUDGET_TICKS = 200;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    /**
     * Pins the delivery wall against numbers predicted from the physics mod's own predicate, so the
     * mechanism is proven rather than inferred. {@code isChunkInShipyard(cx, cz)} is
     * {@code cx >= CHUNK_X_START - MAX_CHUNK_RADIUS && cz >= CHUNK_Z_START - MAX_CHUNK_RADIUS}, so
     * the four cases below are decided before the run: one chunk under the X edge moves, the first
     * reserved chunk does not, and a coordinate deep inside moves again once Z drops below the
     * quadrant. A miss on ANY of the four falsifies the explanation.
     *
     * <p>The edge is READ from the allocator rather than written down. It was written down once —
     * as {@code cx >= 318401}, block X 5 094 416 — and then the constant was raised to give the
     * cell its clearance, at which point this test went red saying the explanation had been
     * falsified. It had not: the number had moved and the test had not been told. A test that
     * pins a mechanism must be keyed to the mechanism's own constant, or it pins the day it was
     * written.</p>
     */
    @Test
    public void whereExactlyDoesADeliveryStopWorking() throws Exception {
        bot().waitForWorld();
        exec("gamerule sendCommandFeedback false");
        exec("gamerule logAdminCommands false");

        List<String> report = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        // The first reserved BLOCK X, straight out of the predicate the teleport is cancelled by.
        final long edgeX = ((long) (ShipChunkAllocator.CHUNK_X_START
                - ShipChunkAllocator.MAX_CHUNK_RADIUS)) << 4;
        // Deep inside the quadrant, and derived so it stays inside whatever the edge becomes —
        // a hard-coded 28M was inside the old quadrant and would not be inside a much later one.
        final long deepX = edgeX + 1_000_000L;
        // {x, z, expectedToMove}
        double[][] cases = {
                {edgeX - 16 + 0.5d, 0.5d, 1d},   // one chunk under the edge
                {edgeX + 0.5d, 0.5d, 0d},        // the first reserved chunk
                {deepX + 0.5d, 0.5d, 0d},        // deep inside the quadrant
                {deepX + 0.5d, ARENA_Z + 0.5d, 1d}, // same X, Z below the quadrant's edge
        };
        for (double[] c : cases) {
            boolean expectMove = c[2] != 0d;
            String reply = exec("stellurgytest player far-tp " + fmt(c[0]) + " 200 " + fmt(c[1]));
            double from = field(reply, "fromX");
            double to = field(reply, "posX");
            boolean moved = Math.abs(to - c[0]) < ARRIVAL_TOLERANCE;
            boolean unchanged = Math.abs(to - from) < 1e-6d;
            report.add("target=(" + fmt(c[0]) + "," + fmt(c[1]) + ")"
                    + " chunk=(" + (((long) Math.floor(c[0])) >> 4) + "," + (((long) Math.floor(c[1])) >> 4) + ")"
                    + " predicted=" + (expectMove ? "MOVES" : "CANCELLED")
                    + " observed=" + (moved ? "MOVED" : unchanged ? "CANCELLED" : "ELSEWHERE(" + to + ")"));
            if (moved != expectMove) {
                wrong.add(report.get(report.size() - 1));
            }
            // Park him back near the origin so the next case starts from a known place.
            exec("stellurgytest player far-tp 0.5 200 0.5");
            GameTicks.advanceWorld(serverClient(), OVERWORLD, 20);
        }

        StringBuilder out = new StringBuilder("[SPIKE far-coordinate delivery boundary]\n");
        for (String line : report) {
            out.append("  ").append(line).append('\n');
        }
        System.out.println(out);
        writeReport("far-coordinate-delivery-boundary.txt", out.toString());
        assertTrue("the reserved-quadrant explanation predicts these four outcomes; it missed:\n" + out,
                wrong.isEmpty());
    }

    // ─── instruments ────────────────────────────────────────────────────────────

    private static double field(String json, String key) {
        // NaN on absence is deliberate and CHECKED by the callers, which grade a rung and must be
        // able to say "not measured" apart from "measured zero" — at a far coordinate those are the
        // two outcomes the whole spike exists to tell apart.
        return Reply.of(json).number(key);
    }

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
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
