package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;


import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Disableability contract for {@code rocketRequireFuel} at rocket-assembly time.
 *
 * <p>{@code rocketRequireFuel=false} means "fuel is not required to fly". The
 * player-facing promise is that a valid rocket (engines + guidance) then
 * assembles regardless of fuel adequacy — the assembly fuel-capacity gate is
 * skipped entirely.</p>
 *
 * <p>This pins a regression introduced by the weight-system merge: it added a
 * {@code getBaseFuelRate() <= 0 -> NOFUEL} guard to {@code hasEnoughFuel}. With
 * {@code rocketRequireFuel=false}, {@link
 * zmaster587.advancedRocketry.api.StatsRocket#getBaseFuelRate} returns 0 by
 * design (the rocket burns no fuel), so the new guard turned every
 * {@code rocketRequireFuel=false} build into {@code NOFUEL} — no number of fuel
 * tanks could satisfy it (the gate fails on the engine fuel <i>rate</i>, not on
 * tank capacity). Before the merge the same path divided by that zero rate and
 * accidentally passed via a {@code +Infinity} burn time.</p>
 *
 * <p>The {@code simple} fixture assembles to SUCCESS on the default
 * {@code rocketRequireFuel=true} (pinned by {@code RocketAssemblySmokeTest}); the
 * contract here is that flipping the flag off does not break that.</p>
 */
public class RocketRequireFuelDisableAssemblesTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String STATUS = "status";

    private String cmd(String c) throws Exception {
        return String.join("\n", client().execute(c));
    }

    /** Build the simple fixture at the given pad and return the raw assemble response. */
    private String buildAndAssemble(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the
        // body below unchanged, so what moved is visible in one place.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        client().execute("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2);
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        site.requireClear(cmd -> String.join("\n", client().execute(cmd)), 2, 10,
                "the craft is built and flown in this volume");
        String fixture = cmd("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple");
        assertTrue("fixture build failed: " + fixture, Reply.of(fixture).ok());
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("no builderPos: " + fixture, bp != null);
        return cmd("artest rocket assemble 0 "
                + bp[0] + " " + bp[1] + " " + bp[2]);
    }

    private static String status(String assembleResponse) {
        Reply mReply = Reply.of(assembleResponse);
        return mReply.has(STATUS) ? mReply.text(STATUS) : "<none>";
    }

    @Test
    public void validRocketAssemblesWhenFuelNotRequired() throws Exception {
        try {
            // Positive control: the simple fixture assembles on the default.
            // The assemble probe reports "ok":true only when the SCAN status was
            // SUCCESS; the "status" field it echoes is the POST-assemble status
            // (ALREADY_ASSEMBLED), so we gate on "ok":true, not status==SUCCESS.
            assertTrue(Reply.of(cmd("artest config set rocketRequireFuel true")).ok());
            String on = buildAndAssemble(FixtureSite.openAir(0, 3400, 3400));
            assertTrue("simple fixture must assemble on rocketRequireFuel=true (scan SUCCESS): " + on,
                    Reply.of(on).ok());

            // Contract: flipping fuel off must NOT block assembly. Pre-fix the
            // scan returned NOFUEL (the regression) and "ok":true was absent.
            assertTrue(Reply.of(cmd("artest config set rocketRequireFuel false")).ok());
            String off = buildAndAssemble(FixtureSite.openAir(0, 3460, 3400));
            assertTrue("with rocketRequireFuel=false a valid rocket must still assemble "
                    + "(no fuel-adequacy gate); scan status was " + status(off) + ": " + off,
                    Reply.of(off).ok());
        } finally {
            // Restore the shared-harness default for any later test in this JVM.
            client().execute("artest config set rocketRequireFuel true");
        }
    }
}
