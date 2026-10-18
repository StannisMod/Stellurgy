package dev.stannismod.stellurgy.integration.vs;

import dev.stannismod.stellurgy.api.IShipActuatorBlock;
import dev.stannismod.stellurgy.ship.control.Actuator;
import dev.stannismod.stellurgy.ship.control.PropellantDemand;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.util.datastructures.IBlockPosSet;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Everything the flight model is derived from, read off a hull in one visit: what it weighs and
 * where, what aboard can push or turn it, and the propellant pool its drives draw from.
 *
 * <p>Two lists of actuators, because a readout owes the pilot both: DESIGN is every device aboard,
 * LIVE is the devices working now. A broken motor is in the first and not the second; so, when
 * consumption is metered, is a drive with no propellant aboard for any of its regimes, and a drive that
 * IS fed is LIVE at the one regime it burns (C10 STAT-38).</p>
 *
 * <p>Game thread only — it reads block states and tiles out of the world.</p>
 */
public final class HullSurvey {

    private final ShipMassFrame mass;
    private final List<Actuator> design;
    private final List<Actuator> live;
    private final int constructionRevision;
    private final ShipPropellant propellant;
    private final boolean metered;
    private final Map<String, Double> poolKg;
    private final List<String> starved;

    private HullSurvey(ShipMassFrame mass, List<Actuator> design, List<Actuator> working,
                       int constructionRevision, ShipPropellant propellant, World world) {
        this.mass = mass;
        this.design = Collections.unmodifiableList(design);
        this.constructionRevision = constructionRevision;
        this.propellant = propellant;
        this.metered = ShipPropellant.metered();
        this.poolKg = metered ? propellant.measure(world) : Collections.<String, Double>emptyMap();
        List<Actuator> live = new ArrayList<>();
        TreeSet<String> starved = new TreeSet<>();
        for (Actuator a : working) {
            if (!metered) {
                live.add(a);
                continue;
            }
            Actuator fed = a.fedBy(fluid -> poolKg.getOrDefault(fluid, 0.0D) > 0.0D);
            if (fed != null) {
                live.add(fed);
            } else {
                for (PropellantDemand d : a.demands()) {
                    starved.add(d.propellant());
                }
            }
        }
        this.live = Collections.unmodifiableList(live);
        this.starved = Collections.unmodifiableList(new ArrayList<>(starved));
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
        ShipMassFrame mass = new FlightComputerMassSource(world).massFrameOfPhysicsShip(shipUuid);
        IBlockPosSet blocks = ship.getBlockPositions();
        if (mass == null || blocks == null) {
            return null;
        }
        List<Actuator> design = new ArrayList<>();
        List<Actuator> working = new ArrayList<>();
        List<BlockPos> tiles = new ArrayList<>();
        blocks.forEach((x, y, z) -> collect(world, new BlockPos(x, y, z), design, working, tiles));
        return new HullSurvey(mass, design, working, revision, ShipPropellant.of(world, tiles), world);
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
        List<Actuator> working = new ArrayList<>();
        List<BlockPos> tiles = new ArrayList<>();
        for (BlockPos p : BlockPos.getAllInBox(min, max)) {
            collect(world, p, design, working, tiles);
        }
        return new HullSurvey(mass, design, working, 0, ShipPropellant.of(world, tiles), world);
    }

    /**
     * The mass frame of the craft on the pad, {@code [min, max]} inclusive, with every rocket fuel tank
     * FULL — the craft the assembler judges (C10 STAT-12): a tank's room is filled with the fluid it holds,
     * or, holding none, with the heaviest its role accepts. {@code null} when it weighs nothing.
     */
    @Nullable
    public static ShipMassFrame padMassWithTanksFull(World world, BlockPos min, BlockPos max) {
        return ShipHullMass.frameOfBoxWithTanksFull(world, min, max);
    }

    private static void collect(World world, BlockPos pos, List<Actuator> design, List<Actuator> working,
                                List<BlockPos> tiles) {
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock().hasTileEntity(state)) {
            tiles.add(pos);
        }
        if (!(state.getBlock() instanceof IShipActuatorBlock)) {
            return;
        }
        IShipActuatorBlock block = (IShipActuatorBlock) state.getBlock();
        int before = design.size();
        block.addActuators(world, pos, state, design);
        if (block.isWorking(world, pos, state)) {
            working.addAll(design.subList(before, design.size()));
        }
    }

    public ShipMassFrame mass() {
        return mass;
    }

    /** Every actuator aboard, as built. */
    public List<Actuator> design() {
        return design;
    }

    /** The actuators working now — each drive at the one regime it burns when consumption is metered. */
    public List<Actuator> live() {
        return live;
    }

    /** The holders the drives draw from. */
    public ShipPropellant propellant() {
        return propellant;
    }

    /** Whether consumption was metered when this survey was taken. */
    public boolean metered() {
        return metered;
    }

    /** The pool when surveyed, kg per propellant fluid; empty when unmetered. */
    public Map<String, Double> poolKg() {
        return poolKg;
    }

    /** The propellants whose absence idles a working drive aboard, sorted; empty when unmetered. */
    public List<String> starved() {
        return starved;
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
