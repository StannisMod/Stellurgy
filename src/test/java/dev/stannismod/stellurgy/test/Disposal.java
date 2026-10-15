package dev.stannismod.stellurgy.test;

/**
 * A scenario's disposal of the crafts it built, each by its own id — what leaves the shared server
 * holding none, which its base checks after every scenario ({@link CraftCensus}).
 *
 * <p>Called at the END of the scenario that built the craft (its {@code finally}, or an {@code @After}):
 * the base judges after the whole scenario, rules and {@code @After} included, so cleanup that waits for
 * the next scenario's {@code @Before} is cleanup that has not happened.</p>
 */
public final class Disposal {

    /** Ticks the substrate gets to collect a ship marked finished: it does so on its next tick. */
    private static final int COLLECTION_TICKS = 40;

    private Disposal() {
    }

    /**
     * Mark the ship {@code shipUuid} of {@code dim} finished and return once the substrate has
     * announced it {@code DESTROYED}. {@code arrange} sends in the ARRANGE role; {@code events} reads
     * the same server's log.
     */
    public static void ship(Events.Probe arrange, Events events, int dim, String shipUuid) throws Exception {
        long mark = events.mark();
        String command = "stellurgytest vs destroy-ship " + dim + " " + shipUuid;
        Reply marked = Reply.of(command, arrange.exec(command)).requireOk("disposing of ship " + shipUuid);
        if (!marked.bool("found")) {
            throw new AssertionError("dim " + dim + " registers no ship " + shipUuid
                    + " to dispose of — the scenario's id for its own craft is wrong: " + marked);
        }
        events.awaitRecordWithFields(mark, "ship_lifecycle", "the disposal of ship " + shipUuid,
                COLLECTION_TICKS, "ship", shipUuid, "cause", "DESTROYED");
    }

    /**
     * Remove the rocket entity {@code entityId}, riders first. The entity is dead when the reply says
     * {@code removed}; the world drops it on its next tick.
     */
    public static void rocket(Events.Probe arrange, int entityId) throws Exception {
        String command = "stellurgytest rocket remove " + entityId;
        Reply removed = Reply.of(command, arrange.exec(command)).requireOk("removing rocket " + entityId);
        if (!removed.bool("found") || !removed.bool("removed")) {
            throw new AssertionError("rocket " + entityId + " was not removed — the scenario's id for its"
                    + " own rocket is wrong, or the rocket is gone already: " + removed);
        }
    }
}
