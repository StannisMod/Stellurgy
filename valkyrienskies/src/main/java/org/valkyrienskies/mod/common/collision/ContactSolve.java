package org.valkyrienskies.mod.common.collision;

import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;

/**
 * Answers one physics step's contacts between a hull and the world together, so a hull standing on its
 * support stays where it stands.
 *
 * <p>A sequential-impulse solve with accumulated, warm-started impulses (E. Catto, "Iterative Dynamics
 * with Temporal Coherence", GDC 2005 — the scheme Box2D uses). Every contact is visited in each of
 * several passes, and each visit moves that contact's TOTAL push toward what its point needs — down as
 * well as up, never below zero — which is what lets a step's weight spread over every contact a hull
 * stands on. A contact that held the hull in the previous step starts from the push it ended that step
 * with, so a hull at rest begins each step already carried and the passes only refine it.</p>
 *
 * <p>What it replaced, measured 2026-10-19 on a 25-block craft released on its pad: the engine answered
 * each contact once, from whatever velocity the contacts before it left behind, so the first one or two
 * took the whole step's weight at an off-centre point; the hull was kicked into a turn of 0.1–0.3 rad/s
 * every step and walked 0.04–0.1 blocks across its pad in two seconds. Eight passes without carrying
 * pushes over still left it rolling at 0.01 rad/s; with them it stood still to 1e-4 blocks.</p>
 *
 * <p>Three laws of a contact, all settled here:</p>
 * <ul>
 *   <li><b>Restitution only for an impact.</b> At or below {@link #restitutionThreshold} the contact is a
 *       body settling onto its support and does not bounce.</li>
 *   <li><b>Position correction.</b> A contact that has sunk in is asked to separate at
 *       {@link #POSITION_CORRECTION} times its depth per second.</li>
 *   <li><b>Coulomb friction with a static cone.</b> Friction up to {@link #STATIC_FRICTION_COEFFICIENT}
 *       times the contact's push stops its point sliding; beyond that the point slides against the
 *       kinetic coefficient the caller gives.</li>
 * </ul>
 *
 * <p>Units are the engine's: blocks, kilograms and wall seconds. Pure: it reads only what it is handed
 * and writes only the velocities and the contacts' own solve state.</p>
 */
public final class ContactSolve {

    private ContactSolve() {}

    /**
     * The closing speed below which the engine has always treated a contact as plastic, in blocks per
     * engine second — about three steps of the 9.8 gravity it was tuned against at 60 steps a second.
     */
    public static final double ENGINE_SLOP = 0.5D;

    /**
     * The separation speed, per block of depth, a sunken contact is asked for: the engine's own
     * position correction, kept as it was.
     */
    public static final double POSITION_CORRECTION = 2.0D;

    /**
     * Static friction coefficient: dry steel on steel, 0.74 (Serway &amp; Jewett, <i>Physics for
     * Scientists and Engineers</i>, Table 5.1, "Coefficients of friction"). A cited handbook value, not a
     * measurement: hulls and pads are plated in metal, and no block carries a coefficient of its own.
     */
    public static final double STATIC_FRICTION_COEFFICIENT = 0.74D;

    /**
     * The closing speed, in blocks per engine second, at or below which a contact takes no restitution.
     *
     * <p>Three steps of the world's gravity. A body resting on support gains one step of it between two
     * contacts; a contact is found only once a point has sunk strictly below the face, so a hull standing
     * exactly on it falls one step unseen and arrives with two; and the position correction adds a little
     * on top. Three steps bounds that, and is also the ratio the engine's own slop stood at against the
     * gravity it was tuned for (0.5 against 9.8 / 60 = 0.163). A drop of more than about four centimetres
     * still arrives faster and bounces. Never below {@link #ENGINE_SLOP}, so a world whose gravity is
     * weaker keeps the engine's own behaviour.</p>
     *
     * <p>It is not simply the engine's slop because Stellurgy's gravity is 32 blocks per engine second
     * squared: one step of it at 60 steps a second is 0.533, above the slop of 0.5, so every resting
     * contact was an impact and a ship parked on its pad bounced every other step (measured 2026-10-19
     * on a 25-block craft released on its pad: 27 of its 64 contacts took restitution on the first
     * landing, and the hull went on hopping between -1.2 and +0.6 blocks per second).</p>
     *
     * @param gravity magnitude of the world's gravity, blocks per engine second squared
     * @param dt      length of the physics step, engine seconds
     */
    public static double restitutionThreshold(double gravity, double dt) {
        return Math.max(ENGINE_SLOP, 3.0D * Math.abs(gravity) * dt);
    }

    /**
     * Which contact this is from one step to the next: the world block, the hull block and which of the
     * hull block's eight sample points touched it. Equal keys in two consecutive steps are the same
     * contact.
     */
    public static final class Key {
        private final long world;
        private final long hull;
        private final int corner;

