package dev.stannismod.stellurgy.test.unit;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Every translation key Stellurgy hands to a localization call must exist in a shipped
 * catalogue.
 *
 * <p>1.12.2 resolves a missing key by returning the key itself, so the failure
 * mode is a player staring at {@code msg.lowOxygen} painted across their HUD
 * rather than a warning sentence. Four such keys shipped simultaneously
 * (monitoring-station tabs, service-station GUI title, rocket name casing, the
 * suffocation warning) before anything noticed, because each individually looks
 * like a typo nobody would make.</p>
 *
 * <p><b>What it cannot see.</b> Only string literals passed directly to a
 * recognised localization call. A key assembled by concatenation, read from a
 * field, or — as with {@code TileRocketServiceStation.getModularInventoryName()}
 * — simply {@code return}ed as a bare string for a caller to localize later, is
 * invisible here. Widening the scan to "any string that looks like a key" would
 * drown the result in false positives; it is deliberately not attempted. Those
 * cases need their own targeted tests.</p>
 */
public class LangKeyCrossReferenceTest {

    /**
     * Localization entry points. Each pattern captures the first string literal
     * argument. {@code tr(} is Stellurgy's own TheOneProbe helper.
     */
    private static final Pattern[] CALLS = {
            Pattern.compile("getLocalizedString\\s*\\(\\s*\"([^\"]+)\""),
            Pattern.compile("I18n\\s*\\.\\s*format\\s*\\(\\s*\"([^\"]+)\""),
            Pattern.compile("translateToLocal\\s*\\(\\s*\"([^\"]+)\""),
            Pattern.compile("new\\s+TextComponentTranslation\\s*\\(\\s*\"([^\"]+)\""),
            Pattern.compile("\\btr\\s*\\(\\s*\"([^\"]+)\""),
    };

    /**
     * Keys that are legitimately absent from the catalogues. Every entry needs a
     * reason; "it fails otherwise" is not one.
     */
    private static final Map<String, String> EXEMPT = new LinkedHashMap<String, String>();
    static {
        EXEMPT.put("tooltip.stellurgy.none",
                "optional by design — TooltipInjector guards it with I18n.hasKey and "
                        + "substitutes a literal \"None\" when absent");    }

    @Test
    public void everyLocalizationKeyIsDefinedInAShippedCatalogue() throws Exception {
        Set<String> catalogue = new HashSet<String>();
        catalogue.addAll(keysOf("/assets/stellurgy/lang/en_US.lang", true));
        // The libVulpes and shield packages keep their own registry domains, and their catalogues
        // with them; 1.12 merges every domain's lang file, so a key in any of them resolves.
        catalogue.addAll(keysOf("/assets/libvulpes/lang/en_US.lang", true));
        catalogue.addAll(keysOf("/assets/affs/lang/en_us.lang", true));
        // Vanilla's own catalogue: code may legitimately reuse a vanilla key ("options.on").
        catalogue.addAll(keysOf("/assets/minecraft/lang/en_us.lang", true));

        Path sources = Paths.get("src", "main", "java");
        assertTrue("source tree not found at " + sources.toAbsolutePath()
                        + " — this test scans sources from the project directory, so it "
                        + "must run with the project root as its working directory",
                Files.isDirectory(sources));

        Map<String, String> misses = new LinkedHashMap<String, String>();
        for (Path file : javaFilesUnder(sources)) {
            String body = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            for (Pattern call : CALLS) {
                Matcher m = call.matcher(body);
                while (m.find()) {
                    String key = m.group(1);
                    if (isSkippable(key) || catalogue.contains(key)) {
                        continue;
                    }
                    misses.put(key, file.toString());
                }
            }
        }

        assertTrue(describe(misses), misses.isEmpty());
    }

    /**
     * A literal is skipped when it cannot be a whole key: a trailing dot marks a
     * concatenation prefix (e.g. {@code "key.controls."}), and a literal with no
     * dot at all is not a translation key. Exempt entries are skipped by name.
     */
    private static boolean isSkippable(String key) {
        return key.endsWith(".") || !key.contains(".") || EXEMPT.containsKey(key);
    }

    private static String describe(Map<String, String> misses) {
        if (misses.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(misses.size()).append(" localization key(s) are handed to a localization "
                + "call but ship in no catalogue, so players see the raw key:\n");
        for (Map.Entry<String, String> e : misses.entrySet()) {
            sb.append("  ").append(e.getKey()).append("   (").append(e.getValue()).append(")\n");
        }
        sb.append("Fix by pointing the code at an existing key, or by adding the key to "
                + "en_US.lang. Prefer the former when a key for the same concept already "
                + "exists: the lang key is the frozen side — the other locales and any "
                + "resource pack are keyed on it, so renaming one silently breaks them all.");
        return sb.toString();
    }

    private static Set<String> keysOf(String resource, boolean required) throws IOException {
        Set<String> keys = new TreeSet<String>();
        InputStream is = LangKeyCrossReferenceTest.class.getResourceAsStream(resource);
        if (required) {
            assertNotNull(resource + " must be on the test classpath", is);
        } else if (is == null) {
            return keys;
        }
        try {
            Scanner sc = new Scanner(is, "UTF-8").useDelimiter("\\A");
            String body = sc.hasNext() ? sc.next() : "";
            for (String line : body.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    keys.add(trimmed.substring(0, eq).trim());
                }
            }
        } finally {
            is.close();
        }
        return keys;
    }

    private static List<Path> javaFilesUnder(Path root) throws IOException {
        final List<Path> files = new ArrayList<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

}
