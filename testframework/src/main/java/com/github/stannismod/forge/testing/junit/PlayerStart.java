package com.github.stannismod.forge.testing.junit;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * What an e2e's player starts with beyond the game as it ships, TYPED — the only way anything reaches an
 * e2e's game directory before its server boots ({@link AbstractPlayerE2ETest#playerStart()}).
 *
 * <p>Three kinds of entry, and nothing else:</p>
 * <ul>
 *   <li>a CONFIG SETTING — a file under {@code config/}, a category, a key and a value. The base writes it
 *       in Forge's configuration syntax, with the type the allowlist gives the key;</li>
 *   <li>a HARVESTED FILE — a file a server booted on the game as it ships writes into its world, copied by
 *       the base under {@code config/} with ONE typed XML insertion: a child element added to the one
 *       element whose attribute has a given value. The base boots that throwaway server itself;</li>
 *   <li>THE OPERATOR — the e2e's player is the server's operator, as the owner who plays on his own server
 *       is. The base writes the server's {@code ops.json} naming him, and nobody else.</li>
 * </ul>
 *
 * <p><b>The allowlist</b> is {@link #grants()} — CODE, so the test JVM cannot rewrite it before the boot (it
 * was a text file on the test classpath until 2026-10-09, and a file under the build directory is one the
 * test can write). Every entry is checked against it before anything is booted, KEY AND VALUE: a grant
 * names the one value (or the values) the entry may set, and an entry it does not grant is refused,
 * loudly. So a test names WHAT its player's start differs in, and the grants say which differences, to
 * which values, any e2e may have. No path into the game directory is handed to a test: no save, no player
 * data, no level file, no config the list does not name.</p>
 *
 * <p>Immutable; each method answers a new start.</p>
 */
public final class PlayerStart {

    private final List<String[]> settings;
    private final List<Harvest> harvests;
    private final boolean operator;

    private PlayerStart(List<String[]> settings, List<Harvest> harvests, boolean operator) {
        this.settings = settings;
        this.harvests = harvests;
        this.operator = operator;
    }

    /** The game as it ships: nothing written before the boot. */
    public static PlayerStart asShipped() {
        return new PlayerStart(Collections.<String[]>emptyList(), Collections.<Harvest>emptyList(), false);
    }

    /** This start with {@code key} of {@code category} in {@code config/<file>} set to {@code value}. */
    public PlayerStart config(String file, String category, String key, String value) {
        List<String[]> more = new ArrayList<>(settings);
        more.add(new String[]{file, category, key, value});
        return new PlayerStart(Collections.unmodifiableList(more), harvests, operator);
    }

    /**
     * This start with the world file {@code worldFile} of a server booted on the game as it ships, copied
     * to the config file the allowlist pairs it with, after {@code insertion}.
     */
    public PlayerStart harvested(String worldFile, XmlInsertion insertion) {
        List<Harvest> more = new ArrayList<>(harvests);
        more.add(new Harvest(worldFile, insertion));
        return new PlayerStart(settings, Collections.unmodifiableList(more), operator);
    }

    /** This start with its player as the server's operator. */
    public PlayerStart asTheServersOperator() {
        return new PlayerStart(settings, harvests, true);
    }

    /**
     * One child element added to the ONE element named {@code element} whose {@code attribute} is
     * {@code value} — refused when there is not exactly one such element, or it already has that child.
     */
    public static final class XmlInsertion {
        final String element;
        final String attribute;
        final String value;
        final String child;
        final String text;

        private XmlInsertion(String element, String attribute, String value, String child, String text) {
            this.element = element;
            this.attribute = attribute;
            this.value = value;
            this.child = child;
            this.text = text;
        }

        /** Insert {@code <child>text</child>} under the {@code <element attribute="value">}. */
        public static XmlInsertion under(String element, String attribute, String value, String child,
                                         String text) {
            return new XmlInsertion(element, attribute, value, child, text);
        }

        @Override
        public String toString() {
            return "<" + child + ">" + text + "</" + child + "> under <" + element + " " + attribute + "=\""
                    + value + "\">";
        }
    }

    private static final class Harvest {
        final String worldFile;
        final XmlInsertion insertion;

        Harvest(String worldFile, XmlInsertion insertion) {
            this.worldFile = worldFile;
            this.insertion = insertion;
        }
    }

    /** Whether the base must boot a server on the game as it ships to read a file of its world. */
    boolean needsHarvest() {
        return !harvests.isEmpty();
    }

