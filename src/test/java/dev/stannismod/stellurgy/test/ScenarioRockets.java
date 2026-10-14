package dev.stannismod.stellurgy.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The rockets ONE scenario built, each held by the PERSISTENT uuid its own act's {@code rocket_born}
 * record carries — and their disposal, by those uuids, at the scenario's end.
 *
 * <p>A scenario on a shared base calls {@link #disposeStanding()} from its {@code @After} (or a
 * {@code finally}): the base's census runs after that and fails a scenario that left a rocket behind.
 * Nothing here looks for a rocket; every uuid comes from a record of the scenario's own act.</p>
 *
 * <p>Held by uuid and not by entity id because an entity id does not survive a dimension change: the
 * rocket that arrives is a new entity under a new id with the same uuid. At disposal each uuid is resolved
 * through {@code rocket find-by-uuid}, an id-keyed lookup over every world, which answers the live copy
 * when there is one. A uuid no world holds a live rocket under — exploded, dismantled, spent by the
 * mission it planned, or only the dead copy a dimension change leaves behind — has nothing left to dispose
 * of, and only then is it skipped.</p>
 */
public final class ScenarioRockets {

    private final Events events;
    private final Events.Probe read;
    private final Events.Probe arrange;
    /** The persistent uuid of every rocket the scenario made, in the order it was made. */
    private final List<String> uuids = new ArrayList<>();
    /** {dim, missionId} of each resource mission the scenario started. */
    private final List<long[]> missions = new ArrayList<>();

    /**
     * @param events  the server log the makers' {@code rocket_born} records are read from
     * @param read    sends in the READ role
     * @param arrange sends in the ARRANGE role (disposal is an arrangement)
     */
    public ScenarioRockets(Events events, Events.Probe read, Events.Probe arrange) {
        this.events = events;
        this.read = read;
        this.arrange = arrange;
    }

    /**
     * One for a test whose server commands are {@code commands}, reading that server's log. Built in a
     * field initialiser: nothing here reaches the server until a scenario calls it.
     *
     * <p>The log reader is given no clock: everything this reads is written inside the call that made it
     * (an assembler press is synchronous), so it never waits, and a step asked of it is refused.</p>
     */
    public static ScenarioRockets of(final ServerCommands commands, EvictionReports evictions) {
        Events.Probe read = c -> commands.read(c).raw();
        Events.Step noWait = t -> {
            throw new IllegalStateException("ScenarioRockets reads records written inside the call that"
                    + " made them, and never waits; a wait of " + t + " ticks was asked of it");
        };
        return new ScenarioRockets(new Events(read, noWait, evictions), read, c -> commands.arrange(c).raw());
    }

    /**
     * Press the assembler at {@code builderPos} through {@code act} and answer the entity id of the ONE
     * rocket that press made, read off its {@code rocket_born} record — refusing, with the assemble reply,
     * when the press made none or several.
     */
    public int assembleBuilt(FixtureSite site, Events.Probe act, int[] builderPos) throws Exception {
        long mark = events.markInstrumented();
        String reply = RocketFixture.assembleBuilt(site, act, builderPos);
        List<String> born = allBornAt(mark, builderPos);
        ArrangementFailure.requireArranged("the assembler at " + Arrays.toString(builderPos) + " made "
                + born.size() + " rocket(s), not one: " + born + "; the assemble reply was " + reply,
                born.size() == 1);
        return (int) Events.number(born.get(0), "e");
    }

    /** {@link RocketFixture#placeAt} then {@link #assembleBuilt}: lay through {@code arrange}, press through {@code act}. */
    public int assembleAt(FixtureSite site, Events.Probe act, String variant, int halo, int height,
                          String what) throws Exception {
        return assembleBuilt(site, act, RocketFixture.placeAt(site, arrange, variant, halo, height, what));
    }

    /**
     * The entity id of the ONE rocket the assembler at {@code builderPos} made since {@code mark}
     * ({@link #mark}), held for disposal — for a press this class does not make itself.
     */
    public int bornAt(long mark, int[] builderPos) throws Exception {
        List<String> born = allBornAt(mark, builderPos);
        ArrangementFailure.requireArranged("the assembler at " + Arrays.toString(builderPos) + " made "
                + born.size() + " rocket(s) since the mark, not one: " + born, born.size() == 1);
        return (int) Events.number(born.get(0), "e");
    }

    /**
     * Every rocket the assembler at {@code builderPos} made since {@code mark}, each held for disposal:
     * how many a press made is the size of this list.
     */
    public List<String> allBornAt(long mark, int[] builderPos) throws Exception {
        List<String> born = RocketFixture.rocketsBornAt(events, mark, builderPos);
        for (String record : born) {
            holdBorn(record);
        }
        return born;
    }

    /** Hold the rocket one {@code rocket_born} record of the scenario's own act names, by its uuid. */
    public void holdBorn(String rocketBornRecord) {
        uuids.add(Events.text(rocketBornRecord, "uuid"));
    }

    /** A mark on the server log, taken BEFORE an act whose births are read after it. */
    public long mark() throws Exception {
        return events.markInstrumented();
    }

    /**
     * Every {@code rocket_born} record the completion of mission {@code missionId} wrote since
     * {@code mark} — the rockets that mission returned, each held for disposal. How many it returned is
     * the size of this list.
     */
    public List<String> returnedByMission(long mark, long missionId) throws Exception {
        List<String> born = Events.recordsWhere(events.since(mark, "rocket_born"), "mission",
                String.valueOf(missionId));
        for (String record : born) {
            holdBorn(record);
        }
        return born;
    }

    /**
     * Hold a resource mission this scenario started on {@code dim}: a mission in flight is a rocket that
     * has not come back yet, and left running it returns one into whichever scenario is on the server
     * when its time is up.
     */
    public long missionStarted(int dim, long missionId) {
        missions.add(new long[]{dim, missionId});
        return missionId;
    }

    /**
     * End every held mission that is still running, then dispose of every held rocket that a world still
     * holds alive under its uuid, and forget them all. A mission no longer registered or already dead, and
     * a rocket no world holds alive, are skipped — see the class note.
     */
    public void disposeStanding() throws Exception {
        try {
            for (long[] mission : missions) {
                String command = "stellurgytest mission state " + mission[1];
                Reply state = Reply.of(command, read.exec(command));
                if (state.ok() && !state.bool("isDead")) {
                    String end = "stellurgytest satellite set-dead " + mission[0] + " " + mission[1];
                    Reply.of(end, arrange.exec(end)).requireOk("ending mission " + mission[1]);
                }
            }
            for (String uuid : uuids) {
                String command = "stellurgytest rocket find-by-uuid " + uuid;
                Reply found = Reply.of(command, read.exec(command));
                // `find-by-uuid` answers the live copy first and a dead one only when no live copy
                // exists; a refusal is "no world holds this uuid". Neither of the last two is standing.
                if (found.ok() && !found.bool("isDead")) {
                    Disposal.rocket(arrange, found.integer("entityId"));
                }
            }
        } finally {
            missions.clear();
            uuids.clear();
        }
    }
}
