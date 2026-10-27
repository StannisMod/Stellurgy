package dev.stannismod.stellurgy.ship.control;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.Vec3i;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * An omnidirectional manoeuvring thruster as the physics system sees it: a nozzle on every face of its
 * block but the one it is mounted on, each a force at the block's centre pushing away from that nozzle.
 * Which nozzles are free to fire is the world's to say; this is what a free one gives.
 */
public final class OmniThruster {

    /**
     * Rated thrust of one nozzle, newtons: a tenth of the small chemical rocket motor's, so a manoeuvring
     * cluster trims and translates a hull a main engine lifts.
     */
    public static final double THRUST_PER_NOZZLE_N = 49_050d;

    private OmniThruster() {}

    /**
     * The forces of the thruster at block {@code (x, y, z)} mounted on its {@code mount} face, with
     * {@code freeNozzles} the faces whose nozzles can fire. The mounting face gives nothing, listed or not.
     */
    public static List<Actuator> at(int x, int y, int z, EnumFacing mount, Collection<EnumFacing> freeNozzles) {
        List<Actuator> out = new ArrayList<>(5);
        Vector3d centre = new Vector3d(x + 0.5D, y + 0.5D, z + 0.5D);
        for (EnumFacing nozzle : EnumFacing.VALUES) {
            if (nozzle == mount || !freeNozzles.contains(nozzle)) {
                continue;
            }
            Vec3i d = nozzle.getDirectionVec();
            // One identity per face, so two nozzles of one block are two devices to the allocator.
            out.add(Actuator.pointForce(new ActuatorId(x, y, z, nozzle.getIndex()), centre,
                    new Vector3d(-d.getX() * THRUST_PER_NOZZLE_N, -d.getY() * THRUST_PER_NOZZLE_N,
                            -d.getZ() * THRUST_PER_NOZZLE_N)));
        }
        return out;
    }
}
