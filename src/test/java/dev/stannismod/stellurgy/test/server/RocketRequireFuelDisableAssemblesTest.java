package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ConfigFlag;
import org.junit.Test;


import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.RocketFixture;

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
 * dev.stannismod.stellurgy.api.StatsRocket#getBaseFuelRate} returns 0 by
 * design (the rocket burns no fuel), so the new guard turned every
 * {@code rocketRequireFuel=false} build into {@code NOFUEL} — no number of fuel
 * tanks could satisfy it (the gate fails on the engine fuel <i>rate</i>, not on
 * tank capacity). Before the merge the same path divided by that zero rate and
 * accidentally passed via a {@code +Infinity} burn time.</p>
 *
 * <p>The {@code simple} fixture assembles to SUCCESS with {@code rocketRequireFuel=true}; the
 * contract here is that flipping the flag off does not break that. The class also holds the other
 * fuel-at-assembly contract: the climb-to-orbit check is a ROCKET's, never a ship's. Each scenario
 * states the flag it needs and puts back what it found — the shared harness world starts with it
 * off.</p>
 */
public class RocketRequireFuelDisableAssemblesTest extends AbstractSharedServerTest {

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
        client().execute("stellurgytest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2);
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        return RocketFixture.assembleAt(site, cmd -> String.join("\n", client().execute(cmd)),
                "simple", 2, 10,
                "the craft is built and flown in this volume");
    }

    private static String status(String assembleResponse) {
        Reply mReply = Reply.of(assembleResponse);
        return mReply.has(STATUS) ? mReply.text(STATUS) : "<none>";
    }

    /**
     * With fuel required, a ROCKET whose tanks cannot carry it to its world's orbit line is refused,
     * and a SHIP built round a flight computer is not held to that climb.
     *
     * <p>This fails if production breaks the contract that <b>{@code TileRocketAssemblingMachine}
     * asks "can its tanks reach orbit" of a rocket only</b>: a rocket's one flight is that climb, while
     * a ship is flown and is a legitimate craft for flights that never leave the planet. The rocket leg
     * is the control — it shows the reach check is live in this world (the overworld's line is its
     * body's, 100 000 blocks), so the ship's success is the exemption and not an empty check.</p>
     *
     * <p>red-witnessed: 2026-10-05, with {@code TileRocketAssemblingMachine#scanRocket} at {@code scannedFlightComputerPos == null}
     * dropped from the NOFUEL branch (the reach check asked of every build), this fails with "a ship must assemble with
     * fuel required even though its tanks cannot reach orbit".</p>
     * Pins INV-RASM-13 (The burn-distance NOFUEL ("can its tanks carry it to orbit", hasEnoughFuel) is asked of a ROCKET only).
     */
    @Test
    public void aShipIsNotHeldToTheClimbToOrbitThatARocketIs() throws Exception {
        try (ConfigFlag fuelRequired = ConfigFlag.set(this::cmd, "rocketRequireFuel", true)) {
            // The fixtures' own extent: two blocks round the pad, ten above it.
            String rocket = RocketFixture.assembleAt(FixtureSite.openAir(0, 3520, 3400),
                    c -> String.join("\n", client().execute(c)), "with-fluid-cargo", 2, 10,
                    "the rocket is built in this volume");
            assertEquals("control: a rocket with cargo in place of tanks must be refused for want of "
                    + "the climb to orbit, or the ship below proves nothing: " + rocket,
                    "NOFUEL", status(rocket));

            String ship = RocketFixture.assembleAt(FixtureSite.openAir(0, 3580, 3400),
                    c -> String.join("\n", client().execute(c)), "with-pilot-deck", 2, 10,
                    "the ship is built in this volume");
            assertTrue("a ship must assemble with fuel required even though its tanks cannot reach "
                    + "orbit: " + ship, Reply.of(ship).ok());
            assertEquals("…and it must be a SHIP, not a rocket entity: " + ship,
                    0, Reply.of(ship).integer("rocketCount"));
        }
    }

    /** Pins INV-RASM-06 (with rocketRequireFuel off a valid structure still assembles). */
    @Test
    public void validRocketAssemblesWhenFuelNotRequired() throws Exception {
        try (ConfigFlag restored = ConfigFlag.set(this::cmd, "rocketRequireFuel", true)) {
            // Positive control: the simple fixture assembles with fuel required.
            // The assemble probe reports "ok":true only when the SCAN status was
            // SUCCESS; the "status" field it echoes is the POST-assemble status
            // (ALREADY_ASSEMBLED), so we gate on "ok":true, not status==SUCCESS.
            String on = buildAndAssemble(FixtureSite.openAir(0, 3400, 3400));
            assertTrue("simple fixture must assemble on rocketRequireFuel=true (scan SUCCESS): " + on,
                    Reply.of(on).ok());

            // Contract: flipping fuel off must NOT block assembly. Pre-fix the
            // scan returned NOFUEL (the regression) and "ok":true was absent.
            assertTrue(Reply.of(cmd("stellurgytest config set rocketRequireFuel false")).ok());
            String off = buildAndAssemble(FixtureSite.openAir(0, 3460, 3400));
            assertTrue("with rocketRequireFuel=false a valid rocket must still assemble "
                    + "(no fuel-adequacy gate); scan status was " + status(off) + ": " + off,
                    Reply.of(off).ok());
        }
    }
}
