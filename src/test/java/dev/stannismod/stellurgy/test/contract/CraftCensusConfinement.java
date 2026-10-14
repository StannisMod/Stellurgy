package dev.stannismod.stellurgy.test.contract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The craft census is the shared bases' instrument and nobody else's: no test source but
 * {@code CraftCensus} may send {@code crafts census}.
 *
 * <p>A test never counts or searches crafts — it holds the ids of the crafts it built, from the records
 * of their assembly. The census is the one place that asks how many crafts the server holds, and it
 * asks only after a scenario, to judge that the answer is none. A census call anywhere else is a count
 * by another name.</p>
 *
 * <p>SILENT about a command assembled from pieces ({@code "crafts " + "census"}): it reads string
 * literals that carry the verb whole.</p>
 */
final class CraftCensusConfinement {

    // NOT-A-PROBE-REPLY: matches Java SOURCE text — a string literal carrying the census verb.
    /** A string literal that carries the census verb. A constant: a compiled {@link Pattern} is immutable. */
    private static final Pattern CENSUS_LITERAL = Pattern.compile("\"[^\"\\n]*crafts\\s+census");
    private static final String OWNER = "CraftCensus.java";

    private CraftCensusConfinement() {
    }

    /** Add a failure for every test source but the owner that sends the census; answer a summary. */
    static String check(Path root, List<String> failures) throws IOException {
        int scanned = 0;
        int offenders = 0;
        for (Path tree : new Path[]{root.resolve("src/test/java"), root.resolve("testframework/src/main/java")}) {
            if (!Files.isDirectory(tree)) {
                failures.add("craft census confinement: no source tree at " + tree + " -- nothing was measured");
                continue;
            }
            List<Path> sources;
            try (Stream<Path> walk = Files.walk(tree)) {
                sources = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
            }
            for (Path source : sources) {
                scanned++;
                if (source.getFileName().toString().equals(OWNER)) {
                    continue;
                }
                String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
                Matcher m = CENSUS_LITERAL.matcher(text);
                if (m.find()) {
                    offenders++;
                    failures.add(root.relativize(source) + ": sends the craft census, the shared bases' check that"
                            + " a scenario left no craft behind. A test holds its own crafts' ids and never"
                            + " counts crafts");
                }
            }
        }
        return "craft census: " + scanned + " test sources read, " + offenders + " send the census outside "
                + OWNER;
    }
}
