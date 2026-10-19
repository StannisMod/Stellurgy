package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Regions;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.junit.Test;
import org.valkyrienskies.mod.common.collision.ContactSolve;
import org.valkyrienskies.mod.common.collision.WorldPhysicsCollider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertTrue;

/**
 * The LAWS of the physics substrate's terrain contact solve ({@code valkyrienskies} INV-VSK-14, INV-VSK-15):
 * when a contact bounces, and that a hull standing on its support stays where it stands.
 *
 * <p>The subject is {@link ContactSolve#solve} and {@link ContactSolve#restitutionThreshold}, called as the
 * collider calls them: the contacts of a step, each carrying over the push the same contact ended the previous
 * step with, visited in a shuffled order. What is NOT production here, and is the experiment's own: the hull
 * (a box of declared mass and inertia), its contact points on its underside, and gravity added to its velocity
 * once per step — the stimulus. Positions are not integrated: the contacts keep the depth they were given, so
 * a step is the same question asked again with the answer the last one left behind.</p>
 *
 * <p>Not seen: the narrow phase that finds the contacts and their depths, the threads, and the integrator
 * ({@code WorldPhysicsCollider}, {@code PhysicsCalculations}). A real hull on a real pad is the harness's
 * ({@code TierTwoCraftFlightModelGroupTest#anAssembledShipStaysWhereItWasBuilt}).</p>
 */
public class ContactSolveLawsTest {

    /** A constant: the engine's step, 1/60 of an engine second ({@code VSConfig.getTimeSimulatedPerTick}). */
    private static final double DT = 1.0D / 60.0D;

    /** A constant: the engine's standard gravity, blocks per engine second² ({@code PhysicsUnits.ENGINE_GRAVITY}). */
    private static final double G = dev.stannismod.stellurgy.integration.vs.PhysicsUnits.ENGINE_GRAVITY;

    /** A constant: the passes the collider gives the solve ({@code WorldPhysicsCollider}'s own count). */
    private static final int PASSES = 8;

    /**
     * A constant: the hull, a 3 × 1 × 3 block slab of 9 tonnes — an experiment input; every verdict is a
     * velocity, which mass and size scale out of.
     */
    private static final double MASS = 9000.0D;

    /**
     * A constant: how deep the hull's underside sits in its support, blocks — an experiment input, of the order
     * a resting hull settles at (the engine's position correction pushes it out at twice its depth a second).
     */
    private static final double DEPTH = 0.002D;

    /**
     * A constant: "does not creep", from the contract — under one texture pixel (1/16 block) of drift in an
     * hour, so no velocity across the support above {@code (1/16) / 3600} blocks per engine second, and no turn
     * that moves the slab's farthest point that fast.
     */
    private static final double CREEP = (1.0D / 16.0D) / 3600.0D;

    /** A constant: one minute of steps at rest — 3 600 calls of the law, each judged. */
    private static final int MINUTE = 3600;

    /**
     * A constant: the sideways nudge, blocks per engine second — between what kinetic friction can take out in
     * one step ({@code 0.15·g·dt} = 0.08) and what the static cone can ({@code 0.74·g·dt} = 0.39), so only a
     * static cone stops it in a step.
     */
    private static final double NUDGE = 0.2D;

    /**
     * A constant: a contact solve answer of a bounce is {@code e × closing} up; "does not bounce" allows a hundredth of the
     * smallest bounce in the region, and "bounces" asks for the bounce to a hundredth of itself.
     */
    private static final double BOUNCE_SHARE = 0.01D;

    /** The slab's underside sample points, relative to its geometric centre: four under each of nine blocks. */
    private static List<Vector3d> underside() {
        List<Vector3d> points = new ArrayList<>();
        for (int bx = -1; bx <= 1; bx++) {
            for (int bz = -1; bz <= 1; bz++) {
                for (int sx = -1; sx <= 1; sx += 2) {
                    for (int sz = -1; sz <= 1; sz += 2) {
                        points.add(new Vector3d(bx + 0.25D * sx, -0.5D, bz + 0.25D * sz));
                    }
                }
            }
        }
        return points;
    }

    /** The slab's inverse inertia about its centre of mass (a solid 3 × 1 × 3 box). */
    private static Matrix3dc invInertia() {
        double ixx = MASS * (1.0D + 9.0D) / 12.0D;
        double iyy = MASS * (9.0D + 9.0D) / 12.0D;
        double izz = MASS * (9.0D + 1.0D) / 12.0D;
        return new Matrix3d(1.0D / ixx, 0, 0, 0, 1.0D / iyy, 0, 0, 0, 1.0D / izz);
    }

