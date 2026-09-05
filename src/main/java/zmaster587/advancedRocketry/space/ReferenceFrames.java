package zmaster587.advancedRocketry.space;

import java.util.List;

import zmaster587.advancedRocketry.universe.SystemBody;

/**
 * Which body a craft's motion is measured AGAINST at a given moment.
 *
 * <p>A frame is a body plus its motion — an origin that moves. A craft sitting still with respect to
 * a world is in orbit around it; a craft sitting still with respect to nothing is being left behind
 * by everything. So the question "how fast is this craft going" has no answer until this class has
 * said what it is going fast RELATIVE TO.</p>
 *
 * <p><b>What was here before, and why it was not enough.</b> A craft's frame was, in effect, the
 * PRIMARY OF ITS CELL: a cell rides its primary and a craft's address is that cell plus an offset,
 * so a craft parked beside a planet kept station with it for free. Measured 2026-08-25: zero drift
 * over 20 000 ticks while the planet travelled 6.5e7 blocks. But a moon is never a cell's primary —
 * it shares its parent's cell and moves INSIDE it — so a craft parked beside a moon was carried by
 * the PARENT and left behind by the moon: 7 066 blocks became 294 996 over the same window, against
 * a descent shell of 7 066. Frames nest, and a one-level frame cannot express that.</p>
 *
 * <p>Pure arithmetic on bodies and a tick: no world, no client state, no registry. Callable from
 * either side.</p>
 */
public final class ReferenceFrames {

    private ReferenceFrames() {
    }

    /**
     * The exponent of the Laplace sphere of influence, {@code r = a·(m/M)^(2/5)}.
     *
     * <p><b>Laplace rather than Hill, and the measurement that chose it is recorded here because
     * neither formula is derivable from the other.</b> The criterion was stated before either was
     * evaluated: a formula is admissible if every body's sphere contains all of its own moons and
     * none of its siblings. Measured on the real Sol values, BOTH are admissible — Luna sits at
     * 384 400 km inside an Earth sphere of 926 000 km (Laplace) or 1 496 000 km (Hill), and the
     * nearest sibling is tens of millions of kilometres away either way — so the criterion did not
     * separate them and something else had to.</p>
     *
     * <p>Laplace is taken for two stated reasons rather than by preference: it is the boundary
     * patched-conic navigation is defined against, which is the model a pilot's instruments imply;
     * and it is the smaller of the two, so a craft is handed to a small body's frame only well
     * inside where that body dominates. The Hill radius describes where an orbit is STABLE against
     * the primary's tide, which is a question about long-term captures and not about which number a
     * pilot's speed readout should be measured against.</p>
     */
    private static final double LAPLACE_EXPONENT = 0.4d;

    /**
     * How far {@code body}'s influence reaches, in CHART BLOCKS, given the {@code primary} it orbits
     * and the moment {@code tick}.
     *
     * <p>{@code 0} when the question has no answer: either mass is unknown, the body does not orbit
     * the primary at a stated distance, or the primary is not more massive than the body. A zero is
     * NOT a small sphere — callers must read it as "this body defines no frame" and pass over it,
     * because a stand-in radius here is indistinguishable from a real one and would silently hand a
     * craft to the wrong body.</p>
     *
     * <p>The orbital radius is taken as the body's ACTUAL distance from its primary at {@code tick},
     * not from a stored orbital-distance field: those fields are stated in two different units on
     * the two levels (a planet's in orbit units about its star, a moon's in moon units about its
     * planet), and a sphere of influence computed from the wrong one is wrong by the ratio of the
     * two. A displacement in blocks is the same quantity at every level.</p>
     */
    public static double soiRadiusBlocks(SystemBody body, SystemBody primary, long tick) {
        if (body == null || primary == null) {
            return 0d;
        }
        double m = body.massEarths();
        double bigM = primary.massEarths();
        if (!(m > 0d) || !(bigM > m)) {
            return 0d;
        }
        double a = orbitalRadiusBlocks(body, primary, tick);
        if (!(a > 0d)) {
            return 0d;
        }
        return a * Math.pow(m / bigM, LAPLACE_EXPONENT);
    }

