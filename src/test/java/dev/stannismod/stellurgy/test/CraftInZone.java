package dev.stannismod.stellurgy.test;

import java.util.UUID;

/**
 * A craft recorded in the zone of one galactic cell, through the PRODUCTION ship ledger — and given
 * back when it is closed.
 *
 * <p>{@code stellurgytest space ledger-settle <ship> <cellKey>} materializes the cell and calls
 * {@code ShipLedger#settle}, which is the call every way into a cell ends in: a seam or zone crossing,
 * a jump arrival, an entry from a planet. So what production decides on a craft being recorded in a
 * zone is decided here exactly as it is for a flown one. What this does NOT have is the flight: no hull
 * stands in the cell, and the crossings that reach {@code settle} are not exercised.</p>
 *
 * <p>The ship is a fresh random id, so it can collide with no real one. {@link #close} forgets it from
 * the ledger and releases the cell's occupant count — both through the probe, both required to
 * succeed: a shared server keeps the slot bound and the cell claimed for as long as either is left
 * behind.</p>
 *
 * <p>Galactic cells only: the probe refuses a zoned key, which carries no lattice width.</p>
 */
public final class CraftInZone implements AutoCloseable {

    /** How this instrument reaches the probe. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    private final Probe probe;
    /** The id the craft is ledgered under. */
    public final String shipId;
    /** The galactic cell it is recorded in, as the key it was given. */
    public final String cellKey;

    private CraftInZone(Probe probe, String shipId, String cellKey) {
        this.probe = probe;
        this.shipId = shipId;
        this.cellKey = cellKey;
    }

    /** Record a new craft at the centre of the galactic cell {@code cellKey}. */
    public static CraftInZone settle(Probe probe, String cellKey) throws Exception {
        String shipId = UUID.randomUUID().toString();
        String command = "stellurgytest space ledger-settle " + shipId + " " + cellKey;
        Reply.of(command, probe.exec(command)).requireOk(command);
        return new CraftInZone(probe, shipId, cellKey);
    }

    /** Forget the craft and give the cell back. */
    @Override
    public void close() throws Exception {
        String forget = "stellurgytest space ledger-forget " + shipId;
        Reply forgot = Reply.of(forget, probe.exec(forget)).requireOk(forget);
        ArrangementFailure.requireArranged("the craft must have been in the ledger to be forgotten: "
                + forgot, forgot.bool("wasKnown"));
        String release = "stellurgytest space release " + cellKey;
        Reply.of(release, probe.exec(release)).requireOk(release);
    }
}
