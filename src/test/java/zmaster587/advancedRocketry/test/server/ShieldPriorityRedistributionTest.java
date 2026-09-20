package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.ShieldTile;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * P4 (D134-5): redistribution priority — under an energy deficit the network fills higher-priority
 * emitters first, so a player can pour a starved supply into the emitters that matter ("all power to the
 * rear shields"). One generator feeds two emitters through the middle; it produces enough for only one,
 * so the two are in genuine competition.
 *
 * <p>The load-bearing, DFS-order-independent proof is that the fed emitter <b>follows the priority
 * setting</b>: raise emitter A and A is the one that powers while B starves; flip the priority to B and
 * the outcome flips. A plain max-flow with equal priority could favour either arbitrarily, so only the
 * flip proves priority — not the layout — decides who is fed.</p>
 *
 * <p>The network solve lives in a {@code WorldTickEvent} handler a command cannot advance from inside a
 * server tick; the test drives it via {@code /artest shield tick}.</p>
 */
public class ShieldPriorityRedistributionTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = FixtureSite.OPEN_AIR_Y;
    private static final int FE_PER_ITERATION = 4000;
    private static final String STORED = "shieldStored";

    @Test
    public void underDeficitTheHigherPriorityEmitterIsFedAndFollowsTheSetting() throws Exception {
        // A - generator - B, all adjacent: one network, the generator between the two emitters. It
        // converts 4000/tick — enough to fill one emitter's 4000/tick intake, not both.
        int ax = 1060, z = 820;
        int gx = ax + 1, bx = ax + 2;
        place("affs:field_generator", ax, z);
        place("affs:shield_generator", gx, z);
        place("affs:field_generator", bx, z);

        // Phase 1: A outranks B. The scarce supply must fill A while B starves.
        setPriority(ax, z, 1);
        setPriority(bx, z, 0);
        chargeBoth(gx, z, 9);

        assertPoweredAndStarved("A(high)", ax, "B(low)", bx, z);

        // Phase 2: flip. Zero both coils, swap the priorities, and the FED emitter must swap to B — proving
        // priority, not the layout or the solver's traversal order, decides who is fed.
        exec("artest shield charge " + DIM + " " + ax + " " + Y + " " + z + " 0");
        exec("artest shield charge " + DIM + " " + bx + " " + Y + " " + z + " 0");
        setPriority(ax, z, 0);
        setPriority(bx, z, 1);
        chargeBoth(gx, z, 9);

        assertPoweredAndStarved("B(high)", bx, "A(low)", ax, z);
    }

    private void assertPoweredAndStarved(String fedName, int fedX, String starvedName, int starvedX, int z)
            throws Exception {
        ShieldTile fed = read(fedX, z);
        ShieldTile starved = read(starvedX, z);
        long fedStored = fed.shieldStored();
        long starvedStored = starved.shieldStored();
        assertTrue("the higher-priority emitter " + fedName + " was not powered under the deficit — the "
                + "scarce supply did not go to it first:\n" + fed.raw(), fed.powered());
        assertTrue("the lower-priority emitter " + starvedName + " should starve while " + fedName
                + " is fed (fed=" + fedStored + " starved=" + starvedStored + "): priority did not "
                + "redistribute the deficit:\n" + starved.raw(), fedStored > starvedStored + 15_000L);
    }

    private void chargeBoth(int gx, int gz, int iterations) throws Exception {
        for (int i = 0; i < iterations; i++) {
            exec("artest energy inject " + DIM + " " + gx + " " + Y + " " + gz + " " + FE_PER_ITERATION);
            exec("artest tile force-tick " + DIM + " " + gx + " " + Y + " " + gz + " 1");
            exec("artest shield tick " + DIM);
        }
    }

    private void setPriority(int x, int z, int value) throws Exception {
        String resp = exec("artest shield priority " + DIM + " " + x + " " + Y + " " + z + " " + value);
        assertTrue("failed to set priority " + value + " at " + x + ": " + resp,
                String.valueOf(value).equals(Reply.of(resp).text("priority")));
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
        assertTrue("no shieldStored in probe response: " + json, mReply.has(STORED));
        return Long.parseLong(mReply.text(STORED));
    }

    private static String exec(String command) throws Exception {
        return join(client().execute(command));
    }

    private static String join(List<String> resp) {
        return String.join("\n", resp);
    }
}
