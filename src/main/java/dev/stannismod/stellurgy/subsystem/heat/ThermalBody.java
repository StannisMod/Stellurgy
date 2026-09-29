package dev.stannismod.stellurgy.subsystem.heat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.atmosphere.AtmosphereHandler;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import dev.stannismod.stellurgy.subsystem.network.ISubsystemNetworkNode;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkManager;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkRegistry;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkState;
import dev.stannismod.stellurgy.tile.heat.TileHeatRadiator;

/**
 * The thing a signature is OF: one ship, taken whole.
 * <p>
 * A coolant loop is the object with a temperature, but it is not the object a sensor looks at. A ship
 * with a chiller has two loops and one hull, and reporting either loop alone would say a ship with
 * its cold side shut down is dark while its hot side is glowing. So a BODY is every loop that belongs
 * to the same ship, plus the hull around them.
 * <p>
 * <b>What decides "the same ship" is the ship itself.</b> A block either belongs to a ship's subspace
 * claim or it does not, which is the physics substrate's own answer and not a second definition of a
 * ship invented here. Off a ship there is no claim to ask, and the fallback is what a hull IS from
 * the inside: the loops that run through the same enclosed air.
 * <p>
 * <b>The hull is measured, not configured.</b> There is no hull survey yet (the same one
 * {@code ShipMassProvider} is waiting for), so the body's size is what it demonstrably has: the air
 * it encloses plus the plumbing it is built from. Its outer skin is then the surface of a cube of
 * that size - the tightest shape there is, so a real hull has more and this floor is an
 * underestimate rather than a flattering guess. When the survey lands, {@link #sizeBlocks()} is the
 * one method that changes.
 */
public final class ThermalBody {

    /**
     * The least of a compartment's warmth that can ever reach the outer skin, whatever the config
     * says.
     * <p>
     * Clamped where it is READ, exactly as the shield's attenuation is, and for the mirror-image
     * reason: a shield may never take ALL of the incident flux, and insulation may never take all of
     * the ship's own glow. Total invisibility is not a setting - a dark ship is found closer, not
     * never.
     */
    private static final double MIN_SKIN_FRACTION = 0.05D;

    /** How many faces a cube of {@code n} blocks presents. The hull model, in one place. */
    private static final double CUBE_FACES = 6.0D;

    private final World world;
    private final BlockPos anchor;
    private final List<HeatNetworkState> loops;
    private final int sizeBlocks;
    private final double cabinKelvin;
    private final double environmentKelvin;

    private ThermalBody(World world, BlockPos anchor, List<HeatNetworkState> loops, int sizeBlocks,
                        double cabinKelvin, double environmentKelvin) {
        this.world = world;
        this.anchor = anchor;
        this.loops = loops;
        this.sizeBlocks = sizeBlocks;
        this.cabinKelvin = cabinKelvin;
        this.environmentKelvin = environmentKelvin;
    }

    /**
     * The body the block at {@code anchor} belongs to, or {@code null} when it belongs to no coolant
     * loop at all - a position with no thermal build has no signature to report, which is a different
     * answer from a signature of zero.
     */
    public static ThermalBody at(World world, BlockPos anchor) {
        if (world == null || world.isRemote || anchor == null || !HeatNetwork.enabled()) {
            return null;
        }
        SubsystemNetworkState own = SubsystemNetworkManager.getState(HeatNetwork.DOMAIN, world, anchor);
        if (!(own instanceof HeatNetworkState)) {
            return null;
        }
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world.provider.getDimension());
        String shipId = VSIntegration.registeredShipIdManagingBlock(world, anchor);
        List<HeatNetworkState> loops = loopsOf(world, handler, shipId, (HeatNetworkState) own);

        int volume = 0;
        double cabin = HeatNetwork.ambientKelvin();
        if (handler != null) {
            IdentityHashMap<AirState, Integer> compartments = compartmentsOf(handler, loops);
            for (java.util.Map.Entry<AirState, Integer> entry : compartments.entrySet()) {
                volume += Math.max(0, entry.getValue());
                cabin = Math.max(cabin, entry.getKey().getTemperatureKelvin());
            }
        }
        for (HeatNetworkState loop : loops) {
            volume += loop.getMemberPositions().size();
        }

