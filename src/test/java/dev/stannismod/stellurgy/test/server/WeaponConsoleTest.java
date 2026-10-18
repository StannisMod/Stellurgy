package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.Test;

import java.util.List;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What a console buys, and what it must never buy.
 *
 * <p>It buys CONVENIENCE: one target for a whole battery, and a way to say "track but do not shoot"
 * without walking to each gun. It must never buy CAPABILITY — every gun here fires perfectly well
 * alone, which is what {@link TurretStandaloneTest} pins, so nothing in this class may be the
 * reason a gun works.</p>
 *
 * <p>The last test is the one that would be easy to leave out: a console that is destroyed must not
 * leave its battery firing at a point nobody can retract.</p>
 *
 * <p>Each wait is on a record: the network's own rebuild ({@code subsystem_network_rebuilt}) before
 * the console is asked what it commands, the gun's rounds ({@code turret_fired}), its hold decision
 * ({@code turret_hold_decided}) and the mount losing its command ({@code turret_aim}).</p>
 */
public class WeaponConsoleTest extends AbstractSharedServerTest {

    /** This class's own site. */
    private static final int X = 9800, Y = 80, Z = 9800;
    /** Controller + four barrels + two cooling jackets; the controller is not a part. */
    private static final int PARTS = 6;

    private final Events events =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /**
     * A console points two guns at once, and the guns were not commanded individually.
     *
     * <p>The layout is a chain over block adjacency — gun, console, gun — because that is what makes
     * one network out of three nodes. No cable is involved: two touching nodes are one network, and
     * a cable is a reach tool rather than a requirement.</p>
     */
    @RedWitness(verdict = "every gun", member = "TileTurret#getEffectiveTarget",
            at = "if (state != null && state.getTarget() != null)",
            replacedBy = "if (state != null && state.getTarget() != null && world.getTileEntity(pos.east()) instanceof TileWeaponConsole)",
            fails = "the second gun never fired on the console's target", on = "2026-10-18")
    @RedWitness(verdict = "both guns", member = "TileWeaponConsole#getGunCount",
            at = "return state == null ? 0 : state.getSinkCount();",
            replacedBy = "return state == null ? 0 : Math.min(1, state.getSinkCount());",
            fails = "the console is not commanding both guns", on = "2026-10-18")
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
        Reply applied = arrange("stellurgytest weaponconsole target 0 " + (base + 1) + " " + Y + " " + Z + " "
                + (base + 40.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D)).requireOk("give the console a target");
        assertTrue("the console refused the target: " + applied, applied.bool("applied"));

