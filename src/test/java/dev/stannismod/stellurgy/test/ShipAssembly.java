package dev.stannismod.stellurgy.test;

import java.util.List;

/**
 * One press of an assembler on a fixture laid for a tier-2 SHIP, and what the press made, read off the
 * assembler's own records rather than off what stands on the pad.
 *
 * <p>"The build routed to a ship and not to a rocket entity" is a statement about THIS press, so it is
 * answered by the {@code rocket_born} records the assembler at {@link #builderPos} wrote since a mark
 * taken before the press ({@link RocketFixture#rocketsBornAt}) — zero of them — and never by how many
 * rockets the world holds. The press is synchronous: a rocket it made is recorded by the time the press
 * has answered.</p>
 */
public final class ShipAssembly {

    /** The assemble reply, verbatim — the scenario's own contract reads it. */
    public final String reply;
    /** Where the assembler that was pressed stands. */
    public final int[] builderPos;
    /** Every {@code rocket_born} record of that assembler since just before the press. */
    public final List<String> rocketsBorn;

    private ShipAssembly(String reply, int[] builderPos, List<String> rocketsBorn) {
        this.reply = reply;
        this.builderPos = builderPos;
        this.rocketsBorn = rocketsBorn;
    }

    /**
     * Press the assembler at {@code builderPos} in {@code dim} through {@code act}, and read what it made
     * off {@code serverLog}, the same server's log.
     */
    public static ShipAssembly press(int dim, Events.Probe act, Events serverLog, int[] builderPos)
            throws Exception {
        long mark = serverLog.mark();
        String reply = act.exec("stellurgytest rocket assemble " + dim
                + " " + builderPos[0] + " " + builderPos[1] + " " + builderPos[2]);
        return new ShipAssembly(reply, builderPos, RocketFixture.rocketsBornAt(serverLog, mark, builderPos));
    }

    /** {@link RocketFixture#placeAt} through {@code arrange}, then {@link #press} at what it laid. */
    public static ShipAssembly at(FixtureSite site, Events.Probe arrange, Events.Probe act, Events serverLog,
                                  String variant, int halo, int height, String what) throws Exception {
        return press(site.dim, act, serverLog, RocketFixture.placeAt(site, arrange, variant, halo, height, what));
    }

    /** Whether the press made no rocket entity — the half of "it routed to a ship" a rocket would break. */
    public boolean madeNoRocket() {
        return rocketsBorn.isEmpty();
    }

    @Override
    public String toString() {
        return reply + " | rockets this press made: " + rocketsBorn;
    }
}
