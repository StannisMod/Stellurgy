package zmaster587.advancedRocketry.test.server;

import java.nio.file.Files;
import java.nio.file.Path;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import zmaster587.advancedRocketry.test.SubsystemStatus;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.GameTicks;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.ArrangementFailure.requireArranged;

/**
 * The space subsystem's clock is <b>its own</b>: it advances by itself and at the tick rate, no
 * world's clock moves it, moving it moves no world, and it comes back where it was after a reboot —
 * on a server where the subsystem came up and on one where it never did.
 *
 * <p>Until this landed the subsystem read the overworld's total world time. That answered
 * <b>zero</b> whenever the server or the overworld was not resolvable — a window every start and
 * stop passes through, in which a body's address, a transit's elapsed time and a capacitor's charge
 * were all silently dated to the beginning of the world with nothing logged. It also made aging the
 * universe for a test a write to a world the whole fork shares.</p>
 *
 * <h2>Why this test owns its server</h2>
 * One leg has to drive the OVERWORLD's clock eleven days forward, which is precisely the hammer the
 * change exists to remove from shared servers. Doing that on the shared harness would leave every
 * vanilla and third-party {@code totalTime % N} gate — day cycle, mob spawns, weather — jumped for
 * whichever class ran next. A throwaway world directory can be hammered; a shared one cannot.
 *
 * <h2>Why the divergence is authored rather than waited for</h2>
 * On a fresh world both counters start at zero and both advance once per tick, so they agree by
 * accident and "the space clock ignored the world clock" would be satisfied by a space clock that
 * simply IS the world clock. Every leg below therefore drives the two apart by a magnitude no
 * elapsed-time slack can cover, and asserts the split it created before concluding anything from it.
 */
public class SpaceClockIsTheSubsystemsOwnE2ETest {

    /**
     * Ticks of the SERVER's own counter the space clock is watched across - the old 3 000 ms said in
     * the units of the thing being watched. The two clocks are deliberately different: the wait is on
     * one, the assertion is about the other.
     */
    private static final int OBSERVED_TICKS = 60;

    /**
     * How far a clock is driven in a leg: twenty million ticks, ~11.6 real days at 20 tps. Six orders
     * of magnitude past anything the few seconds of a leg can accumulate on its own, so "it did not
     * follow" and "it followed" cannot be confused.
     */
    private static final long JUMP_TICKS = 20_000_000L;

    /**
     * How much either clock is allowed to move on its own while a leg runs. A leg is a handful of
     * probe round-trips, so this is generous by two orders of magnitude — and still four below
     * {@link #JUMP_TICKS}.
     */
    private static final long ELAPSED_SLACK_TICKS = 20_000L;

    private Path root;
    private RealDedicatedServerHarness harness;

