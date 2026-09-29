package org.valkyrienskies.mod.common.ships.interpolation;

import net.minecraft.util.math.AxisAlignedBB;
import org.valkyrienskies.mod.common.ships.ship_transform.ShipTransform;

import javax.annotation.Nonnull;

/**
 * An interface that allows for different ship interpolation algorithms to be implemented easily.
 */
public interface ITransformInterpolator {

    /**
     * Sends the latest transform and AABB to the interpolator.
     */
    void onNewTransformPacket(@Nonnull ShipTransform newTransform, @Nonnull AxisAlignedBB newAABB);

    /**
     * The same, with the craft's DECLARED motion — world frame, blocks and radians per second — as
     * it was published beside that pose.
     *
     * <p>An implementation that advances the pose from the craft's own motion needs this; one that
     * only filters toward the newest pose can ignore it. It is a separate method rather than a
     * replacement so an interpolator is free to want neither.</p>
     */
    void onNewTransformPacket(@Nonnull ShipTransform newTransform, @Nonnull AxisAlignedBB newAABB,
                              double linearX, double linearY, double linearZ,
                              double angularX, double angularY, double angularZ);

    /**
     * Moves the interpolator up 1 tick, moving the current transform closer to the latest transform.
     */
    void tickTransformInterpolator();

    /**
     * Returns the current smoothed transform.
     */
    @Nonnull
    ShipTransform getCurrentTickTransform();

    /**
     * The rate the pose this interpolator SHOWS is actually moving at — world frame, blocks and
     * radians per second — written into {@code outLinear} and {@code outAngular}.
     *
     * <p>Not the same question as "what did the craft declare". Whatever an implementation does
     * between packets — chase the newest pose, advance from a declared velocity, retire a
     * prediction error — the pose it shows moves at some rate, and anything standing on that pose
     * has to be carried at exactly that rate or it slides across the deck. Measured when this was
     * not asked: a body was carried at the declared 0.07 blocks/tick while the shown pose stepped
     * 0.5, and the capture guard read the difference as a teleport.</p>
     */
    void getShownVelocity(@Nonnull org.joml.Vector3d outLinear, @Nonnull org.joml.Vector3d outAngular);

    /**
     * Returns the current smoothed AxisAlignedBB.
     */
    @Nonnull
    AxisAlignedBB getCurrentAABB();

}
