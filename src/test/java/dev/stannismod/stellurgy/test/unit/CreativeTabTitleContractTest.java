package dev.stannismod.stellurgy.test.unit;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Every creative tab this jar builds has a title in a shipped English catalogue.
 *
 * <p>A tab's title is {@code itemGroup.<label>}, resolved through the merged lang files of every
 * domain in the jar; 1.12 answers a missing key with the key itself, so the failure is a tab named
 * {@code itemGroup.advancedRocketryOres} and nothing logged. That exact shape shipped: libVulpes'
 * ore tab kept its old label when the lang key was renamed with the mod, and the label and its key
 * live in different packages and different domains, so no review of one sees the other.</p>
 *
 * <p>Scans source, like {@code LangKeyCrossReferenceTest}: a tab is a static field, and loading
 * every class that declares one would construct half the mod. <b>What it cannot see</b>: a label
 * that is not a string literal passed straight to {@code new CreativeTabs(...)}.</p>
 */
public class CreativeTabTitleContractTest {

    /** Every compiled main source root; a tab declared in any of them ships in the jar. */
    private static final String[] SOURCE_ROOTS = {
            "src/main/java",
            "valkyrienskies/src/main/java",
    };

    /** Every root that ships assets/&lt;domain&gt;/lang/. */
    private static final String[] RESOURCE_ROOTS = {
            "src/main/resources",
            "valkyrienskies/src/main/resources",
    };

    /**
     * The tabs this jar builds today, as a floor: a scan that found fewer has stopped matching the
     * code it is aimed at, and would then pass on nothing.
     */
    private static final int MIN_TABS = 4;

    // NOT-A-PROBE-REPLY: Java source text, the string literal handed to a CreativeTabs constructor
    private static final Pattern NEW_TAB = Pattern.compile("new\\s+CreativeTabs\\s*\\(\\s*\"([^\"]+)\"");

    /** Labels known to be untranslated - each a recorded bug, never a permanent exception. */
    private static final Map<String, String> EXEMPT = new LinkedHashMap<String, String>();
    static {
        EXEMPT.put("tabAffs", "known bug: the shield subsystem's tab has no itemGroup.tabAffs in any catalogue yet");
    }

    @Test
    public void everyCreativeTabHasAnEnglishTitle() throws IOException {
        TreeSet<String> catalogue = new TreeSet<String>();
        for (String root : RESOURCE_ROOTS) {
            for (Path lang : filesUnder(Paths.get(root), ".lang")) {
                if (lang.getFileName().toString().equalsIgnoreCase("en_us.lang")) {
                    catalogue.addAll(keysOf(lang));
                }
            }
        }

        Map<String, String> tabs = new LinkedHashMap<String, String>();
        for (String root : SOURCE_ROOTS) {
            Path dir = Paths.get(root);
            assertTrue("source root not found at " + dir.toAbsolutePath()
                    + " - this test must run with the project root as its working directory",
                    Files.isDirectory(dir));
            for (Path file : filesUnder(dir, ".java")) {
                Matcher m = NEW_TAB.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                while (m.find()) {
                    tabs.put(m.group(1), file.toString());
                }
            }
        }
        assertTrue("found only " + tabs.size() + " creative tab(s) " + tabs.keySet()
                + " - the scan has stopped matching the code", tabs.size() >= MIN_TABS);

        StringBuilder misses = new StringBuilder();
        for (Map.Entry<String, String> tab : tabs.entrySet()) {
            if (!EXEMPT.containsKey(tab.getKey()) && !catalogue.contains("itemGroup." + tab.getKey())) {
                misses.append("  itemGroup.").append(tab.getKey()).append("   (").append(tab.getValue()).append(")\n");
            }
        }
        assertTrue("creative tab(s) whose title no en_US catalogue defines - the player reads the raw key:\n"
                + misses, misses.length() == 0);

        for (String exempt : EXEMPT.keySet()) {
            assertTrue("exemption '" + exempt + "' is stale: the tab is translated now or no longer exists."
                    + " Delete the exemption so this test guards it.",
                    tabs.containsKey(exempt) && !catalogue.contains("itemGroup." + exempt));
        }
    }

    private static TreeSet<String> keysOf(Path lang) throws IOException {
        TreeSet<String> keys = new TreeSet<String>();
        for (String line : new String(Files.readAllBytes(lang), StandardCharsets.UTF_8).split("\\r?\\n")) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (!trimmed.startsWith("#") && eq > 0) {
                keys.add(trimmed.substring(0, eq).trim());
            }
        }
        return keys;
    }

    private static TreeSet<Path> filesUnder(Path root, final String suffix) throws IOException {
        final TreeSet<Path> files = new TreeSet<Path>();
        if (!Files.isDirectory(root)) {
            return files;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(suffix)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }
}
