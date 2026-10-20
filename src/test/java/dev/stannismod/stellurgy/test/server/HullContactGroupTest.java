package dev.stannismod.stellurgy.test.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stannismod.stellurgy.test.ConfigFlag;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Plot;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.WarShip;
import dev.stannismod.stellurgy.test.Weapons;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A HULL as a contact: the fire-control sensor acquiring a ship, telling a friendly one by the
 * installation's hull rule and a transponder aboard it, ranking it against a creature, and the gun
 * engaging what the sensor handed it (fire-control-sensor INV-FCS-09..13, 15..17; weapons
 * INV-GUN-13..15).
 *
 * <h2>Why a group of its own</h2>
 * <p>The cluster is the joint between three things no existing group holds together: the sensor's
 * candidate sources and order, the weapons subsystem's hull rule and transponder, and the gun's
 * handling of an acquired hull. {@code FireControlSensorTest} is the sensor against creatures on the
 * ground, {@code ASensorAboardAShipTest} is a sensor screening its own deck, {@code TurretOnAShipTest}
 * is guns mounted on and ordered at ships; none of them acquires a hull.</p>
 *
 * <h2>The world every scenario stands in</h2>
 * <p>A ground battery — a gun controller with one barrel, a fire-control sensor beside it fed by a
 * creative plug, a weapons console — on a stone platform in the open-air band, and one or two
 * tier-2 hulls built by production's own assembly ({@link WarShip}) and parked where the scenario
 * needs them. Every reading of the sensor is its own sweep record ({@code sensor_swept}), whose tracks
 * name each contact by ship id or entity id in the order the sweep ranked them; every reading of the
 * gun is its own decision and fire records. The plots are 256 blocks apart, more than twice the
 * sensor's radius, so no scenario's sensor can see a sibling's hull or creature.</p>
 *
 * <h2>What this does NOT see</h2>
 * <p>The input pipeline: every act here is a probe arranging state (a device put into a transponder
 * slot, a console rule set by name) or production's own tick acting on it; a player putting the
 * device in through the slot's screen, and a console button choosing the rule, are a client's. Not
 * seen either: a sensor aboard a MOVING ship ordering contacts against its own motion (the order's
 * relative-motion law is pinned in the fast tier, INV-FCS-14), and a creature aboard a vouched-for
 * hull being screened with it.</p>
 */
public class HullContactGroupTest extends AbstractSharedServerTest {

    /**
     * A constant: the deadline of a link on the NEXT sweep record. A sweep comes every
     * {@code fireControlSensorScanIntervalTicks} (10 shipped, at most 200 by the config's own bound),
     * so 400 ticks is twice the longest interval any scenario here sets — a deadline, not an estimate.
     */
    private static final int SWEEP_LINK_TICKS = 400;

    /**
     * A constant: the deadline of a link on a gun's fire record once its contact is published. A
     * mount turns at single-digit degrees a tick, so a half-turn is a few dozen ticks, and the barrel
     * gun's interval is 20 ticks; 600 is an order of magnitude past their sum.
     */
    private static final int FIRE_LINK_TICKS = 600;

    /** A constant: the network's code in every scenario that sets one; any non-empty code would do. */
    private static final String OUR_CODE = "hull331";

    /** A constant: a code that is not {@link #OUR_CODE}. */
    private static final String THEIR_CODE = "other331";

    /**
     * A constant: the gap, in blocks, from the sensor to the near face of a hull a scenario only needs
     * held well — inside the plateau of any radius the config admits above 40, and far enough that a
     * round leaving the gun clears the battery's own line-of-fire check before the hull.
     */
    private static final int HELD_GAP = 30;

    /**
     * A constant: the speed, blocks per tick, under which a parked hull counts as AT REST for an
     * arrangement. Measured 2026-10-20 on this class's first run: a hull with physics off reported
     * 1.5e-17; this is five orders above that and, over the hundred-tick flight of the longest round
     * here, leads the aim by under 1e-10 blocks.
     */
    private static final double AT_REST_BLOCKS_PER_TICK = 1.0E-12D;

    /**
     * A constant: the closing speed, blocks per tick, above which a hull is MOVING for an arrangement.
     * The hull is commanded at 4 blocks a second (0.2 a tick); a twentieth of that is unmistakably
     * motion and seven orders above the order's own floor.
     */
    private static final double MOVING_BLOCKS_PER_TICK = 0.01D;

