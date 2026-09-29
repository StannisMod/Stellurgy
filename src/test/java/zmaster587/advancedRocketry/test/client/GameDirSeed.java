package zmaster587.advancedRocketry.test.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a class needs to be in the game directory BEFORE its server boots — declared as VALUES, never
 * as a file.
 *
 * <h2>The distinction this type exists to make</h2>
 *
 * <p>"This class writes its own {@code advancedRocketry.cfg}" was recorded for a long time as a
 * reason a class could not share a harness. It is not: what makes two such classes unmergeable is
 * that each writes the WHOLE file and so erases the other's settings — a property of how the config
 * is expressed, not of what the tests need. Measured 2026-08-23 across the four client classes that
 * carried that justification: one wants {@code spaceCellPoolSize=1}, two want the same
 * {@code orbitHeight=255}, and the only key with two opposite values
 * ({@code allowTimeSkipOnPlanets}) is already on the runtime whitelist of
 * {@code artest config set}, whose own comment says flipping it at runtime is how BOTH sides of a
 * flag get exercised in one server. Nothing there was in conflict.</p>
 *
 * <p>So a class states KEYS. Two classes seeding one directory can then be merged, and a real
 * disagreement — the same key with two different values — is a loud failure at seed time rather
 * than a silent last-writer-wins.</p>
 *
 * <p>A seed that nobody added anything to is EMPTY, and an empty seed means the harness boots in a
 * bare temp directory exactly as it always has: this type changes nothing for a class that declares
 * nothing.</p>
 */
public final class GameDirSeed {

    /** section -> (typed key -> value), preserving declaration order so the file reads sensibly. */
    private final Map<String, Map<String, String>> config = new LinkedHashMap<>();
    /** Which class asked for each key, so a clash can name both sides. */
    private final Map<String, String> declaredBy = new LinkedHashMap<>();
    private String planetDefs;
    private String planetDefsDeclaredBy;

    /**
     * One config key, in Forge's own {@code advancedRocketry.cfg} spelling.
     *
     * @param section the config section, e.g. {@code performance}, {@code rockets}, {@code planet}
     * @param typedKey the key WITH its Forge type prefix, e.g. {@code I:spaceCellPoolSize},
     *                 {@code B:allowTimeSkipOnPlanets}
     * @param value    the value as it appears in the file
     * @param declarer who is asking — used only to describe a clash
     */
    public GameDirSeed config(String section, String typedKey, Object value, Class<?> declarer) {
        String rendered = String.valueOf(value);
        String path = section + "/" + typedKey;
        Map<String, String> keys = config.get(section);
        if (keys == null) {
            keys = new LinkedHashMap<>();
            config.put(section, keys);
        }
        String previous = keys.put(typedKey, rendered);
        String previousDeclarer = declaredBy.put(path, declarer.getName());
        if (previous != null && !previous.equals(rendered)) {
            throw new IllegalStateException("two declarations disagree about " + path + ": "
                    + previousDeclarer + " asked for " + previous + ", " + declarer.getName()
                    + " asks for " + rendered + ". Two values for one key is the ONE thing a merged"
                    + " seed cannot resolve — either they are the same test family and one value is"
                    + " wrong, or the key must be flipped per scenario at runtime (artest config"
                    + " set) instead of seeded.");
        }
        return this;
    }

    /**
     * The whole {@code planetDefs.xml}, because a galaxy is one document and a planet's meaning
     * depends on the star it hangs off. A family that needs several planets states them in ONE
     * catalogue; two classes each declaring a different catalogue is a clash, and says so.
     */
    public GameDirSeed planetDefs(String xml, Class<?> declarer) {
        if (planetDefs != null && !planetDefs.equals(xml)) {
            throw new IllegalStateException("two declarations disagree about planetDefs.xml: "
                    + planetDefsDeclaredBy + " and " + declarer.getName() + " each state a whole"
                    + " galaxy. Merge them into one catalogue — the dimension ids and star names are"
                    + " what must not collide, and a single file is where that can be checked.");
        }
        planetDefs = xml;
        planetDefsDeclaredBy = declarer.getName();
        return this;
    }

    public boolean isEmpty() {
        return config.isEmpty() && planetDefs == null;
    }

    /** Write what was declared into {@code root}, and return a one-line description for the log. */
    String writeInto(Path root) throws IOException {
        Path arConfigDir = root.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        StringBuilder described = new StringBuilder();
        if (!config.isEmpty()) {
            StringBuilder cfg = new StringBuilder("# seeded by the shared client harness\n");
            for (Map.Entry<String, Map<String, String>> section : config.entrySet()) {
                cfg.append(section.getKey()).append(" {\n");
                for (Map.Entry<String, String> key : section.getValue().entrySet()) {
                    cfg.append("    ").append(key.getKey()).append('=').append(key.getValue())
                            .append('\n');
                    described.append(' ').append(section.getKey()).append('/').append(key.getKey())
                            .append('=').append(key.getValue());
                }
                cfg.append("}\n");
            }
            Files.write(arConfigDir.resolve("advancedRocketry.cfg"),
                    cfg.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (planetDefs != null) {
            Files.write(arConfigDir.resolve("planetDefs.xml"),
                    planetDefs.getBytes(StandardCharsets.UTF_8));
            described.append(" planetDefs.xml(").append(planetDefsDeclaredBy).append(')');
        }
        return described.toString();
    }
}
