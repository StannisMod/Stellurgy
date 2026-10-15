package com.github.stannismod.forge.testing;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Where a harness leaves the child JVM's log after the child is gone.
 *
 * <p>Both harnesses delete the child's working directory at {@code close()}, so the log a failure
 * has to be read out of only survives if it is copied somewhere stable first. This class owns that
 * "somewhere", for the client and the server alike.</p>
 *
 * <p><b>Per CHECKOUT, not per machine — and that is the point.</b> These files used to live in the
 * system temp directory. Two checkouts of the same project running gates at the same time is normal
 * on a developer box, and their logs then land side by side under names that say nothing about
 * which tree produced them; "the newest one" is a natural way to find yours and is wrong exactly
 * when it matters. It cost four runs and a wrong report on 2026-08-21: four consecutive readings of
 * a log written by a different checkout's client, taken as evidence about this one. Keeping them
 * inside the project that produced them removes the ambiguity instead of documenting it.</p>
 *
 * <p><b>Per TEST-JVM inside a checkout</b> — the PID suffix, which is the older half of this rule:
 * one fixed name let concurrent forks clobber each other's diagnostics, which makes every
 * post-mortem unreliable precisely when parallel runs make failures interesting.</p>
 *
 * <p><b>Last boot wins within one JVM.</b> A class whose failing test is not the last to run loses
 * its log; filter to the single method when the log is the evidence.</p>
 */
public final class PostMortemLogs {

    /**
     * System property naming the directory these logs go in. Defaults to
     * {@code <working directory>/build/test-post-mortem} — Gradle runs a {@code Test} task with the
     * consuming project's root as the working directory, the same assumption
     * {@code RealClientHarness.resolveNativesDir} already relies on. Set this when your build does
     * not run tests from the project root.
     */
    public static final String PROP_DIRECTORY = "forge.test.postMortemDir";

    private PostMortemLogs() {
    }

    /** The stable post-mortem location of this test JVM's last CLIENT log. */
    public static Path client() {
        return directory().resolve("forge-test-client-last-pid" + pid() + ".log");
    }

    /** The stable post-mortem location of this test JVM's last SERVER log. */
    public static Path server() {
        return directory().resolve("forge-test-server-last-pid" + pid() + ".log");
    }

    /**
     * The directory, created if missing.
     *
     * <p>Falls back to the system temp directory if the preferred one cannot be created — losing a
     * post-mortem log is a worse outcome than writing it somewhere less tidy, and every caller
     * either prints the path it got or hands it to the test, so a fallback is visible rather than
     * silent.</p>
     */
    private static Path directory() {
        String override = System.getProperty(PROP_DIRECTORY);
        Path preferred = override != null && !override.trim().isEmpty()
                ? Paths.get(override.trim())
                : Paths.get(System.getProperty("user.dir", ".")).resolve("build").resolve("test-post-mortem");
        try {
            Files.createDirectories(preferred);
            return preferred;
        } catch (IOException cannotCreate) {
            return Paths.get(System.getProperty("java.io.tmpdir"));
        }
    }

    private static String pid() {
        String jvm = ManagementFactory.getRuntimeMXBean().getName();
        int at = jvm.indexOf('@');
        return at > 0 ? jvm.substring(0, at) : jvm;
    }
}
