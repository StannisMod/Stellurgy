package dev.stannismod.stellurgy.test.server;

import org.junit.After;
import org.junit.Test;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The step that was missing: <b>nobody names the target.</b>
 *
 * <p>Everything the gun system could do before this class needed a human in the loop — a linker, a
 * console, a probe. A battery could track what it was told about and could not notice anything. The
 * sensor is the block that closes that, and these two tests are the two things it has to be true of:
 * it finds a hostile on its own, and it cannot hold everything equally well.</p>
 *
 * <h3>Each test carries its own control</h3>
 * <p>"The gun fired" is worthless on its own — a gun fires for a dozen reasons. So the first test
 * watches the SAME battery and the SAME zombie with acquisition switched off and then on, and the
 * second watches the same pair through a listening sensor and then an illuminating one. Only the one
 * variable moves, and the state before it moves is asserted rather than assumed. What the sensor saw
 * is read off its own per-sweep record ({@code sensor_swept}); what the gun decided, off its own fire
 * decision; and that it fired ON THE ACQUISITION, off the round's own record.</p>
 *
 * <h3>Why the site is roofed and floored</h3>
 * <p>A zombie in daylight burns, and a burning body is a beacon — it would sail over any lock
 * threshold and turn the second test green for exactly the wrong reason. Building a box removes the
 * question rather than relying on the world's clock.</p>
 */
public class FireControlSensorE2ETest extends AbstractSharedServerTest {

    /** This class's own site, clear of every other server test's. */
    private static final int X = 9400, Y = 80, Z = 9400;

    /** Where a contact is comfortably lockable by listening alone. */
    private static final int NEAR_TARGET = 18;

    /** Far enough that a cool body's own radiance no longer resolves it. */
    private static final int FAR_TARGET = 60;

    /** Controller + four barrels + two cooling jackets; the controller is not a part. */
    private static final int PARTS = 6;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /** Each key this scenario has set, with the value it held before; emptied by {@link #restoreConfig}. */
    private final java.util.Map<String, String> configBefore = new java.util.LinkedHashMap<>();

    /** Game rules this scenario changed, with the value each held before — restored in {@code @After}. */
    private final java.util.Map<String, String> gameRulesBefore = new java.util.LinkedHashMap<>();

    /**
     * A battery nobody has told anything acquires a hostile that walks into range, and stops doing
     * so the moment acquisition is switched off — which is what says the sensor is the reason.
     *
     * <p>red-witnessed: with {@code enableFireControlSensor} taken out of the sensor's gate
     * ({@code TileFireControlSensor#update} at {@code if (!StellurgyConfiguration.getCurrentConfig().enableFireControlSensor)}), this fails at "the sensor went on running with
     * acquisition switched off — no `sensor_gate_refused` carrying pos = 9401,80,9400 and sensor = false
     * was recorded within 600 ticks" (re-taken 2026-10-03 after the war switch was removed from the same
     * gate, with the condition made never-true at run time). The off window's "fired at
     * something nobody named it" is downstream of that link and was not reached by it: a round in the
     * window needs an acquisition, which needs a sweep past a gate the link proves refused.</p>
     *
     * <p>red-witnessed: with {@code TileFireControlSensor#publish} at {@code state.setAcquiredTrack(contacts.get(0), world.getTotalWorldTime(), hold);} publishing the contact as the
     * network's named target ({@code setTargetEntity}) instead of as an acquisition, this fails at "the
     * gun fired, but not on an acquisition — something else gave it a target: {...acquired:false...}"
     * (2026-09-30).</p>
     */
    @Test
    public void aSensorAcquiresAHostileThatNobodyNamed() throws Exception {
        int base = X;
        buildSite(base);
        long built = events.markInstrumented();
        buildBattery(base);
        Weapons.awaitAssembled(events, built, base, Y, Z, PARTS,
                "the gun never assembled, so its silence would say nothing");
        Reply gun = read(base);
        assertTrue("something has already given this gun a target — then nothing below is about"
                + " acquisition: " + gun, !gun.bool("hasTarget"));

        // The control first: the same battery, the same target, acquisition switched off.
        // The refusal below is recorded on the EDGE into refusing, so the sensor must have been running
        // when the flag is switched off, or the edge is already behind the mark.
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged("acquisition was already"
                + " switched off before this scenario switched it off, so its refusal edge lies behind"
                + " the mark", "true".equals(ask("stellurgytest config get enableFireControlSensor")
                .requireOk("read enableFireControlSensor").text("value")));
        long off = events.mark();
        config("enableFireControlSensor", "false");
        spawnZombie(base + NEAR_TARGET);
        charge(base);
        // The sensor's own gate says it refused: switched off is a decision production takes in the
        // sensor's tick, before any sweep, and the refusal is the record this waits on. It names the
        // flag it read.
        events.awaitRecordWithFields(off, "sensor_gate_refused",
                "the sensor went on running with acquisition switched off — the config flag does not"
                        + " disable the mechanic", Weapons.SUBJECT_TICKS,
                "pos", Weapons.at(base + 1, Y, Z), "sensor", "false");
        Reply silent = read(base);
        assertTrue("a battery with acquisition disabled is holding a contact anyway: " + silent,
                !silent.bool("acquired"));

        // One variable moves.
        long on = events.mark();
        config("enableFireControlSensor", "true");
        events.awaitMatching(on, "sensor_swept",
                reply -> hasContactAt(reply, base + 1),
                "at the sensor, holding a contact", "the sensor never found the zombie standing "
                        + NEAR_TARGET + " blocks in front of it", Weapons.SUBJECT_TICKS);

        charge(base);
        String fired = Weapons.awaitFired(events, on, base, Y, Z,
                "the battery never fired on a target its own sensor was holding: " + read(base));
        assertEquals("the gun fired, but not on an acquisition — something else gave it a target: "
                + fired, "true", Events.text(fired, "acquired"));

        // Only now can the off window's silence be read: a gun with nothing to shoot never asks the
        // fire question, so its fire recorder is first proved live by the round just fired.
        java.util.List<String> whileOff = Weapons.firedSince(events, off, base, Y, Z);
        whileOff.removeIf(round -> Events.number(round, "seq") >= on);
        assertTrue("a battery with acquisition disabled fired at something nobody named it — the"
                + " config flag does not disable the mechanic (marks " + off + ".." + on + "): "
                + whileOff, whileOff.isEmpty());
    }