    private final Events log = new Events(c -> read(c).raw(),
            ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /** The hulls this scenario built, disposed of by id when it ends. */
    private final List<WarShip> built = new ArrayList<>();

    /** Plots far enough apart that a sensor in one never reaches into the next (radius 96 shipped). */
    @Override
    protected Plot.Lane lane() {
        return new Plot.Lane(4000, 4000, 256, 256);
    }

    @After
    public void disposeOfTheBuiltHulls() throws Exception {
        for (WarShip ship : built) {
            ship.dispose();
        }
        built.clear();
    }

    // =========================================================================== the scenarios

    /**
     * INV-FCS-09 and its control INV-FCS-17. With the sensor switched off, a hostile hull parked in
     * range is never swept and never fired on; switched on, the same sensor publishes that hull — the
     * contact names its ship id — and the same gun fires on it as an acquisition, with no target named
     * by anybody. The hull carries no weapons and no transponder, so under the network's default rule
     * with no code set it is nobody's friend.
     */
    @Test
    @Pins({"INV-FCS-09", "INV-FCS-17"})
    @RedWitness(verdict = "the hull is the contact", member = "TacticalScan#hullTrackOf",
            at = "if (mode != SensorMode.ACTIVE || extent == null) {", replacedBy = "if (true) {",
            fails = "INV-FCS-09 switched on, the sensor must hold the parked hull", on = "2026-10-20")
    @RedWitness(verdict = "it is published to the battery", member = "TileFireControlSensor#publish",
            at = "state.setAcquiredTrack(contacts.get(0), world.getTotalWorldTime(), hold);",
            replacedBy = "state.clearAcquiredTrack();", fails = "INV-FCS-09 published", on = "2026-10-20")
    @RedWitness(verdict = "the gun fires on it", member = "TileTurret#interceptOf",
            at = "return shipIntercept(track.getShip());", replacedBy = "return null;",
            fails = "INV-FCS-09 the gun must fire on the hull its sensor published", on = "2026-10-20")
    @RedWitness(verdict = "switched off, the sensor refuses", member = "TileFireControlSensor#update",
            at = "if (!StellurgyConfiguration.getCurrentConfig().enableFireControlSensor) {",
            replacedBy = "if (!StellurgyConfiguration.getCurrentConfig().enableFireControlSensor && world.isRemote) {",
            fails = "INV-FCS-17 switched off, the sensor's tick must be refused", on = "2026-10-20")
    @RedWitness(verdict = "switched off, nothing is swept or fired", member = "TileFireControlSensor#update",
            at = "registered = false; } return; } if (!registered) {",
            replacedBy = "registered = false; } } if (!registered) {",
            fails = "INV-FCS-17 switched off, no sweep may run and no round leave", on = "2026-10-20")
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advance(client(), GameTicks.server(), offDose);",
            why = "the dose is how long the switch is held off with a hostile hull in range: three scan"
                    + " intervals and three of the gun's fire intervals, in which a working sensor sweeps"
                    + " three times and its gun fires; the claim is that NOTHING happens, so no record of"
                    + " it can end the wait")
    public void aHostileHullInRangeIsTheContactAndTheBatteryFiresOnIt() throws Exception {
        String what = "a hostile hull parked beside a battery";
        WarShip hull;
        Battery battery;
        long offMark;
        try (ConfigFlag off = ConfigFlag.set(commands(), "enableFireControlSensor", false)) {
            offMark = log.markInstrumented();
            battery = battery(plot().siteAt(120, 20), what);
            log.awaitRecordWithFields(offMark, "sensor_gate_refused",
                    "INV-FCS-17 switched off, the sensor's tick must be refused", SWEEP_LINK_TICKS,
                    "pos", battery.sensorPos(), "sensor", "false");
            hull = build(site(), null, what);
            double[] box = parkEastOf(hull, battery.groundOrigin(), HELD_GAP, what);
            requireArranged(what + " — the parked hull must lie inside the sensor's radius for the control"
                    + " to say anything: nearest point " + nearestDistance(battery.groundOrigin(), box)
                    + ", radius " + configNumber("fireControlSensorRadius"),
                    nearestDistance(battery.groundOrigin(), box) < configNumber("fireControlSensorRadius"));
            int offDose = 3 * (int) configNumber("fireControlSensorScanIntervalTicks")
                    + 3 * read("stellurgytest turret read " + battery.turretAt()).integer("fireInterval");
            GameTicks.advance(client(), GameTicks.server(), offDose);
            String swept = log.since(offMark, "sensor_swept");
            Events.assertInstrumentRan(swept, "sensor_sweep_events", "the switched-off sensor swept nothing");
            List<String> sweptHere = Events.recordsWhere(swept, "pos", battery.sensorPos());
            // The gun's own tick is the observation point of a round that did not leave: with no
            // contact it never reaches its fire question, so that question's instrument is silent by
            // design, and the aim instrument at the end of every tick is the one that was looking.
            String launched = log.since(offMark, "turret_fired");
            Events.assertInstrumentRan(launched, "turret_aim_events", "the switched-off battery fired nothing");
            List<String> firedHere = Events.recordsWhere(launched, "pos", battery.turretPos());
            assertTrue("INV-FCS-17 switched off, no sweep may run and no round leave — sweeps " + sweptHere
                    + ", rounds " + firedHere, sweptHere.isEmpty() && firedHere.isEmpty());
        }

        long onMark = log.mark();
        awaitSweep(onMark, battery.sensorPos(), s -> s.holds(hull.vsShip),
                "INV-FCS-09 switched on, the sensor must hold the parked hull " + hull.vsShip);
        // The sweep's record is written as the sweep returns and the contact is published in the same
        // tick, so a read after the record finds what was handed over.
        Reply gun = read("stellurgytest turret read " + battery.turretAt());
        requireArranged(what + " — nobody may have named the gun a target: " + gun,
                !gun.bool("trackingEntity") && !read("stellurgytest weaponconsole read "
                        + battery.consoleAt()).bool("hasTarget"));
        // `acquiredShip` is written only while the gun holds an acquisition: its absence means "nothing
        // was handed over", which is exactly the failure this verdict names, so it reads as null.
        assertEquals("INV-FCS-09 published: the battery's acquisition must be the hull, by its ship id: " + gun,
                hull.vsShip, gun.textOr("acquiredShip", null));
        awaitFiredOn(onMark, battery, hull, "INV-FCS-09 the gun must fire on the hull its sensor published");
    }

    /**
     * INV-FCS-10. A sensor aboard a ship — powered by a plug and networked with a console aboard the
     * same ship, no transponder anywhere — holds a second hull in range and never its own, in the same
     * sweep. The own hull is inside the sensor's own box (range zero, the plateau's quality) and answers
     * no rule, so it would be the first contact if identity did not keep it out.
     */
    @Test
    @Pins("INV-FCS-10")
    @RedWitness(verdict = "never its own", member = "TacticalScan#sweep", at = "if (shipId.equals(ownShipId)) {",
            replacedBy = "if (false) {", fails = "INV-FCS-10 a sensor must never hold its own hull", on = "2026-10-20")
    @RedWitness(verdict = "it holds the other hull", member = "TacticalScan#hullTrackOf",
            at = "if (mode != SensorMode.ACTIVE || extent == null) {", replacedBy = "if (true) {",
            fails = "INV-FCS-10 the ship's sensor must hold the other hull", on = "2026-10-20")
    public void aSensorNeverHoldsItsOwnHull() throws Exception {
        String what = "a sensor aboard a hull, with another hull nearby";
        WarShip own = build(site(), builder -> {
            int rx = builder[0] + 1, ry = builder[1], rz = builder[2] + 4;
            place(rx, ry + 3, rz, "stellurgy:fireControlSensor");
            place(rx, ry + 3, rz + 1, "stellurgy:weaponConsole");
            place(rx + 1, ry + 3, rz, "libvulpes:creativePowerBattery");
        }, what);
        WarShip other = build(plot().siteAt(20, 80), null, what);
        int[] seat = own.seat();
        int[] sensor = {seat[0], seat[1] - 1, seat[2]};
        String sensorAt = site().dim + " " + sensor[0] + " " + sensor[1] + " " + sensor[2];
        arrange("stellurgytest sensor mode " + sensorAt + " active").requireOk("illuminate the ship's sensor");

        long mark = log.mark();
        Sweep sweep = awaitSweep(mark, Weapons.at(sensor[0], sensor[1], sensor[2]),
                s -> "ACTIVE".equals(s.mode) && s.holds(other.vsShip),
                "INV-FCS-10 the ship's sensor must hold the other hull " + other.vsShip
                        + " (the positive half: it holds hulls at all)");
        assertTrue("INV-FCS-10 a sensor must never hold its own hull " + own.vsShip + ": " + sweep.record,
                !sweep.holds(own.vsShip));
    }

