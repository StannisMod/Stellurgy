package com.github.stannismod.forge.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Where a test keeps what it produced for a reader — a report, a captured frame — and the only place a
 * test writes a file itself.
 *
 * <p>Everything lands under {@code <working directory>/build/<folder>} (Gradle runs a {@code Test} task
 * with the consuming project's root as the working directory, as {@link PostMortemLogs} assumes), in a
 * folder and under a name that are NAMES, never paths: a game directory is out of reach from here by
 * construction, so a test's own output can never stand in for the start or the save of a world.</p>
 */
public final class TestArtefacts {

    private TestArtefacts() {
    }

    /** Write {@code text} as {@code build/<folder>/<name>} (UTF-8); answers where it went. */
    public static Path writeText(String folder, String name, String text) throws IOException {
        Path target = target(folder, name);
        Files.write(target, text.getBytes(StandardCharsets.UTF_8));
        return target;
    }

    /**
     * Copy {@code produced} — a file a harness child made, e.g. a screenshot — to
     * {@code build/<folder>/<name>}; answers where it went.
     */
    public static Path keepCopy(String folder, String name, Path produced) throws IOException {
        Path target = target(folder, name);
        Files.copy(produced, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** Where {@code build/<folder>/<name>} is, for a message that points a reader at it; writes nothing. */
    public static Path where(String folder, String name) {
        requireName(folder);
        requireName(name);
        return Paths.get(System.getProperty("user.dir", ".")).resolve("build").resolve(folder).resolve(name)
                .toAbsolutePath();
    }

    private static Path target(String folder, String name) throws IOException {
        Path target = where(folder, name);
        Files.createDirectories(target.getParent());
        return target;
    }

    private static void requireName(String name) {
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")
                || name.indexOf(':') >= 0) {
            throw new IllegalArgumentException("REFUSED: a test artefact's folder and name are names, not paths: "
                    + name);
        }
    }
}
