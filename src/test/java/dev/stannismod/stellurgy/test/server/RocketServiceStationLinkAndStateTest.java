package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.PeriodicGateWindow;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ScenarioRockets;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.After;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Service-station observability + link contract.
 *
 * <p>{@link dev.stannismod.stellurgy.tile.infrastructure.TileRocketServiceStation}
 * is the only way for a player to repair a rocket's worn parts late-game.
 * Pre-this-test, the 562-line tile had ZERO server-tier coverage — only
 * {@code IInfrastructure} link/unlink + {@code maxLinkDistance} from
 * {@code RocketInfrastructureSmokeTest} touched it generically.</p>
 *
 * <p>Pins two contracts:</p>
 *
 * <ul>
 *   <li><b>Link contract</b>: {@code rocket.linkInfrastructure(serviceStation)}
 *       sets {@code linkedRocket} to that rocket entity; service-state
 *       probe reads it back as the entity id.</li>
 *   <li><b>Fresh-rocket invariant</b>: a freshly-assembled rocket
 *       (no flight time, no wear) has zero {@code partsToRepair} —
 *       the production "wear via use" path is the ONLY way to grow
 *       that list. Pin protects against a regression that auto-marks
 *       freshly-assembled parts as worn.</li>
 * </ul>
 *
 * <p><b>Out of scope (deferred)</b>: the full repair cycle (worn parts
 * &rarr; linked PrecisionAssembler &rarr; completed item &rarr; stage=0) requires
 * fixture infrastructure for injecting {@code TileBrokenPart} instances
 * with stage&gt;0 into a rocket's {@code StorageChunk}, plus an
 * adjacent {@code TilePrecisionAssembler} with a valid recipe. That
 * fixture cost (~6-8 hours) was deemed too high relative to the
 * audit-batch ROI. This test pins the OBSERVABILITY surface needed by
 * a future repair-cycle test to detect that production state is
 * sane between phases.</p>
 */
public class RocketServiceStationLinkAndStateTest extends AbstractSharedServerTest {

    private static final String LINKED_ID = "linkedRocketId";

    private final ScenarioRockets rockets = ScenarioRockets.of(commands(), evictionReports());

    @After
    public void disposeOfThisScenariosRockets() throws Exception {
        rockets.disposeStanding();
    }
    private static final String PARTS_COUNT = "partsToRepairCount";

    /**
     * The one anchor every Y in this class is derived from — the craft's base and the service
     * station standing ten blocks from it. A hard-coded 64 until 2026-09-21; the class moves as
     * a unit, because the link it asserts is between two things that must stay level.
     */
    private static final int CY_PAD       = FixtureSite.OPEN_AIR_Y;
    private static final int CZ_PAD       = 7000;
    private static final int CX_WITH_LINK = 7400;

    /** Building a rocket via {@code fixture rocket simple}, placing a
     *  service station nearby, and calling {@code infra link} attaches
     *  the rocket to the station — service-state probe reads back the
     *  entity id.
     *
     *  <p>Also pins the fresh-rocket invariant: a just-assembled rocket
     *  has zero worn parts (TileBrokenPart with stage>0). Production
     *  only marks parts as worn through the wear-on-use path; if a
     *  regression auto-stages-up new parts, this test fires.</p> */
    @Test
    public void linkedFreshRocketAppearsInServiceStationStateWithZeroWornParts()
            throws Exception {
        // Build + assemble a standard rocket fixture far from any other patch.
        // FIRST link, ASSERTING where the warmup+fill pair DUG and threw its own answer away. The
        // rocket is the one the press's own `rocket_born` record names.
        int rocketId = rockets.assembleAt(FixtureSite.openAir(0, CX_WITH_LINK, CZ_PAD), c -> act(c).raw(),
                "simple", 2, 10, "the fresh craft the service station is linked to stands in this volume");

        // Place service station near the launchpad (not on it — the pad
        // is occupied). Position-isolated from CX_NO_LINK.
        int sx = CX_WITH_LINK + 10, sy = CY_PAD, sz = CZ_PAD;
        String place = arrange("stellurgytest place 0 " + sx + " " + sy + " " + sz
                + " stellurgy:serviceStation").raw();
        assertTrue("service station place failed: " + place,
                Reply.of(place).bool("placed"));

        // Link.
        String link = arrange("stellurgytest infra link 0 " + sx + " " + sy + " " + sz
                + " " + rocketId).raw();
        assertTrue("infra link must succeed: " + link,
                Reply.of(link).ok());

        // Verify the service station now reports the rocket's entityId.
        String state = read("stellurgytest infra service-state 0 " + sx + " " + sy + " " + sz).raw();
        assertEquals("linked service station must report rocket's entityId",
                rocketId, extract(state, LINKED_ID));

        // Fresh-rocket invariant — no worn parts (TileBrokenPart with
        // stage>0). Production wear-on-use is the only path that grows
        // this count; a regression that auto-stages-up fresh parts
        // would surface here as a non-zero count.
        assertEquals("freshly-assembled rocket must have zero worn parts: "
                        + state,
                0, extract(state, PARTS_COUNT));
    }

