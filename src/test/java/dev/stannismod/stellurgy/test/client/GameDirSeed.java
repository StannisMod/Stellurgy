package dev.stannismod.stellurgy.test.client;

import com.github.stannismod.forge.testing.server.StartFiles;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a class needs to be in the game directory BEFORE its server boots — declared as VALUES, never
 * as a file.
 *
 * <h2>The distinction this type exists to make</h2>
 *
 * <p>"This class writes its own {@code stellurgy.cfg}" was recorded for a long time as a
 * reason a class could not share a harness. It is not: what makes two such classes unmergeable is
 * that each writes the WHOLE file and so erases the other's settings — a property of how the config
 * is expressed, not of what the tests need. Measured 2026-08-23 across the four client classes that
 * carried that justification: one wants {@code spaceCellPoolSize=1}, two want the same
 * {@code orbitHeight=255}, and the only key with two opposite values
 * ({@code allowTimeSkipOnPlanets}) is already on the runtime whitelist of
 * {@code stellurgytest config set}, whose own comment says flipping it at runtime is how BOTH sides of a
 * flag get exercised in one server. Nothing there was in conflict.</p>
 *
 * <p>So a class states KEYS. Two classes seeding one directory can then be merged, and a real
 * disagreement — the same key with two different values — is a loud failure at seed time rather
 * than a silent last-writer-wins.</p>
 *
 * <p>A seed that nobody added anything to is EMPTY, and an empty seed means the harness boots in a
 * bare temp directory exactly as it always has: this type changes nothing for a class that declares
 * nothing.</p>
 *
 * <p>It is the typed START of every Stellurgy server the tests boot — the shared bases' and an own boot's
 * alike: the harness writes {@link #files()} before the first boot ({@code ServerRoot#seed}), and nothing
 * else writes a game directory's start.</p>
 */
public final class GameDirSeed implements StartFiles {

    /** Where the mod reads its config and its planet catalogue, relative to the game directory. */
    private static final String CONFIG_DIR =
            "config/" + dev.stannismod.stellurgy.api.StellurgyConfiguration.configFolder + "/";

    /** section -> (typed key -> value), preserving declaration order so the file reads sensibly. */
    private final Map<String, Map<String, String>> config = new LinkedHashMap<>();
    /** Which class asked for each key, so a clash can name both sides. */
    private final Map<String, String> declaredBy = new LinkedHashMap<>();
    private String planetDefs;
    private String planetDefsDeclaredBy;
    private String thermalTable;
    private String thermalTableDeclaredBy;
    private String operator;

    /**
     * The seed every shared harness world starts from: fuel is not required to fly
     * ({@code rockets/rocketsRequireFuel=false}). A rocket's fuel adequacy is judged against the climb
     * to its world's orbit line, which is a body's own atmosphere — 100 000 blocks on Earth — and a
     * scenario whose subject is not fuel must not stand or fall on whether a fixture's tanks reach it.
     * A scenario whose subject IS a fuel gate turns the requirement on at runtime
     * ({@code stellurgytest config set rocketRequireFuel true}) and restores it.
     */
    public static GameDirSeed forTheSharedHarness() {
        return new GameDirSeed().config("rockets", "B:rocketsRequireFuel", false, GameDirSeed.class);
    }

    /**
     * One config key, in Forge's own {@code stellurgy.cfg} spelling.
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
                    + " wrong, or the key must be flipped per scenario at runtime (stellurgytest config"
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

    /**
     * The whole {@code thermalMaterials.json} a pack ships, because the table is one document the mod
     * merges with its own rows at start. Two classes each declaring a different table is a clash, and
     * says so.
     */
    public GameDirSeed thermalTable(String json, Class<?> declarer) {
        if (thermalTable != null && !thermalTable.equals(json)) {
            throw new IllegalStateException("two declarations disagree about thermalMaterials.json: "
                    + thermalTableDeclaredBy + " and " + declarer.getName() + " each state a whole table");
        }
        thermalTable = json;
        thermalTableDeclaredBy = declarer.getName();
        return this;
    }

    /**
     * The harness client's player as the server's operator ({@code ops.json}), so the commands vanilla keeps for
     * an operator reach the {@code CommandEvent} at all: {@code CommandHandler#executeCommand} checks the
     * permission BEFORE it posts the event. For a class whose subject is what a judge on that event decides about
     * a player's own command.
     */
    public GameDirSeed theClientAsOperator() {
        operator = com.github.stannismod.forge.testing.client.RealClientHarness.CLIENT_USERNAME;
        return this;
    }

    public boolean isEmpty() {
        return config.isEmpty() && planetDefs == null && thermalTable == null && operator == null;
    }

    /**
     * What was declared, as the files the harness writes before the first boot
     * ({@code ServerRoot#seed}): {@code config/advRocketry/stellurgy.cfg} for the keys,
     * {@code config/advRocketry/planetDefs.xml} for the catalogue,
     * {@code config/advRocketry/thermalMaterials.json} for the thermal table — each only when declared.
     */
    @Override
    public Map<String, String> files() {
        Map<String, String> files = new LinkedHashMap<>();
        if (!config.isEmpty()) {
            StringBuilder cfg = new StringBuilder("# seeded by the test harness\n");
            for (Map.Entry<String, Map<String, String>> section : config.entrySet()) {
                cfg.append(section.getKey()).append(" {\n");
                for (Map.Entry<String, String> key : section.getValue().entrySet()) {
                    cfg.append("    ").append(key.getKey()).append('=').append(key.getValue())
                            .append('\n');
                }
                cfg.append("}\n");
            }
            files.put(CONFIG_DIR + "stellurgy.cfg", cfg.toString());
        }
        if (planetDefs != null) {
            files.put(CONFIG_DIR + "planetDefs.xml", planetDefs);
        }
        if (thermalTable != null) {
            files.put(CONFIG_DIR + "thermalMaterials.json", thermalTable);
        }
        if (operator != null) {
            files.put("ops.json", com.github.stannismod.forge.testing.junit.PlayerStart.opsFileFor(operator));
        }
        return files;
    }

    /** One line for the log: every declared key with its value, and whose catalogue the planet file is. */
    @Override
    public String describe() {
        StringBuilder described = new StringBuilder();
        for (Map.Entry<String, Map<String, String>> section : config.entrySet()) {
            for (Map.Entry<String, String> key : section.getValue().entrySet()) {
                described.append(' ').append(section.getKey()).append('/').append(key.getKey())
                        .append('=').append(key.getValue());
            }
        }
        if (planetDefs != null) {
            described.append(" planetDefs.xml(").append(planetDefsDeclaredBy).append(')');
        }
        if (thermalTable != null) {
            described.append(" thermalMaterials.json(").append(thermalTableDeclaredBy).append(')');
        }
        if (operator != null) {
            described.append(" ops.json(").append(operator).append(')');
        }
        return described.toString();
    }
}
