package dev.stannismod.stellurgy.atmosphere;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.subsystem.network.ISubsystemNetworkNode;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkDomain;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkState;

import java.util.ArrayList;
import java.util.List;

/**
 * The ventilation domain: a central regeneration plant, the zones it serves, and the ducts between
 * them, over the shared subsystem-network primitive.
 * <p>
 * <b>The commodity is REGENERATION WORK, and its unit is ppm·blocks per tick</b> — an absolute
 * amount of carbon dioxide converted, not a partial pressure. The distinction is the whole reason
 * this tier exists: a partial pressure is a statement about one room, so a plant quoting one could
 * not say what it is worth to a ship of rooms, and a large cabin would be scrubbed as fast as a
 * cupboard on the same number. Multiplying by the zone's volume makes the quantity comparable
 * across rooms, which is what lets a duct's capacity mean "supports this much crew" the way D127-5
 * intends.
 * <p>
 * Nothing here re-implements a network. The graph, the max-flow solve, the priority tiers and the
 * statistics are the shared primitive's; this class is an identity and a unit.
 * <p>Every static field of this type is effectively final, process lifetime: built once at class initialisation, and holds an immutable value.</p>
 */
public final class LifeSupportNetwork {

    /**
     * The domain handle. Ventilation nodes register under it, so a duct and a shield cable laid
     * through the same wall never join one graph.
     */
    public static final SubsystemNetworkDomain DOMAIN = new SubsystemNetworkDomain("LifeSupport") {
        @Override
        public void onComponentTicked(SubsystemNetworkState state, List<ISubsystemNetworkNode> members) {
            payPortUpkeep(members);
        }
    };

    /** A node that can pay a port's running cost out of its own power: the plant. */
    public interface UpkeepSupply {
        /** Pay {@code fe} this tick if the buffer holds it; answers whether it was paid. */
        boolean payUpkeep(int fe);
    }

    /** A node whose running cost the network pays: the port. */
    public interface UpkeepConsumer {
        /** This tick's running cost has been paid. */
        void upkeepPaid();
    }

    /**
     * Every port on a network is kept running by the plants on it — the power reaches the port through
     * the ducts, never by a cable to the port, so a port with no plant behind it is a port without
     * power. One plant pays as many ports as its buffer covers; the next takes over when it runs short.
     * Order inside a component is the membership's, which is stable between rebuilds.
     */
    static void payPortUpkeep(List<ISubsystemNetworkNode> members) {
        int fe = Math.max(0, StellurgyConfiguration.getCurrentConfig().lifeSupportPortFePerTick);
        List<UpkeepSupply> supplies = new ArrayList<>();
        for (ISubsystemNetworkNode member : members) {
            if (member instanceof UpkeepSupply)
                supplies.add((UpkeepSupply) member);
        }
        for (ISubsystemNetworkNode member : members) {
            if (!(member instanceof UpkeepConsumer))
                continue;
            for (UpkeepSupply supply : supplies) {
                if (supply.payUpkeep(fe)) {
                    ((UpkeepConsumer) member).upkeepPaid();
                    break;
                }
            }
        }
    }

    /** The network solves every tick; the config states rates per second, as the rest of the tier does. */
    public static final int TICKS_PER_SECOND = 20;

    private LifeSupportNetwork() {
    }

    /** A per-second rate as the per-tick amount the solver deals in. */
    public static int perTick(int ratePerSecond) {
        return Math.max(0, ratePerSecond / TICKS_PER_SECOND);
    }

    /**
     * Absolute regeneration work for a partial pressure in a zone of this size.
     * <p>
     * <b>This is the seam between two units, and it is here rather than scattered.</b> A zone's air is
     * held in the composition's own unit, which is a thousand times finer than the ppm the network's
     * commodity is quoted in; the shared network primitive carries an int, and a real cabin's carbon
     * dioxide across a real volume would saturate one if the finer unit crossed into it. So the
     * conversion happens at the boundary, in both directions, and the network keeps the unit its
     * config keys are written in.
     * <p>
     * Clamped to int: a zone big enough to overflow this is not a room, it is a bug. The volume is
     * applied BEFORE the conversion, so a big cabin holding a share too thin to name on its own still
     * asks for the work that share is worth — a floor imposed per-room rather than per-ship would be
     * a second floor, and the composition already has the only one.
     */
    public static int absolute(long partialPressure, int zoneVolume) {
        long work = Math.max(0L, partialPressure) * Math.max(1, zoneVolume) / AirState.PER_PPM;
        return (int) Math.min(Integer.MAX_VALUE, work);
    }

    /** The inverse: what this much work amounts to as a partial pressure in a zone of this size. */
    public static long partialPressure(int absoluteWork, int zoneVolume) {
        return (long) Math.max(0, absoluteWork) * AirState.PER_PPM / Math.max(1, zoneVolume);
    }
}
