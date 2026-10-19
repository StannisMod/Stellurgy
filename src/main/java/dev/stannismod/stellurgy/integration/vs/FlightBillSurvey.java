package dev.stannismod.stellurgy.integration.vs;

import dev.stannismod.stellurgy.api.AtmosphereDensity;
import dev.stannismod.stellurgy.api.FreeFlightPhysics;
import dev.stannismod.stellurgy.ship.control.FlightBill;
import dev.stannismod.stellurgy.ship.control.ShipCapability;
import dev.stannismod.stellurgy.space.TerrainHeightFinder;
import dev.stannismod.stellurgy.tile.TileAdvancedFlightComputer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.valkyrienskies.mod.common.config.VSConfig;

import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The body below a ship, as the flight law sees it, handed to {@link FlightBill} (C10 STAT-46): the field
 * and the air the physics step applies, the physics step itself, the velocity law the flight computer
 * flies by, where the ship stands, the line its takeoff fires on, and the ground under its hull.
 */
public final class FlightBillSurvey {

    private FlightBillSurvey() {}

    /**
     * The bill of the ship {@code physicsId}, whose flight computer stands at {@code computer}, in
     * {@code world}.
     *
     * @param gravity the field's magnitude in m/s², as the readout states it
     * @param line    the altitude the ship's takeoff fires above — the flight computer's own entry line
     * @param poolKg  the propellant aboard, kg per fluid; {@code null} when consumption is off
     */
    public static FlightBill of(World world, UUID physicsId, BlockPos computer, ShipCapability live,
                                Map<String, Double> poolKg, double gravity, OptionalInt line) {
        if (!(gravity > 0)) {
            return FlightBill.withoutBody(live, poolKg, FlightBill.NoBody.NO_FIELD);
        }
        if (!line.isPresent()) {
            return FlightBill.withoutBody(live, poolKg, FlightBill.NoBody.NO_LINE);
        }
        double[] pose = VSIntegration.getShipWorldPosition(world, computer);
        AxisAlignedBB hull = VSIntegration.shipWorldBoundsOf(world, physicsId);
        if (pose == null || hull == null || !(world instanceof WorldServer)) {
            return FlightBill.withoutBody(live, poolKg, FlightBill.NoBody.NO_POSE);
        }
        int minX = (int) Math.floor(hull.minX);
        int minZ = (int) Math.floor(hull.minZ);
        int ground = TerrainHeightFinder.terrainTopOfFootprint((WorldServer) world, minX, minZ,
                Math.max(1, (int) Math.ceil(hull.maxX) - minX), Math.max(1, (int) Math.ceil(hull.maxZ) - minZ));
        double surface = ground + (pose[1] - hull.minY);
        return FlightBill.over(live, poolKg, body(gravity, AtmosphereDensity.inAtmospheres(world),
                VSConfig.getTimeSimulatedPerTick(), pose[1], line.getAsInt(), surface));
    }

    /**
     * A body of {@code gravity} m/s² and {@code atmospheres} of air, stepped every {@code engineStep}
     * engine seconds, through the functions the physics step and the flight computer use: the air's
     * retention ({@link FreeFlightPhysics#ambientDragFactor}), the velocity law with its gravity
     * feed-forward ({@link FreeFlightPhysics#shipControlAccel}), and the climb full vertical throttle
     * commands ({@link TileAdvancedFlightComputer#SHIP_MAX_SPEED}) — converted to SI by {@link PhysicsUnits}.
     */
    public static FlightBill.Body body(double gravity, double atmospheres, double engineStep, double startY,
                                       double lineY, double surfaceY) {
        double engineGravity = gravity * PhysicsUnits.ACCELERATION;
        FlightBill.ControlLaw law = (commanded, velocity) -> FreeFlightPhysics.shipControlAccel(
                0, commanded * PhysicsUnits.SECONDS, 0, 0, velocity * PhysicsUnits.SECONDS, 0, engineStep,
                0, -engineGravity, 0, Double.POSITIVE_INFINITY)[1] / PhysicsUnits.ACCELERATION;
        return new FlightBill.Body(gravity, FreeFlightPhysics.ambientDragFactor(atmospheres, engineStep),
                engineStep * PhysicsUnits.SECONDS, TileAdvancedFlightComputer.SHIP_MAX_SPEED / PhysicsUnits.SECONDS,
                law, startY, lineY, surfaceY);
    }
}
