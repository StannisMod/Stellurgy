package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest dim info <dim>} — what the server knows about one dimension.
 *
 * <p>Measured 2026-09-17: seven classes read this reply, spelling fifteen field names between them,
 * and the same three idioms recurred — a {@code contains} on a rendered pair, a {@code contains} on
 * a bare field name to mean "the probe reports it at all", and a NEGATED {@code contains} on the
 * four characters {@code "null"} to mean "this world has one".</p>
 *
 * <h2>The reply has THREE blocks and two of them are conditional</h2>
 *
 * <p>The head ({@code dim}, {@code loaded}, {@code isARPlanet}) is always there. The SPAWN block
 * exists only for a loaded world. The PLANET block ({@code name}, {@code terrainSource},
 * {@code gravity}, …) exists only for a dimension AR has properties for. A caller reaching for
 * {@code gravity} on a nether reply is not told it asked the wrong question — the field is simply
 * absent — so the blocks are entry points here, each refusing when its block is not present.</p>
 *
 * <h2>{@code "null"} is a WORD in this reply, not an absence</h2>
 *
 * <p>Where a world or its collaborator does not exist, the producer writes the four-character string
 * {@code "null"} rather than leaving the field out. Every class that reads a class name from here
 * had to know that, and each spelled the knowledge itself. So the class-name and path accessors
 * below REFUSE that value: {@code biomeProviderClass()} answers a real class name or throws, and
 * "the world has no biome provider" is a question asked as {@link #loaded} instead.</p>
 *
 * <h2>{@code worldType} is reported beside the OVERWORLD's on purpose</h2>
 *
 * <p>A secondary world's {@code WorldInfo} delegates both {@code worldType} and
 * {@code generatorOptions}, so a planet can silently publish the SAVE's identity. The failure mode
 * is never "a wrong name", it is "somebody else's name" — which is only visible as a pair, and is
 * why both are carried.</p>
 */
public final class DimInfo {

    /** How this reader reaches the probe. The callers sit under three different bases. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** What the producer writes where a thing does not exist. See the class note. */
    private static final String ABSENT = "null";

    /** The dimension this reading is about, as the reply echoed it back. */
    public final int dim;
    /** Whether a {@code WorldServer} for it is loaded right now. */
    public final boolean loaded;
    /** Whether AR minted this dimension — the question {@code isDimensionCreated} answers. */
    public final boolean arPlanet;

    private final Reply reply;
    private final String raw;

    private DimInfo(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.dim = reply.integer("dim");
        this.loaded = reply.bool("loaded");
        this.arPlanet = reply.bool("isARPlanet");
    }

    /**
     * Read one {@code dim info} reply, or refuse.
     *
     * <p>Refuses the {@code {"error":"invalid dim id"}} shape — an id the probe could not parse
     * answers nothing about any world, and every field a caller then reads is absent, which reads
     * as "not loaded, not a planet, no spawn".</p>
     */
    public static DimInfo of(String infoReply) {
        String text = String.valueOf(infoReply);
        Reply reply = Reply.of("artest dim info", text);
        if (reply.has("error") || !reply.has("loaded")) {
            throw new AssertionError("`artest dim info` did not answer about a dimension, so"
                    + " nothing here is a reading of one — an unloaded world and an unparsable id"
                    + " look the same to every field below: " + text);
        }
        return new DimInfo(reply, text);
    }

    /** Ask the server about one dimension. */
    public static DimInfo forDim(Probe probe, int dim) throws Exception {
        return of(probe.exec("artest dim info " + dim));
    }

    /** The world provider's FQN — {@code WorldProviderPlanet} for an AR planet. */
    public String providerClass() {
        return present("providerClass");
    }

    /** The biome provider's FQN. */
    public String biomeProviderClass() {
        return present("biomeProviderClass");
    }

    /** The chunk generator's FQN — what {@code terrainSource} selects. */
    public String chunkGeneratorClass() {
        return present("chunkGeneratorClass");
    }

    /** The world's save folder, relative to the save root. */
    public String saveDir() {
        return present("saveDir");
    }

    /** The world type this dimension PUBLISHES through the vanilla {@code WorldInfo} API. */
    public String worldType() {
        return present("worldType");
    }

    /** Its generator options string — empty is a legitimate value, {@code "null"} is not. */
    public String generatorOptions() {
        return present("generatorOptions");
    }

    /** The overworld's own world type, for the comparison the class note describes. */
    public String overworldWorldType() {
        return present("overworldWorldType");
    }

    /** The overworld's own generator options, for that same comparison. */
    public String overworldGeneratorOptions() {
        return present("overworldGeneratorOptions");
    }

    /** Whether the reply carries the spawn block at all — it does exactly when the world is loaded. */
    public boolean hasSpawn() {
        return reply.has("spawnX");
    }

    /**
     * The world spawn the SERVER holds, exactly as vanilla packs it into {@code SPacketSpawnPosition}.
     *
     * <p>For an AR planet this is the OVERWORLD's spawn — a {@code DerivedWorldInfo} delegates it —
     * and that is a fact about the dimension rather than a limitation of the probe.</p>
     */
    public int spawnX() {
        return spawn("spawnX");
    }

    public int spawnY() {
        return spawn("spawnY");
    }

    public int spawnZ() {
        return spawn("spawnZ");
    }

    private int spawn(String field) {
        if (!hasSpawn()) {
            throw new AssertionError("dim " + dim + " reports no spawn point, because no world for"
                    + " it is loaded — a zero here would be a coordinate: " + raw);
        }
        return reply.integer(field);
    }

    /** Whether AR holds properties for this dimension — the planet block below exists exactly then. */
    public boolean hasPlanetProperties() {
        return reply.has("terrainSource");
    }

    /** The planet's name as AR holds it. */
    public String planetName() {
        return planetField("name");
    }

    /** Which generator the planet asked for: {@code NATIVE}, {@code TEMPLATE}, {@code MOD_WORLDTYPE}. */
    public String terrainSource() {
        return planetField("terrainSource");
    }

    /** How long its day is, in ticks. */
    public int rotationalPeriod() {
        requirePlanet("rotationalPeriod");
        return reply.integer("rotationalPeriod");
    }

    /** Atmosphere density in AR's own units — 100 is Earth-like. */
    public int atmosphereDensity() {
        requirePlanet("atmosphereDensity");
        return reply.integer("atmosphereDensity");
    }

    /** The gravitational multiplier, where 1.0 is Earth-like. */
    public double gravity() {
        requirePlanet("gravity");
        return reply.number("gravity");
    }

    /** Distance from its star in AR's own units. */
    public int orbitalDistance() {
        requirePlanet("orbitalDistance");
        return reply.integer("orbitalDistance");
    }

    private String planetField(String field) {
        requirePlanet(field);
        return present(field);
    }

    private void requirePlanet(String field) {
        if (!hasPlanetProperties()) {
            throw new AssertionError("AR holds no properties for dim " + dim + ", so `" + field
                    + "` is not a value it has — this is not one of AR's dimensions: " + raw);
        }
    }

    /**
     * A field whose absence, or whose literal {@code "null"}, means the thing does not exist.
     *
     * <p>Refused rather than handed on: every caller that read one of these had to know that
     * {@code "null"} was a word here, and a caller that did not would print it as a class name.</p>
     */
    private String present(String field) {
        String value = reply.text(field);
        if (ABSENT.equals(value)) {
            throw new AssertionError("dim " + dim + " has no `" + field + "` — the probe answers the"
                    + " four characters \"null\" there, which is not a name: " + raw);
        }
        return value;
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "dim " + dim + " loaded=" + loaded + " arPlanet=" + arPlanet
                + (hasPlanetProperties() ? " terrainSource=" + reply.reported("terrainSource") : "");
    }
}