        Weapons.awaitFired(events, ordered, base, Y, Z, "the first gun never fired on the console's target");
        Weapons.awaitFired(events, ordered, base + 2, Y, Z, "the second gun never fired on the console's"
                + " target: one console must point the whole battery, not the nearest gun");
    }

    /**
     * Hold fire stops the shooting without losing the target.
     */
    @RedWitness(verdict = "stops", member = "TileTurret#update",
            at = "if (!onTarget || isHoldingFire() || !canFireNow())",
            replacedBy = "if (!onTarget || (isHoldingFire() && world == null) || !canFireNow())",
            fails = "the battery kept firing while holding fire", on = "2026-10-18")
    @RedWitness(verdict = "holds", member = "TileTurret#isHoldingFire",
            at = "return state != null && state.isHoldFire();",
            replacedBy = "return state != null && state.isHoldFire() && world.getTotalWorldTime() % 7L != 0L;",
            fails = "the gun stopped holding inside the hold window", on = "2026-10-18")
    @RedWitness(verdict = "keeps the target", member = "TileWeaponConsole#getTarget",
            at = "return state == null ? null : state.getTarget();",
            replacedBy = "return state == null || state.isHoldFire() ? null : state.getTarget();",
            fails = "holding fire lost the target", on = "2026-10-18")
    @Test
    @Wait(shape = Wait.Shape.WINDOW, at = "int interval = read(base).integer(\"fireInterval\");",
            why = "while it holds, nothing is decided per tick that a record could close — the hold returns before"
                    + " the fire question. So the claim is an absence over an interval: three of this gun's own"
                    + " fire intervals (read off the gun), charged, on target, held. Both ends are marks, and"
                    + " the assertion reads the hold chain over the same window.")
    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advance(client(), GameTicks.server(), 3 * Math.max(1,"
            + " interval));",
            why = "three of the gun's own fire intervals, closed by the two marks around it (above).")
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
        arrange("stellurgytest weaponconsole target 0 " + (base + 1) + " " + Y + " " + Z + " "
                + (base + 40.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D)).requireOk("give the console a target");
        Weapons.awaitFired(events, ordered, base, Y, Z,
                "the gun never fired before hold-fire, so the test would prove nothing");

        long held = events.mark();
        arrange("stellurgytest weaponconsole holdfire 0 " + (base + 1) + " " + Y + " " + Z + " true")
                .requireOk("hold fire");
        charge(base);
        // The gun asks "am I holding fire" once it is on target, before it would ask to fire; the
        // record of that answer is the link, and the hold is the gun's own decision rather than a
        // silence.
        events.awaitRecordWithFields(held, "turret_hold_decided",
                "the gun on target never answered that it was holding fire", Weapons.SUBJECT_TICKS,
                "pos", Weapons.at(base, Y, Z), "held", "true");
        int interval = read(base).integer("fireInterval");
        GameTicks.advance(client(), GameTicks.server(), 3 * Math.max(1, interval));
        long heldEnd = events.mark();
        String holds = events.since(held, "turret_hold_decided");
        Events.assertInstrumentRan(holds, "turret_hold_events", "the gun kept holding over the window");
        assertTrue("the gun stopped holding inside the hold window (marks " + held + ".." + heldEnd
                + "): " + holds, Events.recordsWhereAll(holds, "pos", Weapons.at(base, Y, Z),
                "held", "false").isEmpty());
        assertTrue("the battery kept firing while holding fire (marks " + held + ".." + heldEnd + ")",
                Weapons.firedSince(events, held, base, Y, Z).isEmpty());

        Reply state = read("stellurgytest weaponconsole read 0 " + (base + 1) + " " + Y + " " + Z)
                .requireOk("read the console");
        assertTrue("holding fire lost the target: tracking and shooting are separate decisions, so a"
                + " battery watching an approaching ship must not have to forget it to stop"
                + " shooting: " + state, state.bool("hasTarget"));

        // And releasing it resumes, which is what says the hold was the reason.
        long released = events.mark();
        arrange("stellurgytest weaponconsole holdfire 0 " + (base + 1) + " " + Y + " " + Z + " false")
                .requireOk("release the hold");
        Weapons.awaitFired(events, released, base, Y, Z,
                "the battery did not resume when hold-fire was released");
    }

    /**
     * Breaking the last console clears the target rather than leaving the battery firing at a point
     * nobody can retract — the one failure a player cannot fix by breaking something.
     */
    @RedWitness(verdict = "cleared", member = "WeaponNetworkDomain#onComponentRebuilt",
            at = "if (controllers.isEmpty())",
            replacedBy = "if (controllers.isEmpty() && controllers == null)",
            fails = "the gun still holds the command of a console that no longer exists", on = "2026-10-18")
    @RedWitness(verdict = "stops firing", member = "TileTurret#update",
            at = "mechanism.clearCommand();",
            replacedBy = "mechanism.clearCommand(); if (shotsFired > 0 && canFireNow()) { launch(TurretFireControl.shipIdAt(world, pos)); }",
            fails = "the battery fired after it lost the command of a console that no longer exists", on = "2026-10-18")
    @Test
    @Wait(shape = Wait.Shape.WINDOW, at = "int interval = gun.integer(\"fireInterval\");",
            why = "a gun with no target takes no fire decision at all — it returns before the question is asked —"
                    + " so no record can close this; the claim is an absence over an interval that must CONTAIN"
                    + " the decision a still-commanded gun would take. Such a gun, on target and charged, fires"
                    + " on the first tick its cooldown reaches zero, and the cooldown set by its last round is"
                    + " the gun's own fire interval (read off the gun). So the interval runs from the drop to"
                    + " one interval plus a tick past the recharge; overshoot only lengthens it, which makes a"
                    + " negative claim stricter, never more lenient.")
    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advance(client(), GameTicks.server(), interval + 1);",
            why = "one fire interval plus a tick past the recharge, which contains the decision (above).")
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
        arrange("stellurgytest weaponconsole target 0 " + (base + 1) + " " + Y + " " + Z + " "
                + (base + 40.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D)).requireOk("give the console a target");
        Weapons.awaitFired(events, ordered, base, Y, Z,
                "the gun never fired on the console's target, so its removal proves nothing");

        long removed = events.mark();
        arrange("stellurgytest fill 0 " + (base + 1) + " " + Y + " " + Z + " " + (base + 1) + " " + Y + " "
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
        int interval = gun.integer("fireInterval");
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

    /**
     * Two batteries joined into one take the order that was given LAST, whichever side it came from.
     *
     * <p>Two rows, each two lone consoles — each its own network — with one gap between them. Each
     * console of a row is given a different access code, one tick apart, and a third console is then
     * placed in the gap, joining the row into one network. In the first row the later order is on
     * the east console, in the second on the west one, so the outcome cannot come from position. Nor
     * from which of the two old networks the joined one inherits: that choice is not ordered, and
     * either way a console holding a newer order must win.</p>
     *
     * <p>Each wait is on {@code weapon_orders_seeded}, the record of that decision, narrowed to the
     * exact consoles of one row; the code is then read once from the console that joined them, which
     * holds no order of its own.</p>
     */
    @RedWitness(member = "WeaponNetworkDomain#seedFromLatestOrders",
            at = "stamp >= latestStamp",
            replacedBy = "stamp <= latestStamp",
            fails = "the joined battery in the first row must take the order given last", on = "2026-10-18")
    @Test
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advance(client(), GameTicks.server(), 1);",
            why = "one tick is the dose — an order is stamped with the tick it was given, so the second pair of"
                    + " orders must be given at least one tick after the first to be the later one.")
    public void theLastOrderWinsWhenTwoBatteriesAreJoined() throws Exception {
        FixtureSite site = clearedSite(1, 2, "two rows of weapon consoles");
        int y = site.y + 1;
        int west = site.x + 1;
        int gap = site.x + 2;
        int east = site.x + 3;
        int eastLastRow = site.z + 1;
        int westLastRow = site.z + 4;

        long lone = events.markInstrumented();
        for (int z : new int[] {eastLastRow, westLastRow}) {
            place("stellurgy:weaponConsole", west, y, z);
            place("stellurgy:weaponConsole", east, y, z);
        }
        for (int z : new int[] {eastLastRow, westLastRow}) {
            for (int x : new int[] {west, east}) {
                events.awaitRecordWithFields(lone, "weapon_orders_seeded",
                        "the console at " + Weapons.at(x, y, z) + " never formed a network of its own",
                        Weapons.ARRANGEMENT_TICKS, "consoles", Weapons.at(x, y, z));
            }
        }

        code(west, y, eastLastRow, "w636-first");
        code(east, y, westLastRow, "w636-first");
        GameTicks.advance(client(), GameTicks.server(), 1);
        code(east, y, eastLastRow, "w636-last");
        code(west, y, westLastRow, "w636-last");

        long joined = events.mark();
        place("stellurgy:weaponConsole", gap, y, eastLastRow);
        place("stellurgy:weaponConsole", gap, y, westLastRow);
        for (int z : new int[] {eastLastRow, westLastRow}) {
            events.awaitRecordWithFields(joined, "weapon_orders_seeded",
                    "the row at z=" + z + " never became one network",
                    Weapons.ARRANGEMENT_TICKS, "consoles",
                    Weapons.at(west, y, z) + "|" + Weapons.at(gap, y, z) + "|" + Weapons.at(east, y, z));
        }

        Reply first = read("stellurgytest weaponconsole read 0 " + gap + " " + y + " " + eastLastRow)
                .requireOk("read the joining console of the first row");
        assertEquals("the joined battery in the first row must take the order given last — the east"
                + " console's: " + first, "w636-last", first.text("code"));
        Reply second = read("stellurgytest weaponconsole read 0 " + gap + " " + y + " " + westLastRow)
                .requireOk("read the joining console of the second row");
        assertEquals("and in the second row too, where the last order came from the west console: "
                + second, "w636-last", second.text("code"));
    }

    // ---- scenario construction

    /** Give the console at this position an access code, refusing unless it is on a network. */
    private void code(int x, int y, int z, String code) throws Exception {
        Reply set = arrange("stellurgytest weaponconsole code 0 " + x + " " + y + " " + z + " " + code)
                .requireOk("give the console at " + Weapons.at(x, y, z) + " a code");
        requireArranged("the console at " + Weapons.at(x, y, z) + " is on no network, so the code was"
                + " not an order: " + set, set.bool("applied"));
    }

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
        return read("stellurgytest weaponconsole read 0 " + consoleX + " " + Y + " " + Z)
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
        arrange("stellurgytest chunk warmup 0 " + ((bx - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((bx + 64) >> 4) + " " + ((Z + 16) >> 4)).requireOk("warm the site's chunks");
        arrange("stellurgytest fill 0 " + (bx - 4) + " " + (Y - 2) + " " + (Z - 4) + " " + (bx + 60) + " "
                + (Y + 12) + " " + (Z + 4) + " minecraft:air").requireOk("clear the site");
        arrange("stellurgytest chunk forceload 0 " + (bx >> 4) + " " + (Z >> 4)).requireOk("hold the chunk");
    }

    private void charge(int bx) throws Exception {
        arrange("stellurgytest turret charge 0 " + bx + " " + Y + " " + Z).requireOk("charge the gun");
    }

    private Reply read(int bx) throws Exception {
        return read("stellurgytest turret read 0 " + bx + " " + Y + " " + Z).requireOk("read the gun");
    }

    private void place(String block, int x, int y, int z) throws Exception {
        Reply placed = arrange("stellurgytest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + y + "," + z + ": " + placed,
                placed.bool("placed"));
    }
}
