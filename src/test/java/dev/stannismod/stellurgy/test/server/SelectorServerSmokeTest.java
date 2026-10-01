package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;

import dev.stannismod.stellurgy.test.DimList;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Server-side companion to the client GUI test — covers the
 * server state machine for {@link
 * dev.stannismod.stellurgy.tile.multiblock.TilePlanetSelector} without
 * needing an OpenGL display.
 *
 * <ol>
 *   <li>Place {@code stellurgy:planetSelector} block.</li>
 *   <li>{@code /stellurgytest selector info} reports {@code hasSelection=false} on a
 *       freshly-placed tile (no client has picked a planet yet).</li>
 *   <li>{@code /stellurgytest selector simulate-click <pos> 0} mimics the wire-side
 *       state change that {@link
 *       dev.stannismod.stellurgy.tile.multiblock.TilePlanetSelector#useNetworkData}
 *       applies when a packet arrives from a real GUI click — sets
 *       {@code dimCache} to Earth's {@code DimensionProperties}.</li>
 *   <li>{@code /stellurgytest selector info} now reports {@code hasSelection=true},
 *       {@code selectedDim=0}.</li>
 *   <li>Simulating a second click flips selection without leaking state
 *       (idempotent re-selection).</li>
 * </ol>
 *
 * <p>It also holds what the two planet selectors DISPLAY of an orbit that the server can read: the
 * selector's distance gauge, and the holographic selector's projection radius.</p>
 *
 * <p>The full client GUI path lives in {@code client/MachineGuiClientGroupE2ETest}.</p>
 */
public class SelectorServerSmokeTest extends AbstractHeadlessServerTest {

    @Test
    public void selectorTileStateMachineFollowsSimulatedClicks() throws Exception {
        // Place at a position that won't collide with other tests' fixtures.
        int x = 250, y = FixtureSite.OPEN_AIR_Y, z = 250;
        String place = String.join("\n", client().execute(
                "stellurgytest place 0 " + x + " " + y + " " + z + " stellurgy:planetSelector"));
        assertTrue("could not place planetSelector: " + place,
                Reply.of(place).bool("placed"));

        // Initial state — no selection yet.
        String empty = String.join("\n", client().execute(
                "stellurgytest selector info 0 " + x + " " + y + " " + z));
        assertTrue("selector info errored on fresh tile: " + empty,
                !Reply.of(empty).has("error"));
        assertTrue("freshly placed selector tile should report hasSelection=false: " + empty,
                (!Reply.of(empty).bool("hasSelection")));

        // Simulate a click selecting Earth (dim 0).
        String clickEarth = String.join("\n", client().execute(
                "stellurgytest selector simulate-click 0 " + x + " " + y + " " + z + " 0"));
        assertTrue("simulate-click failed: " + clickEarth,
                Reply.of(clickEarth).ok());

        // dimCache must now reflect Earth.
        String earthInfo = String.join("\n", client().execute(
                "stellurgytest selector info 0 " + x + " " + y + " " + z));
        assertTrue("selection didn't stick: " + earthInfo,
                Reply.of(earthInfo).bool("hasSelection"));
        assertTrue("selectedDim mismatch: " + earthInfo,
                (Reply.of(earthInfo).integer("selectedDim") == 0));

        // Probe non-existent planet dim — must reject without mutating state.
        String reject = String.join("\n", client().execute(
                "stellurgytest selector simulate-click 0 " + x + " " + y + " " + z + " 99999"));
        assertTrue("expected rejection for non-registered planet dim 99999: " + reject,
                "planet dim not registered".equals(Reply.of(reject).text("error")));

        String unchanged = String.join("\n", client().execute(
                "stellurgytest selector info 0 " + x + " " + y + " " + z));
        assertTrue("selection unexpectedly mutated after rejected simulate-click: " + unchanged,
                (Reply.of(unchanged).integer("selectedDim") == 0));
    }

    /**
     * The selector's distance gauge reads a planet's orbit at 6.25 hundredths of the bar per AU, so
     * Earth reads 6. That is the raw unit over 16 as the gauge drew it before the distance unit became
     * a length — then a unit was a hundredth of an AU; the change was ratified as one of
     * representation, so the reading did not move.
     *
     * <p>Two planets, so that one reading at the default orbit of one AU cannot pass for the law:
     * Earth, and one the production {@code planet generate} command derives for Sol, whose orbit this
     * test reads rather than chooses. Acceptance, stated before the code: each reads
     * {@code floor(orbitAU x 100 / 16)} exactly — one verdict over both. Read raw, Earth reads
     * 93 498. The other checks are ARRANGEMENTS and raise {@code ArrangementFailure}.</p>
     *
     * <p>What this does not see: the gauge being DRAWN — it is the server copy of the tile answering
     * the same {@code getTotalProgress} the client's bar is drawn from.</p>
     *
     * <p>red-witnessed: 2026-09-30, with {@code TilePlanetSelector#distanceGauge} at {@code return (int) (body.orbitalDist / (double) AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU} back on
     * {@code orbitalDist / 16}: "each planet must read floor(orbitAU x 100 / 16) on the distance
     * gauge: Earth at 1.0 AU → 93498; GaugeTarget at 11.998453186842863 AU → 1121839; : arrays first
     * differed at element [0]; expected:&lt;6&gt; but was:&lt;93498&gt;" — both planets wrong.</p>
     */
    @Test
    public void theDistanceGaugeReadsAnOrbitAtItsScalePerAu() throws Exception {
        int x = 262, y = FixtureSite.OPEN_AIR_Y, z = 262;
        String where = x + " " + y + " " + z;
        requireArranged("could not place planetSelector", Reply.of(String.join("\n", client().execute(
                "stellurgytest place 0 " + where + " stellurgy:planetSelector"))).bool("placed"));

        DimList.Probe probe = command -> String.join("\n", client().execute(command));
        DimList before = DimList.from(probe);
        client().execute("ar planet generate 0 GaugeTarget");
        int[] added = DimList.from(probe).addedSince(before);
        requireArranged("planet generate must register exactly one new dimension: "
                + java.util.Arrays.toString(added), added.length == 1);

        int[] planets = {0, added[0]};
        int[] expected = new int[planets.length];
        int[] read = new int[planets.length];
        StringBuilder readings = new StringBuilder();
        for (int i = 0; i < planets.length; i++) {
            Reply planet = Reply.of(probe.exec("stellurgytest planet info " + planets[i]));
            double orbitAu = planet.longInteger("orbitalDistance")
                    / (double) AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;
            expected[i] = (int) Math.floor(orbitAu * 100d / 16d);
            requireArranged("the gauge must have something to read — an orbit under 0.16 AU reads 0, "
                    + "which cannot tell the law from a gauge that reads nothing: " + planet, expected[i] >= 1);

            Reply click = Reply.of(probe.exec("stellurgytest selector simulate-click 0 " + where + " " + planets[i]));
            requireArranged("simulate-click " + planets[i] + " failed: " + click, click.ok());
            Reply info = Reply.of(probe.exec("stellurgytest selector info 0 " + where));
            requireArranged("the selection must be the planet just clicked: " + info,
                    info.integer("selectedDim") == planets[i]);
            read[i] = info.integer("distanceGauge");
            readings.append(planet.text("name")).append(" at ").append(orbitAu).append(" AU → ")
                    .append(read[i]).append("; ");
        }
        assertArrayEquals("each planet must read floor(orbitAU x 100 / 16) on the distance gauge: "
                + readings, expected, read);
    }

    /**
     * The holographic selector projects each planet of Sol at {@code H x (0.1 + orbitAU)} blocks from
     * its centre, {@code H} being the hologram's size: one hologram block per AU. That is
     * {@code orbitalDist / 100} as it projected before the distance unit became a length — then a
     * unit was a hundredth of an AU — and the change was ratified as one of representation.
     *
     * <p>A freshly placed projector has {@code size = 0.02}, so {@code H = size x 10 + 0.8 = 1}, and
     * its fade-in ({@code onTime}) climbs 0.2 per tick to 1 (production {@code :107-108}); twenty
     * ticks cover the rebuild tick, the one-tick position delay and the five climbing steps with room.
     * Acceptance: every planet of Sol stands {@code 0.1 + orbitAU} blocks out — Earth at 1.1 — within
     * the sine table's step at the farthest radius, one projected planet per planet of Sol (one verdict
     * over the sorted radii). Read raw, Earth stands 14 960 blocks out. The slack was first 0.05 and
     * was tightened to the derived one on 2026-09-30, after the first greens.</p>
     *
     * <p>red-witnessed: 2026-09-30 (re-taken the same day when the radius moved into its own method),
     * with {@code TileHolographicPlanetSelector#projectionRadius} at
     * {@code return PROJECTION_INNER_RADIUS + body.orbitalDist / (double) AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU}
     * back on {@code body.orbitalDist / 100d}: "each planet of Sol must be projected 0.1 + its orbit in
     * AU out, slack 1.4914462988202526E-4 (expected radii [1.1], projected [14959.890000000001]): …
     * arrays first differed at element [0]; expected:&lt;1.1&gt; but was:&lt;14959.890000000001&gt;".</p>
     */
    @Test
    public void theHologramProjectsAPlanetOneHologramBlockPerAu() throws Exception {
        int x = 300, y = FixtureSite.OPEN_AIR_Y, z = 300;
        requireArranged("could not place the holographic planet selector", Reply.of(String.join("\n",
                client().execute("stellurgytest place 0 " + x + " " + y + " " + z + " stellurgy:planetHoloSelector")))
                .bool("placed"));

        DimList.Probe probe = command -> String.join("\n", client().execute(command));
        // What a PLANET reports as its parent — Earth is one — so a moon, which reports its planet,
        // is told apart; the projector draws the planets of its star, not their moons.
        int planetParent = Reply.of(probe.exec("stellurgytest planet info 0")).integer("parent");
        java.util.List<Double> expectedRadii = new java.util.ArrayList<>();
        boolean earthCounted = false;
        for (int dim : DimList.from(probe).registered()) {
            Reply planet = Reply.of(probe.exec("stellurgytest planet info " + dim));
            int starId = planet.integer("starId");
            int parent = planet.integer("parent");
            if (starId == 0 && parent == planetParent) {
                expectedRadii.add(0.1d + planet.longInteger("orbitalDistance")
                        / (double) AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU);
                earthCounted |= dim == 0;
            }
        }
        requireArranged("Earth must be among the planets of Sol this reads: " + expectedRadii, earthCounted);
        java.util.Collections.sort(expectedRadii);
        double farthest = expectedRadii.get(expectedRadii.size() - 1);

        Reply tick = Reply.of(probe.exec("stellurgytest tile force-tick 0 " + x + " " + y + " " + z + " 20"));
        requireArranged("the projector must tick: " + tick, tick.ok());

        // Searched far enough to FIND a planet the raw unit misplaces — twice the farthest planet's
        // radius times the factor a raw read is off by — so a misplaced planet is reported with its
        // radius rather than as an absence.
        double rawFactor = AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU / 100d;
        double reach = 2d * farthest * rawFactor;
        double centreX = x + 0.5, centreZ = z + 0.5;
        String near = probe.exec("stellurgytest entity near 0 " + centreX + " " + (y + 1) + " " + centreZ
                + " " + reach + " EntityUIPlanet");
        String[] projected = Reply.of(near).objectArray("entities");
        double[] radii = new double[projected.length];
        for (int i = 0; i < projected.length; i++) {
            Reply e = Reply.of(projected[i]);
            radii[i] = Math.hypot(e.number("x") - centreX, e.number("z") - centreZ);
        }
        java.util.Arrays.sort(radii);
        double[] want = new double[expectedRadii.size()];
        for (int i = 0; i < want.length; i++) {
            want[i] = expectedRadii.get(i);
        }
        // The slack is MathHelper's sine table: its index truncates to one of 65 536 steps, so each
        // coordinate of a projected planet can be off by its radius times one step.
        double tolerance = farthest * Math.sqrt(2d) * (2d * Math.PI / 65536d);
        assertArrayEquals("each planet of Sol must be projected 0.1 + its orbit in AU out, slack " + tolerance
                + " (expected radii " + java.util.Arrays.toString(want) + ", projected "
                + java.util.Arrays.toString(radii) + "): " + near, want, radii, tolerance);
    }

    /**
     * The selector's distance gauge reads a MOON's distance from its planet as it read one while
     * Luna stood at 150: {@code floor(moonViewUnits / 16)}, the moon-view distance being 150 for a
     * moon at Luna's distance — so Luna reads 9.
     *
     * <p>Acceptance, stated before the code: every moon of Earth the server holds reads
     * {@code floor(150 x orbit / 3 844 / 16)} — 9 for Luna at her real 3 844 units — one verdict
     * over all of them. Through the planet law a moon reads 0; read raw, Luna reads 240. The other
     * checks are ARRANGEMENTS.</p>
     *
     * <p>What this does not see: the gauge being DRAWN, as for the planet gauge above; and a moon at
     * any distance but the one the server's moons stand at — the proportion is pinned in
     * {@code AstronomicalBodyHelperTest}.</p>
     *
     * <p>red-witnessed: 2026-09-30, with {@code TilePlanetSelector#distanceGauge} at
     * {@code if (body.isMoon())} made false (a moon read through the planet law), this fails with
     * "each moon of Earth must read floor(moonViewUnits / 16) on the distance gauge: Luna at 3844
     * units → 0; : arrays first differed at element [0]; expected:&lt;9&gt; but was:&lt;0&gt;".</p>
     */
    @Test
    public void theDistanceGaugeReadsAMoonAsItReadLunaAt150() throws Exception {
        int x = 274, y = FixtureSite.OPEN_AIR_Y, z = 274;
        String where = x + " " + y + " " + z;
        requireArranged("could not place planetSelector", Reply.of(String.join("\n", client().execute(
                "stellurgytest place 0 " + where + " stellurgy:planetSelector"))).bool("placed"));

        DimList.Probe probe = command -> String.join("\n", client().execute(command));
        java.util.List<Integer> moons = moonsOfEarth(probe);
        requireArranged("Earth must have a moon for a moon's gauge to be read", !moons.isEmpty());

        int[] expected = new int[moons.size()];
        int[] read = new int[moons.size()];
        StringBuilder readings = new StringBuilder();
        for (int i = 0; i < moons.size(); i++) {
            int moon = moons.get(i);
            Reply body = Reply.of(probe.exec("stellurgytest planet info " + moon));
            long orbit = body.longInteger("orbitalDistance");
            // 150 and 16: Luna's view distance and the gauge's divisor, as they stood before Luna's
            // distance was corrected — the contract, not production's constants read back.
            expected[i] = (int) Math.floor(150d * orbit / AstronomicalBodyHelper.MOON_REFERENCE_UNITS / 16d);
            requireArranged("the gauge must have something to read — a moon reading 0 cannot tell the law "
                    + "from the planet law: " + body, expected[i] >= 1);

            Reply click = Reply.of(probe.exec("stellurgytest selector simulate-click 0 " + where + " " + moon));
            requireArranged("simulate-click " + moon + " failed: " + click, click.ok());
            Reply info = Reply.of(probe.exec("stellurgytest selector info 0 " + where));
            requireArranged("the selection must be the moon just clicked: " + info,
                    info.integer("selectedDim") == moon);
            read[i] = info.integer("distanceGauge");
            readings.append(body.text("name")).append(" at ").append(orbit).append(" units → ")
                    .append(read[i]).append("; ");
        }
        assertArrayEquals("each moon of Earth must read floor(moonViewUnits / 16) on the distance gauge: "
                + readings, expected, read);
    }

    /**
     * A holographic selector centred on a planet projects each of its moons as it projected one
     * while Luna stood at 150: {@code H x (0.1 + moonViewUnits / 100)} blocks from the centre, so
     * Luna 1.6 blocks out at {@code H = 1}. Through the planet law a moon stood at 0.1 — on its
     * planet's edge.
     *
     * <p>The hologram centres only aboard a station ({@code selectSystem} resolves the station from
     * the projector's coordinates), and only on a player's right-click of a projected planet — once
     * to select it, once to centre it. The test creates a station, stands the projector on the
     * station's own coordinates, and ARRANGES the two right-clicks with a fake player; the act under
     * test is the placement that follows. The projected bodies after centring are the entities the
     * rebuild spawned — every entity the star view had is killed by it — so they are told apart by
     * id, not by timing.</p>
     *
     * <p>Acceptance, stated before the code: the centred view's projected bodies stand at the sorted
     * radii {@code [0 (Earth), 0.1 + 150 x orbit / 3 844 / 100 for each moon]} — {@code [0, 1.6]}
     * with Luna alone — within the sine table's step at the farthest radius, one verdict. Through the
     * planet law Luna stands at 0.1026.</p>
     *
     * <p>red-witnessed: 2026-09-30, with {@code TileHolographicPlanetSelector#projectionRadius} at
     * {@code if (body.isMoon())} made false (a moon projected through the planet law), this fails
     * with "a planet centred in the hologram must carry its moons 0.1 + moonViewUnits / 100 out,
     * slack 2.16937643464764E-4 (expected [0.0, 1.6], projected [0.0, 0.10256955525784028]): …
     * arrays first differed at element [1]; expected:&lt;1.6&gt; but was:&lt;0.10256955525784028&gt;".</p>
     */
    @Test
    public void theHologramCentredOnAPlanetProjectsItsMoonAsItProjectedLunaAt150() throws Exception {
        DimList.Probe probe = command -> String.join("\n", client().execute(command));
        Reply station = Reply.of(probe.exec("stellurgytest station create 0"));
        requireArranged("a station about Earth must be created: " + station, station.ok());
        Reply stationInfo = Reply.of(probe.exec("stellurgytest station info " + station.integer("id")));
        int x = stationInfo.integer("spawnX"), y = FixtureSite.OPEN_AIR_Y, z = stationInfo.integer("spawnZ");
        String where = x + " " + y + " " + z;
        Reply owner = Reply.of(probe.exec("stellurgytest station at " + where));
        // `station at` writes stationAtPos as null when no station owns the coordinates:
        // absence is the answer "no station", read as -1, which this arrangement refuses.
        int ownerId = owner.integerOr("stationAtPos", -1);
        requireArranged("the projector's coordinates must resolve to the station: " + owner,
                ownerId == station.integer("id"));
        requireArranged("could not place the holographic planet selector", Reply.of(
                probe.exec("stellurgytest place 0 " + where + " stellurgy:planetHoloSelector")).bool("placed"));

        java.util.List<Integer> moons = moonsOfEarth(probe);
        requireArranged("Earth must have a moon for the centred view to carry one", !moons.isEmpty());
        java.util.List<Double> expectedRadii = new java.util.ArrayList<>();
        expectedRadii.add(0d);
        for (int moon : moons) {
            long orbit = Reply.of(probe.exec("stellurgytest planet info " + moon)).longInteger("orbitalDistance");
            // 0.1, 150 and 100: the inner radius, Luna's view distance and the view units per
            // hologram block, as they stood before Luna's distance was corrected — the contract.
            expectedRadii.add(0.1d + 150d * orbit / AstronomicalBodyHelper.MOON_REFERENCE_UNITS / 100d);
        }
        java.util.Collections.sort(expectedRadii);
        double farthest = expectedRadii.get(expectedRadii.size() - 1);
        double centreX = x + 0.5, centreZ = z + 0.5;
        // Far enough to FIND a moon read raw (3 844 / 100 blocks for Luna) rather than miss it.
        double reach = 2d * farthest * AstronomicalBodyHelper.MOON_REFERENCE_UNITS / 150d;
        String projected = "stellurgytest entity near 0 " + centreX + " " + (y + 1) + " " + centreZ
                + " " + reach + " EntityUIPlanet";

        Reply tick = Reply.of(probe.exec("stellurgytest tile force-tick 0 " + where + " 20"));
        requireArranged("the projector must tick: " + tick, tick.ok());
        // Earth is the projected planet standing 0.1 + its orbit in AU out — the star view's own law,
        // pinned above; another planet of Sol (a generated one) stands elsewhere.
        double earthRadius = 0.1d + Reply.of(probe.exec("stellurgytest planet info 0")).longInteger("orbitalDistance")
                / (double) AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;
        String starView = probe.exec(projected);
        java.util.List<Integer> earthEntities = new java.util.ArrayList<>();
        java.util.Set<Integer> starViewIds = new java.util.HashSet<>();
        for (String entity : Reply.of(starView).objectArray("entities")) {
            Reply e = Reply.of(entity);
            starViewIds.add(e.integer("id"));
            // 1e-3: above the sine table's step at Earth's radius (1.1 x sqrt(2) x 2 pi / 65 536 =
            // 1.5e-4) and far below the gap to any other body a star view projects. `entity near`:
            // the producer always writes x and z for every entity it lists.
            if (Math.abs(Math.hypot(e.number("x") - centreX, e.number("z") - centreZ) - earthRadius) < 1e-3) {
                earthEntities.add(e.integer("id"));
            }
        }
        requireArranged("the star view must project Earth, once, " + earthRadius + " out: " + starView,
                earthEntities.size() == 1);
        int planetEntity = earthEntities.get(0);
        for (int click = 0; click < 2; click++) {
            Reply interact = Reply.of(probe.exec("stellurgytest entity interact 0 " + planetEntity));
            requireArranged("right-clicking the projected planet must reach it: " + interact, interact.ok());
        }
        tick = Reply.of(probe.exec("stellurgytest tile force-tick 0 " + where + " 20"));
        requireArranged("the projector must tick after centring: " + tick, tick.ok());

        String centred = probe.exec(projected);
        java.util.List<Double> radii = new java.util.ArrayList<>();
        for (String entity : Reply.of(centred).objectArray("entities")) {
            Reply e = Reply.of(entity);
            // `entity near`: the producer always writes id for every entity it lists.
            if (!starViewIds.contains(e.integer("id"))) {
                radii.add(Math.hypot(e.number("x") - centreX, e.number("z") - centreZ));
            }
        }
        java.util.Collections.sort(radii);
        // The slack is MathHelper's sine table: its index truncates to one of 65 536 steps, so each
        // coordinate of a projected body can be off by its radius times one step.
        double tolerance = farthest * Math.sqrt(2d) * (2d * Math.PI / 65536d);
        assertArrayEquals("a planet centred in the hologram must carry its moons 0.1 + moonViewUnits / 100 out,"
                        + " slack " + tolerance + " (expected " + expectedRadii + ", projected " + radii + "): "
                        + centred,
                expectedRadii.stream().mapToDouble(Double::doubleValue).toArray(),
                radii.stream().mapToDouble(Double::doubleValue).toArray(), tolerance);
    }

    /** Earth's moons, as the server registers them: every dimension whose parent is Earth. */
    private static java.util.List<Integer> moonsOfEarth(DimList.Probe probe) throws Exception {
        java.util.List<Integer> moons = new java.util.ArrayList<>();
        for (int dim : DimList.from(probe).registered()) {
            // `planet info` on a registered dimension: the producer always writes parent.
            if (Reply.of(probe.exec("stellurgytest planet info " + dim)).integer("parent") == 0) {
                moons.add(dim);
            }
        }
        return moons;
    }

    @Test
    public void selectorInfoOnEmptyPositionErrorsCleanly() throws Exception {
        String resp = String.join("\n", client().execute("stellurgytest selector info 0 100 80 100"));
        assertTrue("expected 'tile not TilePlanetSelector' on empty pos: " + resp,
                "tile not TilePlanetSelector".equals(Reply.of(resp).text("error")));
    }
}
