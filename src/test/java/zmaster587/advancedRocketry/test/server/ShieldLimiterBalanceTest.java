package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.ShieldTile;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * P6, the harness-observable half of "the interesting limiter binds": in an ordinary build the
 * constraint must be <b>emitter placement</b> (construction-derived, the fun limiter), not
 * <b>cable throughput</b> (pipe-sizing, the boring one). The final "floor trivial, ceiling deep" verdict
 * is a manual playtest — but "does plumbing bind before the emitters do?" is a number the server can
 * answer, so it is pinned here rather than left to an eyeball.
 *
 * <p>The pinned contract is a <em>relationship</em>, not a magnitude: whatever the tunables are set to,
 * a single cable must carry more than a single emitter can absorb, so a normal multi-emitter network is
 * emitter-limited. It deliberately does not assert any specific throughput value (balance numbers are
 * tunable and never pinned, per the repo's testing principles).</p>
 */
public class ShieldLimiterBalanceTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = FixtureSite.OPEN_AIR_Y;

    @Test
    public void cableCarriesMoreThanASingleEmitterAbsorbs() throws Exception {
        int x = 1070, z = 900;
        place("affs:shield_cable", x, z);
        place("affs:field_generator", x + 1, z);

        ShieldTile cable = ShieldTile.at(cmd -> exec(cmd), DIM, x, Y, z);
        ShieldTile emitter = ShieldTile.at(cmd -> exec(cmd), DIM, x + 1, Y, z);
        assertTrue("expected a cable at the probed position:\n" + cable.raw(), cable.is(ShieldTile.CABLE));
        assertTrue("expected an emitter at the probed position:\n" + emitter.raw(),
                emitter.is(ShieldTile.EMITTER));

        long cableThroughput = cable.cableThroughput();
        long emitterThroughput = emitter.rechargeThroughput();
        assertTrue("emitter throughput not reported:\n" + emitter.raw(), emitterThroughput > 0);

        assertTrue("a single cable (" + cableThroughput + ") does not carry more than one emitter absorbs ("
                        + emitterThroughput + "): pipe-sizing binds before emitter placement does, which "
                        + "inverts the P6 intent that the emitter is the interesting limiter.",
                cableThroughput > emitterThroughput);
    }

    private void place(String block, int x, int z) throws Exception {
        String resp = exec("artest place " + DIM + " " + x + " " + Y + " " + z + " " + block);
        assertTrue("failed to place " + block + ": " + resp, resp.contains("\"placed\":true"));
    }

    private static long readLong(String json, String key) {
        assertTrue("no " + key + " field in: " + json, Reply.of(json).has(key));
        return Reply.of(json).integer(key);
    }

    private static String exec(String command) throws Exception {
        return join(client().execute(command));
    }

    private static String join(List<String> resp) {
        return String.join("\n", resp);
    }
}