    /** The step's contacts, the points taken relative to {@code com}, each carrying what it ended last step with. */
    private static List<ContactSolve.Contact> contacts(Vector3d com, Map<ContactSolve.Key, ContactSolve.Contact> last,
                                                       Random order) {
        List<Vector3d> points = underside();
        List<ContactSolve.Contact> step = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            ContactSolve.Key key = new ContactSolve.Key(i, 0L, 0);
            ContactSolve.Contact c = new ContactSolve.Contact(key,
                    points.get(i).sub(com, new Vector3d()), new Vector3d(0, -1, 0), DEPTH);
            ContactSolve.Contact previous = last.get(key);
            if (previous != null) {
                c.carryOver(previous);
            }
            step.add(c);
        }
        Collections.shuffle(step, order);
        return step;
    }

    private static void step(List<ContactSolve.Contact> contacts, Vector3d v, Vector3d w, double gravity) {
        ContactSolve.solve(contacts, v, w, 1.0D / MASS, invInertia(),
                ContactSolve.restitutionThreshold(gravity, DT), WorldPhysicsCollider.COEFFICIENT_OF_RESTITUTION,
                WorldPhysicsCollider.KINETIC_FRICTION_COEFFICIENT, PASSES);
    }

    /** The fastest any point of the slab moves ACROSS the support (x, z) — linear plus the turn at its far corner. */
    private static double creep(Vector3d v, Vector3d w) {
        double across = Math.sqrt(v.x * v.x + v.z * v.z);
        double farthest = Math.sqrt(1.5D * 1.5D + 0.5D * 0.5D + 1.5D * 1.5D) + 0.5D;
        return across + w.length() * farthest;
    }

    /**
     * INV-VSK-15, at rest: a hull standing on its support stays where it stands, every step for a minute,
     * whatever order its contacts are visited in — with its centre of mass off the middle of its footprint, so
     * the contacts must carry it unequally and a solve that lets one of them take the weight turns it.
     */
    @Pins("INV-VSK-15")
    @Regions(of = "INV-VSK-15", value = {"at rest"})
    @RedWitness(member = "ContactSolve#solve",
            at = "double pushed = Math.max(0.0D, c.pushed + (normal - c.target) / c.normalInvMass);",
            replacedBy = "double pushed = c.pushed + Math.max(0.0D, (normal - c.target) / c.normalInvMass);",
            fails = "INV-VSK-15 at rest", on = "2026-10-19")
    @Test
    public void aHullAtRestOnItsSupportStaysWhereItStands() {
        standAMinute("at rest", new Vector3d());
    }

    /**
     * INV-VSK-15, nudged: the same hull, moving sideways at {@link #NUDGE} as it settles, stops in the step and
     * stays stopped — which only a static cone does: kinetic friction alone takes less than half of it a step.
     */
    @Pins("INV-VSK-15")
    @Regions(of = "INV-VSK-15", value = {"nudged"})
    @RedWitness(member = "ContactSolve#solve", at = "if (magnitude > STATIC_FRICTION_COEFFICIENT * c.pushed) {",
            replacedBy = "if (magnitude > 0.0D) {", fails = "INV-VSK-15 nudged", on = "2026-10-19")
    @Test
    public void aHullNudgedOnItsSupportStopsInTheStep() {
        standAMinute("nudged", new Vector3d(NUDGE, 0.0D, 0.0D));
    }

    /** A minute of steps of the off-centre slab on its support from {@code v}, the law judged after every one. */
    private static void standAMinute(String region, Vector3d v) {
        Vector3d com = new Vector3d(0.3D, 0.0D, -0.2D);
        Random order = new Random(823L);
        Vector3d w = new Vector3d();
        Map<ContactSolve.Key, ContactSolve.Contact> last = new HashMap<>();
        for (int s = 0; s < MINUTE; s++) {
            v.y -= G * DT;
            List<ContactSolve.Contact> contacts = contacts(com, last, order);
            step(contacts, v, w, G);
            last.clear();
            double carried = 0.0D;
            for (ContactSolve.Contact c : contacts) {
                last.put(c.key(), c);
                carried += c.pushed();
            }
            // The positive half of "stays where it stands": the support really carried the hull this step —
            // at least its weight over the step (to the rounding of a sum of 36 pushes, far inside a part in
            // 1e9), or it would be falling, not standing.
            requireRegion(region + ", step " + s + ": the contacts must carry the hull's weight, pushing "
                    + carried + " against " + MASS * G * DT, carried >= MASS * G * DT * (1.0D - 1.0e-9D));
            assertTrue("INV-VSK-15 " + region + ": step " + s + " leaves the hull moving across its support at "
                    + creep(v, w) + " blocks/s (v " + v + ", w " + w + "), above the creep bound " + CREEP,
                    creep(v, w) <= CREEP);
        }
    }

    /**
     * INV-VSK-14: a hull settling onto its support — arriving at two steps of gravity, the most a resting hull
     * brings (one step, and one more when a contact is found only once a point has sunk) — leaves without a
     * bounce; dropped onto it at four steps' worth it bounces at the coefficient of restitution; and where
     * gravity is weak, the engine's own slop of 0.5 still governs.
     */
    @Pins("INV-VSK-14")
    @Regions(of = "INV-VSK-14", value = {"settling", "dropped", "weak gravity"})
    @RedWitness(verdict = "INV-VSK-14 settling", member = "ContactSolve#restitutionThreshold",
            at = "3.0D * Math.abs(gravity) * dt", replacedBy = "0.0D", fails = "INV-VSK-14 settling",
            on = "2026-10-19")
    @RedWitness(verdict = "INV-VSK-14 dropped", member = "ContactSolve#solve",
            at = "double bounce = closing > restitutionThreshold ? restitution : 0.0D;",
            replacedBy = "double bounce = 0.0D;", fails = "INV-VSK-14 dropped", on = "2026-10-19")
    @RedWitness(verdict = "INV-VSK-14 weak gravity", member = "ContactSolve#restitutionThreshold",
            at = "Math.max(ENGINE_SLOP, 3.0D * Math.abs(gravity) * dt)", replacedBy = "3.0D * Math.abs(gravity) * dt",
            fails = "INV-VSK-14 weak gravity", on = "2026-10-19")
    @Test
    public void aHullSettlingOntoItsSupportDoesNotBounceAndOneDroppedOntoItDoes() {
        double e = WorldPhysicsCollider.COEFFICIENT_OF_RESTITUTION;
        double correction = ContactSolve.POSITION_CORRECTION * DEPTH;

        double settling = 2.0D * G * DT;
        double settled = after(settling, G);
        assertTrue("INV-VSK-14 settling: arriving at " + settling + " blocks/s under g " + G + " the hull leaves"
                        + " at " + settled + "; settling it leaves at its position correction " + correction,
                Math.abs(settled - correction) <= BOUNCE_SHARE * e * settling);

        double dropped = 4.0D * G * DT;
        double bounced = after(dropped, G);
        double expected = correction + e * (dropped + correction);
        assertTrue("INV-VSK-14 dropped: arriving at " + dropped + " blocks/s it must bounce to " + expected
                        + ", and left at " + bounced,
                Math.abs(bounced - expected) <= BOUNCE_SHARE * e * dropped);

        double weakG = G / 4.0D;
        double weakArrival = 0.45D;
        requireRegion("weak gravity: three steps of the weak field must lie below the arrival, and the arrival"
                + " below the engine's slop", 3.0D * weakG * DT < weakArrival && weakArrival < ContactSolve.ENGINE_SLOP);
        double weak = after(weakArrival, weakG);
        assertTrue("INV-VSK-14 weak gravity: arriving at " + weakArrival + " under g " + weakG + " the hull leaves at "
                        + weak + "; below the engine's slop it leaves at its position correction " + correction,
                Math.abs(weak - correction) <= BOUNCE_SHARE * e * weakArrival);
    }

    /** The vertical speed the centred slab leaves with, arriving straight down at {@code closing} onto fresh contacts. */
    private static double after(double closing, double gravity) {
        Vector3d v = new Vector3d(0.0D, -closing, 0.0D);
        Vector3d w = new Vector3d();
        step(contacts(new Vector3d(), new HashMap<ContactSolve.Key, ContactSolve.Contact>(), new Random(14L)), v, w,
                gravity);
        return v.y;
    }

    private static void requireRegion(String what, boolean holds) {
        if (!holds) {
            throw new IllegalStateException("arranged: " + what);
        }
    }
}
