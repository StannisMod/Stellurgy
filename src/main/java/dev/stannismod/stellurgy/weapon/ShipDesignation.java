package dev.stannismod.stellurgy.weapon;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import dev.stannismod.stellurgy.api.FreeFlightPhysics;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkManager;
import dev.stannismod.stellurgy.tile.weapon.TileWeaponConsole;

/**
 * A pilot names a ship as his own ship's target by looking at it.
 *
 * <h3>What "the ship he is looking at" is</h3>
 * <p>From a seat a pilot cannot point at anything but straight ahead — his view is his ship's heading
 * — so the ship named is the one nearest the line from his eye along that heading: of every loaded ship
 * in his world other than his own, the one whose world bounds lie at the smallest angle from it — zero
 * when the line passes through them.
 * A ship behind him is never named. There is no further cone: the nearest ship to the cursor is named
 * however far off it lies, and the chat line says how far away it is, so a pilot who got the wrong one
 * can see that he did.</p>
 *
 * <h3>Who takes the order</h3>
 * <p>Every weapons network with a console aboard the pilot's ship. It replaces whatever that network
 * was told before — a point, a creature or another ship — because one battery engages one thing.</p>
 */
public final class ShipDesignation {

    private ShipDesignation() {
    }

    /**
     * {@code pilot}, seated at {@code seatPos}, has asked to engage the ship ahead of him.
     *
     * @return the ship named, by the physics substrate's id; null when nothing was designated, which
     *         the pilot has already been told the reason for
     */
    public static String designate(World world, BlockPos seatPos, EntityPlayer pilot) {
        String own = VSIntegration.registeredShipIdManagingBlock(world, seatPos);
        if (own == null) {
            pilot.sendMessage(new TextComponentTranslation("msg.shipDesignation.notAboard"));
            return null;
        }
        // A seated pilot's look IS his ship's heading: his client pins his rotation to the ship's
        // attitude every tick and the mouse steers the flight cursor instead. So the heading is read
        // from the ship itself rather than from the last rotation his client happened to send.
        FreeFlightPhysics.Quat attitude = VSIntegration.shipAttitudeForId(world, own);
        if (attitude == null) {
            pilot.sendMessage(new TextComponentTranslation("msg.shipDesignation.notAboard"));
            return null;
        }
        double[] basis = FreeFlightPhysics.bodyBasisFromQuat(attitude);
        Vec3d look = new Vec3d(basis[0], basis[1], basis[2]);
        Vec3d eye = pilot.getPositionEyes(1.0F);
        String named = null;
        AxisAlignedBB namedHull = null;
        double namedAngle = Math.PI / 2.0D;
        for (Map.Entry<String, AxisAlignedBB> ship : VSIntegration.loadedShipBoxes(world).entrySet()) {
            if (own.equals(ship.getKey())) {
                continue;
            }
            double angle = angleFromSight(eye, look, ship.getValue());
            if (angle < namedAngle) {
                named = ship.getKey();
                namedHull = ship.getValue();
                namedAngle = angle;
            }
        }
        if (named == null) {
            pilot.sendMessage(new TextComponentTranslation("msg.shipDesignation.noShipAhead"));
            return null;
        }

        Set<WeaponNetworkState> batteries = Collections.newSetFromMap(new IdentityHashMap<>());
        SubsystemNetworkManager manager = SubsystemNetworkManager.of(world);
        if (manager != null) {
            for (TileWeaponConsole console : manager.nodesIn(WeaponNetworkDomain.INSTANCE, world,
                    TileWeaponConsole.class)) {
                WeaponNetworkState network = console.network();
                if (network != null && own.equals(VSIntegration.registeredShipIdManagingBlock(world,
                        console.getPos()))) {
                    batteries.add(network);
                }
            }
        }
        if (batteries.isEmpty()) {
            pilot.sendMessage(new TextComponentTranslation("msg.shipDesignation.noBattery"));
            return null;
        }
        for (WeaponNetworkState battery : batteries) {
            battery.clearTarget();
            battery.setTargetShip(named);
        }
        pilot.sendMessage(new TextComponentTranslation("msg.shipDesignation.designated",
                (int) Math.round(eye.distanceTo(centreOf(namedHull)))));
        return named;
    }

    /**
     * The middle of a box. Not {@code AxisAlignedBB#getCenter}: that one is client-only in 1.12, and
     * this runs on a dedicated server.
     */
    public static Vec3d centreOf(AxisAlignedBB box) {
        return new Vec3d((box.minX + box.maxX) * 0.5D, (box.minY + box.maxY) * 0.5D,
                (box.minZ + box.maxZ) * 0.5D);
    }

    /**
     * How far off the line of sight a hull lies, in radians: zero when the line passes through its
     * bounds, otherwise the angle to the middle of them.
     */
    private static double angleFromSight(Vec3d eye, Vec3d look, AxisAlignedBB hull) {
        Vec3d toHull = centreOf(hull).subtract(eye);
        double reach = toHull.lengthVector() + hull.getAverageEdgeLength() * 2.0D;
        if (hull.contains(eye) || hull.calculateIntercept(eye, eye.add(look.scale(reach))) != null) {
            return 0.0D;
        }
        double length = toHull.lengthVector();
        if (length < 1.0E-9D) {
            return 0.0D;
        }
        double cos = look.dotProduct(toHull) / (look.lengthVector() * length);
        return Math.acos(Math.max(-1.0D, Math.min(1.0D, cos)));
    }
}
