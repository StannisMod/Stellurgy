package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

import java.util.List;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What a console buys, and what it must never buy.
 *
 * <p>It buys CONVENIENCE: one target for a whole battery, and a way to say "track but do not shoot"
 * without walking to each gun. It must never buy CAPABILITY — every gun here fires perfectly well
 * alone, which is what {@link TurretStandaloneE2ETest} pins, so nothing in this class may be the
 * reason a gun works.</p>
 *
 * <p>The last test is the one that would be easy to leave out: a console that is destroyed must not
 * leave its battery firing at a point nobody can retract.</p>
 *
 * <p>Each wait is on a record: the network's own rebuild ({@code subsystem_network_rebuilt}) before
 * the console is asked what it commands, the gun's rounds ({@code turret_fired}), its hold decision
 * ({@code turret_hold_decided}) and the mount losing its command ({@code turret_aim}).</p>
 */
public class WeaponConsoleE2ETest extends AbstractSharedServerTest {

    /** This class's own site. */
    private static final int X = 9800, Y = 80, Z = 9800;
    /** Controller + four barrels + two cooling jackets; the controller is not a part. */
    private static final int PARTS = 6;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /**
     * A console points two guns at once, and the guns were not commanded individually.
     *
     * <p>The layout is a chain over block adjacency — gun, console, gun — because that is what makes
     * one network out of three nodes. No cable is involved: two touching nodes are one network, and
     * a cable is a reach tool rather than a requirement.</p>
     *
     * <p>red-witnessed: with {@code TileTurret#getEffectiveTarget} at {@code if (state != null && state.getTarget() != null)} taking the network's target only when a
     * console stands directly east of the gun (the nearest gun alone commanded), this fails with "the
     * second gun never fired on the console's target: one console must point the whole battery, not
     * the nearest gun". 2026-09-30.</p>
     *
     * <p>red-witnessed: with {@code TileWeaponConsole.getGunCount} ({@code TileWeaponConsole#getGunCount} at {@code return state == null ? 0 : state.getSinkCount();})
     * counting at most one gun, this fails at "the console is not commanding both guns ...
     * {...guns:1...} expected:&lt;2&gt; but was:&lt;1&gt;" (2026-09-30).</p>
     */
    @Test
    public void aConsolePointsEveryGunOnItsNetwork() throws Exception {
        int base = X;
        buildSite(base);
        long built = events.markInstrumented();
        buildGun(base);
        buildGun(base + 2);
        Weapons.awaitAssembled(events, built, base, Y, Z, PARTS, "the first gun never assembled");
        Weapons.awaitAssembled(events, built, base + 2, Y, Z, PARTS, "the second gun never assembled");
        charge(base);
        charge(base + 2);

        Reply seen = placeConsoleAndAwaitNetwork(base + 1);
        assertEquals("the console is not commanding both guns — the three blocks did not form one"
                + " network: " + seen, 2, seen.integer("guns"));

        long ordered = events.mark();
        Reply applied = ask("stellurgytest weaponconsole target 0 " + (base + 1) + " " + Y + " " + Z + " "
                + (base + 40.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D)).requireOk("give the console a target");
        assertTrue("the console refused the target: " + applied, applied.bool("applied"));

        Weapons.awaitFired(events, ordered, base, Y, Z, "the first gun never fired on the console's target");
        Weapons.awaitFired(events, ordered, base + 2, Y, Z, "the second gun never fired on the console's"
                + " target: one console must point the whole battery, not the nearest gun");
    }

