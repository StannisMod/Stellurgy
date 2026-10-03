package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression guard for findings
 * C076, (HIGH) and C045 (MED): a solar
 * array/panel ticking in the space dimension ({@code StellurgyConfiguration
 * .spaceDimId}, default {@code -2}) off any station must NOT crash.
 *
 * <p>Both tiles branch on {@code spaceDimId} and read
 * {@code SpaceObjectManager.getSpaceStationFromBlockCoords(this.pos)
 * .getInsolationMultiplier()}. That lookup is null when no station occupies
 * the tile's grid cell. Before the fix the deref was unguarded
 * ({@code TileSolarArray.java:138}, {@code TileSolarPanel.java:60}), so the
 * NPE escaped {@code World.updateEntities} and hard-crashed the dedicated
 * server every tick.</p>
 *
 * <p><b>Corrected contract, pinned here</b>: off-station in the space dim →
 * 0 insolation (mirrors the sibling {@code TileMicrowaveReciever}'s guarded
 * path), so the tile ticks to 0 RF and the server keeps running. This test
 * previously pinned the crash (its polarity was flipped when the null-guard
 * fix landed). Recorded as a known defect.</p>
 *
 * <p>The two off-station scenarios create no station, so their grid cells in dim -2 resolve to a
 * null station. The third creates two stations of its own — one with no resolved planet, one
 * orbiting the overworld — and stands its panels on the cells {@code SpaceObjectManager} gives
 * them, which are the cells nearest the grid's centre and nowhere near the off-station scenarios'
 * coordinates. Position-isolated per method.</p>
 */
public class SolarTileSpaceDimUnresolvedStationNpeTest extends AbstractSharedServerTest {

    private static final int SPACE_DIM = -2;

    /** C076 — TileSolarArray ticks off-station in the space dim without NPE. */
    @Test
    public void solarArrayInSpaceDimOffStationTicksWithoutCrashing() throws Exception {
        int cx = 9100, cy = FixtureSite.OPEN_AIR_Y, cz = 9100;

        ok(client().execute("stellurgytest dim load " + SPACE_DIM));

        String fixture = join(client().execute("stellurgytest fixture multiblock solar-array "
                + SPACE_DIM + " " + cx + " " + cy + " " + cz));
        assertTrue("fixture multiblock solar-array must build in dim " + SPACE_DIM
                + ": " + fixture, Reply.of(fixture).ok());

        String tryComplete = join(client().execute("stellurgytest machine try-complete "
                + SPACE_DIM + " " + cx + " " + cy + " " + cz));
        assertTrue("solar array must validate (isComplete=true) so update() reaches "
                + "the insolation branch: " + tryComplete,
                Reply.of(tryComplete).bool("isComplete"));

        // Fixed: the off-station tick returns 0 insolation instead of NPEing.
        String tick = join(client().execute("stellurgytest tile force-tick "
                + SPACE_DIM + " " + cx + " " + cy + " " + cz + " 5"));
        assertTrue("TileSolarArray.update() off-station in the space dim must tick "
                + "without throwing (0 insolation, not a crash) after the null-guard "
                + "fix at TileSolarArray.java:138: " + tick,
                Reply.of(tick).ok());
        assertTrue("server must survive the off-station solar-array tick",
                client().isAlive());
    }

    /** C045 — TileSolarPanel ticks off-station in the space dim without NPE. */
    @Test
    public void solarPanelInSpaceDimOffStationTicksWithoutCrashing() throws Exception {
        int x = 9300, y = 200, z = 9300;

        ok(client().execute("stellurgytest dim load " + SPACE_DIM));

        ok(client().execute("stellurgytest fill " + SPACE_DIM + " " + (x - 2) + " " + (y - 2)
                + " " + (z - 2) + " " + (x + 2) + " " + (y + 4) + " " + (z + 2)
                + " minecraft:air"));

        String place = join(client().execute("stellurgytest place " + SPACE_DIM
                + " " + x + " " + y + " " + z + " stellurgy:solarGenerator"));
        assertTrue("solar generator must place: " + place,
                Reply.of(place).ok() || Reply.of(place).bool("placed"));

        client().execute("time set day");
        client().execute("weather clear 100000");

        String tick = join(client().execute("stellurgytest tile force-tick "
                + SPACE_DIM + " " + x + " " + y + " " + z + " 5"));
        assertTrue("TileSolarPanel.getPowerPerOperation() off-station in the space dim "
                + "must tick without throwing (0 insolation) after the null-guard fix "
                + "at TileSolarPanel.java:60: " + tick,
                Reply.of(tick).ok());
        assertTrue("server must survive the off-station solar-panel tick",
                client().isAlive());
    }

