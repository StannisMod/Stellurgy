package zmaster587.advancedRocketry.space;

/**
 * The single owner of the cell&harr;slot-world POSE mapping — how an absolute {@link GalacticCoord}
 * inside a cell realizes as a world-frame position in that cell's slot world, and back.
 *
 * <p>The mapping is <b>honest-3D and CENTRED on all three axes</b>: the cell centre sits at the
 * world origin, so world X = local X, world Y = local Y, world Z = local Z, and the canonical local
 * range {@code [-HALF_CELL, HALF_CELL)} realizes as the same range in the world.</p>
 *
 * <h2>Why Y is centred like the other two, and what had to move for it</h2>
 *
 * <p>Y used to carry a {@code + HALF_CELL} shift so the band sat entirely above the vanilla
 * void-kill line, which meant Y spent the WHOLE cell going up while X and Z spent half of it in
 * each direction. That asymmetry put the top of every cell at world Y = {@code CELL}, twice as far
 * from the origin as either horizontal extreme — and past a hard vanilla limit: the server
 * disconnects a player whose position packet exceeds 3.0e7 on any axis
 * ({@code NetHandlerPlayServer.isMovePlayerPacketInvalid}), so with a 32M cell the top two million
 * blocks could not hold a pilot at all. Measured 2026-09-11: a seated pilot teleported there was
 * kicked with {@code multiplayer.disconnect.invalid_player_movement} inside the same tick.</p>
 *
 * <p>Centred, the extreme on every axis is {@code HALF_CELL} — 16M for today's cell — which is
 * inside the envelope this game has actually measured itself in (movement, standing, collision
 * stand-off, render and the client/server round trip are all exact out to 24M).</p>
 *
 * <p>The void-kill the old shift was avoiding is handled where it belongs: a cell world does not
 * kill an entity for being below Y = -64, because in a cell that is not the void — it is simply the
 * lower half of the cell. See the mixin that gates it.</p>
 *
 * <p>BLOCK content (a crossing's paste band, station blocks) stays at ordinary block Y (0..256);
 * only entity/ship POSES use the honest range. Centring also puts that band where it reads
 * correctly: a ship pasted at world Y 200 is now at local Y 200 — just above the cell's middle —
 * where under the shift it inverted to {@code -HALF_CELL - 56}, i.e. the cell's very floor, which is
 * the wart {@link #coordOfPoseWithin} exists to paper over. Proximity math runs on
 * {@link GalacticCoord} directly, never on realized world doubles.</p>
 */
public final class CellWorldMapper {

    private CellWorldMapper() { }

    /**
     * The world-frame pose position {@code [x,y,z]} realizing {@code coord} in its own cell's slot
     * world. Only meaningful for the slot world bound to {@code coord.cellKey()}.
     */
    public static double[] poseWorldOf(GalacticCoord coord) {
        return new double[]{
                coord.localX(),
                (double) coord.localY(),
                coord.localZ()};
    }

    /**
     * The absolute coordinate of a world-frame pose {@code (wx,wy,wz)} inside {@code cell}'s slot
     * world — the inverse of {@link #poseWorldOf}. {@code cell} identifies the slot world's bound
     * cell (local offsets ignored); an out-of-range pose renormalises into a neighbouring sector
     * per {@link GalacticCoord#ofSectorLocal}, which is exactly the seam-crossing semantics.
     */
    public static GalacticCoord coordOfPose(GalacticCoord cell, double wx, double wy, double wz) {
        return GalacticCoord.ofSectorLocal(
                cell.sectorX(), cell.sectorY(), cell.sectorZ(),
                Math.round(wx),
                Math.round(wy),
                Math.round(wz));
    }

    /**
     * {@link #coordOfPose} held inside {@code cell} — the reading for a pose that is REPORTING where a
     * ship is, as opposed to one integrating a path.
     *
     * <p>The difference matters because a cell name is not a position: it names a world, a slot
     * binding and a ledger row. A ship whose pose sits a little outside its own cell's local range
     * would otherwise report itself into the NEXT sector, a cell nobody materialized and nobody
     * bound. It then cannot descend (its cell holds no bodies), its jumps are refused, and the cell
     * it is really in loses the ledger's garbage-collection protection.</p>
     *
     * <p>The arrival paste band used to be the routine way in: under the old {@code +HALF_CELL} Y
     * shift a paste at world Y 200 inverted to a local Y of {@code -HALF_CELL - 56}, i.e. just off
     * the cell's floor. Centring Y put that case back inside the cell — world Y 200 is local Y 200 —
     * so this saturation now serves what it was named for: a pose that has genuinely gone past a
     * face.</p>
     *
     * <p>Saturating instead makes the report wrong by at most the overshoot, in the one axis that
     * overshot, and keeps every name-keyed lookup pointing at the world the ship is actually in.</p>
     */
    public static GalacticCoord coordOfPoseWithin(GalacticCoord cell, double wx, double wy, double wz) {
        return cell.cellCentre().plusLocalSaturating(
                Math.round(wx),
                Math.round(wy),
                Math.round(wz));
    }

    /** Whether {@code pose} lies outside {@code cell}, i.e. whether {@link #coordOfPoseWithin} clamped. */
    public static boolean poseEscapesCell(double wx, double wy, double wz) {
        return !GalacticCoord.localWithinCell(Math.round(wx))
                || !GalacticCoord.localWithinCell(Math.round(wy))
                || !GalacticCoord.localWithinCell(Math.round(wz));
    }
}
