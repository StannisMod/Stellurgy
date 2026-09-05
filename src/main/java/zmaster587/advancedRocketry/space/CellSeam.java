package zmaster587.advancedRocketry.space;

/**
 * The cell face, read as something a ship can FLY THROUGH.
 *
 * <p>A cell's local range is finite, so a ship under sustained thrust reaches its face. Two answers
 * were possible: stop it, or carry it. This class holds the second one's arithmetic — when a pose
 * has left its cell far enough to count, and where the ship belongs in the neighbour it entered.
 * Nothing here touches Minecraft, a world or a ledger; that is {@link CellCrossingController}'s work.</p>
 *
 * <h3>Why a margin exists at all</h3>
 *
 * <p>A face is a mathematical plane, and a ship loitering ON one would otherwise re-decide its cell
 * every tick, ping-ponging between two worlds and paying a full cut-and-paste each time. So the
 * crossing arms only once the pose is {@link #CARRY_MARGIN} PAST the face, and the ship is placed
 * {@link #REENTRY_DEPTH} inside the neighbour rather than on its face — with
 * {@code REENTRY_DEPTH > CARRY_MARGIN}, so coming back costs
 * {@code REENTRY_DEPTH + CARRY_MARGIN} of deliberate travel and cannot happen by drift.</p>
 *
 * <h3>Both are FRACTIONS of the cell, never absolutes</h3>
 *
 * <p>An absolute margin silently encodes an assumed speed and an assumed cell size: change either and
 * a number chosen for "about two seconds" quietly becomes two minutes or two ticks. Derived from
 * {@link GalacticCoord#HALF_CELL} they move with the cell, and the property they were chosen for —
 * a duration — survives.</p>
 */
public final class CellSeam {

    /**
     * How far past its cell's face a pose must be before the ship is carried: {@code HALF_CELL/10 000}
     * = 1 600 blocks at today's cell. Ratified 2026-08-17 in flight time, which is the unit that
     * matters: about 2 s at a 40 b/t cruise, and still 4 ticks for a craft doing 395 b/t (first cosmic
     * velocity, which the acceleration law makes reachable). Small enough that the ship is never long
     * in a place its cell does not name, large enough that no single tick of any plausible speed
     * straddles the decision.
     */
    public static final long CARRY_MARGIN = GalacticCoord.HALF_CELL / 10_000L;

    /**
     * How far inside the neighbour's opposite face the carried ship is placed: {@code HALF_CELL/1 000}
     * = 16 000 blocks, ten times {@link #CARRY_MARGIN}. Ratified 2026-08-17: coming straight back is
     * about 20 s of deliberate flight at a 40 b/t cruise, so a pilot who crosses knows he crossed.
     */
    public static final long REENTRY_DEPTH = GalacticCoord.HALF_CELL / 1_000L;

    // ─── The SPHERE boundary, inside a zone ────────────────────────────────────
    //
    // A cell of a zone is not bounded by a cube for any physical reason. Its body's influence ends
    // at a SPHERE, and that is where a craft stops being carried by it — so inside a zone the seam
    // is the sphere and the cube face is only the outer bound of what a slot world can hold.
    //
    // The GALACTIC lattice keeps the cube: a galactic cell has no sphere to be bounded by, its
    // extent IS the cube, and nothing above it nests.

    /**
     * How far past a zone's sphere a craft must be before it is carried out, as a FRACTION of that
     * sphere's radius — the same {@code 1/10 000} the cube face uses, with the radius substituting
     * for the half-cell.
     *
     * <p>A fraction and not an absolute, for the reason the cube's margin is one: an absolute encodes
     * an assumed speed and an assumed size, and a sphere's radius varies by four orders of magnitude
     * across the bodies in one system. The 2026-07-30 ruling that no invariant may rest on ship speed
     * survives verbatim — the hysteresis is still a relation between two fractions, and only the
     * thing they are fractions OF has changed.</p>
     */
    public static final double SPHERE_CARRY_FRACTION = 1d / 10_000d;

    /**
     * How far INSIDE a sphere a craft must be before it is taken into that body's zone, as a
     * fraction of the radius: {@code 1/1 000}, ten times {@link #SPHERE_CARRY_FRACTION}, exactly as
     * {@code REENTRY_DEPTH} is ten times {@code CARRY_MARGIN}.
     *
     * <p>The asymmetry is the hysteresis, and it points the same way it always did: a craft that has
     * entered a body's influence must travel deliberately to leave it again, and cannot flicker
     * between two frames by drifting on the boundary.</p>
     */
    public static final double SPHERE_REENTRY_FRACTION = 1d / 1_000d;

    private CellSeam() { }

    /**
     * The coordinate a world-frame pose denotes inside {@code cell} — the cell's name with the pose's
     * own local offset, NOT carried and NOT saturated.
     *
     * <p>Neither of the two mappers will do here. {@code coordOfPose} CARRIES an out-of-range offset
     * into the next sector, which is the answer for a ship integrating a path and the wrong one for
     * a ship being ASKED whether it has left; {@code coordOfPoseWithin} SATURATES, which reports a
     * craft on the face however far past it flew. The sphere test needs the true displacement, so
     * this keeps it.</p>
     */
    public static GalacticCoord coordOfPose(GalacticCoord cell, double wx, double wy, double wz) {
        if (cell == null) {
            return null;
        }
        return cell.cellCentre().withLocal(localOf(wx, false), localOf(wy, true), localOf(wz, false));
    }

