package dev.stannismod.stellurgy.client;

import dev.stannismod.stellurgy.atmosphere.AtmosphereSummary;
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
 *
 * <p><b>What it holds is a READOUT, not a model.</b> One {@link AtmosphereSummary} — a pressure,
 * whether the air can be breathed, the warning to show and the statements that hold — computed by the
 * server and drawn by the client, which decides nothing about it. A stale readout therefore costs a
 * lagging line of text and nothing else.</p>
 */
public final class ClientAtmosphere {

    /** No pressure has been received. Distinguishable from a legitimate reading of zero, which is
     *  what the previous default of plain {@code 0} was not: every reader tests for this sentinel. */
    public static final int NO_READING = -1;

    private AtmosphereSummary summary = AtmosphereSummary.UNKNOWN;
    private boolean reported;
    private long lastSuffocationTime = Long.MIN_VALUE;

    private ClientAtmosphere() {
    }

    /** What {@code world}'s client copy has been told about its player's air. */
    public static ClientAtmosphere of(World world) {
        return WorldRuntime.of(world, ClientAtmosphere.class, ClientAtmosphere::new);
    }

    /** The server's last readout for this player's position; {@link AtmosphereSummary#UNKNOWN}
     *  before the first report. Never null. */
    public AtmosphereSummary summary() {
        return summary;
    }

    /** The pressure the server last reported, in hundredths of an atmosphere, or {@link #NO_READING}
     *  before the first report — which is a different answer from "vacuum". */
    public int pressure() {
        return reported ? summary.pressureCentiAtm() : NO_READING;
    }

    /** Take a fresh readout from the server. */
    public void accept(AtmosphereSummary summary) {
        this.summary = summary == null ? AtmosphereSummary.UNKNOWN : summary;
        this.reported = summary != null;
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
