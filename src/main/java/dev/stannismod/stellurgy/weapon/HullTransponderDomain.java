package dev.stannismod.stellurgy.weapon;

import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkDomain;

/**
 * The index of every loaded hull transponder: the one domain whose only job is "which of these blocks
 * are in the world now", so that the hull rule {@link HullAllegianceRule#TRANSPONDER} finds a ship's
 * transponders by asking, rather than by scanning a ship's blocks.
 *
 * <p>Its own domain and not a node of the weapons network: a transponder is no part of a battery. It
 * carries no energy, is commanded by no console, and joining a battery's network would make it a
 * member of something whose rebuild counts members and looks for consoles. It moves nothing, so it
 * overrides nothing.</p>
 */
public final class HullTransponderDomain extends SubsystemNetworkDomain {

    /**
     * Effectively final, process lifetime: built once at class initialisation. A domain holds nothing
     * but its name.
     */
    public static final HullTransponderDomain INSTANCE = new HullTransponderDomain();

    private HullTransponderDomain() {
        super("HullTransponder");
    }
}
