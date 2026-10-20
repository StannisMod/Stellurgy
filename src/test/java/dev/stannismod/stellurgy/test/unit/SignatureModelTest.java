package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.sensor.SensorMode;
import dev.stannismod.stellurgy.api.sensor.TargetTrack;
import dev.stannismod.stellurgy.sensor.SignatureModel;
import dev.stannismod.stellurgy.sensor.ThreatOrder;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The one claim this model exists to make: <b>how far away a thing can be noticed and how well it
 * can be held are different questions with different answers.</b>
 *
 * <p>Everything below is a way of failing if those two ever collapse into one number. Nothing here
 * pins a constant — the reference temperature, the reference range and the blocks-per-watt scale are
 * balance, and a test that pinned them would go red the first time somebody tuned the mechanic
 * without changing anything about how it works. What is pinned is the SHAPE: which variable each
 * term depends on, and which it must be blind to.</p>
 *
 * <p>The fire-control sensor's other pure law lives here too, as the owner's laws do: the order its
 * contacts are handed over in (fire-control-sensor INV-FCS-14), below.</p>
 */
public class SignatureModelTest {

    private static final double EPSILON = 1.0E-9D;

    /**
     * The whole design in one scenario. Two ships shed <b>exactly the same watts</b> — one a large
     * cool array, the other a compact array sixteen times hotter and sixteen times smaller. They are
     * therefore noticed at the same range, and they are not remotely the same target: the compact
     * hot one is held sixteen times better at any distance.
     *
     * <p>This fails the moment either term is computed from the other's input — if range stopped
     * depending on area, or quality started to.</p>
     */
    @Test
    public void twoTargetsSheddingTheSameWattsAreNoticedAlikeAndHeldNothingAlike() {
        double coolTemperature = 300.0D, coolArea = 160.0D;
        double hotTemperature = coolTemperature * 2.0D;      // radiance ×16
        double hotArea = coolArea / 16.0D;                   // so the total power is identical

        assertEquals("the scenario is only about the difference between the two terms if the total"
                + " power really is equal",
                SignatureModel.radiatedPower(coolTemperature, coolArea),
                SignatureModel.radiatedPower(hotTemperature, hotArea), 1.0E-6D);

        assertEquals("equal total power must mean an equal detection range — that term may not see"
                + " temperature except through the power it produces",
                SignatureModel.detectionRangeBlocks(coolTemperature, coolArea),
                SignatureModel.detectionRangeBlocks(hotTemperature, hotArea), 1.0E-6D);

        double range = 200.0D;
        double coolLock = SignatureModel.passiveQuality(coolTemperature, range);
        double hotLock = SignatureModel.passiveQuality(hotTemperature, range);
        assertTrue("this comparison means nothing if either quality is clamped",
                coolLock > 0.0D && hotLock > 0.0D && hotLock < 1.0D);
        assertEquals("the hotter array must be sixteen times the lock at the same range: quality is"
                + " radiance, and radiance is temperature to the fourth — if area leaked into this"
                + " term, these two would be equal and the build trade would be gone",
                16.0D, hotLock / coolLock, 1.0E-6D);
    }

    /**
     * The two terms disagree about which of two targets is the better one, and that disagreement is
     * the mechanic. A large cool radiator sheds MORE total power than a small hot one — so it is
     * noticed from further away — while the small hot one is the better lock at any given range.
     */
    @Test
    public void aLargeCoolTargetIsSeenFurtherOffAndHeldWorseThanASmallHotOne() {
        double coolTemperature = 350.0D, coolArea = 400.0D;
        double hotTemperature = 900.0D, hotArea = 1.0D;
        assertTrue("this scenario needs the cool one to be the brighter total emitter, or it is not"
                + " testing the disagreement at all",
                SignatureModel.radiatedPower(coolTemperature, coolArea)
                        > SignatureModel.radiatedPower(hotTemperature, hotArea));

        assertTrue("the bigger total emitter must be detectable further away",
                SignatureModel.detectionRangeBlocks(coolTemperature, coolArea)
                        > SignatureModel.detectionRangeBlocks(hotTemperature, hotArea));
        assertTrue("and the hotter one must still be the better lock at the same range — if the"
                + " brighter total emitter also locked better, there would be one number here",
                SignatureModel.passiveQuality(hotTemperature, 60.0D)
                        > SignatureModel.passiveQuality(coolTemperature, 60.0D));
    }