    /**
     * A solar panel on a station whose ORBITING PLANET IS UNRESOLVED ticks without throwing and
     * makes nothing, while the same panel on a station orbiting the overworld makes power.
     *
     * <p>The other null path of the two above: here a station DOES own the panel's grid cell, so the
     * tile's own null-station guard passes it by and the station is asked for its insolation. The
     * station is created with no planet — the state a station is in between planet assignments,
     * {@code created} with its parent still {@code INVALID_PLANET} — and this test fails if
     * {@code SpaceStationObject#getInsolationMultiplier} stops deciding that a station with no
     * resolved planet receives no sunlight (it used to dereference the missing planet, which threw
     * inside the tile tick). The control is the same panel on a station orbiting the overworld, read
     * through the same instrument, so a zero below cannot be a panel that would never have made
     * anything here.</p>
     *
     * <p>Each panel stands at its station's spawn location as {@code SpaceObjectManager} placed it,
     * in the open-air band, with the column above it cleared to the build limit (256): the panel
     * generates only when it sees the sky. One forced update is the stimulus; the world ticks the
     * panel too between placement and the read (measured 2026-10-02: the control held 14 after one
     * forced update, above the 10 a single update can add), so the control's figure is "more than
     * nothing", not a per-tick number — and the unresolved panel's 0 covers those ticks as well.</p>
     *
     * <p>Not seen: the {@code created == false} form of the same state (a station registered before
     * it is unpacked) — no probe registers an uncreated station, and both forms reach the same
     * branch; and the two other consumers of this decision, the solar array and the microwave
     * receiver.</p>
     *
     * red-witnessed: with {@code SpaceStationObject#getInsolationMultiplier} at
     * {@code return (orbiting != null) ? orbiting.getPeakInsolationMultiplierWithoutAtmosphere() : 0.0}
     * made to dereference the planet unguarded, this fails at the tick step: the world's own tick of
     * the panel threw, the shared server went down, and the force-tick exchange failed with "Server
     * bridge exchange failed" (2026-10-02).
     * red-witnessed: with {@code SpaceStationObject#getInsolationMultiplier} at
     * {@code return (orbiting != null) ? orbiting.getPeakInsolationMultiplierWithoutAtmosphere() : 0.0}
     * answering 1.0 on its unresolved branch, this fails at the made-nothing verdict with "expected:<0> but was:<10>"
     * (2026-10-02).
     * red-witnessed: with {@code SpaceStationObject#getInsolationMultiplier} at
     * {@code return (orbiting != null) ? orbiting.getPeakInsolationMultiplierWithoutAtmosphere() : 0.0}
     * answering 0.0 on its resolved branch, this fails at the
     * control verdict, the panel on the overworld's station storing 0 (2026-10-02).
     */
    @Test
    public void aSolarPanelOnAStationWithNoResolvedPlanetTicksWithoutThrowingAndMakesNothing()
            throws Exception {
        Reply loaded = Reply.of("stellurgytest dim load",
                join(client().execute("stellurgytest dim load " + SPACE_DIM)));
        ArrangementFailure.requireArranged("the space dimension must be loaded: " + loaded,
                loaded.bool("loaded"));

        int[] resolved = panelOnANewStation(0, "the control panel on a station orbiting the overworld");
        int[] unresolved = panelOnANewStation(Constants.INVALID_PLANET,
                "the panel on a station with no planet");

        String controlTick = join(client().execute("stellurgytest tile force-tick " + SPACE_DIM
                + " " + resolved[0] + " " + resolved[1] + " " + resolved[2] + " 1"));
        ArrangementFailure.requireArranged("the control panel must tick: " + controlTick,
                Reply.of("stellurgytest tile force-tick", controlTick).ok());
        int controlStored = storedIn(resolved);
        System.out.println("[station-insolation] control panel stored " + controlStored
                + " after one update");
        assertTrue("a panel on a station orbiting the overworld must make power in one update —"
                + " without that, the zero below would say nothing: stored " + controlStored,
                controlStored > 0);

        String tick = join(client().execute("stellurgytest tile force-tick " + SPACE_DIM
                + " " + unresolved[0] + " " + unresolved[1] + " " + unresolved[2] + " 1"));
        assertTrue("a panel on a station with no resolved planet must tick without throwing: "
                + tick, Reply.of("stellurgytest tile force-tick", tick).ok());
        assertEquals("a station with no resolved planet receives no sunlight, so its panel makes"
                + " nothing (the control made " + controlStored + ")", 0, storedIn(unresolved));
    }

    /**
     * Create a station orbiting {@code planet}, stand a solar panel at its spawn location, and answer
     * the panel's position.
     */
    private int[] panelOnANewStation(int planet, String what) throws Exception {
        Reply station = Reply.of("stellurgytest station create",
                join(client().execute("stellurgytest station create " + planet)));
        ArrangementFailure.requireArranged(what + " — the station must be created: " + station,
                station.ok());
        ArrangementFailure.requireArranged(what + " — the station must orbit " + planet + ": "
                + station, station.integer("orbitingBody") == planet);
        Reply info = Reply.of("stellurgytest station info",
                join(client().execute("stellurgytest station info " + station.integer("id"))));
        FixtureSite site = FixtureSite.openAir(SPACE_DIM, info.integer("spawnX"),
                info.integer("spawnZ"));
        site.requireClear(cmd -> join(client().execute(cmd)), 0, 255 - site.y, what);
        int[] panel = {site.x, site.y + 1, site.z};
        Reply placed = Reply.of("stellurgytest place", join(client().execute("stellurgytest place "
                + SPACE_DIM + " " + panel[0] + " " + panel[1] + " " + panel[2]
                + " stellurgy:solarGenerator")));
        ArrangementFailure.requireArranged(what + " — the panel must be placed: " + placed,
                placed.ok());
        return panel;
    }

    /** The energy a panel holds, through its Forge energy capability. */
    private int storedIn(int[] panel) throws Exception {
        Reply stored = Reply.of("stellurgytest energy stored", join(client().execute(
                "stellurgytest energy stored " + SPACE_DIM + " " + panel[0] + " " + panel[1] + " "
                        + panel[2])));
        ArrangementFailure.requireArranged("the panel must expose stored energy: " + stored,
                stored.bool("hasEnergy"));
        return stored.integer("energyStored");
    }

    private static String ok(java.util.List<String> resp) {
        return join(resp);
    }

    private static String join(java.util.List<String> resp) {
        return String.join("\n", resp);
    }
}