    /**
     * INV-FCS-11. Under the rule {@code TRANSPONDER} with the network's code set, a hull whose
     * transponder carries that code is left out of the list; recoded to another code it is held again;
     * recoded back, left out; its device taken out, held; a device with our code put back, left out.
     * Each verdict is read on the first sweep after the act, so "within one scan interval" is the link
     * itself.
     *
     * <p>Not here: the transponder BROKEN. Measured: with the tile's unregistering on invalidation
     * removed, a broken transponder still stops vouching, because the block's drop empties the slot it
     * leaves behind — two protections, neither of which a single inversion can show, so a verdict on it
     * would have no witness.</p>
     */
    @Test
    @Pins("INV-FCS-11")
    @RedWitness(verdict = "carrying our code, left out", member = "HullAllegianceRule#isFriend",
            at = "ours.equals(transponder.getCarriedCode())", replacedBy = "false",
            fails = "INV-FCS-11 left out", on = "2026-10-20")
    @RedWitness(verdict = "recoded away, held again", member = "HullAllegianceRule#isFriend",
            at = "ours.equals(transponder.getCarriedCode())", replacedBy = "true",
            fails = "INV-FCS-11 held again: recoded", on = "2026-10-20")
    @RedWitness(verdict = "taken out, held again", member = "TileHullTransponder#removeStackFromSlot",
            at = "device = ItemStack.EMPTY;", replacedBy = "",
            fails = "INV-FCS-11 held again: taken out", on = "2026-10-20")
    public void aTransponderCarryingOurCodeKeepsItsHullOffTheListUntilTheCredentialGoes() throws Exception {
        String what = "a hull carrying our code in its transponder";
        Battery battery = battery(plot().siteAt(120, 20), what);
        battery.command(this, "code", OUR_CODE);
        battery.command(this, "allegiance", "TRANSPONDER");
        battery.command(this, "holdfire", "true");
        WarShip hull = build(site(), builder -> {
            int[] at = transponderCell(builder);
            place(at[0], at[1], at[2], "stellurgy:hullTransponder");
            arrange("stellurgytest transponder put " + site().dim + " " + at[0] + " " + at[1] + " " + at[2]
                    + " " + OUR_CODE).requireOk("our code into the transponder before assembly");
        }, what);
        int[] t = transponderAboard(hull);
        String transponder = site().dim + " " + t[0] + " " + t[1] + " " + t[2];
        requireArranged(what + " — the transponder must be aboard, holding our code, after assembly",
                OUR_CODE.equals(read("stellurgytest transponder read " + transponder).text("code")));
        double[] box = parkEastOf(hull, battery.groundOrigin(), HELD_GAP, what);
        requireArranged(what + " — the hull must be in range, or being left out says nothing",
                nearestDistance(battery.groundOrigin(), box) < configNumber("fireControlSensorRadius"));

        assertLeftOut(battery, hull, "INV-FCS-11 left out: carrying our code");

        arrange("stellurgytest transponder recode " + transponder + " " + THEIR_CODE).requireOk("recode away");
        assertHeld(battery, hull, "INV-FCS-11 held again: recoded to another code");

        arrange("stellurgytest transponder recode " + transponder + " " + OUR_CODE).requireOk("recode back");
        assertLeftOut(battery, hull, "INV-FCS-11 left out: recoded back to our code");

        arrange("stellurgytest transponder take " + transponder).requireOk("take the device out");
        assertHeld(battery, hull, "INV-FCS-11 held again: taken out");

        arrange("stellurgytest transponder put " + transponder + " " + OUR_CODE).requireOk("put our code back");
        assertLeftOut(battery, hull, "INV-FCS-11 left out: a device with our code put back");
    }

    /**
     * INV-FCS-12. Two hulls whose world boxes overlap: A carries our code in its transponder, B carries
     * nothing. In one sweep B is held and A is not — the transponder vouches for the ship it is aboard,
     * by id, never for whatever stands where it is.
     */
    @Test
    @Pins("INV-FCS-12")
    @RedWitness(verdict = "the overlapping hull is held", member = "HullAllegianceRule#aboard",
            at = "return shipId.equals(VSIntegration.shipIdOfBlock(world, pos));",
            replacedBy = "String own = VSIntegration.shipIdOfBlock(world, pos); double[] w = own == null ? null"
                    + " : VSIntegration.toWorldFrameFor(world, own, pos.getX() + 0.5D, pos.getY() + 0.5D,"
                    + " pos.getZ() + 0.5D); return w != null && VSIntegration.shipIdsAt(world, w[0], w[1], w[2])"
                    + ".contains(shipId);",
            fails = "INV-FCS-12 the overlapping hull with no transponder must be held", on = "2026-10-20")
    @RedWitness(verdict = "the vouched-for hull is left out", member = "HullAllegianceRule#isFriend",
            at = "ours.equals(transponder.getCarriedCode())", replacedBy = "false",
            fails = "INV-FCS-12 the hull whose own transponder carries our code must be left out", on = "2026-10-20")
    public void aTransponderVouchesOnlyForTheHullItIsAboard() throws Exception {
        String what = "two overlapping hulls, one vouched for";
        Battery battery = battery(plot().siteAt(120, 20), what);
        battery.command(this, "code", OUR_CODE);
        battery.command(this, "allegiance", "TRANSPONDER");
        battery.command(this, "holdfire", "true");
        WarShip vouched = build(site(), builder -> {
            int[] at = transponderCell(builder);
            place(at[0], at[1], at[2], "stellurgy:hullTransponder");
            arrange("stellurgytest transponder put " + site().dim + " " + at[0] + " " + at[1] + " " + at[2]
                    + " " + OUR_CODE).requireOk("our code into A's transponder");
        }, what);
        WarShip bare = build(plot().siteAt(20, 80), null, what);
        double[] a = parkEastOf(vouched, battery.groundOrigin(), HELD_GAP, what);
        double[] b = parkEastOf(bare, battery.groundOrigin(), HELD_GAP + 1, what);
        requireArranged(what + " — the two world boxes must overlap: A " + java.util.Arrays.toString(a)
                + ", B " + java.util.Arrays.toString(b), overlap(a, b));

        long mark = log.mark();
        Sweep sweep = awaitSweep(mark, battery.sensorPos(), s -> "ACTIVE".equals(s.mode),
                what + " — a sweep with both hulls parked");
        assertTrue("INV-FCS-12 the overlapping hull with no transponder must be held: " + sweep.record,
                sweep.holds(bare.vsShip));
        assertTrue("INV-FCS-12 the hull whose own transponder carries our code must be left out: " + sweep.record,
                !sweep.holds(vouched.vsShip));
    }

