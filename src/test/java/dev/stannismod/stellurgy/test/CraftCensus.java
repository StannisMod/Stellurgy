package dev.stannismod.stellurgy.test;

import com.github.stannismod.forge.testing.server.TestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * The shared bases' judgement that a scenario left no craft behind: after every scenario the server
 * holds ZERO ships and ZERO rockets.
 *
 * <p>A test never counts or searches crafts. It holds the id of every craft it built — from the record
 * of its own assembly — and disposes of each by that id. This is the one place that asks the server
 * how many crafts there are, and it asks for one answer only: none. The shared server outlives the
 * scenario, so a craft left behind would stand, and move, in the next scenario's world.</p>
 *
 * <p><b>What it does with a leak.</b> It fails the scenario that left it, naming every craft, and it
 * disposes of them first, so the failure stays with that scenario instead of landing on whichever
 * runs next. A leaked ship is confirmed gone by its own {@code DESTROYED} unnaming, a leaked rocket by
 * the removal's reply.</p>
 *
 * <p>SILENT about what the census cannot see: a world that is not loaded, and a rocket in an unloaded
 * chunk of a loaded one.</p>
 */
public final class CraftCensus {

    /** The census verb — a READ, and the only caller allowed to send it is this class. */
    public static final String CENSUS = "stellurgytest crafts census";

    /** Ticks the substrate gets to collect a ship marked finished: it does so on its next tick. */
    private static final int COLLECTION_TICKS = 40;

    private CraftCensus() {
    }

    /**
     * Fail when the server {@code server} holds any craft after {@code scenario}, having disposed of
     * every one it found. {@code events} reads that server's log.
     */
    public static void requireNoneLeft(String scenario, TestClient server, Events events) throws Exception {
        Reply census = Reply.answering(CENSUS, server.read(CENSUS)).requireOk("the craft census");
        String[] ships = census.objectArrayOrEmpty("ships");
        String[] rockets = census.objectArrayOrEmpty("rockets");
        if (ships.length == 0 && rockets.length == 0) {
            return;
        }
        List<String> problems = new ArrayList<>();
        long mark = events.mark();
        List<String> marked = new ArrayList<>();
        for (String row : ships) {
            Reply entry = Reply.of(CENSUS, row);
            int dim = entry.integer("dim");
            Reply ship = Reply.of(CENSUS, entry.object("ship"));
            String id = ship.text("id");
            String destroy = "stellurgytest vs destroy-ship " + dim + " " + id;
            Reply destroyed = Reply.answering(destroy, server.arrange(destroy));
            if (!destroyed.ok() || !destroyed.bool("found")) {
                problems.add("ship " + id + " in dim " + dim + " could not be marked finished: " + destroyed);
                continue;
            }
            marked.add(id);
        }
        for (String id : marked) {
            try {
                events.awaitRecordWithFields(mark, "ship_lifecycle",
                        "the base's disposal of leaked ship " + id, COLLECTION_TICKS,
                        "ship", id, "cause", "DESTROYED");
            } catch (AssertionError notCollected) {
                problems.add("ship " + id + " was marked finished and not collected: "
                        + notCollected.getMessage());
            }
        }
        for (String row : rockets) {
            Reply rocket = Reply.of(CENSUS, row);
            String remove = "stellurgytest rocket remove " + rocket.integer("e");
            Reply removed = Reply.answering(remove, server.arrange(remove));
            if (!removed.ok() || !removed.boolOr("removed", false)) {
                problems.add("rocket " + rocket.integer("e") + " could not be removed: " + removed);
            }
        }
        throw new AssertionError(scenario + " left crafts behind — every scenario disposes of the crafts"
                + " it built, by their ids. Ships: " + java.util.Arrays.toString(ships)
                + "; rockets: " + java.util.Arrays.toString(rockets)
                + (problems.isEmpty()
                        ? ". The base disposed of all of them, so the next scenario starts clean."
                        : ". The base could NOT dispose of all of them, so the next scenario may meet them: "
                                + problems));
    }
}