        HeatEnvironment environment = HeatEnvironment.at(world, anchor);
        double equilibrium = HullMelting.equilibriumKelvin(environment.incidentFluxPerCell(anchor));
        return new ThermalBody(world, anchor, loops, volume, cabin, equilibrium);
    }

    /**
     * Every loop of this body.
     * <p>
     * On a ship the answer is the ship's own: a block either belongs to that subspace claim or it
     * does not, and two loops under one claim are two loops of one hull however far apart they are
     * laid.
     * <p>
     * Off a ship there is no claim to ask, so the hull is what the loops SHARE: a loop is part of
     * this body when it runs through a compartment the anchor's loop also runs through. Two
     * installations on the same planet are two bodies because they enclose different air, which is
     * the same statement the ship case makes and the only one available without a claim. A loop out
     * in the open, enclosing nothing, is a body by itself.
     */
    private static List<HeatNetworkState> loopsOf(World world, AtmosphereHandler handler,
                                                  String shipId, HeatNetworkState own) {
        List<HeatNetworkState> loops = new ArrayList<>();
        Set<BlockPos> roots = new HashSet<>();
        loops.add(own);
        roots.add(own.getRoot());
        IdentityHashMap<AirState, Integer> hull = shipId != null || handler == null
                ? null : compartmentsOf(handler, loops);
        if (shipId == null && (hull == null || hull.isEmpty())) {
            return loops;
        }
        for (ISubsystemNetworkNode node : SubsystemNetworkRegistry.snapshot(HeatNetwork.DOMAIN)) {
            BlockPos pos = node.getNodePos();
            if (pos == null || node.getNodeWorld() != world) {
                continue;
            }
            SubsystemNetworkState state = SubsystemNetworkManager.getState(HeatNetwork.DOMAIN, world, pos);
            if (!(state instanceof HeatNetworkState) || roots.contains(state.getRoot())) {
                continue;
            }
            if (shipId != null) {
                if (!shipId.equals(VSIntegration.registeredShipIdManagingBlock(world, pos))) {
                    continue;
                }
            } else if (!sharesACompartment(handler, hull, (HeatNetworkState) state)) {
                continue;
            }
            roots.add(state.getRoot());
            loops.add((HeatNetworkState) state);
        }
        return loops;
    }

    /** Whether this loop runs through any of the compartments the body already claims. */
    private static boolean sharesACompartment(AtmosphereHandler handler,
                                              IdentityHashMap<AirState, Integer> hull,
                                              HeatNetworkState candidate) {
        for (AirState air : compartmentsOf(handler, java.util.Collections.singletonList(candidate))
                .keySet()) {
            if (hull.containsKey(air)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The compartments this body's plumbing runs through, and how big each one is.
     * <p>
     * Sampled from the loop's own blocks and their faces, because a pipe is solid and so belongs to
     * no air zone itself - the room is what it is touching. Zones are told apart by the identity of
     * the air in them, which is what the atmosphere subsystem hands out; two rooms are two objects
     * however similar their contents.
     */
    private static IdentityHashMap<AirState, Integer> compartmentsOf(AtmosphereHandler handler,
                                                                    List<HeatNetworkState> loops) {
        IdentityHashMap<AirState, Integer> found = new IdentityHashMap<>();
        for (HeatNetworkState loop : loops) {
            for (BlockPos member : loop.getMemberPositions()) {
                for (EnumFacing face : EnumFacing.VALUES) {
                    BlockPos side = member.offset(face);
                    AirState air = handler.getAirStateAt(side);
                    if (air != null && !found.containsKey(air)) {
                        found.put(air, handler.getBlobSizeAt(side));
                    }
                }
            }
        }
        return found;
    }

    /** The loops this body is made of - one for a simple ship, two or more once a chiller is in. */
    public List<HeatNetworkState> loops() {
        return loops;
    }

    /**
     * How big the body is, in blocks: the air it encloses plus the blocks its loops are made of.
     * <p>
     * Both halves are things the player built and neither is authored anywhere. It is a PROXY for a
     * hull survey, and it is honest about which way it errs: a compartment nobody plumbed does not
     * count, so a body is never credited with more hull than it can show.
     */
    public int sizeBlocks() {
        return sizeBlocks;
    }

    /** Radiating surface the hull presents: the faces of a cube holding {@link #sizeBlocks()}. */
    public double hullCells() {
        if (sizeBlocks <= 0) {
            return 0.0D;
        }
        return CUBE_FACES * Math.pow(sizeBlocks, 2.0D / 3.0D);
    }

    /** The warmest air this body encloses - ambient when it encloses none. */
    public double cabinKelvin() {
        return cabinKelvin;
    }

    /**
     * How hot the OUTER SKIN is, which is what a sensor actually sees of the hull.
     * <p>
     * Nowhere near the cabin: a hull is insulated, and the skin sits most of the way down to whatever
     * the outside is holding it at. That gap is the whole reason shutting the radiators is worth
     * doing - without it the hull alone would out-glow the array and silence would buy nothing.
     * <p>
     * It is never at the outside's own temperature either. Whatever the config asks for, a fraction
     * of the ship's warmth gets out, so a ship running silent is found closer rather than not at all.
     * And it RISES as the ship cooks itself: the air is what it is a fraction of, so silence gets
     * louder the longer it is held. That is a consequence of the model, not a rule written into it.
     */
    public double skinKelvin() {
        double above = Math.max(0.0D, cabinKelvin - environmentKelvin);
        return environmentKelvin + skinFraction() * above;
    }

    /** How much of the difference between the enclosed air and the outside reaches the skin. */
    public static double skinFraction() {
        double asked = StellurgyConfiguration.getCurrentConfig().shipHeatHullSkinFraction / 1000.0D;
        return Math.max(MIN_SKIN_FRACTION, Math.min(1.0D, asked));
    }

    /**
     * What this body shows a passive sensor: every working radiating cell at its own loop's
     * temperature, plus the hull.
     * <p>
     * A cell that is not working contributes nothing - shut, or blocked, it is not a radiating
     * surface - which is what makes running silent collapse the first term. The hull term does not
     * collapse with it, and that is {@link #skinKelvin()}'s whole job.
     */
    public ThermalSignature signature() {
        ThermalSignature signature = ThermalSignature.surface(hullCells(), skinKelvin());
        for (HeatNetworkState loop : loops) {
            signature = signature.plus(ThermalSignature.surface(loop.getRadiatingCells(),
                    loop.getTemperatureKelvin()));
        }
        return signature;
    }

    /**
     * Whether every sink this body has is shut. A body with no radiators at all is not "silent" -
     * it has nothing to be quiet with, and calling it so would let a ship that built no cooling
     * claim the state a ship pays thermal mass for.
     */
    public boolean isRunningSilent() {
        boolean any = false;
        for (TileHeatRadiator radiator : radiators()) {
            any = true;
            if (!radiator.isClosed()) {
                return false;
            }
        }
        return any;
    }

    /**
     * Shut every sink on this body, or open them again, and answer how many cells changed hands.
     * <p>
     * Ship-wide and not per cell: the moment to go dark is a tactical decision about the whole ship,
     * so the control that will sit at the pilot's station calls this. Closing is FREE and instant;
     * what it costs is that the loop now has nowhere to put anything, which the failure ladder
     * collects on its own schedule.
     */
    public int setSinksClosed(boolean closed) {
        int changed = 0;
        for (TileHeatRadiator radiator : radiators()) {
            if (radiator.isClosed() != closed) {
                radiator.setClosed(closed);
                changed++;
            }
        }
        return changed;
    }

    /** Every radiating cell on this body, working or not. */
    public List<TileHeatRadiator> radiators() {
        List<TileHeatRadiator> found = new ArrayList<>();
        for (HeatNetworkState loop : loops) {
            for (BlockPos member : loop.getMemberPositions()) {
                TileEntity tile = world.getTileEntity(member);
                if (tile instanceof TileHeatRadiator) {
                    found.add((TileHeatRadiator) tile);
                }
            }
        }
        return found;
    }

    /** Where this body was asked about. */
    public BlockPos anchor() {
        return anchor;
    }
}