    /**
     * The trade the whole passive/active split exists for: a cool body far enough away is SEEN by a
     * listening sensor and cannot be held well enough to shoot at. Illuminating it holds it — and
     * that is the only thing that changes between the two halves of this test.
     *
     * <p>red-witnessed, one inversion per line, 2026-09-30:</p>
     * <ul>
     *   <li>{@code TargetTrack.isLocked} ({@code TargetTrack.java:88}) answering true: fails at "a cool
     *       body at 60 blocks was locked by listening alone ... {...quality:0.038...locked:true...}".</li>
     *   <li>{@code TileTurret.isLockedWellEnoughToFire} ({@code TileTurret.java:539-546}) answering
     *       true: fails at "the gun's own lock gate called a contact at 60 blocks, heard by listening
     *       alone, held well enough to shoot at: {...permitted:true...locked:true,cooldown:0,heat:0...}".</li>
     *   <li>the lock conjunct deleted from {@code TileTurret.canFireNow} ({@code TileTurret.java:441}):
     *       fails at "the battery was permitted to fire on a contact it cannot hold, with the lock the
     *       only input against it ... {...permitted:true...locked:false,cooldown:0,heat:0...}".
     *       Re-taken 2026-10-03, after the war switch left both the record and the decision filter
     *       {@code onlyTheLockOpen}: with {@code TileTurret#canFireNow} at
     *       {@code && isLockedWellEnoughToFire()} deleted, it fails at the same verdict on
     *       "{...permitted:true,drive:WORKING,operable:true,energy:20000,friendly:false,locked:false,
     *       cooldown:0,heat:0,caller:auto}".</li>
     *   <li>{@code TileFireControlSensor.isEmitting} ({@code TileFireControlSensor.java:297}) answering
     *       false: fails at "an actively illuminating sensor must be emitting ... {...mode:ACTIVE,
     *       emitting:false}".</li>
     * </ul>
     * <p>"The battery fired on a contact it cannot hold" is downstream of the two lock verdicts and was
     * not reached by either inversion: with the lock refusing and every other input shown satisfied,
     * the gun is refused in the tick it asks.</p>
     */
    @Test
    public void aCoolTargetTooFarToHoldByListeningIsHeldByIlluminating() throws Exception {
        int base = X + 200;
        buildSite(base);
        config("enableFireControlSensor", "true");
        config("fireControlSensorRadius", "96.0");
        config("fireControlSensorLockQualityToFire", "0.25");
        config("fireControlSensorActiveLockQuality", "0.95");
        long built = events.markInstrumented();
        buildBattery(base);
        Weapons.awaitAssembled(events, built, base, Y, Z, PARTS, "the gun never assembled");

        long listening = events.mark();
        spawnZombie(base + FAR_TARGET);
        charge(base);

        // Listening. It hears the zombie and cannot resolve it.
        String heard = events.awaitMatching(listening, "sensor_swept",
                reply -> hasContactAt(reply, base + 1),
                "at the sensor, holding a contact", "the listening sensor did not even detect the"
                        + " zombie, so nothing below is about the LOCK", Weapons.SUBJECT_TICKS);
        String firstHeard = contactSweepsAt(heard, base + 1).get(0);
        assertEquals("a cool body at " + FAR_TARGET + " blocks was locked by listening alone — then"
                + " illuminating buys nothing and the mode is decoration: " + firstHeard,
                "false", Events.text(firstHeard, "locked"));

        // The gun follows the contact and asks the fire question on it. The decision that counts is
        // one taken with every OTHER input to it satisfied — no friend, the gun whole,
        // its drive working, no cooldown, no heat, and a charge worth a shot — because any of those
        // refusing would make "not permitted" true whatever the lock gate says. The record carries
        // each of them, so the arrangement is read off the decision itself rather than assumed.
        int perShot = read(base).integer("energyPerShot");
        String decisions = events.awaitMatching(listening, "turret_fire_decided",
                reply -> !onlyTheLockOpen(reply, base, perShot).isEmpty(),
                "taken on target with every input but the lock satisfied",
                "the gun never asked the fire question on the sensor's contact with everything but the"
                        + " lock in its favour, so nothing below is about the lock", Weapons.SUBJECT_TICKS);
        String refused = onlyTheLockOpen(decisions, base, perShot).get(0);
        assertEquals("the gun's own lock gate called a contact at " + FAR_TARGET + " blocks, heard by"
                + " listening alone, held well enough to shoot at: " + refused, "false",
                Events.text(refused, "locked"));
        assertEquals("the battery was permitted to fire on a contact it cannot hold, with the lock the"
                + " only input against it: a poor track must mean tracking without shooting: " + refused,
                "false", Events.text(refused, "permitted"));
        assertTrue("the battery fired on a contact it cannot hold: " + read(base),
                Weapons.firedSince(events, listening, base, Y, Z).isEmpty());

        // Same sensor, same zombie, same distance — it switches the light on.
        long active = events.mark();
        ask("stellurgytest sensor charge 0 " + (base + 1) + " " + Y + " " + Z).requireOk("charge the sensor");
        ask("stellurgytest sensor mode 0 " + (base + 1) + " " + Y + " " + Z + " active")
                .requireOk("switch the sensor to active");

        String lit = events.awaitMatching(active, "sensor_swept",
                reply -> !Events.recordsWhereAll(reply, "pos", Weapons.at(base + 1, Y, Z),
                        "locked", "true").isEmpty(),
                "at the sensor, locked", "illuminating did not produce a lock on the same target at"
                        + " the same range", Weapons.SUBJECT_TICKS);
        String locked = Events.recordsWhereAll(lit, "pos", Weapons.at(base + 1, Y, Z), "locked", "true")
                .get(0);
        assertEquals("an actively illuminating sensor must be emitting — that is its whole price: "
                + locked, "true", Events.text(locked, "emitting"));

        charge(base);
        Weapons.awaitFired(events, active, base, Y, Z,
                "the battery still would not fire once the contact was properly held: " + read(base));
    }

