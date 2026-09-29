package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.ShieldTile;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.List;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * P1 trim (c): the shield <em>accumulator</em> — a bulk reserve that is BOTH a shield source and a
 * shield sink. These pin its player-visible contracts on the vendored AFFS network:
 *
 * <ul>
 *   <li><b>Dual-role bridge.</b> A generator and an emitter separated <em>only</em> by an accumulator
 *       (G-A-E, no cable, generator not adjacent to the emitter) still power the shield. The generator
 *       can reach the emitter only if the accumulator absorbs energy (as a sink) and re-emits it (as a
 *       source) — a plain source or plain sink could not bridge the gap.</li>
 *   <li><b>Bulk reserve.</b> Fed surplus, the accumulator stores far more than a generator's small
 *       conversion buffer ever holds — it is the network's reserve, not a smoothing store.</li>
 *   <li><b>Energy is conserved on drain.</b> A pre-charged accumulator that is the sole source of a
 *       drained emitter sustains it for many ticks; it does not haemorrhage its reserve. This guards
 *       the fix that caps the emitter's advertised demand at its real per-tick intake, so the network
 *       never extracts from a source more than the coil actually receives.</li>
 * </ul>
 *
 * <p>The network solve lives in a {@code WorldTickEvent} handler that a command cannot advance from
 * inside a server tick; the test drives it deterministically via {@code /artest shield tick}.</p>
 */
public class ShieldAccumulatorTest extends AbstractSharedServerTest {

    /**
     * The reserves this class reads, in power units.
     *
     * <p>All three are the test's own bars on an ARRANGEMENT rather than contract thresholds: the
     * accumulator must have built a bulk reserve before anything about its behaviour can be read,
     * and the haemorrhage leg needs a reserve big enough that a leak is visible against it.</p>
     */
    private static final long BULK_RESERVE = 100_000L;
    /** @see #BULK_RESERVE */
    private static final long CHARGED_RESERVE = 150_000L;
    /** @see #BULK_RESERVE */
    private static final long RESERVE_AFTER_DRAW = 120_000L;

    private static final int DIM = 0;
    private static final int Y = FixtureSite.OPEN_AIR_Y;
    private static final int FE_PER_ITERATION = 4000;
    private static final String STORED = "shieldStored";

    @Test
    public void accumulatorBridgesGeneratorToEmitter() throws Exception {
        // Line: generator - accumulator - emitter, each adjacent along +X. The generator is NOT
        // adjacent to the emitter, so energy can reach the emitter only through the accumulator.
        int gx = 950, gz = 772;
        int ax = gx + 1, ex = gx + 2;
        place("affs:shield_generator", gx, gz);
        place("affs:shield_accumulator", ax, gz);
        place("affs:field_generator", ex, gz);

        for (int i = 0; i < 90; i++) {
            chargeIteration(gx, gz);
        }

        ShieldTile emitter = read(ex, gz);
        assertTrue("emitter behind an accumulator (G-A-E, no cable) never powered — the accumulator "
                        + "did not bridge generator to emitter as a dual-role store:\n" + emitter.raw(),
                emitter.powered());
    }

    @Test
    public void accumulatorBuildsBulkReserve() throws Exception {
        // Generator directly feeding an accumulator, no emitter: every converted unit is surplus, so it
        // all lands in the accumulator's reserve.
        int gx = 956, gz = 772;
        int ax = gx + 1;
        place("affs:shield_generator", gx, gz);
        place("affs:shield_accumulator", ax, gz);

        for (int i = 0; i < 60; i++) {
            chargeIteration(gx, gz);
        }

        ShieldTile acc = read(ax, gz);
        long stored = acc.shieldStored();
        // 60 iterations of 4000 FE -> shield is 240k of supply; a generator's own buffer is a small
        // fraction of that. The reserve must be genuinely bulk, not a smoothing buffer.
        assertTrue("accumulator failed to build a bulk reserve (stored=" + stored + "): it is not "
                        + "storing the network's surplus:\n" + acc.raw(), stored > BULK_RESERVE);
    }

    @Test
    public void accumulatorReserveIsConservedNotBled() throws Exception {
        // Charge an accumulator to a large reserve from a generator, then let it be the SOLE source of
        // a freshly-placed emitter with generation cut off. A conserving network drains the reserve
        // only as fast as the emitter's coil intakes; a leaking one (the pre-fix bug) empties it almost
        // at once because it extracts the emitter's whole free capacity while the coil accepts a sliver.
        int gx = 962, gz = 772;
        int ax = gx + 1;   // accumulator sits east of the generator
        int ez = gz - 1;   // emitter attaches to the accumulator's -Z face (not adjacent to the generator)
        place("affs:shield_generator", gx, gz);
        place("affs:shield_accumulator", ax, gz);

        for (int i = 0; i < 80; i++) {
            chargeIteration(gx, gz);
        }
        long reserveBefore = read(ax, gz).shieldStored();
        assertTrue("precondition: accumulator did not charge (stored=" + reserveBefore + ")",
                reserveBefore > CHARGED_RESERVE);

        // Attach the emitter to the charged accumulator and cut generation (no more FE). Drain any
        // residue left in the generator's small buffer so the accumulator is the only real source.
        place("affs:field_generator", ax, ez);
        for (int i = 0; i < 6; i++) {
            exec("artest tile force-tick " + DIM + " " + gx + " " + Y + " " + gz + " 1");
            exec("artest shield tick " + DIM);
        }

        // Now run the emitter off the reserve for a good many ticks.
        for (int i = 0; i < 20; i++) {
            exec("artest shield tick " + DIM);
        }

        ShieldTile emitter = read(ax, ez);
        assertTrue("emitter did not power from the accumulator's reserve:\n" + emitter.raw(),
                emitter.powered());

        long reserveAfter = read(ax, gz).shieldStored();
        // Conserved: the coil intakes at most a few thousand per tick, so ~26 drain ticks cost well
        // under 100k; the reserve stays comfortably above 120k. The leaking bug would have emptied a
        // 150k+ reserve within a handful of ticks.
        assertTrue("accumulator reserve haemorrhaged (before=" + reserveBefore + " after="
                        + reserveAfter + "): energy is leaving the source faster than the emitter "
                        + "receives it — the network is not conserving energy.",
                reserveAfter > RESERVE_AFTER_DRAW);
    }

    private void chargeIteration(int gx, int gz) throws Exception {
        exec("artest energy inject " + DIM + " " + gx + " " + Y + " " + gz + " " + FE_PER_ITERATION);
        exec("artest tile force-tick " + DIM + " " + gx + " " + Y + " " + gz + " 1");
        exec("artest shield tick " + DIM);
    }

    private ShieldTile read(int x, int z) throws Exception {
        return ShieldTile.at(cmd -> exec(cmd), DIM, x, Y, z);
    }

    private void place(String block, int x, int z) throws Exception {
        String resp = exec("artest place " + DIM + " " + x + " " + Y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + Y + "," + z + ": " + resp,
                Reply.of(resp).bool("placed"));
    }

    private static long readStored(String json) {
        Reply mReply = Reply.of(json);
        assertTrue("no shieldStored field in probe response: " + json, mReply.has(STORED));
        return Long.parseLong(mReply.text(STORED));
    }

    private static String exec(String command) throws Exception {
        return join(client().execute(command));
    }

    private static String join(List<String> resp) {
        return String.join("\n", resp);
    }
}
