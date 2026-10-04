package dev.stannismod.stellurgy.client;

import dev.stannismod.stellurgy.api.IAtmosphere;
import dev.stannismod.stellurgy.world.WorldRuntime;
import net.minecraft.world.World;

/**
 * What this client has been told about the air around its own player in one client world.
 *
 * <p>None of it is derivable client-side: the atmosphere a position holds is the server's answer,
 * and it arrives by packet ({@code PacketAtmSync}, {@code PacketOxygenState}). So this is a copy,
 * and like every copy it is only as current as the last packet.</p>
 *
 * <p>Owned by the client WORLD the report describes ({@link WorldRuntime}): a reading is of a position
 * in that world, and the suffocation mark is a moment on that world's clock. Kept across a world
 * change, a pressure would be drawn on the next world's HUD as if it had been measured there, and the
 * mark would be compared with a clock that knows nothing about it.</p>
 *
 * <p><b>Why it is not on {@code AtmosphereHandler} any more.</b> It lived there as three public
 * statics beside that class's server-side machinery, marked only by a one-line comment saying they
 * were the client's. A server-side path read one of them anyway and reported it to the player —
 * always the field's default, because on a dedicated server nothing ever writes it. A client class
 * makes that mistake a compile error rather than a wrong number.</p>
 */
public final class ClientAtmosphere {

    /** No pressure has been received. Distinguishable from a legitimate reading of zero, which is
     *  what the previous default of plain {@code 0} was not: every reader tests for this sentinel. */
    public static final int NO_READING = -1;

    private IAtmosphere atmosphere;
    private int pressure = NO_READING;
    private long lastSuffocationTime = Long.MIN_VALUE;

    private ClientAtmosphere() {
    }

    /** What {@code world}'s client copy has been told about its player's air. */
    public static ClientAtmosphere of(World world) {
        return WorldRuntime.of(world, ClientAtmosphere.class, ClientAtmosphere::new);
    }

    /** The atmosphere the server last reported at this player's position, or {@code null} before the
     *  first report. */
    public IAtmosphere atmosphere() {
        return atmosphere;
    }

    /** The pressure the server last reported, in hundredths of an atmosphere, or {@link #NO_READING}
     *  before the first report — which is a different answer from "vacuum". */
    public int pressure() {
        return pressure;
    }

    /** Take a fresh atmosphere report from the server. */
    public void accept(IAtmosphere atmosphere, int pressure) {
        this.atmosphere = atmosphere;
        this.pressure = pressure;
    }

    /** Whether a suffocation report arrived within the last {@code ticks} of this world's clock. */
    public boolean suffocatedWithin(long worldTime, long ticks) {
        return lastSuffocationTime != Long.MIN_VALUE
                && worldTime >= lastSuffocationTime && worldTime - lastSuffocationTime < ticks;
    }

    /** Record a suffocation report at {@code worldTime} on this world's clock. */
    public void suffocatedAt(long worldTime) {
        lastSuffocationTime = worldTime;
    }
}
