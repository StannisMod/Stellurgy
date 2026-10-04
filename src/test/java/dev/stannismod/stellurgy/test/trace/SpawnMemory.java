package dev.stannismod.stellurgy.test.trace;

/**
 * Where a queued tier-2 ship dies inside Valkyrien Skies' own spawn pass — the server's readings,
 * kept in its {@link SideTrace} and fed by {@code MixinWorldServerShipManagerDiag}.
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
 * <p>A test reads it with {@code stellurgytest invoke-static} on {@link #snapshot()} and clears it
 * with {@link #reset()}. Test source set: absent from a released jar.</p>
 */
public final class SpawnMemory {

    /** Times VS's spawn pass ran with a non-empty spawn queue since the last reset. */
    private volatile long spawnNewShipsRuns;
    /** Times it RETURNED NORMALLY. {@code runs > returns} ⇒ it exited by throw. */
    private volatile long spawnNewShipsReturns;
    /** Spawn-queue size seen at the last entry: how many spawns VS tried to process. */
    private volatile int lastSpawnQueueSize;
    /** Max queryable-ship count observed at a spawn RETURN since reset — see the class note. */
    private volatile int maxShips;
    /** Flood block count at the last spawn attempt (what VS's size abort tests against). */
    private volatile int lastFoundSetSize = -1;
    /** Whether the last flood reached bedrock — the other leg of the same abort. */
    private volatile boolean lastCleanHouse;
    /** VS's spawn-blacklist size at the last flood. It used to be rebuilt in place (cleared, then
     *  repopulated), so a small value meant a flood caught mid-rebuild; it is now swapped in whole,
     *  so a small value means a small configured set. {@code -1} = never read. */
    private volatile int lastBlacklistSize = -1;
    /** WHERE an escaped flood went: the found set's bbox and the block at its farthest corner. */
    private volatile String lastFloodShape = "";

    /** The memory of the server this thread runs. */
    public static SpawnMemory here() {
        return SideTrace.here().memory(SpawnMemory.class, SpawnMemory::new);
    }

    /** This server's readings as JSON — the {@code invoke-static} entry. */
    public static String snapshot() {
        SpawnMemory m = here();
        return "{\"spawnNewShipsRuns\":" + m.spawnNewShipsRuns
                + ",\"spawnNewShipsReturns\":" + m.spawnNewShipsReturns
                + ",\"lastSpawnQueueSize\":" + m.lastSpawnQueueSize
                + ",\"maxShips\":" + m.maxShips
                + ",\"lastFoundSetSize\":" + m.lastFoundSetSize
                + ",\"lastCleanHouse\":" + m.lastCleanHouse
                + ",\"lastBlacklistSize\":" + m.lastBlacklistSize
                + ",\"floodShape\":\"" + m.lastFloodShape + "\"}";
    }

    /** Clear this server's readings — the {@code invoke-static} entry, called before an assembly under test. */
    public static String reset() {
        SpawnMemory m = here();
        m.spawnNewShipsRuns = 0L;
        m.spawnNewShipsReturns = 0L;
        m.lastSpawnQueueSize = 0;
        m.maxShips = 0;
        m.lastFoundSetSize = -1;
        m.lastCleanHouse = false;
        m.lastBlacklistSize = -1;
        m.lastFloodShape = "";
        return "reset";
    }

    /** At the spawn pass's entry, with the current queue size. */
    public void noteSpawnEntry(int queueSize) {
        if (queueSize > 0) {
            spawnNewShipsRuns++;
            lastSpawnQueueSize = queueSize;
        }
    }

    /** At the spawn pass's NORMAL return (never on a throw). */
    public void noteSpawnReturn() {
        spawnNewShipsReturns++;
    }

    /** At the spawn pass's return, with the queryable-ship count. */
    public void noteQueryableCount(int count) {
        if (count > maxShips) {
            maxShips = count;
        }
    }

    /** Right after VS builds its flood detector. */
    public void noteDetector(int foundSetSize, boolean cleanHouse, int blacklistSize) {
        lastFoundSetSize = foundSetSize;
        lastCleanHouse = cleanHouse;
        lastBlacklistSize = blacklistSize;
    }

    /** For a huge flood only, with the geometry the mixin already formatted. */
    public void noteFloodShape(String shape) {
        lastFloodShape = shape;
    }
}