    /**
     * Hold fire stops the shooting without losing the target.
     *
     * <p>red-witnessed: with the hold's answer ignored in {@code TileTurret.update}'s fire gate
     * ({@code TileTurret#update} at {@code if (!onTarget || isHoldingFire() || !canFireNow())}, {@code isHoldingFire()} still asked), this fails at "the battery
     * kept firing while holding fire" (2026-09-29).</p>
     *
     * <p>red-witnessed, one inversion per verdict, 2026-09-30: with {@code TileTurret.isHoldingFire}
     * ({@code TileTurret.java:619}) letting go of the hold one tick in seven, this fails at "the gun
     * stopped holding inside the hold window ... {...held:true},{...held:false}..."; with
     * {@code TileWeaponConsole.getTarget} ({@code TileWeaponConsole.java:191}) answering nothing while the
     * network holds fire, at "holding fire lost the target ... {...holdFire:true...hasTarget:false}".</p>
     */
    @Test
    public void holdFireStopsTheShootingAndKeepsTheTarget() throws Exception {
        int base = X + 100;
        buildSite(base);
        long built = events.markInstrumented();
        buildGun(base);
        Weapons.awaitAssembled(events, built, base, Y, Z, PARTS, "the gun never assembled");
        charge(base);
        assertEquals("the console is not commanding the gun: ", 1,
                placeConsoleAndAwaitNetwork(base + 1).integer("guns"));

        long ordered = events.mark();
        ask("stellurgytest weaponconsole target 0 " + (base + 1) + " " + Y + " " + Z + " "
                + (base + 40.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D)).requireOk("give the console a target");
        Weapons.awaitFired(events, ordered, base, Y, Z,
                "the gun never fired before hold-fire, so the test would prove nothing");

        long held = events.mark();
        ask("stellurgytest weaponconsole holdfire 0 " + (base + 1) + " " + Y + " " + Z + " true")
                .requireOk("hold fire");
        charge(base);
        // The gun asks "am I holding fire" once it is on target, before it would ask to fire; the
        // record of that answer is the link, and the hold is the gun's own decision rather than a
        // silence.
        events.awaitRecordWithFields(held, "turret_hold_decided",
                "the gun on target never answered that it was holding fire", Weapons.SUBJECT_TICKS,
                "pos", Weapons.at(base, Y, Z), "held", "true");
        // WINDOW: while it holds, nothing is decided per tick that a record could close — the hold
        // returns before the fire question. So the claim is an absence over an interval: three of
        // this gun's own fire intervals (read off the gun), charged, on target, held. Both ends are
        // marks, and the assertion reads the hold chain over the same window.
        int interval = read(base).integer("fireInterval");
        // WINDOW: three of the gun's own fire intervals, closed by the two marks around it (above).
        GameTicks.advance(client(), GameTicks.server(), 3 * Math.max(1, interval));
        long heldEnd = events.mark();
        String holds = events.since(held, "turret_hold_decided");
        Events.assertInstrumentRan(holds, "turret_hold_events", "the gun kept holding over the window");
        assertTrue("the gun stopped holding inside the hold window (marks " + held + ".." + heldEnd
                + "): " + holds, Events.recordsWhereAll(holds, "pos", Weapons.at(base, Y, Z),
                "held", "false").isEmpty());
        assertTrue("the battery kept firing while holding fire (marks " + held + ".." + heldEnd + ")",
                Weapons.firedSince(events, held, base, Y, Z).isEmpty());

        Reply state = ask("stellurgytest weaponconsole read 0 " + (base + 1) + " " + Y + " " + Z)
                .requireOk("read the console");
        assertTrue("holding fire lost the target: tracking and shooting are separate decisions, so a"
                + " battery watching an approaching ship must not have to forget it to stop"
                + " shooting: " + state, state.bool("hasTarget"));

        // And releasing it resumes, which is what says the hold was the reason.
        long released = events.mark();
        ask("stellurgytest weaponconsole holdfire 0 " + (base + 1) + " " + Y + " " + Z + " false")
                .requireOk("release the hold");
        Weapons.awaitFired(events, released, base, Y, Z,
                "the battery did not resume when hold-fire was released");
    }