    /**
     * INV-FCS-13. One sensor, one creature at rest and one hull, both lockable; the hull is farther. At
     * rest, the hull is handed over AFTER the creature (neither arrives, the nearer wins); closing on the
     * sensor, the same hull is handed over FIRST. Both times the sweep enumerates the same candidates in
     * the same order — the hulls before the creatures — so an order that followed the enumeration would
     * put the hull first both times, and one that ranked by lock and range alone would put it second
     * both times.
     */
    @Test
    @Pins("INV-FCS-13")
    @RedWitness(verdict = "at rest, the nearer creature first", member = "TacticalScan#sweep",
            at = "tracks.sort(ThreatOrder.of(origin, ownVelocity, lockFloor));", replacedBy = "",
            fails = "INV-FCS-13 at rest", on = "2026-10-20")
    @RedWitness(verdict = "closing, the hull first", member = "ThreatOrder#of",
            at = ".thenComparingDouble(t -> timeToContact(t, origin, own))", replacedBy = "",
            fails = "INV-FCS-13 closing", on = "2026-10-20")
    public void theContactHandedOverFollowsArrivalTimeNotScanOrder() throws Exception {
        String what = "a creature at rest and a hull, both held";
        Battery battery = battery(plot().siteAt(120, 20), what);
        battery.command(this, "holdfire", "true");
        double[] origin = battery.groundOrigin();
        // The creature: twenty blocks north of the sensor, standing on a block of its own.
        int cx = (int) Math.floor(origin[0]), cy = battery.sensor[1], cz = (int) Math.floor(origin[2]) - 20;
        place(cx, cy - 1, cz, "minecraft:stone");
        Reply spawned = arrange("stellurgytest arrange held-target " + site().dim + " " + (cx + 0.5D) + " " + cy
                + " " + (cz + 0.5D) + " 331 " + System.identityHashCode(this)).requireOk("the creature");
        String creature = String.valueOf(spawned.integer("entityId"));
        WarShip hull = build(site(), null, what);
        double[] box = parkEastOf(hull, origin, 50, what);
        requireArranged(what + " — the hull must be the farther: " + nearestDistance(origin, box),
                nearestDistance(origin, box) > 20.5D);

        long restMark = log.mark();
        Sweep atRest = awaitSweep(restMark, battery.sensorPos(),
                s -> "ACTIVE".equals(s.mode) && s.holds(hull.vsShip) && s.holdsEntity(creature)
                        && s.entity(creature).get("vx").getAsDouble() == 0.0D
                        && s.entity(creature).get("vy").getAsDouble() == 0.0D
                        && s.entity(creature).get("vz").getAsDouble() == 0.0D,
                what + " — a sweep holding both, the creature standing still");
        requireArranged(what + " — the parked hull must be at rest, up to the physics residue (1.5e-17"
                + " blocks/tick measured on 2026-10-20): " + atRest.record,
                Math.abs(closing(atRest, atRest.hull(hull.vsShip))) < AT_REST_BLOCKS_PER_TICK);
        requireArranged(what + " — both must be lockable, or the lock stage decides: " + atRest.record,
                atRest.hull(hull.vsShip).get("quality").getAsDouble() >= configNumber("fireControlSensorLockQualityToFire")
                        && atRest.entity(creature).get("quality").getAsDouble()
                        >= configNumber("fireControlSensorLockQualityToFire"));
        assertTrue("INV-FCS-13 at rest, the nearer creature must be handed over before the hull: " + atRest.record,
                atRest.rankOfEntity(creature) < atRest.rankOf(hull.vsShip));

        arrange("stellurgytest vs unpark-by-id " + site().dim + " " + hull.vsShip).requireOk("release the hull");
        Reply flown = arrange("stellurgytest vs force-vel-by-id " + site().dim + " " + hull.vsShip + " -4 0 0");
        requireArranged(what + " — the hull's own flight computer must take the command: " + flown,
                flown.bool("commanded") && flown.bool("afcResolved"));
        long closingMark = log.mark();
        Sweep closing = awaitSweep(closingMark, battery.sensorPos(),
                s -> "ACTIVE".equals(s.mode) && s.holds(hull.vsShip) && s.holdsEntity(creature)
                        && closing(s, s.hull(hull.vsShip)) > MOVING_BLOCKS_PER_TICK,
                what + " — a sweep that measures the hull closing");
        requireArranged(what + " — the creature must still be at rest and the hull still the farther: "
                + closing.record, closing(closing, closing.entity(creature)) == 0.0D
                && closing.hull(hull.vsShip).get("distance").getAsDouble()
                > closing.entity(creature).get("distance").getAsDouble());
        assertTrue("INV-FCS-13 closing, the hull must be handed over before the nearer creature at rest: "
                + closing.record, closing.rankOf(hull.vsShip) < closing.rankOfEntity(creature));
    }

    /**
     * INV-FCS-15. The hull is parked over the sensor, so its nearest point is straight up and its
     * centre half a hull-height further. The radius is set BETWEEN the two from what was measured: the
     * nearest point inside the first three quarters of the envelope (where an illuminated contact is
     * held at the plateau), the centre outside the envelope altogether. The hull is held, at the
     * plateau's quality, its distance the nearest point's; lifted until the nearest point is outside,
     * it is not held. The radius is not the shipped one; the rule — range to the nearest point — has no
     * scale in it, so the contract is the same at any radius.
     */
    @Test
    @Pins("INV-FCS-15")
    @RedWitness(verdict = "held by its nearest point", member = "TacticalScan#hullTrackOf",
            at = "double distance = ThreatOrder.nearestPoint(extent, origin).distanceTo(origin);",
            replacedBy = "double distance = new Vec3d((extent.minX + extent.maxX) * 0.5D, (extent.minY"
                    + " + extent.maxY) * 0.5D, (extent.minZ + extent.maxZ) * 0.5D).distanceTo(origin);",
            fails = "INV-FCS-15 held", on = "2026-10-20")
    @RedWitness(verdict = "its range is the nearest point's", member = "TacticalScan#hullTrackOf",
            at = "quality, mode, distance);", replacedBy = "quality, mode, distance + 1.0D);",
            fails = "INV-FCS-15 range", on = "2026-10-20")
    @RedWitness(verdict = "its quality is the nearest point's", member = "TacticalScan#hullTrackOf",
            at = "double quality = SignatureModel.activeQuality(distance, radiusBlocks, activePlateau);",
            replacedBy = "double quality = SignatureModel.activeQuality(distance + 0.25D * radiusBlocks,"
                    + " radiusBlocks, activePlateau);",
            fails = "INV-FCS-15 quality", on = "2026-10-20")
    @RedWitness(verdict = "not held once its nearest point is outside", member = "TacticalScan#hullTrackOf",
            at = "double distance = ThreatOrder.nearestPoint(extent, origin).distanceTo(origin);",
            replacedBy = "double distance = Math.min(ThreatOrder.nearestPoint(extent, origin).distanceTo(origin),"
                    + " radiusBlocks * 0.75D);",
            fails = "INV-FCS-15 not held", on = "2026-10-20")
    public void aHullIsInRangeWhenItsNearestPointIs() throws Exception {
        String what = "a hull parked over the sensor";
        Battery battery = battery(plot().siteAt(120, 20), what);
        battery.command(this, "holdfire", "true");
        double[] origin = battery.groundOrigin();
        WarShip hull = build(site(), null, what);
        double[] box = parkAbove(hull, origin, 5, what);
        double near = nearestDistance(origin, box);
        double centre = distance(origin, centreOf(box));
        // The radius: above the nearest point by a third (so it sits in the first three quarters) and
        // below the centre. Exists only when the hull is tall enough for both, which is measured here.
        double radius = (near / 0.75D + centre) / 2.0D;
        requireArranged(what + " — the nearest point (" + near + ") must sit in the first three quarters of a"
                + " radius the centre (" + centre + ") is beyond", near <= 0.75D * radius && centre > radius);
        try (ConfigFlag r = ConfigFlag.set(commands(), "fireControlSensorRadius", radius)) {
            long mark = log.mark();
            Sweep inside = awaitSweep(mark, battery.sensorPos(), s -> "ACTIVE".equals(s.mode),
                    what + " — a sweep at the set radius");
            assertTrue("INV-FCS-15 held: with its nearest point inside (" + near + " of " + radius + ") and its"
                    + " centre beyond, the hull must be held: " + inside.record, inside.holds(hull.vsShip));
            JsonObject track = inside.hull(hull.vsShip);
            // Both expected values are the contract's: the range IS the nearest point's distance, which
            // the test measures off the box itself, and inside the first three quarters of the envelope
            // an illuminated contact is held at the configured plateau.
            assertEquals("INV-FCS-15 range: the hull's range must be its nearest point's: " + inside.record,
                    near, track.get("distance").getAsDouble(), 1.0E-6D);
            assertEquals("INV-FCS-15 quality: inside the plateau the hull must be held at the plateau's quality: "
                    + inside.record, configNumber("fireControlSensorActiveLockQuality"),
                    track.get("quality").getAsDouble(), 1.0E-9D);

            double[] lifted = parkAbove(hull, origin, (int) Math.ceil(radius) + 2, what);
            requireArranged(what + " — lifted, the nearest point must be outside the radius: "
                    + nearestDistance(origin, lifted), nearestDistance(origin, lifted) > radius);
            long outMark = log.mark();
            Sweep outside = awaitSweep(outMark, battery.sensorPos(), s -> "ACTIVE".equals(s.mode),
                    what + " — a sweep with the hull lifted");
            assertTrue("INV-FCS-15 not held: with its nearest point outside the radius, the hull must not be held: "
                    + outside.record, !outside.holds(hull.vsShip));
        }
    }

