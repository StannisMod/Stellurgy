package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;


import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * Regression guard for the Free Flight launch gate.
 *
 * <p>Entering Free Flight must require the same fuel + climb authority (TWR &gt; 1)
 * the engine-start ritual enforces. {@code prepareLaunch()} is reachable WITHOUT
 * that ritual — a redstone monitoring station calls it directly — so a fuel-less
 * or underpowered FF craft launched that way used to call {@code startFreeFlight()}
 * unconditionally: it set isInFlight but never thrust, never left the ground, and
 * therefore never re-landed — a permanent on-pad in-flight dead-state with no way
 * to restart the engine.
 *
 * <p>This pins that a fuel-drained FF rocket driven through {@code prepareLaunch()}
 * stays grounded ({@code isInFlight == false}). Before the gate was shared into
 * {@code prepareLaunch()} this assertion failed (the rocket entered flight).
 */
public class FreeFlightLaunchGateTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int buildAndAssemble(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the
        // body below unchanged, so what moved is visible in one place.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        site.requireClear(cmd -> ok(client().execute(cmd)), 2, 10,
                "the craft is built and flown in this volume");

        String fixture = ok(client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple"));
        assertTrue("fixture failed: " + fixture, Reply.of(fixture).ok());
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture missing builderPos: " + fixture, bp != null);
        int bx = bp[0];
        int by = bp[1];
        int bz = bp[2];

        String assemble = ok(client().execute(
                "artest rocket assemble 0 " + bx + " " + by + " " + bz));
        assertTrue("assemble failed: " + assemble, Reply.of(assemble).ok());

        String list = ok(client().execute("artest rocket list 0"));
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("rocket list empty after assemble: " + list, !built.isEmpty());
        return built.get(built.size() - 1).id;
    }

    @Test
    public void fuellessFreeFlightRocketStaysGroundedOnPrepareLaunch() throws Exception {
        int id = buildAndAssemble(FixtureSite.openAir(0, 3380, 700));

        String mode = ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        assertTrue("set FREE_FLIGHT failed: " + mode, "FREE_FLIGHT".equals(Reply.of(mode).text("flightMode")));

        // Remove all fuel so canStartFreeFlight() must reject (rocketRequireFuel
        // defaults true).
        String drain = ok(client().execute("artest rocket drain-fuel " + id));
        assertTrue("drain-fuel failed: " + drain, Reply.of(drain).ok());

        // Drive prepareLaunch through the redstone-equivalent server entry.
        String resp = ok(client().execute("artest rocket ff-prepare-launch " + id));
        assertTrue("ff-prepare-launch probe failed: " + resp, Reply.of(resp).ok());
        assertTrue("rocket must be in FREE_FLIGHT mode for this pin: " + resp,
                Reply.of(resp).bool("isFreeFlight"));

        // The gate: no fuel => must NOT enter flight (no on-pad dead-state).
        assertTrue("fuel-less FF rocket must stay grounded after prepareLaunch "
                        + "(gate regression — it entered flight): " + resp,
                (!Reply.of(resp).bool("isInFlight")));
    }
}