    /**
     * The automatic fire decisions of the gun at {@code (gx, Y, Z)} taken with every input other than
     * the lock satisfied, oldest first. The inputs are the record's own fields: the
     * friend-or-foe answer, the build, the drive, the raw cooldown and heat of a gun that has not fired
     * yet, and a charge of at least one shot's price (the gun's own {@code energyPerShot}).
     */
    /**
     * A sensor sweeps once every {@code fireControlSensorScanIntervalTicks} ticks — the configured
     * number is the gap between two sweeps, which is what its description promises a player.
     *
     * <p>Read off the sweeps themselves: {@code sensor_swept} is written by every sweep (the RETURN of
     * {@code TileFireControlSensor.sweep}), stamped with the tick of the world the sensor stands in, so
     * the gap between two consecutive records at this sensor's position IS the period — no sample of
     * anything, no budget deciding. The interval is set to 7, not left at the shipped 10, so an answer
     * that merely equals the default cannot pass. A bare sensor on a held chunk is enough: the sweep
     * does not depend on a gun, a target or power (a passive sensor pays nothing).</p>
     *
     * <p>red-witnessed: with {@code TileFireControlSensor#update} at {@code scanCooldown = Math.max(1, StellurgyConfiguration.getCurrentConfig().fireControlSensorScanIntervalTicks) - 1;}
     * set to the interval rather than {@code interval - 1} (the shape it had before), this fails with "a sensor configured
     * to sweep every 7 ticks swept at gaps [8,8,8]" (2026-09-30).</p>
     */
    @Test
    public void aSensorSweepsExactlyOncePerConfiguredInterval() throws Exception {
        int sx = X + 400;
        int interval = 7;
        config("fireControlSensorScanIntervalTicks", String.valueOf(interval));
        ask("stellurgytest chunk warmup 0 " + ((sx - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((sx + 16) >> 4) + " " + ((Z + 16) >> 4)).requireOk("warm the sensor's chunk");
        ask("stellurgytest chunk forceload 0 " + (sx >> 4) + " " + (Z >> 4)).requireOk("hold the sensor's chunk");
        fill(sx - 2, Y - 1, Z - 2, sx + 2, Y + 2, Z + 2, "minecraft:air");

        long placed = events.markInstrumented();
        place("stellurgy:fireControlSensor", sx, Y, Z);
        String at = sx + "," + Y + "," + Z;
        // A deadline for the link, not a verdict: four sweeps at seven ticks is under thirty ticks.
        String swept = events.awaitMatching(placed, "sensor_swept",
                reply -> Events.recordsWhere(reply, "pos", at).size() >= 4,
                "four sweeps at " + at, "the sensor never swept four times", 40 * interval);
        java.util.List<String> sweeps = Events.recordsWhere(swept, "pos", at);
        StringBuilder gaps = new StringBuilder();
        boolean allExact = true;
        // The first sweep follows placement at a phase the registration sets; the gaps AFTER it are
        // the cadence, so they start from the first record.
        for (int i = 1; i < sweeps.size(); i++) {
            long gap = (long) Events.number(sweeps.get(i), "tick") - (long) Events.number(sweeps.get(i - 1), "tick");
            gaps.append(i == 1 ? "" : ",").append(gap);
            allExact &= gap == interval;
        }
        assertTrue("a sensor configured to sweep every " + interval + " ticks swept at gaps [" + gaps
                + "]: " + swept, allExact);
    }

    private static java.util.List<String> onlyTheLockOpen(String reply, int gx, int perShot) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String decision : Events.recordsWhereAll(reply, "pos", Weapons.at(gx, Y, Z), "caller", "auto",
                "friendly", "false", "operable", "true", "drive", "WORKING",
                "cooldown", "0", "heat", "0")) {
            if (Events.number(decision, "energy") >= perShot) {
                out.add(decision);
            }
        }
        return out;
    }