    /**
     * INV-FCS-16. A listening sensor holds no hull — the hull carries no coolant loop, which is measured
     * — and the same sensor illuminating holds it.
     */
    @Test
    @Pins("INV-FCS-16")
    @RedWitness(verdict = "listening, no hull", member = "TacticalScan#hullTrackOf",
            at = "if (mode != SensorMode.ACTIVE || extent == null) {", replacedBy = "if (extent == null) {",
            fails = "INV-FCS-16 a listening sensor must hold no hull", on = "2026-10-20")
    @RedWitness(verdict = "illuminating, the hull", member = "TacticalScan#hullTrackOf",
            at = "if (mode != SensorMode.ACTIVE || extent == null) {", replacedBy = "if (true) {",
            fails = "INV-FCS-16 the same sensor illuminating must hold the hull", on = "2026-10-20")
    public void aListeningSensorHoldsNoHullAndAnIlluminatingOneDoes() throws Exception {
        String what = "a hull beside a listening sensor";
        Battery battery = battery(plot().siteAt(120, 20), what);
        battery.command(this, "holdfire", "true");
        arrange("stellurgytest sensor mode " + battery.sensorAt() + " passive").requireOk("listen");
        WarShip hull = build(site(), null, what);
        int[] seat = hull.seat();
        Reply heat = read("stellurgytest heat signature " + site().dim + " " + seat[0] + " " + seat[1] + " " + seat[2]);
        requireArranged(what + " — the hull must carry no coolant loop: " + heat, heat.has("isBody") && !heat.bool("isBody"));
        double[] box = parkEastOf(hull, battery.groundOrigin(), HELD_GAP, what);
        requireArranged(what + " — the hull must be in range",
                nearestDistance(battery.groundOrigin(), box) < configNumber("fireControlSensorRadius"));

        long mark = log.mark();
        Sweep listening = awaitSweep(mark, battery.sensorPos(), s -> "PASSIVE".equals(s.mode),
                what + " — a listening sweep with the hull parked");
        assertTrue("INV-FCS-16 a listening sensor must hold no hull: " + listening.record,
                !listening.holds(hull.vsShip));

        arrange("stellurgytest sensor mode " + battery.sensorAt() + " active").requireOk("illuminate");
        long litMark = log.mark();
        Sweep lit = awaitSweep(litMark, battery.sensorPos(), s -> "ACTIVE".equals(s.mode),
                what + " — an illuminating sweep");
        assertTrue("INV-FCS-16 the same sensor illuminating must hold the hull: " + lit.record,
                lit.holds(hull.vsShip));
    }

