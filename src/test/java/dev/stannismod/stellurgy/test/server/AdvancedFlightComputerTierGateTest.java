package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Disposal;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShipBirth;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Tier-2 gate — an Advanced Flight Computer in an assembled structure decides whether the launch
 * pad builds an ordinary rocket or a movable physics ship.
 *
 * <p><b>There is ONE half of that gate now, not two.</b> This class used to describe a fork on
 * whether the physics substrate was present: without it the computer was inert and the build fell
 * back to a rocket, with it the build routed to ship assembly. The substrate is compiled into this
 * jar, so the first branch was unreachable — its two scenarios were silently skipped on every run
 * for months, and the production fork behind them was deleted on 2026-09-22. What is left is the
 * gate that can actually go both ways: an AFC-bearing build assembles a ship and no rocket, and an
 * AFC alone satisfies guidance.</p>
 *
 * <p>What this deliberately does NOT assert: that the ship then fully materialises, is pilotable,
 * or simulates physics. The observable contract here is the routing decision — the press names a
 * ship it ASSEMBLED and its assembler records no rocket born — read off production's own records of
 * that press.</p>
 */
public class AdvancedFlightComputerTierGateTest extends AbstractSharedServerTest {


    private static final String VARIANT = "with-advanced-flight-computer";

    /** Server ticks for the press's naming of its ship to be recorded: the substrate names it on its next tick. */
    private static final int NAMING_LINK_TICKS = 200;

    // THE TWO "WITHOUT VS" SCENARIOS ARE DELETED, 2026-09-22, and their own @Ignore said when to do
    // it. Retired in place on 2026-08-21 with this reason: "Retired rather than deleted because the
    // production fallback it describes may still exist, and would then be dead code with no test to
    // say so; that is its own finding, not this file's." That finding was made and acted on today —
    // `TileRocketAssemblingMachine`'s no-substrate fork is gone, along with the other 85 branches on
    // an availability probe for a class compiled into this jar. There is no fallback left for these
    // to describe, so keeping them would pin a behaviour the code no longer has.

    @Test
    public void flightComputerWithVsAssemblesShipNotRocket() throws Exception {
        // The defining contract of the fork WITH VS: the AFC diverts the build to a ship, so no
        // EntityRocket is born on the pad and a ship is.
        ShipBirth ship = assembleFixture(FixtureSite.openAir(0, 1600, 1600), VARIANT,
                "with VS, an AFC-bearing build must become a ship");
        assertTrue("with VS, an AFC-bearing build must not spawn a rocket: " + ship,
                ship.rocketsBorn.isEmpty());
    }

    @Test
    public void flightComputerAloneSatisfiesGuidanceAndAssemblesShipWithVs() throws Exception {
        // The build a player actually makes: an Advanced Flight Computer and NO guidance
        // computer. The AFC IS the tier-2 ship's flight computer, so it must satisfy the
        // "computer with instructions" requirement — the scan must not reject it as NOGUIDANCE,
        // and with VS the build routes to a ship (no rocket).
        ShipBirth ship = assembleFixture(FixtureSite.openAir(0, 2000, 2000), "advanced-flight-computer-only",
                "an AFC alone must satisfy the guidance requirement and route to a ship");
        assertTrue("an AFC alone must satisfy the guidance requirement and route to a ship "
                + "(no rocket): " + ship, ship.rocketsBorn.isEmpty());
    }

    /** The craft this scenario built, once the press has named it; disposed of when the scenario ends. */
    private ShipBirth built;

    @org.junit.After
    public void disposeOfTheCraft() throws Exception {
        if (built != null) {
            Disposal.ship(c -> arrange(c).raw(), events, built.dim, built.physicsId);
            built = null;
        }
    }

    private final Events events =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /**
     * Place the fixture on a pad and run scan+assemble; answers the ship the press made, read off the
     * press's own records ({@link ShipBirth}).
     */
    private ShipBirth assembleFixture(FixtureSite site, String variant, String what) throws Exception {
        int[] bp = placeFixture(site, variant);
        long mark = events.mark();
        String assemble = String.join("\n", client().act("stellurgytest rocket assemble 0 "
                + bp[0] + " " + bp[1] + " " + bp[2]));
        assertTrue("assemble (" + variant + ") failed: " + assemble, Reply.of(assemble).ok());
        built = ShipBirth.ofPress(events, mark, 0, bp, assemble, NAMING_LINK_TICKS, what);
        return built;
    }

    /**
     * Place the fixture on a pad (warmup + pre-clear + spawn) WITHOUT assembling; returns the
     * builder position.
     */
    private int[] placeFixture(FixtureSite site, String variant) throws Exception {
        final int baseX = site.x, baseZ = site.z;
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        String warmup = String.join("\n", client().arrange(
                "stellurgytest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2));
        assertTrue("chunk warmup failed: " + warmup, Reply.of(warmup).ok());

        // FIRST link: the volume this craft is built in is EMPTY. The site stands in open air, so
        // this ASSERTS rather than digs - anything standing here means the arrangement is wrong,
        // and it is said now instead of arriving many links later as a scan that found nothing.
        return RocketFixture.placeAt(site, cmd -> String.join("\n", client().arrange(cmd)),
                variant, 2, 10,
                "the craft is built and scanned in this volume");
    }
}