    /** Whether the sweeps of the sensor at {@code (sx, Y, Z)} in {@code reply} include one holding a contact. */
    private static boolean hasContactAt(String reply, int sx) {
        return !contactSweepsAt(reply, sx).isEmpty();
    }

    private static java.util.List<String> contactSweepsAt(String reply, int sx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String sweep : Events.recordsWhere(reply, "pos", Weapons.at(sx, Y, Z))) {
            if (Events.number(sweep, "contacts") > 0) {
                out.add(sweep);
            }
        }
        return out;
    }

    // ---- scenario construction

    /**
     * A gun and a sensor, touching, which is all it takes to be one network: no cable, no console.
     * The sensor is the only thing here that was not already possible.
     */
    private void buildBattery(int bx) throws Exception {
        place("stellurgy:turret", bx, Y, Z);
        for (int i = 1; i <= 4; i++) {
            place("stellurgy:gunBarrel", bx, Y + i, Z);
        }
        place("stellurgy:gunCooling", bx, Y, Z + 1);
        place("stellurgy:gunCooling", bx, Y, Z - 1);
        place("stellurgy:fireControlSensor", bx + 1, Y, Z);
        ask("stellurgytest sensor charge 0 " + (bx + 1) + " " + Y + " " + Z).requireOk("charge the sensor");
    }

    /** A floored, roofed, cleared corridor: no daylight, no terrain in the line of fire, no falling. */
    private void buildSite(int bx) throws Exception {
        int far = bx + FAR_TARGET + 12;
        // A roofed corridor is a dark room, and a dark room breeds contacts nobody put there. The
        // only thing this battery is allowed to notice is the zombie this test spawns.
        gameRule("doMobSpawning", "false");
        ask("stellurgytest chunk warmup 0 " + ((bx - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((far + 16) >> 4) + " " + ((Z + 16) >> 4)).requireOk("warm the corridor's chunks");
        fill(bx - 4, Y, Z - 4, far, Y + 6, Z + 4, "minecraft:air");
        fill(bx - 4, Y - 1, Z - 4, far, Y - 1, Z + 4, "minecraft:stone");
        fill(bx - 4, Y + 7, Z - 4, far, Y + 7, Z + 4, "minecraft:stone");
        // Everything has to keep ticking, including the zombie at the far end.
        for (int cx = (bx - 16) >> 4; cx <= (far + 16) >> 4; cx++) {
            ask("stellurgytest chunk forceload 0 " + cx + " " + (Z >> 4)).requireOk("hold a chunk");
        }
    }

    private void spawnZombie(int bx) throws Exception {
        Reply resp = ask("stellurgytest entity spawn 0 " + (bx + 0.5D) + " " + Y + " " + (Z + 0.5D)
                + " minecraft:zombie");
        assertTrue("could not spawn the target: " + resp, resp.bool("spawned"));
    }

    /**
     * Set a config key for this scenario, remembering what it held the FIRST time this scenario
     * touched it. The server is shared by every class in the fork, so a radius or a lock floor left
     * behind is a different sensor in whatever runs next.
     */
    private void config(String key, String value) throws Exception {
        if (!configBefore.containsKey(key)) {
            configBefore.put(key, ask("stellurgytest config get " + key).requireOk("read " + key)
                    .text("value"));
        }
        ask("stellurgytest config set " + key + " " + value).requireOk("set " + key);
    }

    /**
     * Set a game rule for this scenario, remembering what it held the first time. Read through the
     * probe's data form: vanilla's {@code /gamerule} answers with a chat line.
     */
    private void gameRule(String rule, String value) throws Exception {
        if (!gameRulesBefore.containsKey(rule)) {
            gameRulesBefore.put(rule, ask("stellurgytest gamerule get " + rule).requireOk("read " + rule)
                    .text("value"));
        }
        exec("gamerule " + rule + " " + value);
        assertEquals("the game rule did not take: ", value,
                ask("stellurgytest gamerule get " + rule).requireOk("read " + rule).text("value"));
    }

    /** Put back every key and game rule this scenario set, to what it held before it touched it. */
    @After
    public void restoreConfig() throws Exception {
        for (java.util.Map.Entry<String, String> key : configBefore.entrySet()) {
            Reply restored = ask("stellurgytest config set " + key.getKey() + " " + key.getValue())
                    .requireOk("restore " + key.getKey());
            System.out.println("[config] restored " + restored);
        }
        configBefore.clear();
        for (java.util.Map.Entry<String, String> rule : gameRulesBefore.entrySet()) {
            exec("gamerule " + rule.getKey() + " " + rule.getValue());
            assertEquals("a game rule was not put back: " + rule.getKey(), rule.getValue(),
                    ask("stellurgytest gamerule get " + rule.getKey()).requireOk("read " + rule.getKey())
                            .text("value"));
        }
        gameRulesBefore.clear();
    }

    private void charge(int bx) throws Exception {
        ask("stellurgytest turret charge 0 " + bx + " " + Y + " " + Z).requireOk("charge the gun");
    }

    // ---- probes

    private Reply read(int bx) throws Exception {
        return ask("stellurgytest turret read 0 " + bx + " " + Y + " " + Z).requireOk("read the gun");
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) throws Exception {
        ask("stellurgytest fill 0 " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " "
                + block).requireOk("fill with " + block);
    }

    private void place(String block, int x, int y, int z) throws Exception {
        Reply placed = ask("stellurgytest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + y + "," + z + ": " + placed,
                placed.bool("placed"));
    }
}
