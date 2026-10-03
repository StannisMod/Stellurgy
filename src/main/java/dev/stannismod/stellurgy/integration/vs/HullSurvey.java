package dev.stannismod.stellurgy.integration.vs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.util.datastructures.IBlockPosSet;

import dev.stannismod.stellurgy.api.IShipActuatorBlock;
import dev.stannismod.stellurgy.ship.control.Actuator;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;

/**
 * Everything the flight model is derived from, read off a hull in one visit: what it weighs and
 * where, and what aboard can push or turn it.
 *
 * <p>Two lists of actuators, because a readout owes the pilot both: DESIGN is every device aboard,
 * LIVE is the devices working now. A broken motor is in the first and not the second.</p>
 *
 * <p>Game thread only — it reads block states and tiles out of the world.</p>
 */
public final class HullSurvey {

    private final ShipMassFrame mass;
    private final List<Actuator> design;
    private final List<Actuator> live;
    private final int constructionRevision;

    private HullSurvey(ShipMassFrame mass, List<Actuator> design, List<Actuator> live,
                       int constructionRevision) {
        this.mass = mass;
        this.design = Collections.unmodifiableList(design);
        this.live = Collections.unmodifiableList(live);
        this.constructionRevision = constructionRevision;
    }

    /**
     * The survey of the ship named by {@code shipUuid}, in its own subspace frame, or {@code null}
     * when this world holds no such ship or the ship weighs nothing — a hull that is not there to be
     * surveyed, which a caller must treat as "no flight model", never as a model of an empty ship.
     */
    @Nullable
    public static HullSurvey ofShip(World world, UUID shipUuid) {
        ShipData ship = VSBridge.shipDataByUuid(world, shipUuid);
        if (ship == null) {
            return null;
        }
        // Read BEFORE the walk: a block that changes during it bumps the count past this value, so
        // the next comparison sees a change and surveys again rather than keeping a stale answer.
        int revision = ship.getConstructionRevision();
        ShipMassFrame mass = ShipHullMass.frameOf(world, shipUuid);
        IBlockPosSet blocks = ship.getBlockPositions();
        if (mass == null || blocks == null) {
            return null;
        }
        List<Actuator> design = new ArrayList<>();
        List<Actuator> live = new ArrayList<>();
        blocks.forEach((x, y, z) -> collect(world, new BlockPos(x, y, z), design, live));
        return new HullSurvey(mass, design, live, revision);
    }

    /**
     * The survey of a craft still standing on the pad: the non-air blocks of {@code [min, max]}
     * inclusive, in world coordinates. {@code null} when it weighs nothing.
     */
    @Nullable
    public static HullSurvey ofBox(World world, BlockPos min, BlockPos max) {
        ShipMassFrame mass = ShipHullMass.frameOfBox(world, min, max);
        if (mass == null) {
            return null;
        }
        List<Actuator> design = new ArrayList<>();
        List<Actuator> live = new ArrayList<>();
        for (BlockPos p : BlockPos.getAllInBox(min, max)) {
            collect(world, p, design, live);
        }
        return new HullSurvey(mass, design, live, 0);
    }

    private static void collect(World world, BlockPos pos, List<Actuator> design, List<Actuator> live) {
        IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof IShipActuatorBlock)) {
            return;
        }
        IShipActuatorBlock block = (IShipActuatorBlock) state.getBlock();
        int before = design.size();
        block.addActuators(world, pos, state, design);
        if (block.isWorking(world, pos, state)) {
            live.addAll(design.subList(before, design.size()));
        }
    }

    public ShipMassFrame mass() {
        return mass;
    }

    /** Every actuator aboard, as built. */
    public List<Actuator> design() {
        return design;
    }

    /** The actuators working now. */
    public List<Actuator> live() {
        return live;
    }

    /** The ship record's construction count this survey was taken at; zero for a pad survey. */
    public int constructionRevision() {
        return constructionRevision;
    }

    /**
     * The construction count of the ship named by {@code shipUuid} right now, or {@code -1} when this
     * world holds no such ship. Compared against {@link #constructionRevision()} to learn that the
     * hull changed since the last survey, without walking it.
     */
    public static int currentConstructionRevision(World world, UUID shipUuid) {
        ShipData ship = VSBridge.shipDataByUuid(world, shipUuid);
        return ship == null ? -1 : ship.getConstructionRevision();
    }
}
