package dev.stannismod.stellurgy.test.trace;

import dev.stannismod.stellurgy.tile.satellite.TerminalReadout;
import dev.stannismod.stellurgy.tile.satellite.TileSatelliteTerminal;

/**
 * What a satellite terminal's screen is drawn from, as one record payload: the {@code outcome} it shows,
 * what its last contact brought ({@code received}, {@code lostAboard}, {@code partsFailed}, {@code readings},
 * {@code missed}, {@code powerPerTick}, {@code aboard}, {@code capacity}), the points in its own buffer
 * ({@code terminalData}), the rows it holds ({@code rows}, of which {@code probeRows} are a flight recorder's)
 * and a digest of their content ({@code rowsDigest}).
 *
 * <p>One payload for both sides — the server's {@code terminal_try} and the client's
 * {@code terminal_copy_applied} — so a test compares the two field by field: each side's payload is read off
 * that side's own terminal object. SILENT about the rows' order beyond what the digest folds in (it is
 * order-sensitive) and about a row's phase below a double's bits.</p>
 */
public final class TerminalState {

    private TerminalState() {
    }

    /** {@code terminal}'s state as the fields above, without braces, ready to follow a place. */
    public static String fields(TileSatelliteTerminal terminal) {
        TerminalReadout readout = terminal.readout();
        TerminalReadout.Contact last = readout.last();
        return "\"outcome\":\"" + terminal.outcome().name() + "\""
                + ",\"received\":" + last.received + ",\"lostAboard\":" + last.lostAboard
                + ",\"partsFailed\":" + last.partsFailed + ",\"readings\":" + last.readings
                + ",\"missed\":" + last.missed + ",\"powerPerTick\":" + last.powerPerTick
                + ",\"aboard\":" + last.aboard + ",\"capacity\":" + last.capacity
                + ",\"terminalData\":" + terminal.getDataObject().getData()
                + ",\"rows\":" + readout.rows().size()
                + ",\"probeRows\":" + probeRows(readout)
                + ",\"rowsDigest\":" + rowsDigest(readout);
    }

    /** How many of the rows are the first probe's, from a flight recorder in the data slot. */
    private static int probeRows(TerminalReadout readout) {
        int n = 0;
        for (TerminalReadout.Row row : readout.rows()) {
            if (row.fromProbe) {
                n++;
            }
        }
        return n;
    }

    /**
     * An order-sensitive digest of every row's tick, phase, kind, count and slot, folded to an int: a reader
     * takes a record's number as a double, which holds an int exactly and a long only to 53 bits.
     */
    private static int rowsDigest(TerminalReadout readout) {
        long digest = 1L;
        for (TerminalReadout.Row row : readout.rows()) {
            digest = 31L * digest + row.tick;
            digest = 31L * digest + Double.doubleToLongBits(row.phaseRad);
            digest = 31L * digest + row.kind.ordinal();
            digest = 31L * digest + row.count;
            digest = 31L * digest + row.slot;
        }
        return (int) (digest ^ (digest >>> 32));
    }
}