    /** Range falls off with the square: twice as far is a quarter as well held. */
    @Test
    public void passiveQualityFallsWithTheSquareOfTheRange() {
        double near = SignatureModel.passiveQuality(400.0D, 40.0D);
        double far = SignatureModel.passiveQuality(400.0D, 80.0D);
        assertTrue("this scenario needs a quality that is not already clamped at either end",
                near > 0.0D && near < 1.0D && far > 0.0D);
        assertEquals("doubling the range must quarter the quality", near / 4.0D, far, near * 1.0E-6D);
    }

    /**
     * The reason the active mode exists: a cold, quiet thing that passive listening cannot hold is
     * held perfectly well the moment you illuminate it — at the price of illuminating.
     */
    @LegacyWitness(prose = "with {@code SignatureModel#activeQuality} at {@code return clamp01(plateau);}'s plateau cut to a fifth, this fails with"
            + " \"and illuminating must lift it over the lock floor (0.25), or going active buys the shooter"
            + " nothing: 0.19\". 2026-09-30.")
    @LegacyWitness(prose = "with {@code SignatureModel#passiveQuality} at {@code return clamp01(temperatureRatio * rangeRatio * rangeRatio);} dividing by the temperature ratio instead of"
            + " multiplying (a cold body read as loud), this fails at \"a cold target at range must be below the"
            + " lock floor (0.25) by listening alone ...: 1.0\" (2026-09-30). Two gentler inversions stayed green,"
            + " and they say how far below the floor the healthy value sits: the passive quality here is 0.0124"
            + " ((280/500)^4 * (32/90)^2), so ten times it (0.124) and a range fall-off made linear (0.035) are"
            + " both still under 0.25.")
    @Test
    public void illuminatingHoldsAColdTargetThatListeningCannot() {
        double coldTarget = 280.0D;
        double range = 90.0D;
        double listening = SignatureModel.passiveQuality(coldTarget, range);
        double illuminating = SignatureModel.activeQuality(range, 128.0D, 0.95D);

        // "Cannot hold" and "holds" are production's own line, not one invented here: the quality a
        // contact must reach before a gun fires at it (the configured default, read from the class).
        double lockFloor = StellurgyConfiguration.getCurrentConfig().fireControlSensorLockQualityToFire;
        assertTrue("the lock floor is switched off, so nothing here straddles it: " + lockFloor,
                lockFloor > 0.0D && lockFloor < 1.0D);
        assertTrue("a cold target at range must be below the lock floor (" + lockFloor + ") by listening"
                + " alone, or going dark buys a target nothing: " + listening, listening < lockFloor);
        assertTrue("and illuminating must lift it over the lock floor (" + lockFloor + "), or going"
                + " active buys the shooter nothing: " + illuminating, illuminating >= lockFloor);
    }

    /** Nothing is invisible: silence moves the line at which a thing is noticed, it does not erase it. */
    @Test
    public void aSilentColdBodyIsStillDetectableSomewhere() {
        double range = SignatureModel.detectionRangeBlocks(SignatureModel.AMBIENT_BODY_KELVIN, 2.0D);
        assertTrue("an ordinary warm body radiates above the background and must be detectable at"
                + " some finite range: " + range, range > 0.0D);
    }

    /** Outside the envelope an illuminator holds nothing at all — the radius is a real limit. */
    @Test
    public void illuminationStopsAtTheEdgeOfTheEnvelope() {
        assertEquals("a contact beyond the sensor's radius is not held, however bright the beam",
                0.0D, SignatureModel.activeQuality(130.0D, 128.0D, 0.95D), EPSILON);
        assertTrue("and just inside it is held, so the zero above is about the edge and not about"
                + " the whole mode being dead",
                SignatureModel.activeQuality(120.0D, 128.0D, 0.95D) > 0.0D);
    }