    @Before
    public void seedWorldDirectory() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -D" + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        root = Files.createTempDirectory("forge-server-space-clock-");
    }

    @After
    public void stopHarness() throws Exception {
        try {
            if (harness != null) {
                harness.close();
                harness = null;
            }
        } finally {
            // Every boot here passes cleanupOnClose=false, because the reboot legs need the world
            // directory to outlive a close — so nobody else deletes it. A whole dedicated-server
            // save per method, times the fork count, times every rerun of a flake hunt, is not
            // something to leave in the system temp dir.
            deleteRecursively(root);
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                                                           java.nio.file.attribute.BasicFileAttributes a)
                    throws java.io.IOException {
                Files.deleteIfExists(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path d, java.io.IOException failed)
                    throws java.io.IOException {
                Files.deleteIfExists(d);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", harness.client().execute(cmd));
    }

    /**
     * Neither clock is the other: driving the overworld's does not move the space clock, and driving
     * the space clock does not move the overworld's.
     *
     * <p><b>Both directions are asserted, and neither is redundant.</b> The first is the contract the
     * arrival defect belonged to — a space computation must not inherit a world's counter. The second
     * is the one that keeps a shared server usable, and it is not implied by the first: a clock could
     * perfectly well ignore the world on the way in and still write to it on the way out.</p>
     *
     * <p>Each direction carries its own arrangement assertion. Without them, a probe that quietly
     * failed to move anything would satisfy every "it did not follow" below.</p>
     */
    @Test
    public void neitherClockMovesTheOther() throws Exception {
        harness = RealDedicatedServerHarness.startWith(root, false);

        // ---- DIRECTION 1: move the WORLD clock. The space clock may not follow it. ----
        long spaceBefore = spaceClock(exec("artest space clock"));
        String worldMoved = exec("artest space set-world-clock " + (worldClock(exec("artest space clock"))
                + JUMP_TICKS));
        assertTrue("the world clock must move: " + worldMoved, Reply.of(worldMoved).ok());
        requireArranged("the overworld's counter must really have jumped, or nothing below is"
                        + " a measurement: " + worldMoved,
                worldClock(worldMoved) - jsonLong(worldMoved, "before") >= JUMP_TICKS / 2L);

        long spaceAfterWorldMove = spaceClock(exec("artest space clock"));
        assertTrue("THE CONTRACT: the space clock is the subsystem's own counter, so a world's clock"
                        + " may not carry it. The overworld jumped " + JUMP_TICKS + " ticks and the"
                        + " space clock moved " + (spaceAfterWorldMove - spaceBefore)
                        + " (allowed " + ELAPSED_SLACK_TICKS + " of ordinary elapsed time)",
                Math.abs(spaceAfterWorldMove - spaceBefore) <= ELAPSED_SLACK_TICKS);

        // ---- DIRECTION 2: move the SPACE clock. No world may follow it. ----
        long worldBefore = worldClock(exec("artest space clock"));
        String spaceMoved = exec("artest space set-clock " + (spaceAfterWorldMove + JUMP_TICKS));
        assertTrue("the space clock must move: " + spaceMoved, Reply.of(spaceMoved).ok());
        requireArranged("the space clock must really have jumped: " + spaceMoved,
                spaceClock(spaceMoved) - spaceAfterWorldMove >= JUMP_TICKS / 2L);

        long worldAfterSpaceMove = worldClock(exec("artest space clock"));
        assertTrue("THE CONTRACT: aging the space subsystem must cost no world its own clock. The"
                        + " space clock was moved " + JUMP_TICKS + " ticks and the overworld's total"
                        + " time moved " + (worldAfterSpaceMove - worldBefore) + " with it (allowed "
                        + ELAPSED_SLACK_TICKS + "). Every vanilla gate keyed on total time — the day"
                        + " cycle, mob spawns, weather — rides on this",
                Math.abs(worldAfterSpaceMove - worldBefore) <= ELAPSED_SLACK_TICKS);
    }

    /**
     * The clock advances on its own, at the tick rate, without anybody setting it.
     *
     * <p>Every other leg here moves a clock and then reads it, which a counter that only ever holds
     * what it was last told would satisfy exactly. This is the leg that says the thing is a CLOCK: it
     * touches nothing and requires the number to have grown by the time it looks again. It is also
     * the direct witness for the defect the owned counter replaced — a subsystem reading a world that
     * was not resolvable answered a frozen zero, forever, and nothing said so.</p>
     *
     * <p><b>The RATE is asserted too, and "it grew" would not cover it.</b> The counter is supposed
     * to advance exactly once per server tick, which is what makes every persisted tick value keep
     * its meaning; a second writer on the same event would run it at twice that and every "it moved"
     * assertion in this class would still pass. The reference is the overworld's own total time,
     * which vanilla advances once per tick — so the two DELTAS over the same window must match. That
     * compares rates, not values: the clocks stay decoupled, which is what the leg above asserts.</p>
     */
    @Test
    public void theClockAdvancesWithoutBeingTold() throws Exception {
        harness = RealDedicatedServerHarness.startWith(root, false);

        // Wait on the SERVER's counter and measure the SPACE clock. Two different clocks, so this is
        // not circular - and it is what the test means: the space clock must move because the server
        // ticked, not because three seconds of somebody's wall clock went by.
        String first = exec("artest space clock");
        GameTicks.advance(harness.client(), GameTicks.server(), OBSERVED_TICKS);
        String second = exec("artest space clock");

        long spaceMoved = spaceClock(second) - spaceClock(first);
        long worldMoved = worldClock(second) - worldClock(first);

        assertTrue("the space clock must advance by itself — a counter that only holds what it was"
                        + " last set to is not a clock, and a clock frozen at zero is the defect this"
                        + " one replaced. moved=" + spaceMoved + " (" + first + " -> " + second + ")",
                spaceMoved > 0L);
        requireArranged("the reference clock must have moved too, or the rate check below"
                        + " compares against a stopped server. overworld moved=" + worldMoved,
                worldMoved > 0L);
        assertTrue("...and it must advance ONCE per server tick, not twice: a second writer on the"
                        + " server-tick event would double the rate and leave every other assertion"
                        + " in this class green. Against the overworld's own once-per-tick counter"
                        + " over the same window: space=" + spaceMoved + " world=" + worldMoved,
                Math.abs(spaceMoved - worldMoved) <= Math.max(4L, worldMoved / 4L));
    }

    /**
     * The counter comes back where it was after the server really restarts.
     *
     * <p>Persistence is what the overworld's clock was giving the subsystem for free, and taking the
     * counter into the subsystem's own hands is a promise to keep paying for it. Everything the
     * subsystem stores is a STAMP against this counter — when a cell was last visited, when a jump
     * arrives — so a clock that restarted at zero would make every one of them read as an age of the
     * entire world: cells collected as ancient on the first tick back, a flight with two minutes left
     * restored as long since landed.</p>
     *
     * <p>The clock is set to a value ~11.6 days along, which no boot of this test could ever reach on
     * its own — asserted on the SECOND boot as a lower bound, so a clock that quietly restarted from
     * zero fails rather than passing on the ticks it accumulated since. No explicit save follows the
     * set for the same reason the neighbouring restart tests take none: the SHUTDOWN save is the one
     * that has to work, and asking for an extra pass would hide a snapshot marked dirty too late.</p>
     */
    @Test
    public void theClockComesBackWhereItWasAfterAReboot() throws Exception {
        // --- boot 1 --------------------------------------------------------------------------------
        harness = RealDedicatedServerHarness.startWith(root, false);
        String vs = exec("artest vs available");

        SubsystemStatus status = SubsystemStatus.read(this::exec);
        assertTrue("the production space subsystem must be live on boot 1 — its world-save hook is "
                        + "what persists the clock, so without it this test would assert nothing: "
                        + status.raw(), status.registered);

        long fresh = spaceClock(exec("artest space clock"));
        requireArranged("a fresh boot's clock must be far below the value set below, or "
                        + "reading that value back afterwards would prove nothing. fresh=" + fresh,
                fresh < JUMP_TICKS / 2L);

        String moved = exec("artest space set-clock " + JUMP_TICKS);
        assertTrue("the clock must be set: " + moved, Reply.of(moved).ok());
        assertEquals("and it must hold the value it was set to: " + moved, JUMP_TICKS,
                spaceClock(moved));

        // --- the reboot: this process really exits -------------------------------------------------
        harness.close();
        harness = null;

        // --- boot 2: a brand new JVM, same world directory -----------------------------------------
        harness = RealDedicatedServerHarness.startWith(root, false);
        SubsystemStatus statusAfter = SubsystemStatus.read(this::exec);
        assertTrue("the production subsystem must come up again on boot 2: " + statusAfter.raw(),
                statusAfter.registered);

        long restored = spaceClock(exec("artest space clock"));
        assertTrue("the subsystem's clock must resume where the last save left it, not restart at"
                        + " zero: every stamp it persisted is dated against this counter, so a reset"
                        + " one ages the whole fleet by the age of the world. set=" + JUMP_TICKS
                        + " restored=" + restored, restored >= JUMP_TICKS);
        assertTrue("...and it must be the SAVED value it resumed from rather than a clock that has"
                        + " been running for eleven days: restored=" + restored,
                restored - JUMP_TICKS <= ELAPSED_SLACK_TICKS);
    }

    // ---- THE DOWN-SUBSYSTEM LEG IS GONE, AND ITS COVERAGE WITH IT -------------------------------
    //
    // `theClockComesBackWithTheSubsystemTurnedOff` stood here until 2026-09-18. It booted a server
    // with `enableSpaceSubsystem=false` and pinned the one thing only a stood-down server can show:
    // that the clock ADVANCES and SURVIVES A REBOOT on a session where the controller was never
    // built -- the reason `SpaceSubsystem.advanceClock()` sits ABOVE the `live == null` return in
    // `SpaceSubsystemEvents`, and the reason the clock restore in `onServerStarted` sits above the
    // same check.
    //
    // The flag was removed that day (maintainer: "давай вообще уберём условие регистрации космоса,
    // он слишком централен"), and with it the only way to ARRANGE a server whose space subsystem is
    // down: the sole remaining condition is Valkyrien Skies missing from the classpath, and VS is
    // vendored into this jar, so no test can produce it.
    //
    // WHAT STILL COVERS WHAT:
    //   * clock persistence across a reboot -- the neighbouring test above, on a server with the
    //     subsystem UP. That is now every server.
    //   * the ORDER of the two statements (advance/restore before the null return) -- NOTHING.
    //     It is unarranged, and moving the increment below the return would go green everywhere.
    //     It is not a weakened assertion, it is an absent one, which is why it is written down here
    //     instead of being quietly dropped.
    //
    // Recovering it needs a seam rather than a flag: `advanceClock()` and `onServerStarted(live)`
    // both already take the subsystem as a PARAMETER (they are static-free by design), so a unit
    // test calling them with `null` would pin the order without any server and without giving an
    // operator a switch for the mod's own subject.


    // --- helpers -----------------------------------------------------------------------------------

    private static long spaceClock(String json) {
        return jsonLong(json, "spaceClock");
    }

    private static long worldClock(String json) {
        return jsonLong(json, "overworld");
    }

    private static long jsonLong(String json, String field) {
        assertTrue("probe response carries no numeric \"" + field + "\": " + json, Reply.of(json).has(field));
        return Reply.of(json).integer(field);
    }
}