    /**
     * Breaking the last console clears the target rather than leaving the battery firing at a point
     * nobody can retract — the one failure a player cannot fix by breaking something.
     *
     * <p>red-witnessed: with {@code WeaponNetworkDomain#onComponentRebuilt} at {@code if (controllers.isEmpty())}'s clear of a console-less network's
     * target disabled, this fails with "the gun still holds the command of a console that no longer
     * exists — no `turret_aim` carrying ... commanded = false was recorded within 600 ticks" while the
     * gun went on firing. 2026-09-30.</p>
     *
     * <p>red-witnessed: with {@code TileTurret.update} ({@code TileTurret#update} at {@code Vec3d target = getEffectiveTarget();}) taking back the
     * last target it had once it had held none for five ticks, this fails at "the battery fired after
     * it lost the command of a console that no longer exists (marks 1851..1916, 21 ticks past the
     * recharge): [{...shot:2...},{...shot:3...}]" (2026-09-30). The "took a command again" verdict after
     * it is not reached by that inversion and was not separately witnessed.</p>
     */
    @Test
    public void losingTheLastConsoleClearsTheTarget() throws Exception {
        int base = X + 200;
        buildSite(base);
        long built = events.markInstrumented();
        buildGun(base);
        Weapons.awaitAssembled(events, built, base, Y, Z, PARTS, "the gun never assembled");
        charge(base);
        assertEquals("the console is not commanding the gun: ", 1,
                placeConsoleAndAwaitNetwork(base + 1).integer("guns"));

        long ordered = events.mark();
        ask("stellurgytest weaponconsole target 0 " + (base + 1) + " " + Y + " " + Z + " "
                + (base + 40.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D)).requireOk("give the console a target");
        Weapons.awaitFired(events, ordered, base, Y, Z,
                "the gun never fired on the console's target, so its removal proves nothing");

        long removed = events.mark();
        ask("stellurgytest fill 0 " + (base + 1) + " " + Y + " " + Z + " " + (base + 1) + " " + Y + " "
                + Z + " minecraft:air").requireOk("remove the console");
        // The rebuild that notices the console is gone happens on the network's own tick; what it
        // costs the gun is its command, which the mount records the tick it loses it.
        String dropped = events.awaitRecordWithFields(removed, "turret_aim",
                "the gun still holds the command of a console that no longer exists",
                Weapons.SUBJECT_TICKS, "pos", Weapons.at(base, Y, Z), "commanded", "false");
        charge(base);
        Reply gun = read(base);
        assertTrue("the gun still holds a target it cannot be told to drop: " + gun,
                !gun.bool("hasTarget"));
        // WINDOW: a gun with no target takes no fire decision at all — it returns before the question
        // is asked — so no record can close this; the claim is an absence over an interval that must
        // CONTAIN the decision a still-commanded gun would take. Such a gun, on target and charged,
        // fires on the first tick its cooldown reaches zero, and the cooldown set by its last round is
        // the gun's own fire interval (read off the gun). So the interval runs from the drop to one
        // interval plus a tick past the recharge; overshoot only lengthens it, which makes a negative
        // claim stricter, never more lenient.
        int interval = gun.integer("fireInterval");
        // WINDOW: one fire interval plus a tick past the recharge, which contains the decision (above).
        GameTicks.advance(client(), GameTicks.server(), interval + 1);
        long windowEnd = events.mark();
        long droppedSeq = (long) Events.number(dropped, "seq");
        List<String> after = Weapons.firedSince(events, removed, base, Y, Z);
        after.removeIf(round -> Events.number(round, "seq") < droppedSeq);
        assertTrue("the battery fired after it lost the command of a console that no longer exists"
                + " (marks " + droppedSeq + ".." + windowEnd + ", " + (interval + 1) + " ticks past the"
                + " recharge): " + after, after.isEmpty());
        String aims = events.since(removed, "turret_aim");
        java.util.List<String> recommanded = Events.recordsWhereAll(aims, "pos", Weapons.at(base, Y, Z),
                "commanded", "true");
        recommanded.removeIf(aim -> Events.number(aim, "seq") < droppedSeq);
        assertTrue("the gun took a command again after the console that gave it was gone (marks "
                + droppedSeq + ".." + windowEnd + "): " + recommanded, recommanded.isEmpty());
    }

    // ---- scenario construction

    /**
     * Place the console and wait for the weapons network to rebuild around it, then answer what the
     * console sees. The rebuild is the network's own, recorded when it runs; the console's
     * {@code guns} is read once after it.
     */
    private Reply placeConsoleAndAwaitNetwork(int consoleX) throws Exception {
        long placed = events.mark();
        place("stellurgy:weaponConsole", consoleX, Y, Z);
        events.awaitRecordWithFields(placed, "subsystem_network_rebuilt",
                "the weapons network never rebuilt after the console was placed",
                Weapons.ARRANGEMENT_TICKS, "domain", "Weapon", "dim", "0");
        return ask("stellurgytest weaponconsole read 0 " + consoleX + " " + Y + " " + Z)
                .requireOk("read the console");
    }

    private void buildGun(int bx) throws Exception {
        place("stellurgy:turret", bx, Y, Z);
        for (int i = 1; i <= 4; i++) {
            place("stellurgy:gunBarrel", bx, Y + i, Z);
        }
        place("stellurgy:gunCooling", bx, Y, Z + 1);
        place("stellurgy:gunCooling", bx, Y, Z - 1);
    }

    private void buildSite(int bx) throws Exception {
        ask("stellurgytest chunk warmup 0 " + ((bx - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((bx + 64) >> 4) + " " + ((Z + 16) >> 4)).requireOk("warm the site's chunks");
        ask("stellurgytest fill 0 " + (bx - 4) + " " + (Y - 2) + " " + (Z - 4) + " " + (bx + 60) + " "
                + (Y + 12) + " " + (Z + 4) + " minecraft:air").requireOk("clear the site");
        ask("stellurgytest chunk forceload 0 " + (bx >> 4) + " " + (Z >> 4)).requireOk("hold the chunk");
    }

    private void charge(int bx) throws Exception {
        ask("stellurgytest turret charge 0 " + bx + " " + Y + " " + Z).requireOk("charge the gun");
    }

    private Reply read(int bx) throws Exception {
        return ask("stellurgytest turret read 0 " + bx + " " + Y + " " + Z).requireOk("read the gun");
    }

    private void place(String block, int x, int y, int z) throws Exception {
        Reply placed = ask("stellurgytest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + y + "," + z + ": " + placed,
                placed.bool("placed"));
    }
}