    // ---- INV-FCS-14: the order contacts are handed over in (ThreatOrder)
    //
    // Each law is checked over seeded generated pairs or lists, every case built from the contract's
    // own quantities: a contact at RANGE r along one of the six axes from the sensor, CLOSING at speed
    // c relative to the sensor (its velocity is the sensor's own plus -c along the line of sight), at
    // quality q. A hull is built so the nearest point of its box IS that point — the box starts there
    // and reaches away from the sensor — so a hull and a creature with the same (r, c, q) are the same
    // threat by construction. What this does NOT see: the sweep that builds the tracks (TacticalScan,
    // which reads the world), and whether the closing speed a sweep measures is the one a contact has.

    /** A constant: the seed, so a red replays bit for bit. */
    private static final long ORDER_SEED = 3310L;

    /**
     * A constant: cases per law. Each law states a minimum count for the region its inversion lives in,
     * and at this size every region is drawn far above its minimum (each is a fixed share of the draw).
     */
    private static final int ORDER_CASES = 400;

    /** A constant: the minimum number of cases a law's red region must be visited by. */
    private static final int ORDER_REGION_MIN = 40;

    /** The lock floor the comparator is placed against: the shipped default, read from a fresh instance. */
    private static double shippedLockFloor() {
        return new StellurgyConfiguration().fireControlSensorLockQualityToFire;
    }

    /** One of the six unit axes, drawn. */
    private static Vec3d anAxis(Random random) {
        Vec3d[] axes = {new Vec3d(1, 0, 0), new Vec3d(-1, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, -1, 0),
                new Vec3d(0, 0, 1), new Vec3d(0, 0, -1)};
        return axes[random.nextInt(axes.length)];
    }

    /**
     * A contact of either kind at {@code range} along {@code axis} from {@code origin}, closing at
     * {@code closing} relative to a sensor moving at {@code own}.
     */
    private static TargetTrack contact(boolean hull, String id, Vec3d origin, Vec3d own, Vec3d axis,
                                       double range, double closing, double quality) {
        Vec3d near = origin.add(axis.scale(range));
        Vec3d velocity = own.add(axis.scale(-closing));
        if (!hull) {
            return TargetTrack.ofEntity(UUID.fromString(id), near, velocity, quality, SensorMode.ACTIVE, 0.0D,
                    range);
        }
        Vec3d far = near.add(axis.scale(4.0D));
        double lx = 2.0D * (1.0D - Math.abs(axis.x));
        double ly = 2.0D * (1.0D - Math.abs(axis.y));
        double lz = 2.0D * (1.0D - Math.abs(axis.z));
        AxisAlignedBB box = new AxisAlignedBB(Math.min(near.x, far.x) - lx, Math.min(near.y, far.y) - ly,
                Math.min(near.z, far.z) - lz, Math.max(near.x, far.x) + lx, Math.max(near.y, far.y) + ly,
                Math.max(near.z, far.z) + lz);
        return TargetTrack.ofHull(id, box, velocity, quality, SensorMode.ACTIVE, range);
    }

    private static String anId(Random random) {
        return new UUID(random.nextLong(), random.nextLong()).toString();
    }

    private static Vec3d aMotion(Random random) {
        return new Vec3d(random.nextDouble() - 0.5D, random.nextDouble() - 0.5D, random.nextDouble() - 0.5D);
    }

