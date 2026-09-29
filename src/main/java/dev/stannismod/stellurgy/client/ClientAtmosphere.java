package dev.stannismod.stellurgy.client;

import dev.stannismod.stellurgy.atmosphere.AtmosphereSummary;

/**
 * What this client has been told about the air around its own player.
 *
 * <p>None of it is derivable client-side: the atmosphere a position holds is the server's answer,
 * and it arrives by packet ({@code PacketAtmSync}, {@code PacketOxygenState}). So this is a copy,
 * and like every copy it is only as current as the last packet.</p>
 *
 * <p><b>OWNER: the CLIENT; LIFETIME: one connection to one server</b>, released by {@link #reset()}
 * from the disconnect hook. Static because a client's connection is what this class describes and
 * there is exactly one of it while these values mean anything — a second would be a second client
 * in this JVM, which cannot exist.</p>
 *
 * <p><b>Why it is not on {@code AtmosphereHandler} any more.</b> It lived there as three public
 * statics beside that class's server-side machinery, marked only by a one-line comment saying they
 * were the client's. A server-side path read one of them anyway and reported it to the player —
 * always the field's default, because on a dedicated server nothing ever writes it. Moving them
 * here makes that mistake a compile error rather than a wrong number.</p>
 *
 * <p><b>What it holds is a READOUT, not a model.</b> It used to keep an atmosphere object and a
 * pressure, which meant the client held a copy of the model and looked things up in it. It now
 * keeps one {@link AtmosphereSummary} — a pressure, whether the air can be breathed, the warning to
 * show and the statements that hold — computed by the server and drawn by the client, which decides
 * nothing about it. A stale readout therefore costs a lagging line of text and nothing else.</p>
 */
public final class ClientAtmosphere {

    /** No pressure has been received. Distinguishable from a legitimate reading of zero, which is
     *  what the previous default of plain {@code 0} was not: every reader tests for this sentinel. */
    public static final int NO_READING = -1;

    private static AtmosphereSummary summary = AtmosphereSummary.UNKNOWN;
    private static long lastSuffocationTime = Integer.MIN_VALUE;

    private ClientAtmosphere() {
    }

    /** The server's last readout for this player's position; {@link AtmosphereSummary#UNKNOWN}
     *  before the first report. Never null. */
    public static AtmosphereSummary summary() {
        return summary;
    }

    /** The pressure the server last reported, in hundredths of an atmosphere, or {@link #NO_READING}
     *  before the first report — which is a different answer from "vacuum". */
    public static int pressure() {
        return summary.pressureCentiAtm();
    }

    /** Take a fresh readout from the server. */
    public static void accept(AtmosphereSummary summary) {
        ClientAtmosphere.summary = summary == null ? AtmosphereSummary.UNKNOWN : summary;
    }

    /** The world time at which this client was last told it is suffocating. */
    public static long lastSuffocationTime() {
        return lastSuffocationTime;
    }

    /** Record a suffocation report, or push the mark back to silence the warning. */
    public static void suffocatedAt(long worldTime) {
        lastSuffocationTime = worldTime;
    }

    /**
     * Forget what this client was told by the server it is leaving. The next server's air has
     * nothing to do with this one's, and a pressure kept across the gap would be drawn on the next
     * world's HUD as if it had been measured there.
     */
    public static void reset() {
        summary = AtmosphereSummary.UNKNOWN;
        lastSuffocationTime = Integer.MIN_VALUE;
    }
}