        public Key(long world, long hull, int corner) {
            this.world = world;
            this.hull = hull;
            this.corner = corner;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) {
                return false;
            }
            Key k = (Key) o;
            return world == k.world && hull == k.hull && corner == k.corner;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * Long.hashCode(world) + Long.hashCode(hull)) + corner;
        }
    }

    /** One point where a hull meets the world in this step, and what the solve has pushed there so far. */
    public static final class Contact {
        /** Which contact this is, across steps. */
        final Key key;
        /** The point, relative to the hull's centre of mass, world frame. */
        final Vector3d inBody;
        /** Unit vector from the hull into the world block; the world pushes back along its negative. */
        final Vector3d axis;
        /** How far the point has sunk in along {@link #axis}, blocks. */
        final double depth;
        /** Total push applied here this step, along {@code -axis}; never negative. */
        double pushed;
        /** Total friction impulse applied here this step, across the contact. */
        final Vector3d friction = new Vector3d();
        /** The velocity along {@link #axis} this point is driven to, fixed on the contact's arrival. */
        double target;
        /** Whether this contact can push the hull at all (see {@link #solve}). */
        boolean takesPart;
        /** The hull's inverse effective mass along {@link #axis} at this point. */
        double normalInvMass;

        public Contact(Key key, Vector3dc inBody, Vector3dc axis, double depth) {
            this.key = key;
            this.inBody = new Vector3d(inBody);
            this.axis = new Vector3d(axis);
            this.depth = depth;
        }

        public Key key() {
            return key;
        }

        /** Start from what the same contact ended the previous step with. */
        public void carryOver(Contact previous) {
            pushed = previous.pushed;
            friction.set(previous.friction);
        }

        /** Total push applied here this step. */
        public double pushed() {
            return pushed;
        }
    }

    /**
     * Resolves {@code contacts} against a hull moving at {@code v} and {@code w}, changing both.
     *
     * <p>A contact takes part only when its point lies on the side of the centre of mass toward the
     * world block it touches — the engine's own rule, so a push never drives the centre of mass into the
     * block. A contact carrying a push over from the previous step ({@link Contact#carryOver}) has it
     * applied before the first pass; its restitution is decided on the velocity it arrived with, before
     * that.</p>
     *
     * @param restitutionThreshold see {@link #restitutionThreshold}
     * @param restitution          the fraction of an impact's closing speed it leaves at
     * @param kineticFriction      the friction coefficient of a point that slides
     * @param passes               visits of every contact
     */
    public static void solve(List<Contact> contacts, Vector3d v, Vector3d w, double invMass, Matrix3dc invInertia,
                             double restitutionThreshold, double restitution, double kineticFriction, int passes) {
        Vector3d tmp = new Vector3d();
        Vector3d point = new Vector3d();
        Vector3d impulse = new Vector3d();
        for (int i = 0; i < contacts.size(); i++) {
            Contact c = contacts.get(i);
            c.takesPart = c.axis.dot(c.inBody) > 0.0D;
            if (!c.takesPart) {
                c.pushed = 0.0D;
                c.friction.zero();
                continue;
            }
            c.normalInvMass = invMass(c.inBody, c.axis, invMass, invInertia, tmp);
            double closing = velocityAt(c.inBody, v, w, point).dot(c.axis) + POSITION_CORRECTION * c.depth;
            double bounce = closing > restitutionThreshold ? restitution : 0.0D;
            c.target = -POSITION_CORRECTION * c.depth - bounce * closing;
        }
        for (int i = 0; i < contacts.size(); i++) {
            Contact c = contacts.get(i);
            if (c.takesPart) {
                c.axis.mul(-c.pushed, impulse).add(c.friction);
                apply(impulse, c.inBody, v, w, invMass, invInertia, tmp);
            }
        }
        Vector3d slip = new Vector3d();
        Vector3d friction = new Vector3d();
        for (int pass = 0; pass < passes; pass++) {
            for (int i = 0; i < contacts.size(); i++) {
                Contact c = contacts.get(i);
                if (!c.takesPart) {
                    continue;
                }
                double normal = velocityAt(c.inBody, v, w, point).dot(c.axis);
                double pushed = Math.max(0.0D, c.pushed + (normal - c.target) / c.normalInvMass);
                c.axis.mul(-(pushed - c.pushed), impulse);
                c.pushed = pushed;
                apply(impulse, c.inBody, v, w, invMass, invInertia, tmp);

                velocityAt(c.inBody, v, w, point);
                c.axis.mul(-point.dot(c.axis), slip).add(point);
                double speed = slip.length();
                friction.set(c.friction);
                if (speed > 0.0D) {
                    slip.div(speed);
                    friction.fma(-speed / invMass(c.inBody, slip, invMass, invInertia, tmp), slip);
                }
                double magnitude = friction.length();
                if (magnitude > STATIC_FRICTION_COEFFICIENT * c.pushed) {
                    friction.mul(kineticFriction * c.pushed / magnitude);
                }
                friction.sub(c.friction, impulse);
                c.friction.set(friction);
                apply(impulse, c.inBody, v, w, invMass, invInertia, tmp);
            }
        }
    }

    /** The hull's inverse effective mass along unit {@code d} at point {@code r}: {@code 1/m + ((I⁻¹(r×d))×r)·d}. */
    private static double invMass(Vector3dc r, Vector3dc d, double invMass, Matrix3dc invInertia, Vector3d tmp) {
        r.cross(d, tmp);
        invInertia.transform(tmp);
        return invMass + tmp.cross(r).dot(d);
    }

    private static Vector3d velocityAt(Vector3dc r, Vector3dc v, Vector3dc w, Vector3d dest) {
        return w.cross(r, dest).add(v);
    }

    private static void apply(Vector3dc impulse, Vector3dc r, Vector3d v, Vector3d w, double invMass,
                              Matrix3dc invInertia, Vector3d tmp) {
        v.fma(invMass, impulse);
        r.cross(impulse, tmp);
        invInertia.transform(tmp);
        w.add(tmp);
    }
}