    /**
     * P1: a contact the battery can lock ranks before one it cannot, however much nearer and faster the
     * one it cannot is (fire-control-sensor MECH-FCS-08, stage 1). Region visited and counted: the
     * unlockable contact is the nearer AND the sooner to arrive — where a threat-first order would put
     * it ahead.
     */
    @Test
    @Pins("INV-FCS-14")
    @RedWitness(member = "ThreatOrder#of", at = "comparingInt(t -> t.isLocked(lockFloor) ? 0 : 1)",
            replacedBy = "comparingInt(t -> 0)", fails = "INV-FCS-14 P1", on = "2026-10-20")
    public void aContactTheBatteryCanLockOutranksOneItCannot() {
        Random random = new Random(ORDER_SEED);
        double floor = shippedLockFloor();
        int unlockableIsTheGreaterThreat = 0;
        for (int i = 0; i < ORDER_CASES; i++) {
            Vec3d origin = aMotion(random).scale(200.0D);
            Vec3d own = aMotion(random);
            double heldRange = 1.0D + random.nextDouble() * 90.0D;
            double poorRange = 1.0D + random.nextDouble() * 90.0D;
            double heldClosing = random.nextDouble() * 2.0D - 1.0D;
            double poorClosing = random.nextDouble() * 2.0D - 1.0D;
            TargetTrack held = contact(random.nextBoolean(), anId(random), origin, own, anAxis(random),
                    heldRange, heldClosing, floor + random.nextDouble() * (1.0D - floor));
            TargetTrack poor = contact(random.nextBoolean(), anId(random), origin, own, anAxis(random),
                    poorRange, poorClosing, random.nextDouble() * floor);
            double heldArrival = heldClosing > 0.0D ? heldRange / heldClosing : Double.POSITIVE_INFINITY;
            double poorArrival = poorClosing > 0.0D ? poorRange / poorClosing : Double.POSITIVE_INFINITY;
            if (poorRange < heldRange && poorArrival < heldArrival) {
                unlockableIsTheGreaterThreat++;
            }
            java.util.Comparator<TargetTrack> order = ThreatOrder.of(origin, own, floor);
            assertTrue("INV-FCS-14 P1: a lockable contact (range " + heldRange + ", closing " + heldClosing
                    + ") must rank before an unlockable one (range " + poorRange + ", closing " + poorClosing
                    + "), case " + i, order.compare(held, poor) < 0 && order.compare(poor, held) > 0);
        }
        assertTrue("the draw must visit the region where the unlockable contact is the nearer and sooner"
                + " one, or nothing above could tell a lock-first order from a threat-first one: "
                + unlockableIsTheGreaterThreat, unlockableIsTheGreaterThreat >= ORDER_REGION_MIN);
    }

    /**
     * P2: with everything else equal — both lockable, the same quality, neither closing — the nearer
     * ranks first (stage 4). Region visited and counted: the farther one carries the smaller identity,
     * where the last stage alone would put it ahead.
     */
    @Test
    @Pins("INV-FCS-14")
    @RedWitness(member = "ThreatOrder#of", at = ".thenComparingDouble(TargetTrack::getDistance)",
            replacedBy = "", fails = "INV-FCS-14 P2", on = "2026-10-20")
    public void allElseEqualTheNearerRanksFirst() {
        Random random = new Random(ORDER_SEED + 1L);
        double floor = shippedLockFloor();
        int fartherHasTheSmallerId = 0;
        for (int i = 0; i < ORDER_CASES; i++) {
            Vec3d origin = aMotion(random).scale(200.0D);
            Vec3d own = aMotion(random);
            double quality = floor + random.nextDouble() * (1.0D - floor);
            double nearRange = 1.0D + random.nextDouble() * 45.0D;
            double farRange = nearRange + 0.5D + random.nextDouble() * 45.0D;
            // Not closing: at rest relative to the sensor, or opening — the arrival stage then has no
            // arrival to compare, which is the case this law is about.
            double nearClosing = -random.nextDouble() * (random.nextBoolean() ? 1.0D : 0.0D);
            double farClosing = -random.nextDouble() * (random.nextBoolean() ? 1.0D : 0.0D);
            String nearId = anId(random);
            String farId = anId(random);
            if (farId.compareTo(nearId) < 0) {
                fartherHasTheSmallerId++;
            }
            TargetTrack near = contact(random.nextBoolean(), nearId, origin, own, anAxis(random), nearRange,
                    nearClosing, quality);
            TargetTrack far = contact(random.nextBoolean(), farId, origin, own, anAxis(random), farRange,
                    farClosing, quality);
            assertTrue("INV-FCS-14 P2: with nothing else between them the nearer (" + nearRange
                    + ") must rank before the farther (" + farRange + "), case " + i,
                    ThreatOrder.of(origin, own, floor).compare(near, far) < 0);
        }
        assertTrue("the draw must visit pairs whose farther member has the smaller identity: "
                + fartherHasTheSmallerId, fartherHasTheSmallerId >= ORDER_REGION_MIN);
    }