    /**
     * How far {@code coord} is from the body whose ZONE it is in, in blocks.
     *
     * <p>Not the same as the in-cell offset, and the difference is the whole point: a craft in an
     * EMPTY cell of a zone is offset from that cell's centre, and the cell is itself offset from the
     * zone's body by {@code sector * cellBlocks}. Distance from the BODY is the sum, and it is the
     * quantity every sphere test here is written against — because a sphere is centred on a body,
     * never on a lattice.</p>
     *
     * <p>{@code -1} for a galactic coordinate, which is in no zone. A negative is not a distance:
     * callers must read it as "this question does not apply here" and fall back to the cube.</p>
     */
    public static double distanceFromZoneBody(GalacticCoord coord) {
        if (coord == null || coord.zone() == null || coord.cellBlocks() <= 0L) {
            return -1d;
        }
        long width = coord.cellBlocks();
        double dx = (double) coord.sectorX() * width + coord.localX();
        double dy = (double) coord.sectorY() * width + coord.localY();
        double dz = (double) coord.sectorZ() * width + coord.localZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Whether a craft {@code distanceBlocks} from a body has left that body's sphere far enough to be
     * carried out to its parent — {@code r > R * (1 + 1/10 000)}.
     *
     * <p>A zone with no radius ({@code 0}) has no sphere to leave, and the answer is {@code false}:
     * the caller falls back to the cube, which is what a galactic cell is bounded by anyway.</p>
     */
    public static boolean hasLeftZone(double distanceBlocks, double zoneRadiusBlocks) {
        return zoneRadiusBlocks > 0d
                && distanceBlocks > zoneRadiusBlocks * (1d + SPHERE_CARRY_FRACTION);
    }

    /**
     * Whether a craft {@code distanceBlocks} from a body is far enough INSIDE that body's sphere to
     * be taken into its zone — {@code r < R * (1 - 1/1 000)}.
     *
     * <p>Strictly deeper than {@link #hasLeftZone}'s threshold, which is what makes the pair a
     * hysteresis rather than one boundary read twice: between the two a craft stays where it is, so
     * neither answer can flip on a drifting pose.</p>
     */
    public static boolean hasEnteredZone(double distanceBlocks, double zoneRadiusBlocks) {
        return zoneRadiusBlocks > 0d
                && distanceBlocks < zoneRadiusBlocks * (1d - SPHERE_REENTRY_FRACTION);
    }

    /**
     * The local offset a world-frame pose component maps to, per {@link CellWorldMapper}'s honest-3D
     * mapping. Y carries the pose band; X and Z do not.
     */
    public static long localOf(double world, boolean isY) {
        long rounded = Math.round(world);
        return isY ? rounded - GalacticCoord.HALF_CELL - CellWorldMapper.POSE_BAND_Y : rounded;
    }

    /**
     * Whether a pose has left its cell far enough to be CARRIED rather than merely reported at the
     * boundary. Strictly more than the margin past the face, on any one axis.
     *
     * <p>Deliberately not the same question as {@link CellWorldMapper#poseEscapesCell}: that one asks
     * whether the REPORT had to saturate, and it is true the moment a pose steps a single block out —
     * including the arrival paste band, which sits far below the cell's own pose range for the few
     * ticks between the paste and the settle. A carry keyed on that question would fire on every
     * arrival.</p>
     */
    public static boolean shouldCarry(double wx, double wy, double wz) {
        return beyondMargin(localOf(wx, false))
                || beyondMargin(localOf(wy, true))
                || beyondMargin(localOf(wz, false));
    }

    private static boolean beyondMargin(long local) {
        return local > GalacticCoord.HALF_CELL + CARRY_MARGIN
                || local < -GalacticCoord.HALF_CELL - CARRY_MARGIN;
    }

    /**
     * Where the ship belongs after being carried out of {@code cell} by {@code pose}: the neighbouring
     * cell it left through, with the ship set {@link #REENTRY_DEPTH} inside the face it came in by.
     *
     * <p>Only the axes that actually crossed move to the entry face. An axis that did not cross keeps
     * the position the pilot flew it to (clamped into the local range, since a pose may sit a little
     * outside without having crossed) — a ship leaving through the +X face has not consented to being
     * re-centred in Y and Z.</p>
     */
    public static GalacticCoord carriedCoord(GalacticCoord cell, double wx, double wy, double wz) {
        long lx = localOf(wx, false);
        long ly = localOf(wy, true);
        long lz = localOf(wz, false);
        return GalacticCoord.ofSectorLocal(
                cell.sectorX() + step(lx), cell.sectorY() + step(ly), cell.sectorZ() + step(lz),
                placed(lx), placed(ly), placed(lz));
    }

    /** Which neighbour an axis left through: -1, 0 or +1 cell. */
    private static long step(long local) {
        if (local > GalacticCoord.HALF_CELL + CARRY_MARGIN) {
            return 1L;
        }
        return local < -GalacticCoord.HALF_CELL - CARRY_MARGIN ? -1L : 0L;
    }

    /** The local offset inside the destination cell for one axis. */
    private static long placed(long local) {
        long crossed = step(local);
        if (crossed > 0L) {
            // Left through the +face: arrive just inside the neighbour's -face.
            return -GalacticCoord.HALF_CELL + REENTRY_DEPTH;
        }
        if (crossed < 0L) {
            return GalacticCoord.HALF_CELL - REENTRY_DEPTH;
        }
        return Math.max(-GalacticCoord.HALF_CELL, Math.min(GalacticCoord.HALF_CELL - 1L, local));
    }
}