    /**
     * INV-GUN-13. A hull parked so its nearest point is in the tail of the envelope, where it is held
     * below the lock floor: the gun turns onto it and is refused, and no round leaves. The tail is
     * computed from the radius and lock floor in force, and the placement is read back on the sweep's
     * own quality.
     */
    @Test
    @Pins("INV-GUN-13")
    @RedWitness(member = "TileTurret#engagementIsAcquired", at = "return acquiredTrack() != null;",
            replacedBy = "return acquiredTrack() != null && !acquiredTrack().isHull();",
            fails = "INV-GUN-13 a gun on a hull held too poorly to lock must put no round into it", on = "2026-10-20")
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advance(client(), GameTicks.server(), dose);",
            why = "the dose is three of the gun's fire intervals on target with the hull held below the"
                    + " floor; the claim is that no round leaves, so no record of one can end the wait")
    public void aWeaklyHeldHullIsTrackedButNeverFiredOn() throws Exception {
        String what = "a hull at the edge of the envelope";
        Battery battery = battery(plot().siteAt(120, 20), what);
        double[] origin = battery.groundOrigin();
        double radius = configNumber("fireControlSensorRadius");
        WarShip hull = build(site(), null, what);
        // Aimed at 0.96 of the radius, deep in the tapering last quarter; where it lands, and that it is
        // below the floor there, is measured below rather than trusted.
        double[] box = parkEastOf(hull, origin, (int) Math.round(radius * 0.96D), what);

        long mark = log.mark();
        Sweep sweep = awaitSweep(mark, battery.sensorPos(), s -> "ACTIVE".equals(s.mode) && s.holds(hull.vsShip),
                what + " — the hull held at the edge");
        double quality = sweep.hull(hull.vsShip).get("quality").getAsDouble();
        double floor = configNumber("fireControlSensorLockQualityToFire");
        requireArranged(what + " — the hull must be held, and below the lock floor (" + floor + "): " + quality
                + " at " + nearestDistance(origin, box), quality > 0.0D && quality < floor);
        String decided = log.awaitRecordWithFields(mark, "turret_fire_decided",
                what + " — the gun must turn onto the hull and ask whether it may fire", FIRE_LINK_TICKS,
                "pos", battery.turretPos(), "caller", "auto");
        int dose = 3 * read("stellurgytest turret read " + battery.turretAt()).integer("fireInterval");
        GameTicks.advance(client(), GameTicks.server(), dose);
        List<String> fired = Weapons.firedSince(log, mark, battery.turret[0], battery.turret[1], battery.turret[2]);
        assertTrue("INV-GUN-13 a gun on a hull held too poorly to lock must put no round into it, its decision"
                        + " being an unlocked refusal: decided " + decided + ", rounds " + fired,
                fired.isEmpty() && "false".equals(Events.text(decided, "locked"))
                        && "false".equals(Events.text(decided, "permitted")));
    }

    /**
     * INV-GUN-14. The gun holds an acquired hull; the sweep is slowed to its longest interval and the
     * hull is moved right after a sweep, so no sweep sees the move. The gun's commanded point moves by
     * exactly the hull's move — the hull is at rest, so the point is its box's middle — while the
     * contact the sweep published still stands where it was taken. Both from one read of the gun.
     */
    @Test
    @Pins("INV-GUN-14")
    @RedWitness(member = "TileTurret#interceptOf", at = "return shipIntercept(track.getShip());",
            replacedBy = "return interceptOf(track.getPosition(), track.getVelocity());",
            fails = "INV-GUN-14 the gun must follow the hull by exactly its move", on = "2026-10-20")
    public void aGunFollowsAnAcquiredHullWhereItIsNotWhereTheSweepSawIt() throws Exception {
        String what = "an acquired hull moved between sweeps";
        Battery battery = battery(plot().siteAt(120, 20), what);
        battery.command(this, "holdfire", "true");
        WarShip hull = build(site(), null, what);
        parkEastOf(hull, battery.groundOrigin(), HELD_GAP, what);
        awaitSweep(log.mark(), battery.sensorPos(), s -> "ACTIVE".equals(s.mode) && s.holds(hull.vsShip),
                what + " — the hull acquired");
        try (ConfigFlag slow = ConfigFlag.set(commands(), "fireControlSensorScanIntervalTicks", 200)) {
            Sweep last = awaitSweep(log.mark(), battery.sensorPos(),
                    s -> "ACTIVE".equals(s.mode) && s.holds(hull.vsShip), what + " — the sweep that sets the long interval");
            JsonObject published = last.hull(hull.vsShip);
            requireArranged(what + " — the hull must be at rest, so the gun's point is its middle: " + last.record,
                    Math.abs(published.get("vx").getAsDouble()) < AT_REST_BLOCKS_PER_TICK
                            && Math.abs(published.get("vy").getAsDouble()) < AT_REST_BLOCKS_PER_TICK
                            && Math.abs(published.get("vz").getAsDouble()) < AT_REST_BLOCKS_PER_TICK);
            long moveMark = log.mark();
            Reply before = read("stellurgytest turret read " + battery.turretAt());
            Reply poseBefore = hull.info();
            hull.parkAt((int) Math.round(poseBefore.number("posX")), (int) Math.round(poseBefore.number("posY")),
                    (int) Math.round(poseBefore.number("posZ")) + 12, 0.5D);
            Reply poseAfter = hull.info();
            Reply after = read("stellurgytest turret read " + battery.turretAt());
            requireArranged(what + " — no sweep may have run since the move, or the published contact moved too",
                    Events.recordsWhere(log.since(moveMark, "sensor_swept"), "pos", battery.sensorPos()).isEmpty());
            requireArranged(what + " — the gun must be on the acquired hull both times: " + before + " / " + after,
                    hull.vsShip.equals(before.text("acquiredShip")) && hull.vsShip.equals(after.text("acquiredShip"))
                            && before.bool("hasTarget") && after.bool("hasTarget"));
            double[] move = {poseAfter.number("posX") - poseBefore.number("posX"),
                    poseAfter.number("posY") - poseBefore.number("posY"),
                    poseAfter.number("posZ") - poseBefore.number("posZ")};
            double[] followed = {after.number("targetX") - before.number("targetX"),
                    after.number("targetY") - before.number("targetY"),
                    after.number("targetZ") - before.number("targetZ")};
            // Positions are sums of doubles of magnitude ~4000, whose rounding is ~1e-12; 1e-6 is six
            // orders above that and seven below the twelve-block move a stale point would miss by.
            for (int axis = 0; axis < 3; axis++) {
                assertEquals("INV-GUN-14 the gun must follow the hull by exactly its move (axis " + axis + "): move "
                        + java.util.Arrays.toString(move) + ", followed " + java.util.Arrays.toString(followed),
                        move[axis], followed[axis], 1.0E-6D);
            }
            requireArranged(what + " — the published contact must still stand where the sweep took it, or the"
                    + " gun could be following a fresh contact: " + before + " / " + after,
                    before.number("acquiredX") == after.number("acquiredX")
                            && before.number("acquiredY") == after.number("acquiredY")
                            && before.number("acquiredZ") == after.number("acquiredZ"));
        }
    }

    /**
     * INV-GUN-15. The gun is firing on an acquired hull whose transponder is empty, under the rule
     * {@code TRANSPONDER}; the sweep is slowed to its longest interval. A device carrying the network's
     * code is put into that transponder: the gun's next fire decision calls the hull a friend and
     * refuses, before any sweep has run, and no round leaves after it.
     */
    @Test
    @Pins("INV-GUN-15")
    @RedWitness(verdict = "the trigger calls it a friend", member = "TileTurret#targetIsFriendly",
            at = "if (acquired != null && acquired.isHull()) {", replacedBy = "if (false) {",
            fails = "INV-GUN-15 a credential put aboard must stop the gun at its next decision", on = "2026-10-20")
    @RedWitness(verdict = "and refuses, before any sweep", member = "TileTurret#canFireNow",
            at = "return !targetIsFriendly()", replacedBy = "return true",
            fails = "INV-GUN-15 refused", on = "2026-10-20")
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advance(client(), GameTicks.server(), dose);",
            why = "the dose is two of the gun's fire intervals after the credential arrived, the span in which"
                    + " a gun the trigger did not stop fires again; the claim is that no round leaves, so no"
                    + " record of one can end the wait")
    public void aCredentialArrivingAboardStopsTheShootingAtTheTrigger() throws Exception {
        String what = "a gun firing on a hull that is about to be vouched for";
        Battery battery = battery(plot().siteAt(120, 20), what);
        battery.command(this, "code", OUR_CODE);
        battery.command(this, "allegiance", "TRANSPONDER");
        WarShip hull = build(site(), builder -> {
            int[] at = transponderCell(builder);
            place(at[0], at[1], at[2], "stellurgy:hullTransponder");
        }, what);
        int[] t = transponderAboard(hull);
        String transponder = site().dim + " " + t[0] + " " + t[1] + " " + t[2];
        parkEastOf(hull, battery.groundOrigin(), HELD_GAP, what);
        awaitFiredOn(log.mark(), battery, hull, what + " — the gun firing on the hull");
        try (ConfigFlag slow = ConfigFlag.set(commands(), "fireControlSensorScanIntervalTicks", 200)) {
            awaitSweep(log.mark(), battery.sensorPos(), s -> "ACTIVE".equals(s.mode) && s.holds(hull.vsShip),
                    what + " — the sweep that sets the long interval");
            long putMark = log.mark();
            arrange("stellurgytest transponder put " + transponder + " " + OUR_CODE).requireOk("our code aboard");
            log.awaitRecordWithFields(putMark, "turret_fire_decided",
                    "INV-GUN-15 a credential put aboard must stop the gun at its next decision", SWEEP_LINK_TICKS,
                    "pos", battery.turretPos(), "friendly", "true");
            int dose = 2 * read("stellurgytest turret read " + battery.turretAt()).integer("fireInterval");
            GameTicks.advance(client(), GameTicks.server(), dose);
            List<String> friendly = Events.recordsWhereAll(log.since(putMark, "turret_fire_decided"),
                    "pos", battery.turretPos(), "friendly", "true");
            double firstFriendly = Events.number(friendly.get(0), "seq");
            boolean sweptFirst = false;
            for (String sweep : Events.recordsWhere(log.since(putMark, "sensor_swept"), "pos", battery.sensorPos())) {
                sweptFirst |= Events.number(sweep, "seq") < firstFriendly;
            }
            boolean everyOneRefused = true;
            for (String decision : friendly) {
                everyOneRefused &= "false".equals(Events.text(decision, "permitted"));
            }
            List<String> fired = Weapons.firedSince(log, putMark, battery.turret[0], battery.turret[1], battery.turret[2]);
            assertTrue("INV-GUN-15 refused: the trigger, before any sweep, must refuse the hull and no round may"
                    + " leave — decisions " + friendly + ", a sweep first " + sweptFirst + ", rounds " + fired,
                    everyOneRefused && !sweptFirst && fired.isEmpty());
        }
    }

    // =========================================================================== the battery

    /** The ground battery a scenario builds: where its gun, sensor and console stand. */
    private static final class Battery {
        final int dim;
        final int[] turret;
        final int[] sensor;
        final int[] console;

        Battery(int dim, int[] turret, int[] sensor, int[] console) {
            this.dim = dim;
            this.turret = turret;
            this.sensor = sensor;
            this.console = console;
        }

        String turretAt() {
            return dim + " " + turret[0] + " " + turret[1] + " " + turret[2];
        }

        String sensorAt() {
            return dim + " " + sensor[0] + " " + sensor[1] + " " + sensor[2];
        }

        String consoleAt() {
            return dim + " " + console[0] + " " + console[1] + " " + console[2];
        }

        String turretPos() {
            return Weapons.at(turret[0], turret[1], turret[2]);
        }

        String sensorPos() {
            return Weapons.at(sensor[0], sensor[1], sensor[2]);
        }

        /** A ground block's place in the world: the middle of the block, as the frame seam puts it. */
        double[] groundOrigin() {
            return new double[]{sensor[0] + 0.5D, sensor[1] + 0.5D, sensor[2] + 0.5D};
        }

        /** One of the console's orders, through the console verb that sets it. */
        void command(HullContactGroupTest test, String order, String value) throws Exception {
            Reply reply = test.arrange("stellurgytest weaponconsole " + order + " " + dim + " " + console[0] + " "
                    + console[1] + " " + console[2] + " " + value).requireOk("console " + order);
            requireArranged("the console must be on the battery's network to take '" + order + "': " + reply,
                    reply.bool("applied"));
        }
    }

    /**
     * A stone platform under the gun and what stands west of it, a gun controller with one barrel above
     * it, a sensor west of the gun with a
     * creative plug west of that, and a console north of the gun — the sensor and console touching the
     * gun, so the three are one network. The gun is charged and the sensor set to illuminate. Every
     * block of it is west or north of the gun, so a hull to the east is in its line of fire.
     */
    private Battery battery(FixtureSite site, String what) throws Exception {
        // Four blocks of halo: the gun stands two in from the platform's west edge and its line of fire
        // must be clear from its muzzle (2.5 out) for three blocks more, which ends four past the
        // platform's east edge.
        site.requireClear(c -> arrange(c).raw(), 4, 6, what + " — the battery's working area and its line of fire");
        int x = site.x, y = site.y, z = site.z;
        // The platform stops at the gun's own column: anything standing east of it — a snow layer the
        // weather lays on bare stone included — is in the gun's line-of-fire check and refuses every round.
        arrange("stellurgytest fill " + site.dim + " " + x + " " + y + " " + z + " " + (x + 2) + " " + y + " "
                + (z + 4) + " minecraft:stone").requireOk("the battery's platform");
        long mark = log.markInstrumented();
        place(x + 2, y + 1, z + 2, "stellurgy:turret");
        place(x + 2, y + 2, z + 2, "stellurgy:gunBarrel");
        place(x + 1, y + 1, z + 2, "stellurgy:fireControlSensor");
        place(x, y + 1, z + 2, "libvulpes:creativePowerBattery");
        place(x + 2, y + 1, z + 1, "stellurgy:weaponConsole");
        Battery battery = new Battery(site.dim, new int[]{x + 2, y + 1, z + 2}, new int[]{x + 1, y + 1, z + 2},
                new int[]{x + 2, y + 1, z + 1});
        Weapons.awaitAssembled(log, mark, x + 2, y + 1, z + 2, 1, what + " — the gun counts its barrel");
        arrange("stellurgytest turret charge " + battery.turretAt()).requireOk("charge the gun");
        arrange("stellurgytest sensor mode " + battery.sensorAt() + " active").requireOk("illuminate");
        return battery;
    }

    private void place(int x, int y, int z, String block) throws Exception {
        Reply placed = arrange("stellurgytest place " + site().dim + " " + x + " " + y + " " + z + " " + block)
                .requireOk("place " + block);
        requireArranged("placing " + block + " at " + x + "," + y + "," + z + " must change the world: " + placed,
                placed.bool("placed"));
    }

    // =========================================================================== the hulls

    /** A tier-2 hull at {@code site}, through production's own assembly; disposed of when the scenario ends. */
    private WarShip build(FixtureSite site, WarShip.BeforeAssembly before, String what) throws Exception {
        WarShip ship = WarShip.build(log, commands(), site, before, what);
        built.add(ship);
        return ship;
    }

    /**
     * Where the fixture's free cell beside its flight computer is before assembly: one up from the
     * outer fuel tank, face to face with it, so the assembly takes it into the hull. The fixture lays
     * its craft around (builder.x+1, builder.y, builder.z+4).
     */
    private static int[] transponderCell(int[] builder) {
        return new int[]{builder[0] + 2, builder[1] + 3, builder[2] + 4};
    }

    /** The same cell aboard: one east and one down from the pilot seat, the assembly being rigid. */
    private static int[] transponderAboard(WarShip ship) throws Exception {
        int[] seat = ship.seat();
        return new int[]{seat[0] + 1, seat[1] - 1, seat[2]};
    }

    /**
     * Park {@code ship} so the near (west) face of its world box lies about {@code gap} blocks east of
     * {@code origin}, its middle level with the origin, and answer the box it then has. The pose is
     * parked on whole blocks, so the gap is approximate: the box is measured, never assumed.
     */
    private double[] parkEastOf(WarShip ship, double[] origin, int gap, String what) throws Exception {
        double[] box = bounds(ship);
        Reply pose = ship.info();
        double minX = box[0] - pose.number("posX");
        double midY = (box[1] + box[4]) * 0.5D - pose.number("posY");
        double midZ = (box[2] + box[5]) * 0.5D - pose.number("posZ");
        ship.parkAt((int) Math.round(origin[0] + gap - minX), (int) Math.round(origin[1] - midY),
                (int) Math.round(origin[2] - midZ), 0.5D);
        return bounds(ship);
    }

    /** Park {@code ship} with the bottom of its world box about {@code gap} blocks over {@code origin}. */
    private double[] parkAbove(WarShip ship, double[] origin, int gap, String what) throws Exception {
        double[] box = bounds(ship);
        Reply pose = ship.info();
        double midX = (box[0] + box[3]) * 0.5D - pose.number("posX");
        double minY = box[1] - pose.number("posY");
        double midZ = (box[2] + box[5]) * 0.5D - pose.number("posZ");
        ship.parkAt((int) Math.round(origin[0] - midX), (int) Math.round(origin[1] + gap - minY),
                (int) Math.round(origin[2] - midZ), 0.5D);
        return bounds(ship);
    }

    /** The hull's world box, ungrown: {@code minX, minY, minZ, maxX, maxY, maxZ}. */
    private double[] bounds(WarShip ship) throws Exception {
        Reply box = read("stellurgytest vs ship-bounds " + site().dim + " " + ship.vsShip).requireOk("the hull's box");
        requireArranged("the registry must know hull " + ship.vsShip + ": " + box, box.bool("known"));
        return new double[]{box.number("minX"), box.number("minY"), box.number("minZ"), box.number("maxX"),
                box.number("maxY"), box.number("maxZ")};
    }

    private double configNumber(String key) throws Exception {
        return read("stellurgytest config get " + key).requireOk("config get " + key).number("value");
    }

    // =========================================================================== geometry, the test's own

    private static double nearestDistance(double[] p, double[] box) {
        double dx = Math.max(0.0D, Math.max(box[0] - p[0], p[0] - box[3]));
        double dy = Math.max(0.0D, Math.max(box[1] - p[1], p[1] - box[4]));
        double dz = Math.max(0.0D, Math.max(box[2] - p[2], p[2] - box[5]));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double[] centreOf(double[] box) {
        return new double[]{(box[0] + box[3]) * 0.5D, (box[1] + box[4]) * 0.5D, (box[2] + box[5]) * 0.5D};
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean overlap(double[] a, double[] b) {
        return a[0] < b[3] && b[0] < a[3] && a[1] < b[4] && b[1] < a[4] && a[2] < b[5] && b[2] < a[5];
    }

    /**
     * How fast a track's range is shrinking, from what the SWEEP recorded: its velocity projected on the
     * line from the sensor's origin to the point its range is measured to (a hull's nearest point, a
     * creature's position). The ground sensor is at rest. Positive is closing.
     */
    private static double closing(Sweep sweep, JsonObject track) {
        double[] point = {track.get("x").getAsDouble(), track.get("y").getAsDouble(), track.get("z").getAsDouble()};
        if (track.has("minX")) {
            point = new double[]{
                    clamp(sweep.origin[0], track.get("minX").getAsDouble(), track.get("maxX").getAsDouble()),
                    clamp(sweep.origin[1], track.get("minY").getAsDouble(), track.get("maxY").getAsDouble()),
                    clamp(sweep.origin[2], track.get("minZ").getAsDouble(), track.get("maxZ").getAsDouble())};
        }
        double[] sight = {point[0] - sweep.origin[0], point[1] - sweep.origin[1], point[2] - sweep.origin[2]};
        double length = Math.sqrt(sight[0] * sight[0] + sight[1] * sight[1] + sight[2] * sight[2]);
        double along = track.get("vx").getAsDouble() * sight[0] + track.get("vy").getAsDouble() * sight[1]
                + track.get("vz").getAsDouble() * sight[2];
        return -along / length;
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    // =========================================================================== the sweep record

    /** One {@code sensor_swept} record, read as data. */
    private static final class Sweep {
        final String record;
        final String mode;
        final double[] origin;
        final List<JsonObject> tracks = new ArrayList<>();

        Sweep(String record) {
            this.record = record;
            this.mode = Events.text(record, "mode");
            String x = Events.text(record, "originX");
            this.origin = x == null ? null : new double[]{Events.number(record, "originX"),
                    Events.number(record, "originY"), Events.number(record, "originZ")};
            JsonElement parsed = new JsonParser().parse(String.valueOf(Events.text(record, "tracks")));
            JsonArray all = parsed.getAsJsonArray();
            for (JsonElement one : all) {
                tracks.add(one.getAsJsonObject());
            }
        }

        JsonObject hull(String ship) {
            for (JsonObject t : tracks) {
                if (t.has("ship") && !t.get("ship").isJsonNull() && ship.equals(t.get("ship").getAsString())) {
                    return t;
                }
            }
            return null;
        }

        JsonObject entity(String entityId) {
            for (JsonObject t : tracks) {
                if (entityId.equals(t.get("entityId").getAsString())) {
                    return t;
                }
            }
            return null;
        }

        boolean holds(String ship) {
            return hull(ship) != null;
        }

        boolean holdsEntity(String entityId) {
            return entity(entityId) != null;
        }

        int rankOf(String ship) {
            return tracks.indexOf(hull(ship));
        }

        int rankOfEntity(String entityId) {
            return tracks.indexOf(entity(entityId));
        }

        JsonObject first() {
            requireArranged("a sweep with no contact has no first: " + record, !tracks.isEmpty());
            return tracks.get(0);
        }
    }

    /**
     * The FIRST sweep of the sensor at {@code sensorPos} since {@code mark} that {@code premise} admits
     * — a link on the sensor's own record, named by its position.
     */
    private Sweep awaitSweep(long mark, String sensorPos, Predicate<Sweep> premise, String what) throws Exception {
        String reply = log.awaitMatching(mark, "sensor_swept", r -> firstSweep(r, sensorPos, premise) != null,
                "of the sensor at " + sensorPos + " meeting its premise", what, SWEEP_LINK_TICKS);
        return firstSweep(reply, sensorPos, premise);
    }

    private static Sweep firstSweep(String reply, String sensorPos, Predicate<Sweep> premise) {
        for (String record : Events.recordsWhere(reply, "pos", sensorPos)) {
            Sweep sweep = new Sweep(record);
            if (premise.test(sweep)) {
                return sweep;
            }
        }
        return null;
    }

    /**
     * A round left the battery's gun while its acquisition named {@code hull}. A wait that expires says,
     * beside the chain, what the gun itself reports — its bearing, its target, its acquisition — since
     * "no round" has several causes the chain alone does not tell apart.
     */
    private void awaitFiredOn(long mark, Battery battery, WarShip hull, String what) throws Exception {
        try {
            log.awaitRecordWithFields(mark, "turret_fired", what, FIRE_LINK_TICKS,
                    "pos", battery.turretPos(), "acquiredShip", hull.vsShip);
        } catch (AssertionError none) {
            throw new AssertionError(none.getMessage() + " | the gun now: "
                    + read("stellurgytest turret read " + battery.turretAt()) + " | the hull's box: "
                    + java.util.Arrays.toString(bounds(hull)) + " | rounds in this world: "
                    + read("stellurgytest shot list " + battery.dim).integer("count")
                    + " | its decisions: " + Events.recordsWhere(log.since(mark, "turret_fire_decided"), "pos",
                    battery.turretPos())
                    + " | the line of fire east of the gun: " + read("stellurgytest shot crossing " + battery.dim
                    + " " + (battery.turret[0] + 3.0D) + " " + (battery.turret[1] + 0.5D) + " " + (battery.turret[2] + 0.5D)
                    + " " + (battery.turret[0] + 6.0D) + " " + (battery.turret[1] + 0.5D) + " "
                    + (battery.turret[2] + 0.5D)), none);
        }
    }

    /** The first sweep after now leaves {@code hull} out, while that sweep illuminates. */
    private void assertLeftOut(Battery battery, WarShip hull, String verdict) throws Exception {
        Sweep sweep = awaitSweep(log.mark(), battery.sensorPos(), s -> "ACTIVE".equals(s.mode),
                "the illuminating sweep after the act, for: " + verdict);
        assertTrue(verdict + " — hull " + hull.vsShip + " must not be held: " + sweep.record, !sweep.holds(hull.vsShip));
    }

    /** The first sweep after now holds {@code hull}. */
    private void assertHeld(Battery battery, WarShip hull, String verdict) throws Exception {
        Sweep sweep = awaitSweep(log.mark(), battery.sensorPos(), s -> "ACTIVE".equals(s.mode),
                "the illuminating sweep after the act, for: " + verdict);
        assertTrue(verdict + " — hull " + hull.vsShip + " must be held: " + sweep.record, sweep.holds(hull.vsShip));
    }
}