    /**
     * P3: with everything else equal — both lockable, the same quality, the same range — the one closing
     * faster RELATIVE TO THE SENSOR ranks first (stage 3); the slower may be closing, at rest or opening.
     * The sensor itself moves in every case, so a closing speed taken over the ground instead of relative
     * to it would mis-order. Region visited and counted: the slower carries the smaller identity.
     */
    @Test
    @Pins("INV-FCS-14")
    @RedWitness(member = "ThreatOrder#of", at = ".thenComparingDouble(t -> timeToContact(t, origin, own))",
            replacedBy = "", fails = "INV-FCS-14 P3", on = "2026-10-20")
    public void allElseEqualTheFasterClosingRanksFirst() {
        Random random = new Random(ORDER_SEED + 2L);
        double floor = shippedLockFloor();
        int slowerHasTheSmallerId = 0;
        for (int i = 0; i < ORDER_CASES; i++) {
            Vec3d origin = aMotion(random).scale(200.0D);
            Vec3d own = aMotion(random);
            double quality = floor + random.nextDouble() * (1.0D - floor);
            double range = 1.0D + random.nextDouble() * 90.0D;
            double faster = 0.05D + random.nextDouble();
            double slower = faster - 0.05D - random.nextDouble() * 1.5D;
            String fastId = anId(random);
            String slowId = anId(random);
            if (slowId.compareTo(fastId) < 0) {
                slowerHasTheSmallerId++;
            }
            TargetTrack fast = contact(random.nextBoolean(), fastId, origin, own, anAxis(random), range, faster,
                    quality);
            TargetTrack slow = contact(random.nextBoolean(), slowId, origin, own, anAxis(random), range, slower,
                    quality);
            assertTrue("INV-FCS-14 P3: at the same range the faster-closing (" + faster
                    + ") must rank before the slower (" + slower + "), case " + i,
                    ThreatOrder.of(origin, own, floor).compare(fast, slow) < 0);
        }
        assertTrue("the draw must visit pairs whose slower member has the smaller identity: "
                + slowerHasTheSmallerId, slowerHasTheSmallerId >= ORDER_REGION_MIN);
    }

    /**
     * P5: the order is KIND-BLIND. A list of mixed contacts is sorted; one creature in it is replaced by
     * a hull that is the same threat — same identity text, range, closing speed and quality, the nearest
     * point of its box where the creature stood — and the sorted identities must not move. Region
     * visited and counted: a creature ranked ahead of the one replaced, which a hull-first order would
     * jump the hull over.
     */
    @Test
    @Pins("INV-FCS-14")
    @RedWitness(member = "ThreatOrder#of", at = "comparingInt(t -> t.isLocked(lockFloor) ? 0 : 1)",
            replacedBy = "comparingInt(t -> (t.isLocked(lockFloor) ? 0 : 2) + (t.isHull() ? 0 : 1))",
            fails = "INV-FCS-14 P5", on = "2026-10-20")
    public void theOrderIsBlindToWhetherAContactIsAHull() {
        Random random = new Random(ORDER_SEED + 3L);
        double floor = shippedLockFloor();
        int aCreatureRankedAheadOfTheReplaced = 0;
        for (int i = 0; i < ORDER_CASES; i++) {
            Vec3d origin = aMotion(random).scale(200.0D);
            Vec3d own = aMotion(random);
            int size = 3 + random.nextInt(5);
            List<TargetTrack> before = new ArrayList<>();
            List<Object[]> drawn = new ArrayList<>();
            for (int k = 0; k < size; k++) {
                boolean hull = k > 0 && random.nextBoolean();
                Object[] d = {anId(random), anAxis(random), 1.0D + random.nextDouble() * 90.0D,
                        random.nextDouble() * 2.0D - 1.0D, random.nextDouble()};
                drawn.add(d);
                before.add(contact(hull, (String) d[0], origin, own, (Vec3d) d[1], (Double) d[2], (Double) d[3],
                        (Double) d[4]));
            }
            // Contact 0 is a creature by construction; it becomes the hull twin.
            Object[] twin = drawn.get(0);
            List<TargetTrack> after = new ArrayList<>(before);
            after.set(0, contact(true, (String) twin[0], origin, own, (Vec3d) twin[1], (Double) twin[2],
                    (Double) twin[3], (Double) twin[4]));
            java.util.Comparator<TargetTrack> order = ThreatOrder.of(origin, own, floor);
            before.sort(order);
            after.sort(order);
            for (TargetTrack t : before) {
                if (t.getSubjectId().equals(twin[0])) {
                    break;
                }
                if (!t.isHull()) {
                    aCreatureRankedAheadOfTheReplaced++;
                    break;
                }
            }
            assertEquals("INV-FCS-14 P5: replacing a creature by a hull that is the same threat must not move"
                    + " anything in the order, case " + i, idsOf(before), idsOf(after));
        }
        assertTrue("the draw must visit lists where a creature ranks ahead of the one replaced: "
                + aCreatureRankedAheadOfTheReplaced, aCreatureRankedAheadOfTheReplaced >= ORDER_REGION_MIN);
    }