    /**
     * Check every entry against the project's allowlist, and answer the config settings — and the operator
     * list, when the start makes him one — as files of the game directory. Refuses, before anything is booted,
     * an entry the list does not grant.
     */
    Map<String, String> settingFiles() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        List<Grant> allowed = grants();
        Map<String, Map<String, List<String>>> byFile = new LinkedHashMap<>();
        for (String[] s : settings) {
            Grant grant = configGrant(allowed, s[0], s[1], s[2]);
            if (grant == null) {
                throw new IllegalArgumentException("REFUSED: the player's start may not set " + s[1] + "." + s[2]
                        + " in config/" + s[0] + " -- the grants are " + describe(allowed));
            }
            requireTyped(grant.type, s[3], s[2]);
            if (!grant.values.contains(s[3])) {
                throw new IllegalArgumentException("REFUSED: the player's start may set " + s[1] + "." + s[2]
                        + " only to " + grant.values + ", not '" + s[3] + "' -- " + grant.why);
            }
            byFile.computeIfAbsent(s[0], f -> new LinkedHashMap<>())
                    .computeIfAbsent(s[1], c -> new ArrayList<>()).add(grant.type + ":" + s[2] + "=" + s[3]);
        }
        for (Harvest h : harvests) {
            harvestGrant(allowed, h);
        }
        for (Map.Entry<String, Map<String, List<String>>> file : byFile.entrySet()) {
            StringBuilder text = new StringBuilder("# the e2e's player start, written by its base\n");
            for (Map.Entry<String, List<String>> category : file.getValue().entrySet()) {
                text.append(category.getKey()).append(" {\n");
                for (String line : category.getValue()) {
                    text.append("    ").append(line).append('\n');
                }
                text.append("}\n");
            }
            out.put(underConfig(file.getKey()), text.toString());
        }
        if (operator) {
            if (!operatorGranted(allowed)) {
                throw new IllegalArgumentException("REFUSED: the player's start may not make him the server's operator"
                        + " -- the grants are " + describe(allowed));
            }
            out.put("ops.json", opsFileFor(com.github.stannismod.forge.testing.client.RealClientHarness.CLIENT_USERNAME));
        }
        return out;
    }

    /**
     * The server's operator list naming {@code name} alone, at level 4, by the id an offline-mode server gives
     * him (vanilla's {@code EntityPlayer#getOfflineUUID}: a name-based UUID of {@code "OfflinePlayer:" + name}).
     */
    private static String opsFileFor(String name) {
        UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "[{\"uuid\":\"" + id + "\",\"name\":\"" + name + "\",\"level\":4,\"bypassesPlayerLimit\":false}]";
    }

    private static boolean operatorGranted(List<Grant> grants) {
        for (Grant g : grants) {
            if (g.operator) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every harvested file, read out of the world of {@code shipped} (a server booted on the game as it
     * ships, and stopped), as a file of the game directory's config, after its insertion.
     */
    Map<String, String> harvestFiles(com.github.stannismod.forge.testing.server.ServerRoot shipped)
            throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        List<Grant> allowed = grants();
        for (Harvest h : harvests) {
            String target = harvestGrant(allowed, h).target;
            String source = com.github.stannismod.forge.testing.server.ServerRoot.LEVEL_NAME + "/" + h.worldFile;
            if (!shipped.exists(source)) {
                throw new IllegalStateException("the server booted on the game as it ships wrote no " + h.worldFile
                        + " in its world (" + shipped + ")");
            }
            Document doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new java.io.StringReader(shipped.readText(source))));
            NodeList candidates = doc.getElementsByTagName(h.insertion.element);
            Element only = null;
            for (int i = 0; i < candidates.getLength(); i++) {
                Element e = (Element) candidates.item(i);
                if (h.insertion.value.equals(e.getAttribute(h.insertion.attribute))) {
                    if (only != null) {
                        throw new IllegalStateException(h.worldFile + " holds more than one element for "
                                + h.insertion);
                    }
                    only = e;
                }
            }
            if (only == null) {
                throw new IllegalStateException(h.worldFile + " holds no element for " + h.insertion);
            }
            if (only.getElementsByTagName(h.insertion.child).getLength() != 0) {
                throw new IllegalStateException(h.worldFile + " already carries a <" + h.insertion.child
                        + "> where " + h.insertion + " would go");
            }
            Element added = doc.createElement(h.insertion.child);
            added.setTextContent(h.insertion.text);
            only.appendChild(added);
            java.io.StringWriter rendered = new java.io.StringWriter();
            javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(
                    new javax.xml.transform.dom.DOMSource(doc),
                    new javax.xml.transform.stream.StreamResult(rendered));
            out.put(underConfig(target), rendered.toString());
            System.out.println("[player start] " + h.worldFile + " of the game as it ships, with " + h.insertion
                    + ", as config/" + target);
        }
        return out;
    }

    /** {@code config/<file>}, refused unless it names a file under {@code config/}. */
    private static String underConfig(String file) {
        Path config = Paths.get("config");
        Path target = config.resolve(file).normalize();
        if (Paths.get(file).isAbsolute() || !target.startsWith(config) || target.equals(config)) {
            throw new IllegalArgumentException("REFUSED: config/" + file + " is not a file under config/");
        }
        return target.toString().replace('\\', '/');
    }

    private static void requireTyped(String type, String value, String key) {
        boolean fits;
        switch (type) {
            case "B":
                fits = "true".equals(value) || "false".equals(value);
                break;
            case "I":
                fits = value.matches("-?\\d+");
                break;
            case "D":
                fits = value.matches("-?\\d+(\\.\\d+)?");
                break;
            case "S":
                fits = value.indexOf('\n') < 0 && value.indexOf('\r') < 0;
                break;
            default:
                fits = false;
        }
        if (!fits) {
            throw new IllegalArgumentException("REFUSED: " + key + " is " + type + " and '" + value
                    + "' is not one");
        }
    }

    /**
     * One permission: a CONFIG setting (file under {@code config/}, category, type, key, the values it may
     * take) or a HARVEST (world file, the config file it goes to, the element / attribute / attribute value
     * it is inserted under, the child, the text it may carry), each with its reason.
     */
    static final class Grant {
        /** The player as the server's operator; no file, key or value of its own. */
        final boolean operator;
        final boolean config;
        final String file;
        final String target;
        final String category;
        final String type;
        final String key;
        final String attribute;
        final String attributeValue;
        final List<String> values;
        final String why;

        private Grant(boolean config, String file, String target, String category, String type, String key,
                      String attribute, String attributeValue, List<String> values, String why) {
            this(false, config, file, target, category, type, key, attribute, attributeValue, values, why);
        }

        private Grant(boolean operator, boolean config, String file, String target, String category, String type,
                      String key, String attribute, String attributeValue, List<String> values, String why) {
            this.operator = operator;
            this.config = config;
            this.file = file;
            this.target = target;
            this.category = category;
            this.type = type;
            this.key = key;
            this.attribute = attribute;
            this.attributeValue = attributeValue;
            this.values = values;
            this.why = why;
        }

        /** The player as the server's operator, with its reason. */
        static Grant theOperator(String why) {
            return new Grant(true, false, "ops.json", null, null, null, null, null, null,
                    Collections.<String>emptyList(), why);
        }

        @Override
        public String toString() {
            if (operator) {
                return "the player as the server's operator";
            }
            return config ? "config/" + file + " " + category + "." + type + ":" + key + " in " + values
                    : file + " -> config/" + target + " <" + key + "> under <" + category + " " + attribute + "=\""
                    + attributeValue + "\"> in " + values;
        }
    }

    /**
     * What a player's start may differ in from the game as it ships, and to which values — a new list on
     * every call, so nothing holds the grants but this method's body. A grant is a ruling that EVERY e2e may
     * start with that difference; add one only with the reason a player's own start can carry it.
     */
    static List<Grant> grants() {
        List<Grant> out = new ArrayList<>();
        // Fuel adequacy on the pad is a mechanic of its own with its own tests; M1's loop is not about it, and
        // leaving it on would fail the loop for a reason that has nothing to do with the loop. Off only.
        out.add(new Grant(true, "advRocketry/stellurgy.cfg", null, "rockets", "B", "rocketsRequireFuel", null, null,
                Collections.singletonList("false"),
                "fuel adequacy is a mechanic of its own; the start may turn it off, never set anything else"));
        // A planet's atmosphere ceiling, stated the one way a world states it: <orbitHeight> in the planet
        // file, for the HOME world only, at the lowest line production accepts (the top of the build band).
        // It shortens the first climb from minutes to seconds and does not touch the crossing's predicate.
        // The file is the one a server on the game as it ships writes for its own built-in universe, so the
        // copy cannot drift from what the mod ships; only the stated line is added.
        out.add(new Grant(false, "advRocketry/planetDefs.xml", "advRocketry/planetDefs.xml", "planet", null,
                "orbitHeight", "DIMID", "0",
                Collections.singletonList(Integer.toString(dev.stannismod.stellurgy.space.TerrainHeightFinder.MAX_BUILD_Y)),
                "the home world's orbit line, at the lowest line production accepts, and on no other planet"));
        // The owner who plays on his own server is its operator, and an operator's commands (`/time add`, the
        // universe's skip) are his acts. Ruled 2026-10-25 for the satellite path, whose dose takes days of game
        // time: "Игрок — оператор".
        out.add(Grant.theOperator("the owner who plays on his own server is its operator"));
        return out;
    }

    private static Grant configGrant(List<Grant> grants, String file, String category, String key) {
        for (Grant g : grants) {
            if (g.config && g.file.equals(file) && g.category.equals(category) && g.key.equals(key)) {
                return g;
            }
        }
        return null;
    }

    /** The grant {@code h} is taken under; refuses, loudly, a harvest no grant covers in key and value. */
    private static Grant harvestGrant(List<Grant> grants, Harvest h) {
        XmlInsertion in = h.insertion;
        for (Grant g : grants) {
            if (!g.config && !g.operator && g.file.equals(h.worldFile) && g.category.equals(in.element)
                    && g.attribute.equals(in.attribute) && g.key.equals(in.child)) {
                if (!g.attributeValue.equals(in.value) || !g.values.contains(in.text)) {
                    throw new IllegalArgumentException("REFUSED: the player's start may take " + h.worldFile + " only"
                            + " as " + g + ", not with " + in + " -- " + g.why);
                }
                return g;
            }
        }
        throw new IllegalArgumentException("REFUSED: the player's start may not take " + h.worldFile + " with " + in
                + " -- the grants are " + describe(grants));
    }

    private static String describe(List<Grant> grants) {
        return grants.toString();
    }
}