    /**
     * How far {@code body} actually is from {@code primary} at {@code tick}, in blocks — the {@code a}
     * a sphere of influence is computed at.
     *
     * <p>Instantaneous rather than mean: on an eccentric or a lifted orbit the two differ, and the
     * boundary a craft crosses is the one that exists when it crosses it.</p>
     */
    public static double orbitalRadiusBlocks(SystemBody body, SystemBody primary, long tick) {
        if (body == null || primary == null) {
            return 0d;
        }
        return body.absoluteAt(tick).distanceTo(primary.absoluteAt(tick));
    }

    /**
     * The body whose frame {@code craft} is in at {@code tick}: the INNERMOST sphere of influence
     * containing it (C19 FRAME-2).
     *
     * <p>Innermost is decided by the SIZE of the containing sphere and not by a hierarchy walk, and
     * that is deliberate: it needs no parent pointers, it is right for a body whose parent is not in
     * the list, and it gives the same answer a nesting walk would whenever the spheres genuinely
     * nest. A craft inside a moon's sphere is inside its planet's too, and the moon's is the smaller
     * of the two.</p>
     *
     * <p>Falls back to the {@code primary} — the body whose frame the craft's neighbourhood already
     * rides — when no smaller sphere contains it. That is not a degradation: outside every moon's
     * influence the planet IS the right frame, which is the same answer the code gave before this
     * class existed, for the same reason.</p>
     *
     * @param craft   where the craft is, absolutely, at {@code tick}
     * @param bodies  the bodies to consider — a system's, or a cell's
     * @param primary the frame to fall back to; may be {@code null}, and then so is the answer
     * @return the body whose frame the craft is in, or {@code null} if there is none
     */
    public static SystemBody frameOf(AbsolutePos craft, List<SystemBody> bodies, SystemBody primary,
                                     long tick) {
        if (craft == null) {
            return primary;
        }
        SystemBody innermost = primary;
        double innermostRadius = Double.MAX_VALUE;
        if (bodies != null) {
            for (SystemBody body : bodies) {
                if (body == null || body == primary || body.equals(primary)) {
                    continue;
                }
                double radius = soiRadiusBlocks(body, primary, tick);
                if (!(radius > 0d) || radius >= innermostRadius) {
                    continue;
                }
                if (craft.distanceTo(body.absoluteAt(tick)) <= radius) {
                    innermost = body;
                    innermostRadius = radius;
                }
            }
        }
        return innermost;
    }

    /**
     * How fast {@code frame} is moving INSIDE its cell at {@code tick}, in blocks per tick — the
     * velocity a craft holding station in that frame must itself be moving at.
     *
     * <p><b>This is the whole fix, and its shape says why the defect looked the way it did.</b> A
     * cell's primary has an in-cell offset of zero at every tick, so this is identically zero for it
     * — which is exactly why a craft parked beside a planet already kept station without anything
     * carrying it. A moon moves inside the shared cell, so this is its orbital velocity about its
     * parent, and a craft that does not match it is left behind at precisely that rate.</p>
     *
     * <p>Read from the ephemeris ANALYTICALLY rather than by differencing two of its positions.
     * Measured 2026-08-25: positions are rounded to whole blocks, so a central difference is
     * quantised to half a block per tick and returned exactly 15.0 where the truth was 14.7344 —
     * tolerable at Luna's speed and ruinous at a slow body's, where the same half-block step is the
     * whole quantity. See {@code BodyEphemeris.velocityBlocksPerTickAt}, which is the same law
     * differentiated and therefore cannot disagree with the position it is the rate of.</p>
     */
    public static double[] frameVelocityBlocksPerTick(SystemBody frame, long tick) {
        if (frame == null) {
            return new double[]{0d, 0d, 0d};
        }
        return frame.offsetLaw().velocityBlocksPerTickAt(tick);
    }
}