    /**
     * P6: the order is TOTAL and does not depend on the order the candidates were listed in — the same
     * contacts listed two ways sort to the same sequence. Region visited and counted: lists in which
     * several contacts tie on every stage but the last, which is where a sweep's enumeration order
     * would otherwise show through.
     */
    @Test
    @Pins("INV-FCS-14")
    @RedWitness(member = "ThreatOrder#of", at = ".thenComparing(TargetTrack::getSubjectId)", replacedBy = "",
            fails = "INV-FCS-14 P6", on = "2026-10-20")
    public void theOrderIsTotalAndIndependentOfEnumeration() {
        Random random = new Random(ORDER_SEED + 4L);
        double floor = shippedLockFloor();
        int listsWithATie = 0;
        for (int i = 0; i < ORDER_CASES; i++) {
            Vec3d origin = aMotion(random).scale(200.0D);
            Vec3d own = aMotion(random);
            Vec3d axis = anAxis(random);
            double range = 1.0D + random.nextDouble() * 90.0D;
            double closing = random.nextDouble() * 2.0D - 1.0D;
            double quality = random.nextDouble();
            List<TargetTrack> listed = new ArrayList<>();
            int tied = 2 + random.nextInt(4);
            for (int k = 0; k < tied; k++) {
                listed.add(contact(random.nextBoolean(), anId(random), origin, own, axis, range, closing, quality));
            }
            for (int k = 0; k < random.nextInt(4); k++) {
                listed.add(contact(random.nextBoolean(), anId(random), origin, own, anAxis(random),
                        1.0D + random.nextDouble() * 90.0D, random.nextDouble() * 2.0D - 1.0D, random.nextDouble()));
            }
            List<TargetTrack> otherWay = new ArrayList<>(listed);
            java.util.Collections.shuffle(otherWay, random);
            if (!idsOf(tiedIn(listed, range)).equals(idsOf(tiedIn(otherWay, range)))) {
                // The tied contacts come in two different orders: a sort that kept the order it was
                // handed would answer two different sequences here.
                listsWithATie++;
            }
            java.util.Comparator<TargetTrack> order = ThreatOrder.of(origin, own, floor);
            listed.sort(order);
            otherWay.sort(order);
            assertEquals("INV-FCS-14 P6: the same contacts listed two ways must sort to one sequence, case " + i,
                    idsOf(listed), idsOf(otherWay));
        }
        assertTrue("the draw must visit lists whose contacts tied on every stage but identity were listed in"
                + " two different orders: " + listsWithATie, listsWithATie >= ORDER_REGION_MIN);
    }

    /**
     * The members of {@code tracks} at the tied range, in the order listed. The untied extras draw their
     * range from a continuum, so none lands on it exactly.
     */
    private static List<TargetTrack> tiedIn(List<TargetTrack> tracks, double range) {
        List<TargetTrack> tied = new ArrayList<>();
        for (TargetTrack t : tracks) {
            if (t.getDistance() == range) {
                tied.add(t);
            }
        }
        return tied;
    }

    private static List<String> idsOf(List<TargetTrack> tracks) {
        List<String> ids = new ArrayList<>();
        for (TargetTrack t : tracks) {
            ids.add(t.getSubjectId());
        }
        return ids;
    }
}
