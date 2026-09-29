package zmaster587.advancedRocketry.command.test;

/**
 * Where a queued tier-2 ship dies inside Valkyrien Skies' own spawn pass.
 *
 * <h2>What it answers</h2>
 *
 * <p>A ship that is queued and named and never appears in the registry has three possible fates,
 * and from outside the JVM they are indistinguishable: the spawn was <b>never processed</b>, it was
 * <b>processed but dropped before {@code addShip}</b>, or it was <b>added and then destroyed</b>.
 * VS's own abort gate prints to {@code System.err}, which the harness does not forward, so its
 * reason is invisible too. These readings separate all of it: {@code runs} vs {@code returns}
 * (a mismatch means the pass exited by THROW), {@code maxShips} (&ge;1 means it did enter the
 * registry at least momentarily), and the flood triple ({@code foundSetSize} / {@code cleanHouse} /
 * {@code blacklistSize}) — the two inputs to the "Ship too big or bedrock detected!" abort plus the
 * state of the blacklist it floods against.</p>
 *
 * <h2>Why it lives here</h2>
 *
 * <p>These used to be mutable statics on {@code VSIntegration}, written by a PRODUCTION mixin, in a
 * shipped game, for a reader that only exists in a test. They are the same readings; what changed is
 * that both halves are now test-side — the store is in the probe's own tree, and the injections that
 * feed it are in a test-only mixin. A released jar carries neither.</p>
 *
 * <p>Written from the mixin's own thread (VS's spawn pass runs on the server thread) and read from a
 * command handler on the same thread; {@code volatile} anyway, because a harness reads these across
 * a JVM boundary and a stale value would read as a finding.</p>
 */
public final class SpawnDiag {

    /** Times VS's spawn pass ran with a non-empty spawn queue since the last reset. */
    public static volatile long spawnNewShipsRuns;
    /** Times it RETURNED NORMALLY. {@code runs > returns} ⇒ it exited by throw. */
    public static volatile long spawnNewShipsReturns;
    /** Spawn-queue size seen at the last entry: how many spawns VS tried to process. */
    public static volatile int lastSpawnQueueSize;
    /** Max queryable-ship count observed at a spawn RETURN since reset — see the class note. */
    public static volatile int spawnDiagMaxShips;

    /** Flood block count at the last spawn attempt (what VS's size abort tests against). */
    public static volatile int lastFoundSetSize = -1;
    /** Whether the last flood reached bedrock — the other leg of the same abort. */
    public static volatile boolean lastCleanHouse;
    /** VS's spawn-blacklist size at the last flood. A small value means it was caught mid-rebuild
     *  ({@code syncWithConfig} clears then repopulates non-atomically), so AIR was floodable and the
     *  flood escaped into terrain. {@code -1} = never read. */
    public static volatile int lastBlacklistSize = -1;
    /** WHERE an escaped flood went: the found set's bbox and the block at its farthest corner. */
    public static volatile String lastFloodShape = "";

    private SpawnDiag() { }

    /** Clear everything. Called by the probe before an assembly under test. */
    public static void reset() {
        spawnNewShipsRuns = 0L;
        spawnNewShipsReturns = 0L;
        lastSpawnQueueSize = 0;
        spawnDiagMaxShips = 0;
        lastFoundSetSize = -1;
        lastCleanHouse = false;
        lastBlacklistSize = -1;
        lastFloodShape = "";
    }

    /** From the test mixin at the spawn pass's entry, with the current queue size. */
    public static void noteSpawnEntry(int queueSize) {
        if (queueSize > 0) {
            spawnNewShipsRuns++;
            lastSpawnQueueSize = queueSize;
        }
    }

    /** From the test mixin at the spawn pass's NORMAL return (never on a throw). */
    public static void noteSpawnReturn() {
        spawnNewShipsReturns++;
    }

    /** From the test mixin at the spawn pass's return, with the queryable-ship count. */
    public static void noteQueryableCount(int count) {
        if (count > spawnDiagMaxShips) {
            spawnDiagMaxShips = count;
        }
    }

    /** From the test mixin, right after VS builds its flood detector. */
    public static void noteDetector(int foundSetSize, boolean cleanHouse, int blacklistSize) {
        lastFoundSetSize = foundSetSize;
        lastCleanHouse = cleanHouse;
        lastBlacklistSize = blacklistSize;
    }

    /** From the test mixin, for a huge flood only, with the geometry it already formatted. */
    public static void noteFloodShape(String shape) {
        lastFloodShape = shape;
    }
}
