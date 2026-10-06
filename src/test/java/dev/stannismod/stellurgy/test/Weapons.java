package dev.stannismod.stellurgy.test;

import java.util.List;

/**
 * The links a weapon scenario waits on, read off the records the gun, the round and the damage
 * engine publish — never off a sample of a probe reply taken until it reads the right way.
 *
 * <p>Every verb here names its subject by POSITION (a gun, a block) or by ID (a round), so a sibling
 * scenario's gun firing on the same shared server cannot satisfy this scenario's wait. The records
 * themselves, and what each one is silent about, are documented at their seams:
 * {@code MixinTileTurretEvents}, {@code MixinShotRegistryEvents}, {@code MixinDamageStateEvents},
 * {@code MixinContactResolverEvents} and {@code MixinTileFireControlSensorEvents}.</p>
 *
 * <p><b>The budgets are deadlines, not verdicts.</b> Each wait is on a record that a healthy run
 * always writes; a wait that expires names which of the three silences it met (the recorder never
 * ran, the ring evicted, or the thing never happened), because {@link Events} prints the envelope
 * that says so.</p>
 */
public final class Weapons {

    private Weapons() {
    }

    /**
     * How long an ARRANGEMENT link may take: a gun counting its build, a mount slewing onto a target.
     * The reference gun traverses at its spec's rate (single-digit degrees a tick), so a half-turn is
     * a few dozen ticks; this is a deadline an order of magnitude past that, not an estimate of it.
     */
    public static final int ARRANGEMENT_TICKS = 600;

    /**
     * How long a SUBJECT link may take once its arrangement stands: a gun on target and charged
     * firing, a round in flight ending. Same order-of-magnitude margin, stated separately so a red
     * says which half ran out.
     */
    public static final int SUBJECT_TICKS = 600;

    /**
     * The lifetime a scenario gives a round it waits to see END, in ticks.
     *
     * <p>Shorter than {@link #SUBJECT_TICKS}, so the ending is a record that always comes: a round
     * that punches through its wall and flies on EXPIRES inside the wait rather than outliving it.
     * At the slow bore speed these scenarios fire at (0.45 blocks a tick) it is 180 blocks of flight,
     * far past every wall they build, so it never cuts a bore short.</p>
     */
    public static final int ROUND_LIFETIME_TICKS = 400;

    /** The {@code pos} field every weapon record carries: {@code "x,y,z"}. */
    public static String at(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    /**
     * The gun at {@code (x,y,z)} counted exactly {@code parts} parts and called itself operable —
     * the record of the re-walk after the last part landed.
     */
    public static String awaitAssembled(Events log, long mark, int x, int y, int z, int parts,
                                        String what) throws Exception {
        return log.awaitRecordWithFields(mark, "turret_assembled", what, ARRANGEMENT_TICKS,
                "pos", at(x, y, z), "operable", "true", "parts", String.valueOf(parts));
    }

    /** A round left the gun at {@code (x,y,z)} — the record, which carries the round's id. */
    public static String awaitFired(Events log, long mark, int x, int y, int z, String what)
            throws Exception {
        return log.awaitRecordWithFields(mark, "turret_fired", what, SUBJECT_TICKS,
                "pos", at(x, y, z));
    }

    /** Every round that left the gun at {@code (x,y,z)} since {@code mark}, oldest first. */
    public static List<String> firedSince(Events log, long mark, int x, int y, int z)
            throws Exception {
        String fired = log.since(mark, "turret_fired");
        Events.assertInstrumentRan(fired, "turret_fire_events",
                "the rounds this gun fired since the mark");
        return Events.recordsWhere(fired, "pos", at(x, y, z));
    }

    /**
     * The mount at {@code (x,y,z)} reported itself ON its command — the arrival, from a mark taken
     * before the command was given.
     */
    public static String awaitOnTarget(Events log, long mark, int x, int y, int z, String what)
            throws Exception {
        return log.awaitRecordWithFields(mark, "turret_aim", what, ARRANGEMENT_TICKS,
                "pos", at(x, y, z), "onTarget", "true");
    }

    /** A {@code turret_beam} edge of the gun at {@code (x,y,z)} carrying every pair given. */
    public static String awaitBeam(Events log, long mark, int x, int y, int z, String what,
                                   String... fieldsAndValues) throws Exception {
        String[] all = new String[fieldsAndValues.length + 2];
        all[0] = "pos";
        all[1] = at(x, y, z);
        System.arraycopy(fieldsAndValues, 0, all, 2, fieldsAndValues.length);
        return log.awaitRecordWithFields(mark, "turret_beam", what, SUBJECT_TICKS, all);
    }

    /** The round {@code id} ended — the record, which carries the stated reason. */
    public static String awaitShotEnded(Events log, long mark, long id, String what)
            throws Exception {
        return log.awaitRecordWithField(mark, "shot_ended", "shot", id, what, SUBJECT_TICKS);
    }

    /**
     * Every stage write at {@code (x,y,z)} in dimension {@code dim} since {@code mark}, oldest
     * first — each carries {@code from}, {@code to} and {@code max}.
     */
    public static List<String> stagesSetAt(Events log, long mark, int dim, int x, int y, int z)
            throws Exception {
        return stagesSetAt(log, mark, Long.MAX_VALUE, dim, x, y, z);
    }

    /**
     * The same, closed at {@code until} (a later mark, exclusive) — for a window that has to be
     * judged AFTER a later leg proved the recorder live. A negative claim over a window in which the
     * stage recorder never ran is a claim about nobody looking, so the caller reads the earlier
     * window only once something in the later one has made the recorder execute.
     */
    public static List<String> stagesSetAt(Events log, long mark, long until, int dim, int x, int y,
                                           int z) throws Exception {
        String staged = log.since(mark, "block_stage_set");
        Events.assertInstrumentRan(staged, "damage_stage_events",
                "the stage writes at " + at(x, y, z) + " since the mark");
        List<String> here = Events.recordsWhereAll(staged, "dim", String.valueOf(dim), "pos", at(x, y, z));
        here.removeIf(record -> Events.number(record, "seq") >= until);
        return here;
    }
}
