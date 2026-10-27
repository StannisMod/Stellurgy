package dev.stannismod.stellurgy.probe;

import net.minecraft.util.math.Vec3d;

/**
 * A fall laid out over a flat world: the trajectory's downrange distance carried along a heading so it ends
 * at the landing point, and its height stood on that point's ground. One block is one metre. Pure, and the
 * same on the server that owns the fall and on every client that draws it.
 */
public final class FallPlacement {

    private final double landX;
    private final double groundY;
    private final double landZ;
    private final double dirX;
    private final double dirZ;
    private final EntryTrajectory path;

    /**
     * @param landX      where it lands, block x (a block's centre is {@code x + 0.5})
     * @param groundY    the height of the ground it lands on
     * @param landZ      where it lands, block z
     * @param headingRad the direction it flies, radians from +x toward +z
     * @param path       the fall
     */
    public FallPlacement(double landX, double groundY, double landZ, double headingRad, EntryTrajectory path) {
        this.landX = landX;
        this.groundY = groundY;
        this.landZ = landZ;
        this.dirX = Math.cos(headingRad);
        this.dirZ = Math.sin(headingRad);
        this.path = path;
    }

    /** The ticks the fall takes. */
    public int ticks() {
        return path.ticks();
    }

    public EntryTrajectory path() {
        return path;
    }

    /**
     * Where the falling body is {@code time} ticks after its entry, a fraction of a tick between two samples
     * read straight between them; before the entry it is at the entry, after the landing at the landing.
     */
    public Vec3d at(double time) {
        int last = path.ticks();
        double t = Math.max(0d, Math.min(last, time));
        int i = (int) Math.floor(t);
        int j = Math.min(last, i + 1);
        double f = t - i;
        double down = path.downrangeAt(i) + (path.downrangeAt(j) - path.downrangeAt(i)) * f;
        double alt = path.altitudeAt(i) + (path.altitudeAt(j) - path.altitudeAt(i)) * f;
        double back = path.landingDownrange() - down;
        return new Vec3d(landX - dirX * back, groundY + alt, landZ - dirZ * back);
    }
}
