package dev.stannismod.stellurgy.test;

import java.util.List;

/**
 * Where a scenario's own craft is NOW, read off production's naming records of that craft — and its
 * disposal there.
 *
 * <p>A craft that crossed (an entry, a jump, a seam, a descent) is re-registered in the world it
 * arrived in, announced as a {@code ship_lifecycle} naming that carries the durable name the crossing
 * preserved. The latest naming of that durable name since the scenario's build mark therefore says
 * which world holds the craft and under which physics id — a record of the scenario's own acts, never
 * a search of a world. Built for the scenarios whose craft moves between worlds before they end; a
 * craft that stayed where it was built is disposed of by the id its assembly named, through
 * {@link Disposal} directly.</p>
 */
public final class OwnShip {

    private OwnShip() {
    }

    /**
     * The latest {@code named} record of the craft {@code durable} since {@code buildMark}, or
     * {@code null} when no naming of it was recorded — the craft was never built.
     */
    public static String latestNaming(Events events, long buildMark, String durable) throws Exception {
        List<String> named = Events.recordsWhereAll(events.since(buildMark, "ship_lifecycle"),
                "durable", durable, "edge", "named");
        String latest = null;
        double latestSeq = Double.NEGATIVE_INFINITY;
        for (String record : named) {
            double seq = Events.number(record, "seq");
            if (seq > latestSeq) {
                latestSeq = seq;
                latest = record;
            }
        }
        return latest;
    }

    /**
     * Dispose of the craft {@code durable} where its latest naming since {@code buildMark} put it.
     *
     * <p>Does nothing when no naming was recorded (nothing was built), or when that world's registry no
     * longer holds the id the naming carried — asked of that one id ({@code vs ship-registered}): the
     * craft is gone already, or was cut out with no arrival named, and the base's census reports
     * anything such a crossing left. {@code read} and {@code arrange} send in those roles;
     * {@code events} reads the server's log.</p>
     */
    public static void disposeOf(Events.Probe read, Events.Probe arrange, Events events, long buildMark,
                                 String durable) throws Exception {
        String naming = latestNaming(events, buildMark, durable);
        if (naming == null) {
            return;
        }
        String ship = Events.text(naming, "ship");
        int dim = (int) Events.number(naming, "dim");
        String asked = "stellurgytest vs ship-registered " + dim + " " + ship;
        Reply registered = Reply.of(asked, read.exec(asked)).requireOk("ask whether " + ship + " is registered");
        if (!registered.bool("registered")) {
            return;
        }
        Disposal.ship(arrange, events, dim, ship);
    }
}