    /**
     * The world ticks this many times while the station is watched: long enough for at least three openings of a
     * gate whose period is up to a quarter of it, which is the evidence an even spacing needs.
     */
    private static final int WATCHED_TICKS = 100;

    /**
     * C16 CLOCK-9 on the service station: its work gate counts the world's TOTAL time, so on a world whose time of
     * day stands still — the overworld's body locked to its star for the window — it still opens, at one even
     * spacing of total time. A gate on the time of day would there open never, or on every tick.
     */
    @Pins("CLOCK-9")
    @RedWitness(verdict = "C16 CLOCK-9", member = "TileRocketServiceStation#canPerformFunction",
            at = "if (world.isRemote || world.getTotalWorldTime() % 20 != 0) {",
            replacedBy = "if (world.isRemote || world.getWorldTime() % 20 != 0) {",
            fails = "at one even spacing of total time longer than a tick", on = "2026-10-26")
    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advanceWorld(client(), 0, WATCHED_TICKS);",
            why = "the measurement is the spacing of the gate's openings over an interval of the world's own ticks;"
                    + " no single record says a gate opens evenly, only the openings recorded across the window do")
    @Test
    public void theStationsGateOpensOnTotalTimeWhereTheTimeOfDayStandsStill() throws Exception {
        FixtureSite site = clearedSite(0, 2, "a lone service station on a world whose time of day stands still");
        int x = site.x;
        int y = site.y + 1;
        int z = site.z;
        arrange("stellurgytest place 0 " + x + " " + y + " " + z + " stellurgy:serviceStation").requireOk();
        arrange("stellurgytest chunk forceload 0 " + (x >> 4) + " " + (z >> 4)).requireOk();
        arrange("stellurgytest arrange tidal-lock 0 true").requireOk();
        try {
            Events events = new Events(command -> String.join("\n", client().read(command)),
                    ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());
            long mark = events.markInstrumented();
            GameTicks.advanceWorld(client(), 0, WATCHED_TICKS);
            String reply = events.since(mark, "periodic_gate_opened");
            Events.assertInstrumentRan(reply, "periodic_gate_events", "the station's update must be watched before"
                    + " an absent opening can mean its gate stayed shut");
            PeriodicGateWindow window = PeriodicGateWindow.of(reply, "rocket_service_station", 0, x, y, z);
            requireArranged("the overworld's time of day must stand still while its body is locked: " + window,
                    window.timeOfDayStoodStill());
            assertTrue("C16 CLOCK-9: on a world whose time of day stands still the station's gate still opens, at"
                            + " one even spacing of total time longer than a tick; openings: " + window,
                    window.openings.size() >= 3 && window.evenSpacing() > 1);
        } finally {
            arrange("stellurgytest arrange tidal-lock 0 false").requireOk();
            arrange("stellurgytest chunk release 0 " + (x >> 4) + " " + (z >> 4)).requireOk();
            arrange("stellurgytest place 0 " + x + " " + y + " " + z + " minecraft:air").requireOk();
        }
    }

    private static int extract(String src, String field) {
        Reply reply = Reply.of(src);
        assertTrue("field `" + field + "` not found in: " + src, reply.has(field));
        return reply.integer(field);
    }
}
