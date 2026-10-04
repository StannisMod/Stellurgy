package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.GameTicks;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.DimList;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.NavStatus;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.TelescopeReading;
import dev.stannismod.stellurgy.universe.GalaxyGenConfig;
import dev.stannismod.stellurgy.universe.Nebula;
import dev.stannismod.stellurgy.universe.StellarMagnitude;
import dev.stannismod.stellurgy.universe.UniverseScale;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The observatory's region survey, driven on a real server through the machine itself.
 *
 * <p>The unit tier pins the arithmetic that reads no mod state — the cone's geometry, the reach a look
 * budget leaves, and the photometry LAW a star's brightness is computed by. This pins the MACHINE: that
 * an observatory can be aimed at a region, that it sweeps through it on the world's own clock when
 * research is on and resolves it outright when research is off, that stopping and re-aiming cost
 * nothing but the cell in flight, and that what it resolved is written onto the crystal sitting in the
 * machine — and, in the scenarios at the end, what the instrument makes of a star with the SHIPPED
 * aperture, what dust costs a look, and what a look teaches the world it is made from. Those read the
 * running configuration and the galaxy, which is why they are here and not in the unit tier.</p>
 *
 * <p>Every number here is the SERVER's answer; the probes state their own side.</p>
 *
 * <p>Position-isolated at x=4300-4660 (clear of the observatory-multiblock fixtures at x=4000-4060).</p>
 */
public class TelescopeRegionScanServerTest extends AbstractSharedServerTest {

    /** How far a telescope's horizon must reach, in light years, for other stars to be within it.
     *  The TEST'S OWN bar on "interstellar". */
    private static final double INTERSTELLAR_LY = 4d;

    /** And how many lattice steps that buys — more than one star's own territory. */
    private static final int MORE_THAN_ONE_TERRITORY_STEPS = 2;

    /**
     * How much WORLD a starved survey is watched across before it is inspected - the old 2 000 ms,
     * said in the ticks the survey actually advances on.
     */
    private static final int STARVED_SURVEY_TICKS = 40;

    /** World a whole survey is given to finish in - the old 40 x 250 ms and 60 x 250 ms. */
    private static final int SURVEY_TICKS = 200;
    private static final int SWEEP_TICKS = 300;

    private static final int CY = FixtureSite.OPEN_AIR_Y;
    private static final int CZ = 4300;

    /** This class's reader of the server's ordered event log. */
    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    private static String join(java.util.List<String> response) {
        return String.join("\n", response);
    }

    /**
     * A small, fast survey. {@code research} chooses which half of the design is under test: off is
     * the default game, where what the instrument reaches is resolved outright; on is the research
     * mode, where the sweep is paced and the time curve is the mechanic.
     */
    private void surveySetup(boolean research, int cellsPerStep, int ticksPerStep)
            throws Exception {
        exec("stellurgytest config set planetsMustBeDiscovered " + research);
        exec("stellurgytest config set telescopeScanBaseTicks " + ticksPerStep);
        // An aperture that sees essentially anything, so what a fixture finds is decided by where it
        // put the fixture and never by how bright the sky happened to draw it. What the SHIPPED
        // aperture sees is the subject of the photometry scenarios at the end of this class, which
        // seat stars of a stated luminosity and state their own aperture.
        exec("stellurgytest config set telescopeLimitingMagnitude 30");
        exec("stellurgytest config set telescopeConeHalfAngleDegrees 20");
        exec("stellurgytest config set telescopeScanMaxCells 1000");
        exec("stellurgytest config set telescopeScanCellsPerStep " + cellsPerStep);
        exec("stellurgytest config set telescopePassiveRadiusSteps 1");
    }

    /** What the instrument is doing right now. */
    private TelescopeReading scope(int x) throws Exception {
        return TelescopeReading.at(this::exec, where(x));
    }

    /** How far apart, in cells, the looks of a directed survey stand in THIS server's universe. */
    private long stride(int x) throws Exception {
        long stride = TelescopeReading.of(exec("stellurgytest telescope scan " + where(x) + " 1 0 0 1"))
                .requireOk("could not aim the instrument to read its stride")
                .stride();
        exec("stellurgytest telescope abort " + where(x));
        return stride;
    }

    private String where(int x) {
        return "0 " + x + " " + CY + " " + CZ;
    }

    /** An observatory with a blank crystal in it, and the cell it stands in. */
    private long[] observatoryWithCrystal(int x) throws Exception {
        String placed = exec("stellurgytest telescope place " + where(x));
        assertTrue("could not place an observatory: " + placed, Reply.of(placed).ok());
        String crystal = exec("stellurgytest telescope crystal " + where(x));
        assertEquals("the crystal must start blank, or every count afterwards means nothing",
                0, Reply.of("stellurgytest telescope crystal", crystal).integer("addresses"));
        return scope(x).originSectors();
    }

    /** Put a system with a planet in it at a cell, so what the instrument finds is determinate. */
    private void systemAt(long sx, long sy, long sz) throws Exception {
        String system = exec("stellurgytest telescope system " + sx + " " + sy + " " + sz);
        assertTrue("could not place a system to be found: " + system, Reply.of(system).ok());
    }

    /**
     * Seat a system {@code steps} territories out along +X, deliberately OFF the cell the survey
     * looks at.
     *
     * <p>The offset is the point of the fixture: a star is one cell of a territory millions of cells
     * wide, so a survey that could see a system only by landing on its star's own address finds
     * nothing. What must be found is the system that OWNS the cell that was looked at.</p>
     */
    private void systemNearTheLookAt(int x, long[] home, int steps) throws Exception {
        systemAt(home[0] + steps * stride(x) + 13L, home[1], home[2]);
    }

    /**
     * Wait for the machine's survey to finish, on the record the machine writes when it does.
     *
     * <p>{@code region_scan_advanced} is written from {@code completeRegionScanIfDue}'s RETURN
     * whenever the scan or its discovery count changed, and carries {@code complete} — true on the
     * step that cleared the active scan. So the wait ends on the machine SAYING it finished, not on
     * a reading taken from outside that happened to catch {@code scanning} false. The mark is the
     * caller's, taken before the survey is started.</p>
     */
    private TelescopeReading awaitSurveyComplete(long mark, int x) throws Exception {
        events.awaitRecordWithFields(mark, "region_scan_advanced",
                "the survey must finish", SURVEY_TICKS,
                "pos", posKey(x), "complete", "true");
        return scope(x);
    }

    /**
     * How this observatory names itself in a record — the same {@code x,y,z} the mixin writes.
     *
     * <p>Derived from the coordinates the test PLACED it at rather than read back, so a record
     * naming a different machine cannot satisfy a wait meant for this one. {@link #where} is the
     * same triple with its dimension in front.</p>
     */
    private String posKey(int x) {
        return x + "," + CY + "," + CZ;
    }

    @Test
    public void withoutResearchWhatTheInstrumentReachesIsResolvedOutright() throws Exception {
        final int x = 4300;
        surveySetup(false, 2, 120);
        long[] home = observatoryWithCrystal(x);
        systemNearTheLookAt(x, home, 4);

        long scanMark = events.mark();
        TelescopeReading.of(exec("stellurgytest telescope scan " + where(x) + " 1 0 0 4"))
                .requireOk("the survey did not start");

        TelescopeReading done = awaitSurveyComplete(scanMark, x);
        assertTrue("the crystal learned nothing from a region holding a system: " + done.raw(),
                done.addressesOnCrystal() >= 1);
        assertTrue("and the machine must report what it discovered: " + done.raw(),
                done.lastDiscoveries >= 1);
    }

    @Test
    public void withResearchTheSurveySweepsCellByCell() throws Exception {
        final int x = 4340;
        // One cell a step — the claim under test — and a step short enough that 27 of them fit in
        // the poll budget: at 20 ticks per sector of distance a single cell took 4 s, so the whole
        // region wanted 108 s against a 10 s budget and the sweep was blamed for the arithmetic.
        // Priced flat per STEP now: a pointing's cost in time is carried by how many steps it
        // needs, because a deeper one already holds proportionally more looks.
        surveySetup(true, 1, 3);
        observatoryWithCrystal(x);

        TelescopeReading started = TelescopeReading.of(exec("stellurgytest telescope scan " + where(x) + " 1 0 0 4"))
                .requireOk("the survey did not start");
        assertTrue("a region worth sweeping must hold more than one cell: " + started.raw(),
                started.cells() > 1);
        assertEquals("a fresh survey has resolved nothing yet", 0, started.cellsDone());

        long total = started.cells();
        long sweepMark = events.mark();
        awaitSurveyComplete(sweepMark, x);

        // THE MONOTONICITY IS READ OFF EVERY STEP, not off the samples a poll happened to take.
        // The machine writes `region_scan_advanced` each time its scan or discovery count moves, so
        // the window below holds the whole sweep; the loop this replaces compared consecutive READS
        // and could step over a backwards move between two of them entirely.
        java.util.List<String> steps = events.recordsWhere(events.since(sweepMark,
                "region_scan_advanced"), "pos", posKey(x));
        assertTrue("the sweep produced no progress records at all, so nothing below is a reading of"
                + " it", !steps.isEmpty());
        long previous = 0L;
        long furthest = 0L;
        for (String step : steps) {
            long done = (long) Events.number(step, "cellsDone");
            assertTrue("a sweep must never go backwards: " + done + " after " + previous
                    + " in " + step, done >= previous);
            previous = done;
            furthest = Math.max(furthest, done);
        }
        assertTrue("the sweep never advanced through its region (" + furthest + "/" + total
                + ") across " + steps.size() + " recorded steps", furthest >= total);
        // AND IT WALKED, rather than resolving the whole region in one pass — which is the sentence
        // this test is named for and the one the count above cannot make: a survey that resolved all
        // twelve cells at once would reach the same total, in a single record.
        assertTrue("the survey resolved its region in one pass instead of sweeping it: "
                + steps.size() + " progress record(s) for " + total + " cells", steps.size() > 1);
    }

    @Test
    public void stoppingASurveyIsFreeAndKeepsWhatWasAlreadyLearned() throws Exception {
        final int x = 4380;
        surveySetup(true, 1, 180);
        long[] home = observatoryWithCrystal(x);
        systemNearTheLookAt(x, home, 3);

        exec("stellurgytest telescope scan " + where(x) + " 1 0 0 3");
        TelescopeReading before = scope(x);
        assertTrue("the survey must be running before it can be stopped: " + before.raw(),
                before.scanning);
        long learned = before.addressesOnCrystal();

        TelescopeReading stopped = TelescopeReading.of(exec("stellurgytest telescope abort " + where(x)))
                .requireOk("stopping must be free and immediate");
        assertFalse("the instrument must be idle after a stop: " + stopped.raw(), stopped.scanning);
        assertTrue("stopping must not take back what was already resolved: " + stopped.raw(),
                stopped.addressesOnCrystal() >= learned);
    }

    @Test
    public void aimingAgainMovesTheRegionWithoutLosingWhatWasLearned() throws Exception {
        final int x = 4420;
        surveySetup(true, 1, 180);
        observatoryWithCrystal(x);

        TelescopeReading first = TelescopeReading.of(exec("stellurgytest telescope scan " + where(x) + " 1 0 0 3"))
                .requireOk("the first survey did not start");
        long learned = first.addressesOnCrystal();

        TelescopeReading second = TelescopeReading.of(exec("stellurgytest telescope scan " + where(x) + " 0 0 1 5"))
                .requireOk("re-aiming mid-survey must be allowed");
        // The DIRECTION and not the corners. A pointing's bounding box is its apex plus its reach
        // on every axis, so re-aiming the same instrument leaves min/max exactly where they were —
        // the aim is where it is looking, which is a vector.
        assertNotEquals("re-aiming must actually move the pointing",
                first.direction(), second.direction());
        assertTrue("and must keep every address already written: " + second.raw(),
                second.addressesOnCrystal() >= learned);
    }

    @Test
    public void theLocalRadarSurveysTheObservatorysOwnNeighbourhood() throws Exception {
        final int x = 4460;
        surveySetup(false, 4, 120);
        long[] home = observatoryWithCrystal(x);

        long radarMark = events.mark();
        TelescopeReading passive = TelescopeReading.of(exec("stellurgytest telescope passive " + where(x)))
                .requireOk("the local radar did not start");
        assertTrue("the local radar is a mode of the machine, not a survey of somewhere else: "
                + passive.raw(), passive.passive);

        // Its region must contain the cell the observatory itself stands in.
        long homeX = home[0];
        long lo = passive.regionMinSector(0);
        long hi = passive.regionMaxSector(0);
        assertTrue("the radar must look around home (" + homeX + "), not at " + passive.regionMin()
                        + ".." + passive.regionMax(),
                lo <= homeX && homeX <= hi);
        assertEquals("and it must walk TERRITORIES: one look already yields every body of the system "
                        + "that owns it, so a neighbourhood is measured in NEIGHBOURS",
                passive.stepCells, passive.stride());

        // An observatory stands on a PLANET, never on its own star. Under the gate this test was
        // written against, the cell it is standing in reported empty and the machine could not name
        // the system it was sitting in.
        TelescopeReading done = awaitSurveyComplete(radarMark, x);
        assertTrue("the radar must resolve the system the observatory is standing in: " + done.raw(),
                done.addressesOnCrystal() >= 1);
    }

    /**
     * <p>red-witnessed: with the survey written into NEITHER of the two places a save takes it from —
     * {@code TileObservatory#writeToNBT} at {@code nbt.setTag("regionScan", scan)} and
     * {@code TileObservatory#writeNetworkData} at {@code nbt.setTag("regionScan", scan)},
     * which {@code TileMultiBlock.writeToNBT} also calls: "the survey did not come back with the
     * chunk: … scanning:false", 2026-09-28. Dropping only the {@code writeToNBT} copy stays GREEN —
     * the network copy lands in the same save compound.</p>
     */
    @Test
    public void aSurveyInFlightSurvivesItsChunkBeingUnloaded() throws Exception {
        final int x = 4540;
        // Research on, one cell a step, a step long enough that the sweep is certainly mid-region
        // when the chunk goes away.
        surveySetup(true, 1, 30);
        observatoryWithCrystal(x);

        long scanMark = events.mark();
        TelescopeReading.of(exec("stellurgytest telescope scan " + where(x) + " 1 0 0 3"))
                .requireOk("the survey did not start");
        // Linked on the survey's first resolved step that did not finish it: that is "mid-region"
        // said by the machine, and it makes the "kept what it had surveyed" claim below one about a
        // count of at least one. The fixed pause this replaces was one step long, so it could just
        // as well interrupt a survey that had resolved nothing and prove nothing about keeping it.
        events.awaitRecordWithFields(scanMark, "region_scan_advanced",
                "the survey must resolve a first step before it can be interrupted mid-region",
                SURVEY_TICKS, "pos", posKey(x), "complete", "false");
        TelescopeReading before = scope(x);
        assertTrue("the survey must still be running to be interrupted: " + before.raw(),
                before.scanning);
        long doneBefore = before.cellsDone();
        String region = before.regionMin();

        String cycled = exec("stellurgytest chunk cycle 0 " + (x >> 4) + " " + (CZ >> 4));
        assertTrue("the chunk was never actually dropped, so nothing was proven: " + cycled,
                Reply.of(cycled).bool("dropped") && Reply.of(cycled).bool("reloaded"));

        TelescopeReading after = scope(x);
        assertTrue("the survey did not come back with the chunk: " + after.raw(), after.scanning);
        assertEquals("it must come back looking at the same region", region, after.regionMin());
        assertTrue("and must not have forgotten the cells it had already surveyed: was "
                        + doneBefore + ", now " + after.cellsDone(),
                after.cellsDone() >= doneBefore);
    }

    @Test
    public void anUnfedInstrumentStallsInsteadOfSurveyingForFree() throws Exception {
        final int x = 4580;
        surveySetup(true, 1, 3);
        // A price no bare observatory can pay: it has no data buses, so it has no distance data.
        exec("stellurgytest config set telescopeSurveyDataPerStep 50");
        try {
            observatoryWithCrystal(x);
            TelescopeReading started = TelescopeReading.of(exec("stellurgytest telescope scan " + where(x)
                    + " 1 0 0 2")).requireOk("the survey did not start");
            assertEquals("a fresh survey has resolved nothing yet: " + started.raw(),
                    0, started.cellsDone());

            // WINDOW: the survey's progress is read as it starts and again after this stretch, and
            // the claim is that it did not move. The stretch is over a dozen steps' worth at this
            // pacing, which a fed instrument spends resolving cells; overshoot only gives a starved
            // one longer to cheat, which can turn a green red and never the reverse.
            GameTicks.advance(client(), GameTicks.server(), STARVED_SURVEY_TICKS);
            TelescopeReading after = scope(x);
            assertTrue("an instrument with no data must still be waiting, not finished: "
                    + after.raw(), after.scanning);
            assertEquals("and must not have resolved a single cell on credit (cellsDone "
                            + started.cellsDone() + " -> " + after.cellsDone() + ")",
                    started.cellsDone(), after.cellsDone());
        } finally {
            exec("stellurgytest config set telescopeSurveyDataPerStep 0");
        }
    }

    @Test
    public void whatTheTelescopeWroteIsWhatAShipCanBeAimedBy() throws Exception {
        final int x = 4620;
        surveySetup(false, 4, 3);
        long[] home = observatoryWithCrystal(x);
        systemNearTheLookAt(x, home, 3);

        long handoverMark = events.mark();
        exec("stellurgytest telescope scan " + where(x) + " 1 0 0 3");
        TelescopeReading surveyed = awaitSurveyComplete(handoverMark, x);
        assertTrue("the survey must have written something to hand over: " + surveyed.raw(),
                surveyed.addressesOnCrystal() >= 1);

        // Carry the crystal to a navigation computer, the way a player would.
        int navX = x + 4;
        String placed = exec("stellurgytest nav place 0 " + navX + " " + CY + " " + CZ);
        assertTrue("could not place a navigation computer: " + placed, Reply.of(placed).ok());
        String handed = exec("stellurgytest telescope handover " + where(x) + " " + navX + " " + CY + " " + CZ);
        assertTrue("the crystal did not reach the console: " + handed, Reply.of(handed).ok());
        // `handover` answers the stack it MOVED, not the instrument's state, so it is read as its
        // own two-field reply rather than as a telescope reading.
        assertTrue("and it must arrive holding what the telescope wrote: " + handed,
                Reply.of("stellurgytest telescope handover", handed).integer("addresses") >= 1);

        NavStatus status = NavStatus.at(this::exec, 0, navX, CY, CZ);
        assertTrue("the console must read the telescope's own crystal: " + status.raw(),
                status.shipCrystals >= 1);
    }

    @Test
    public void theHorizonIsALengthAnInstrumentCouldActuallyHave() throws Exception {
        final int x = 4660;
        // The half of the defect that no amount of resolving would have fixed: the reach was stated
        // in cells, so 24 of them was 0.16 AU — a fifth of the way to Mercury — and every aim inside
        // the horizon stayed inside the solar system. A horizon is a LENGTH.
        surveySetup(false, 4, 3);
        observatoryWithCrystal(x);

        TelescopeReading idle = scope(x);
        assertTrue("a telescope's horizon must reach other stars, in light years: " + idle.reachLy,
                idle.reachLy >= INTERSTELLAR_LY);
        assertTrue("and must buy more than one star's territory: " + idle.reachSteps,
                idle.reachSteps >= MORE_THAN_ONE_TERRITORY_STEPS);

        TelescopeReading aimed = TelescopeReading.of(
                        exec("stellurgytest telescope scan " + where(x) + " 1 0 0 " + idle.reachSteps))
                .requireOk("the survey did not start");
        assertTrue("an aim at the horizon must land an interstellar distance away: "
                        + aimed.distanceLy() + " ly",
                aimed.distanceLy() >= INTERSTELLAR_LY);
        exec("stellurgytest telescope abort " + where(x));
    }

    @Test
    public void aFartherRegionIsALongerSurveyOnTheRealClock() throws Exception {
        final int x = 4500;
        surveySetup(true, 2, 40);
        observatoryWithCrystal(x);

        long nearTicks = TelescopeReading.of(exec("stellurgytest telescope scan " + where(x) + " 1 0 0 2"))
                .requireOk("the near survey did not start")
                .estimatedTicks();
        exec("stellurgytest telescope abort " + where(x));

        long farTicks = TelescopeReading.of(exec("stellurgytest telescope scan " + where(x) + " 1 0 0 20"))
                .requireOk("the far survey did not start")
                .estimatedTicks();
        exec("stellurgytest telescope abort " + where(x));

        assertTrue("a farther region must be a longer survey: near=" + nearTicks + " far=" + farTicks,
                farTicks > nearTicks);
    }

    // ── what the SHIPPED instrument sees, what dust costs it, and what a look teaches ─────────────
    //
    // The scenarios below state every telescope setting they rest on, at its shipped value unless the
    // scenario's own subject moves it, because the methods above leave their own aperture behind
    // (a limit of 30) and JUnit promises no order. Each stands on its own plot.

    /** The record the machine writes when a survey step resolves; {@code complete} on the last. */
    private static final String SURVEY_ADVANCED = "region_scan_advanced";

    /**
     * Ticks a survey is given to say it finished. With the research switch off the whole pointing is
     * resolved on the tile's first server tick after the aim ({@code TileObservatory}'s completion
     * pass, its instant branch), so this is a ceiling against a machine that never ticks and not a
     * pace; measured 2026-10-02: 2 to 5 ticks from the aim to the completing record, over five
     * surveys of up to 95 territories.
     */
    private static final int INSTANT_SURVEY_TICKS = 100;

    /**
     * How many worlds a seated system asks the procedural sky to derive. Any one is enough to tell a
     * body list from a bare address (the address is ONE record at the star's cell); two, so a
     * derivation that found room for only one still leaves the system more than its address.
     */
    private static final int RETINUE = 2;

    /** A sun-like star is one Sun in size at the Sun's temperature: one solar luminosity. */
    private static final double SUN_SIZE = 1d;

    /**
     * The star seated behind a cloud: ten Suns across, a hundred times the Sun's light, five
     * magnitudes brighter. Brightness is not that scenario's subject, and a sun-like star behind the
     * cloud the shipped sky first offers needs an aperture past what a player can set (measured
     * 2026-10-02: m = 32.7 through 20.2 magnitudes of dust, so an aperture of 40.2).
     */
    private static final double BRIGHT_STAR_SIZE = 10d;

    /**
     * The deepest aperture the config loader accepts — the upper bound it reads
     * {@code telescopeLimitingMagnitude} with in {@code StellurgyConfiguration}.
     */
    private static final double PLAYER_APERTURE_CEILING = 40d;

    /** The overworld, which the stock universe binds to Earth. */
    private static final int EARTH = 0;

    private static String at(FixtureSite site) {
        return site.dim + " " + site.x + " " + site.y + " " + site.z;
    }

    /** How the event recorder names a machine: its x,y,z, without the dimension. */
    private static String machineKey(FixtureSite site) {
        return site.x + "," + site.y + "," + site.z;
    }

    private static String cell(long[] sectors) {
        return sectors[0] + " " + sectors[1] + " " + sectors[2];
    }

    /** Set a whitelisted config key, refusing the arrangement if the server would not. */
    private void configure(String key, Object value) throws Exception {
        Reply.of("stellurgytest config set", exec("stellurgytest config set " + key + " " + value))
                .requireOk("set " + key + " to " + value);
    }

    /** A whitelisted config key's value right now, as the server prints it — to be put back. */
    private String configured(String key) throws Exception {
        return Reply.of("stellurgytest config get", exec("stellurgytest config get " + key))
                .requireOk("read " + key).text("value");
    }

    /** Every telescope setting a scenario below rests on, at the value the game ships with. */
    private void shippedInstrument() throws Exception {
        // Research off: the survey resolves outright, so a scenario is about WHAT a look makes of the
        // sky and not about the clock the research mode paces it on.
        configure("planetsMustBeDiscovered", false);
        configure("telescopeLimitingMagnitude", StellurgyConfiguration.DEFAULT_TELESCOPE_LIMITING_MAGNITUDE);
        configure("telescopeResolveMarginMagnitudes",
                StellurgyConfiguration.DEFAULT_TELESCOPE_RESOLVE_MARGIN_MAGNITUDES);
        configure("telescopeConeHalfAngleDegrees",
                StellurgyConfiguration.DEFAULT_TELESCOPE_CONE_HALF_ANGLE_DEGREES);
        configure("telescopeScanMaxCells", StellurgyConfiguration.DEFAULT_TELESCOPE_SCAN_MAX_CELLS);
        configure("telescopeScanCellsPerStep", StellurgyConfiguration.DEFAULT_TELESCOPE_SCAN_CELLS_PER_STEP);
        configure("telescopeScanBaseTicks", StellurgyConfiguration.DEFAULT_TELESCOPE_SCAN_BASE_TICKS);
        // The loader's default price of a survey step is zero (StellurgyConfiguration's
        // telescopeSurveyDataPerStep); a bare observatory has no data buses to pay any other.
        configure("telescopeSurveyDataPerStep", 0);
    }

    /** The shipped procedural sky — stars with derived worlds, and the clouds between them. */
    private void proceduralSky() throws Exception {
        GalaxyGenConfig shipped = GalaxyGenConfig.defaults();
        Reply.of("stellurgytest space gen-install",
                exec("stellurgytest space gen-install " + shipped.density + " " + shipped.minSpacing))
                .requireOk("install the shipped procedural sky");
    }

    private void releaseProceduralSky() throws Exception {
        Reply.of("stellurgytest space gen-reset", exec("stellurgytest space gen-reset"))
                .requireOk("put the server's own sky back");
    }

    /**
     * An observatory at {@code site} with a blank crystal in it, and what it reads idle.
     *
     * <p>Its chunk is HELD for the scenario ({@code chunk hold}, dropped by {@link #releaseChunks}):
     * in play the operator stands at the machine and keeps its world loaded; a headless server has
     * nobody there, and a world nobody holds is unloaded between two probe calls — measured
     * 2026-10-02 on the home moon, where four of six parallel instances read "standing world not
     * loaded" mid-scenario. The hold also keeps the tile ticking, which is what completes a survey.</p>
     */
    private TelescopeReading standObservatory(FixtureSite site) throws Exception {
        Reply.of("stellurgytest chunk hold", exec("stellurgytest chunk hold " + at(site) + " 0"))
                .requireOk("hold the observatory's chunk loaded");
        Reply.of("stellurgytest telescope place", exec("stellurgytest telescope place " + at(site)))
                .requireOk("stand an observatory at " + site);
        blankCrystal(site);
        TelescopeReading idle = TelescopeReading.at(this::exec, at(site)).requireOk("read the observatory");
        requireArranged("the observatory must know where in the galaxy it stands: " + idle.raw(),
                idle.hasOrigin());
        return idle;
    }

    private void releaseChunks() throws Exception {
        Reply.of("stellurgytest chunk release", exec("stellurgytest chunk release"))
                .requireOk("release the held chunks");
    }

    private void blankCrystal(FixtureSite site) throws Exception {
        int held = Reply.of("stellurgytest telescope crystal",
                exec("stellurgytest telescope crystal " + at(site))).integer("addresses");
        requireArranged("the crystal must start blank, or every count after it means nothing: " + held,
                held == 0);
    }

    /**
     * Seat a system with {@link #RETINUE} worlds at {@code sectors}, its star {@code sizeSuns} in size
     * at the Sun's temperature. Returns its cell key.
     */
    private String systemAt(long[] sectors, String name, double sizeSuns) throws Exception {
        for (long sector : sectors) {
            requireArranged("the probe seats a system by integer sectors: " + cell(sectors),
                    Math.abs(sector) <= Integer.MAX_VALUE);
        }
        return Reply.of("stellurgytest telescope system", exec("stellurgytest telescope system "
                        + cell(sectors) + " " + name + " " + RETINUE + " " + sizeSuns + " "
                        + (int) StellarMagnitude.SOLAR_TEMPERATURE_UNITS))
                .requireOk("seat the system " + name).text("cellKey");
    }

    /** Production's photometry of the system seated at {@code sectors}, from this observatory. */
    private Reply photometry(FixtureSite site, long[] sectors) throws Exception {
        return Reply.of("stellurgytest telescope sees",
                exec("stellurgytest telescope sees " + at(site) + " " + cell(sectors)))
                .requireOk("read the photometry of " + cell(sectors));
    }

    /** What the crystal in the machine holds of the system owning {@code sectors}. */
    private Reply recorded(FixtureSite site, long[] sectors) throws Exception {
        return Reply.of("stellurgytest telescope entries",
                exec("stellurgytest telescope entries " + at(site) + " " + cell(sectors)))
                .requireOk("read what the crystal holds of " + cell(sectors));
    }

    /**
     * The crystal holds the system's bare address and none of its other bodies.
     *
     * <p>Read as the system's OWN records only ({@code telescope entries}: {@code atAnchor},
     * {@code beyondAddress}): an address the survey wrote for some other seat that this system's
     * neighbourhood shadows is reported apart ({@code foreign}) and is not this system's record.</p>
     */
    private static boolean addressAlone(Reply system) {
        return system.bool("atAnchor") && system.integer("beyondAddress") == 0;
    }

    /** The crystal holds no record of the system at all — neither its address nor any body. */
    private static boolean unseen(Reply system) {
        return !system.bool("atAnchor") && system.integer("held") == 0;
    }

    /**
     * Aim along {@code dir}, {@code steps} territories deep, and wait for the MACHINE to say it
     * finished — the completing {@code region_scan_advanced} of this observatory, from a mark taken
     * before the aim.
     */
    private void surveyAlong(FixtureSite site, long[] dir, int steps) throws Exception {
        long before = Reply.of("stellurgytest clock", exec("stellurgytest clock")).longInteger("tick");
        long mark = events.mark();
        TelescopeReading aimed = TelescopeReading.of(exec("stellurgytest telescope scan " + at(site) + " "
                + cell(dir) + " " + steps)).requireOk("aim the instrument");
        requireArranged("the pointing must reach as deep as it was aimed (" + steps + " territories)"
                        + " rather than be cut short by its look budget: " + aimed.raw(),
                aimed.distanceCells() >= (long) steps * aimed.stride());
        String done = events.awaitRecordWithFields(mark, SURVEY_ADVANCED, "the survey must finish",
                INSTANT_SURVEY_TICKS, "pos", machineKey(site), "complete", "true");
        System.out.println("[photometry] survey finished " + ((long) Events.number(done, "tick") - before)
                + " ticks after the aim");
    }

    /** Run the local radar over the observatory's own neighbourhood and wait for it to finish. */
    private void localRadar(FixtureSite site) throws Exception {
        long mark = events.mark();
        TelescopeReading.of(exec("stellurgytest telescope passive " + at(site)))
                .requireOk("start the local radar");
        events.awaitRecordWithFields(mark, SURVEY_ADVANCED, "the local radar must finish",
                INSTANT_SURVEY_TICKS, "pos", machineKey(site), "complete", "true");
    }

    /**
     * With the SHIPPED aperture, a sun-like star near enough is NAMED — every body of its system on
     * the crystal — one farther out is a point of light whose address alone is written, and one past
     * the aperture's reach is not seen at all. Then, on the same instrument: a resolve margin of zero
     * names whatever is detected, and a deeper aperture registers the star the shipped one missed.
     *
     * <p>Fails if {@code TelescopeScan#detect} stops deciding registration by the configured limiting
     * magnitude and resolvability by that limit less the configured margin, or
     * {@code TelescopeScan#characterise} stops following only a resolvable detection to its
     * bodies.</p>
     *
     * <p>The three distances are DERIVED from production's photometry for a one-Sun star at the
     * shipped limit {@code L} and margin {@code M}: the near star at half its resolving reach, the far
     * one at twice its detecting reach (1.5 magnitudes past {@code L}), and the middle one at the
     * geometric mean of the two reaches, which is the distance that sits the same number of
     * magnitudes inside both limits. Each is then MEASURED where production computes it
     * ({@code telescope sees}, dust included) and refused as an arrangement unless it landed on the
     * side it was placed for.</p>
     *
     * <p>Sees the survey a running observatory performs once aimed; does NOT see the GUI's aim
     * buttons (the probe aims), the research-paced sweep (switched off here), or a sky other than the
     * shipped procedural one.</p>
     *
     * <p>red-witnessed: with {@code TelescopeScan#characterise} at
     * {@code if (wholeSystem && hit.resolvable() && !isObscuredAt(hit.extinctionMagnitudes()))} reading
     * {@code if (false)}, this fails with "a sun-like star 2.98… ly away must be NAMED by the shipped
     * telescope"; with {@code TelescopeScan#detect} at {@code magnitude <= resolveLimit));} reading
     * {@code magnitude <= limitMagnitude}, with "one 32.79… ly away must be a point of light - its
     * address alone"; with {@code limitMagnitude += 5d;} inserted after
     * {@code double resolveLimit = limitMagnitude - resolveMarginMagnitudes();}, with "and one 283.19…
     * ly away, past the shipped reach, must not be seen at all"; with
     * {@code TelescopeScan#resolveMarginMagnitudes} at
     * {@code return Math.max(0d, StellurgyConfiguration.getCurrentConfig().telescopeResolveMarginMagnitudes);}
     * answering the shipped default, with "with a resolve margin of zero the star that was only a light
     * must be named"; with {@code TelescopeScan#limitMagnitude} at
     * {@code return StellurgyConfiguration.getCurrentConfig().telescopeLimitingMagnitude;} answering the
     * shipped default, with "an aperture one magnitude deeper must register the star the shipped one
     * missed, as a light" — one inversion per run, 2026-10-02.</p>
     */
    @Test
    public void theShippedApertureNamesANearSunSeesAFarOneAsALightAndMissesOneBeyondIt()
            throws Exception {
        FixtureSite site = site();
        String marginBefore = configured("telescopeResolveMarginMagnitudes");
        proceduralSky();
        try {
            shippedInstrument();
            TelescopeReading idle = standObservatory(site);
            long stride = idle.stepCells;
            requireArranged("the shipped procedural sky must be the one the instrument walks: its"
                    + " territories are " + GalaxyGenConfig.defaults().minSpacing + " cells, the"
                    + " instrument strides " + stride, stride == GalaxyGenConfig.defaults().minSpacing);
            long[] home = idle.originSectors();

            double limit = StellurgyConfiguration.DEFAULT_TELESCOPE_LIMITING_MAGNITUDE;
            double margin = StellurgyConfiguration.DEFAULT_TELESCOPE_RESOLVE_MARGIN_MAGNITUDES;
            double sun = StellarMagnitude.luminositySuns(SUN_SIZE, StellarMagnitude.SOLAR_TEMPERATURE_UNITS);
            double detectLy = StellarMagnitude.detectionRangeLightYears(sun, limit);
            double resolveLy = StellarMagnitude.detectionRangeLightYears(sun, limit - margin);
            double strideLy = UniverseScale.lightYearsForCells(stride);
            int near = (int) Math.max(1L, (long) Math.floor(0.5d * resolveLy / strideLy));
            int middle = (int) Math.round(Math.sqrt(resolveLy * detectLy) / strideLy);
            int far = (int) Math.ceil(2d * detectLy / strideLy);
            System.out.println("[photometry] a one-Sun star at the shipped aperture is resolved to "
                    + resolveLy + " ly and detected to " + detectLy + " ly; a territory is " + strideLy
                    + " ly; seating at " + near + ", " + middle + " and " + far + " territories");

            long[] down = {0L, -1L, 0L};
            long[] nearCell = {home[0], home[1] - near * stride, home[2]};
            long[] middleCell = {home[0], home[1] - middle * stride, home[2]};
            long[] farCell = {home[0], home[1] - far * stride, home[2]};
            String nearKey = systemAt(nearCell, "near-sun", SUN_SIZE);
            String middleKey = systemAt(middleCell, "middle-sun", SUN_SIZE);
            String farKey = systemAt(farCell, "far-sun", SUN_SIZE);

            // Where each star stands against the shipped limits, read where production computes it.
            Reply nearSeen = photometry(site, nearCell);
            double shippedLimit = nearSeen.number("limit");
            double shippedResolve = nearSeen.number("resolveLimit");
            double nearMag = nearSeen.element("systems", "anchor", nearKey).number("apparentMagnitude");
            double middleMag = photometry(site, middleCell).element("systems", "anchor", middleKey)
                    .number("apparentMagnitude");
            double farMag = photometry(site, farCell).element("systems", "anchor", farKey)
                    .number("apparentMagnitude");
            System.out.println("[photometry] limit " + shippedLimit + ", resolve " + shippedResolve
                    + "; near m=" + nearMag + ", middle m=" + middleMag + ", far m=" + farMag);
            requireArranged("the near star must be bright enough to make out: m=" + nearMag
                    + " against " + shippedResolve, nearMag <= shippedResolve);
            requireArranged("the middle star must be bright enough to register and too faint to make"
                    + " out: m=" + middleMag + " against " + shippedResolve + ".." + shippedLimit,
                    middleMag > shippedResolve && middleMag <= shippedLimit);
            requireArranged("the far star must be past the aperture: m=" + farMag + " against "
                    + shippedLimit, farMag > shippedLimit);
            for (long[] seat : new long[][] {nearCell, middleCell, farCell}) {
                Reply system = recorded(site, seat);
                requireArranged("each seated system must hold more bodies than its address, or a named"
                        + " system and a bare address write the same thing: " + system,
                        system.integer("bodies") > 1);
            }

            surveyAlong(site, down, far + 1);
            Reply nearHeld = recorded(site, nearCell);
            Reply middleHeld = recorded(site, middleCell);
            Reply farHeld = recorded(site, farCell);
            assertEquals("a sun-like star " + near * strideLy + " ly away must be NAMED by the shipped"
                            + " telescope - every body of its system on the crystal: " + nearHeld,
                    nearHeld.integer("bodies"), nearHeld.integer("held"));
            assertTrue("one " + middle * strideLy + " ly away must be a point of light - its address"
                    + " alone: " + middleHeld, addressAlone(middleHeld));
            assertTrue("and one " + far * strideLy + " ly away, past the shipped reach, must not be"
                    + " seen at all: " + farHeld, unseen(farHeld));

            // No margin: everything detectable is resolvable.
            configure("telescopeResolveMarginMagnitudes", 0);
            blankCrystal(site);
            surveyAlong(site, down, far + 1);
            Reply middleNamed = recorded(site, middleCell);
            assertEquals("with a resolve margin of zero the star that was only a light must be named: "
                    + middleNamed, middleNamed.integer("bodies"), middleNamed.integer("held"));

            // A deeper aperture - one magnitude past the far star - registers what the shipped one
            // missed, on the same instrument that missed it.
            configure("telescopeResolveMarginMagnitudes", margin);
            configure("telescopeLimitingMagnitude", farMag + 1d);
            blankCrystal(site);
            surveyAlong(site, down, far + 1);
            Reply farFound = recorded(site, farCell);
            System.out.println("[photometry] the deeper aperture's record of the far system: " + farFound);
            assertTrue("an aperture one magnitude deeper must register the star the shipped one"
                    + " missed, as a light: " + farFound, addressAlone(farFound));
        } finally {
            configure("telescopeResolveMarginMagnitudes", marginBefore);
            releaseProceduralSky();
            releaseChunks();
        }
    }

    /**
     * A system behind dust THICKER than the concealment threshold is still written down — its address
     * alone, never nothing — while the same system behind the same dust read against a threshold
     * above it is named. The threshold is read in magnitudes of extinction.
     *
     * <p>Fails if {@code TelescopeScan#characterise} stops deciding that an obscured detection costs
     * the look its bodies and not the look itself, or {@code TelescopeScan#isObscuredAt} stops
     * comparing the threshold against the extinction in magnitudes.</p>
     *
     * <p>The cloud is a real one of the shipped procedural sky ({@code space nebula-find}), the
     * system is seated on the far side of it along the instrument's own aim, and the dust on that
     * sight line is MEASURED ({@code space extinction}); the two thresholds straddle that one reading,
     * so the only thing that differs between the two looks is which side of the threshold the same
     * dust falls. The thin one sits midway between the dust in magnitudes and the same dust as a
     * column, which is what makes the unit matter; the thick one at half the dust. The aperture is set
     * from production's own magnitude of the star (dust included) plus the shipped margin and one
     * more, so brightness alone would let the instrument name it every time.</p>
     *
     * <p>Does NOT see a look with no observer (no production caller makes one) or the obscured-look
     * count the operator is shown.</p>
     *
     * <p>red-witnessed: with {@code TelescopeScan#characterise} at
     * {@code !isObscuredAt(hit.extinctionMagnitudes())} comparing the column
     * ({@code hit.extinctionMagnitudes() / Nebula.MAGNITUDES_PER_DENSITY_LIGHT_YEAR}), this fails with
     * "behind dust thinner than the threshold the system must be named"; the same with
     * {@code TelescopeScan#isObscuredAt} at {@code return threshold > 0d && extinctionMagnitudes >= threshold;}
     * reading {@code extinctionMagnitudes > 0d}; with that line reading {@code return false;}, with
     * "behind dust thicker than the threshold the system must still be written down - its address
     * alone, never nothing" (it was named); with {@code TelescopeScan#characterise} at
     * {@code if (!namedSomething)} also requiring the look unobscured, the same message (nothing was
     * written) — one inversion per run, 2026-10-02.</p>
     */
    @Test
    public void thickDustLeavesASystemItsAddressAndThinDustHidesNothing() throws Exception {
        FixtureSite site = site();
        String marginBefore = configured("telescopeResolveMarginMagnitudes");
        String thresholdBefore = configured("telescopeObscuredAtMagnitudes");
        proceduralSky();
        try {
            shippedInstrument();
            TelescopeReading idle = standObservatory(site);
            long stride = idle.stepCells;
            long[] home = idle.originSectors();

            // The probe's own ceiling on how far it walks, so the search is as wide as it can be.
            Reply cloud = Reply.of("stellurgytest space nebula-find",
                    exec("stellurgytest space nebula-find 4096 " + stride)).requireOk("look for a cloud");
            requireArranged("the shipped sky must hold a cloud along the search: " + cloud,
                    cloud.bool("found") && cloud.has("centreX"));
            double[] toCloud = {cloud.longInteger("centreX") - home[0],
                    cloud.longInteger("centreY") - home[1], cloud.longInteger("centreZ") - home[2]};
            double cloudCells = Math.sqrt(toCloud[0] * toCloud[0] + toCloud[1] * toCloud[1]
                    + toCloud[2] * toCloud[2]);
            requireArranged("the cloud must be somewhere other than where the instrument stands: " + cloud,
                    cloudCells > 0d);
            for (double component : toCloud) {
                requireArranged("the aim at the cloud must fit the instrument's integer aim: " + cloud,
                        Math.abs(component) <= Integer.MAX_VALUE);
            }
            // The first whole territory past the cloud's far edge, along the aim: the look of that
            // shell lands on the axis, `k` strides out.
            int k = (int) Math.ceil((cloudCells + cloud.number("radiusCells")) / stride) + 1;
            long[] aim = {(long) toCloud[0], (long) toCloud[1], (long) toCloud[2]};
            long[] seat = {home[0] + Math.round(toCloud[0] / cloudCells * k * stride),
                    home[1] + Math.round(toCloud[1] / cloudCells * k * stride),
                    home[2] + Math.round(toCloud[2] / cloudCells * k * stride)};
            String seatKey = systemAt(seat, "veiled-star", BRIGHT_STAR_SIZE);

            Reply line = Reply.of("stellurgytest space extinction", exec("stellurgytest space extinction "
                    + cell(home) + " " + cell(seat))).requireOk("measure the dust on the sight line");
            double dust = line.number("magnitudes");
            requireArranged("the sight line must cross dust: " + line, dust > 0d);
            double magnitude = photometry(site, seat).element("systems", "anchor", seatKey)
                    .number("apparentMagnitude");
            double aperture = magnitude + StellurgyConfiguration.DEFAULT_TELESCOPE_RESOLVE_MARGIN_MAGNITUDES + 1d;
            System.out.println("[nebula] cloud " + cloudCells + " cells out (radius "
                    + cloud.number("radiusCells") + "), system " + k + " territories along it; dust "
                    + dust + " mag, star m=" + magnitude + ", aperture " + aperture);
            requireArranged("the aperture that makes this star out through the dust must be one a player"
                    + " can configure (the loader allows at most " + PLAYER_APERTURE_CEILING + "): "
                    + aperture, aperture <= PLAYER_APERTURE_CEILING);
            configure("telescopeLimitingMagnitude", aperture);
            requireArranged("the system must hold more bodies than its address: " + recorded(site, seat),
                    recorded(site, seat).integer("bodies") > 1);

            // Thin: the threshold above the dust on this line, by less than the same dust read as a
            // column. The calibration reads a column of C density-light-years as C * M magnitudes with
            // M < 1, so the column is the LARGER number, and a threshold midway between the two is
            // above the dust in magnitudes and below it as a column: a concealment that compared the
            // threshold with the column would hide this system, and one that reads magnitudes does not.
            double perColumn = Nebula.MAGNITUDES_PER_DENSITY_LIGHT_YEAR;
            requireArranged("the calibration must read a column as fewer magnitudes than its length, or"
                    + " the thin threshold below cannot tell the two units apart: " + perColumn,
                    perColumn < 1d);
            double thinThreshold = (dust + dust / perColumn) / 2d;
            configure("telescopeObscuredAtMagnitudes", thinThreshold);
            surveyAlong(site, aim, k + 1);
            Reply named = recorded(site, seat);
            assertEquals("behind dust thinner than the threshold the system must be named: " + named,
                    named.integer("bodies"), named.integer("held"));

            // Thick: the same dust, the threshold at half of it.
            configure("telescopeObscuredAtMagnitudes", dust / 2d);
            blankCrystal(site);
            surveyAlong(site, aim, k + 1);
            Reply veiled = recorded(site, seat);
            System.out.println("[nebula] the obscured look's record of the system: " + veiled);
            assertTrue("behind dust thicker than the threshold the system must still be written down -"
                    + " its address alone, never nothing: " + veiled, addressAlone(veiled));
        } finally {
            configure("telescopeObscuredAtMagnitudes", thresholdBefore);
            configure("telescopeResolveMarginMagnitudes", marginBefore);
            releaseProceduralSky();
            releaseChunks();
        }
    }

    /**
     * Only a body a look actually MADE OUT teaches the world the observatory stands on. Recording
     * positions only teaches nothing, a system registered but too faint to make out teaches nothing,
     * and the same system made out teaches its worlds — on one observatory, in that order.
     *
     * <p>Fails if {@code TelescopeScan#characterise} stops reporting only the bodies it named, or
     * {@code TileObservatory}'s survey stops teaching the ground exactly those.</p>
     *
     * <p>Stands on the home moon and looks at the home system with the local radar, because a body
     * the home system holds has a world of its own (Earth), and only a world can be taught. The moon
     * and not Earth: the radar scenario above teaches EARTH about the home system with its own wide
     * aperture, and JUnit promises no order, so Earth's own knowledge is not a clean slate here. The
     * apertures are set from production's magnitude of the home star as seen from here: the shipped
     * margin plus one magnitude makes it out, half the margin registers it without making it out.</p>
     *
     * <p>Sees the operator's toggle pressed through the machine's own button handler; does NOT see the
     * GUI that sends it, or the sync of what was taught to a client.</p>
     *
     * <p>red-witnessed: with {@code TelescopeScan#characterise} at
     * {@code if (wholeSystem && hit.resolvable() && !isObscuredAt(hit.extinctionMagnitudes()))} dropping
     * {@code wholeSystem}, this fails with "recording positions only writes the home system's address
     * alone"; with every body of the system reported to {@code named} inside {@code if (!namedSomething)},
     * with "and teaches the world it stands on nothing"; with the same report made only when
     * {@code wholeSystem}, with "and a point of light teaches the world it stands on nothing"; with
     * {@code TelescopeScan#detect} at {@code magnitude <= resolveLimit));} reading
     * {@code magnitude <= limitMagnitude}, with "a system registered but too faint to make out is an
     * address alone"; at {@code if (memory.record(entryFor(body, observedTick, nameOf)))} skipping a
     * body with a dimension, with "a look that made the home system out must name Earth on the
     * crystal"; with {@code TileObservatory#teachThisBody} at {@code here.discoverPlanet(dimId);}
     * removed, with "and must teach the world it stands on that Earth is there" — one inversion per
     * run, 2026-10-02.</p>
     */
    @Test
    public void onlyWhatALookMadeOutTeachesTheWorldItStandsOn() throws Exception {
        int moon = homeMoon();
        FixtureSite site = plot().inDimension(moon).site();
        String marginBefore = configured("telescopeResolveMarginMagnitudes");
        try {
            shippedInstrument();
            // The radar's own territory and nothing else: the home system is the whole subject.
            configure("telescopePassiveRadiusSteps", 0);
            TelescopeReading idle = standObservatory(site);
            long[] home = idle.originSectors();
            Reply sky = photometry(site, home);
            requireArranged("the observatory's own territory must hold exactly its own system: " + sky,
                    sky.arrayLength("systems") == 1);
            String homeSystem = recorded(site, home).text("anchor");
            double magnitude = sky.element("systems", "anchor", homeSystem).number("apparentMagnitude");
            double margin = StellurgyConfiguration.DEFAULT_TELESCOPE_RESOLVE_MARGIN_MAGNITUDES;
            System.out.println("[teaching] from dim " + moon + " the home system " + homeSystem
                    + " is m=" + magnitude);
            requireArranged("arrangement: this world must not know Earth before anything is surveyed",
                    !earthKnownOn(moon));

            // 1. Positions only, through an aperture that could make the system out.
            configure("telescopeLimitingMagnitude", magnitude + margin + 1d);
            Reply.of("stellurgytest telescope whole-system",
                    exec("stellurgytest telescope whole-system " + at(site) + " false"))
                    .requireOk("set the instrument to record positions only");
            localRadar(site);
            Reply positions = recorded(site, home);
            assertTrue("recording positions only writes the home system's address alone: " + positions,
                    addressAlone(positions));
            assertFalse("and teaches the world it stands on nothing", earthKnownOn(moon));

            // 2. Whole systems, through an aperture that registers the system and cannot make it out.
            Reply.of("stellurgytest telescope whole-system",
                    exec("stellurgytest telescope whole-system " + at(site) + " true"))
                    .requireOk("set the instrument to follow detections to their bodies");
            configure("telescopeLimitingMagnitude", magnitude + margin / 2d);
            blankCrystal(site);
            localRadar(site);
            Reply light = recorded(site, home);
            assertTrue("a system registered but too faint to make out is an address alone: " + light,
                    addressAlone(light));
            assertFalse("and a point of light teaches the world it stands on nothing", earthKnownOn(moon));

            // 3. The same system, made out.
            configure("telescopeLimitingMagnitude", magnitude + margin + 1d);
            blankCrystal(site);
            localRadar(site);
            Reply madeOut = recorded(site, home);
            boolean namesEarth = false;
            for (int dim : madeOut.intArray("namedDims")) {
                namesEarth |= dim == EARTH;
            }
            assertTrue("a look that made the home system out must name Earth on the crystal: " + madeOut,
                    namesEarth);
            assertTrue("and must teach the world it stands on that Earth is there", earthKnownOn(moon));
        } finally {
            configure("telescopeResolveMarginMagnitudes", marginBefore);
            releaseChunks();
        }
    }

    /** Whether the world {@code standing} has itself learned of Earth — its own knowledge, not the pack's. */
    private boolean earthKnownOn(int standing) throws Exception {
        return Reply.of("stellurgytest planet knowledge",
                exec("stellurgytest planet knowledge " + standing + " " + EARTH)).bool("local");
    }

    /** The registered world whose parent is Earth. */
    private int homeMoon() throws Exception {
        for (int dim : DimList.from(this::exec).registered()) {
            if (dim == EARTH) {
                continue;
            }
            Reply info = Reply.of("stellurgytest planet info", exec("stellurgytest planet info " + dim));
            if (info.has("parent") && info.integer("parent") == EARTH) {
                return dim;
            }
        }
        ArrangementFailure.arrangementFailed("the stock universe must hold a moon of Earth");
        return EARTH;
    }

    // ── one system, one address ──────────────────────────────────────────────────────────────────

    /**
     * The passive radar's reach at the value the config loader ships ({@code StellurgyConfiguration},
     * {@code telescopePassiveRadiusSteps}' default): the observatory's own territory and the
     * twenty-six around it.
     */
    private static final int SHIPPED_RADAR_RADIUS = 1;

    /**
     * A survey writes a system onto the crystal ONCE: the local radar, run with the shipped settings
     * through a procedural sky in which an authored system stands close enough to the edge of its
     * territory that its neighbourhood reaches into the next one, leaves that system's address on the
     * crystal and no record at any other cell under its name.
     *
     * <p>An authored system's neighbourhood is the box half a territory to each side of its anchor
     * ({@code UniverseRegistry#storedAnchorNear}), and the pack's word on what stands there is the whole
     * of it: no procedural seat whose own neighbourhood reaches that box is a system. A look whose own
     * cell lies OUTSIDE the box is answered with the procedural seats of the look's territory
     * ({@code UniverseRegistry#anchorsInTerritory}); if one of them reached into the box, every
     * attribution query ({@code UniverseRegistry#anchorForCell}) would name the authored system for it,
     * and a positions-only survey would write that seat's cell as an address labelled with the authored
     * system ({@code TelescopeScan#characterise}): one system, two addresses, the second at a cell where
     * nothing stands. Fails if {@code UniverseRegistry#anchorsInTerritory} stops dropping the seats
     * {@code UniverseRegistry#clearOfAuthored} refuses.</p>
     *
     * <p>The arrangement is chosen from the procedural field as it stood BEFORE the system was seated,
     * so it does not depend on how the disagreement is resolved: a seat {@code P} of a radar look's
     * territory, the look's own cell more than half a territory from where the system will stand, and
     * the system seated within half a territory of {@code P} and of the NEXT look along one axis — so
     * the radar does look at the system itself, and the positive half of the verdict (its address is
     * there) is measured in the same survey. The system is seated in that next look's territory, which
     * no other look of the radar enumerates, so no seat the survey pins can share its super-cell; and
     * {@code P} is nearer to it than to any other seat the survey could pin. {@code P} is read untouched
     * (its own anchor, no stored override) before anything is seated, and the system's own look is
     * read answering the system once it is.</p>
     *
     * <p>The HOME system is held to the same verdict, and needs no seating: it is authored, the
     * observatory stands inside its neighbourhood, and every other look of the radar lands a whole
     * territory out — outside a box only half a territory wide, in territories that box reaches into.
     * Its arrangement is that geometry alone; whether the procedural field puts a seat in the overlap is
     * the field's, which is why the seated system above carries the arrangement measured before the
     * fact and the home system rides beside it. Measured 2026-10-02 on the first run: home 5 foreign.</p>
     *
     * <p>Positions only — the operator's toggle, pressed through the machine's own button handler —
     * because a look that makes a system out writes its bodies, and the seat's bodies ARE the authored
     * system's, which merge on the crystal: the defect shows only where an address is written. The
     * aperture is the shipped one; the seated star's registration is measured where production
     * computes it ({@code telescope sees}) and refused as an arrangement if it would not register.</p>
     *
     * <p>Sees the survey a running observatory performs and what its crystal holds; does NOT see the
     * navigation console's listing of that crystal on a client, which renders the same records (no
     * client decision is involved), nor an authored system written by a pack file rather than seated by
     * the probe (the registry stores both through {@code UniverseRegistry#place}).</p>
     *
     * <p>red-witnessed: with {@code UniverseRegistry#anchorsInTerritory} at
     * {@code if (clearOfAuthored(seat))} reading {@code if (true)}, this fails with
     * "expected:&lt;seated=[0 home=0]&gt; but was:&lt;seated=[1 home=5]&gt;" (the same reading the tree
     * gave before the mask existed, 2026-10-02); with {@code TelescopeScan#characterise} at
     * {@code if (!namedSomething)} reading {@code if (false)}, with "must have written both addresses"
     * ("expected:&lt;seated=[true home=tru]e&gt; but was:&lt;seated=[false home=fals]e&gt;") — one
     * inversion per run, 2026-10-03.</p>
     */
    @Test
    public void aSurveyWritesASystemOnceAndNoNeighbouringSeatUnderItsName() throws Exception {
        FixtureSite site = site();
        String radiusBefore = configured("telescopePassiveRadiusSteps");
        proceduralSky();
        try {
            shippedInstrument();
            configure("telescopePassiveRadiusSteps", SHIPPED_RADAR_RADIUS);
            TelescopeReading idle = standObservatory(site);
            long s = idle.stepCells;
            int minSpacing = GalaxyGenConfig.defaults().minSpacing;
            requireArranged("the radar must stride by the installed sky's territory (" + minSpacing
                    + " cells), or its looks do not land one per territory: " + s, s == minSpacing);
            // A system's neighbourhood: half a territory to each side of its anchor — the box
            // UniverseRegistry#storedAnchorNear attributes by, which IGalaxyGenerator documents.
            long reach = minSpacing / 2L;
            long[] home = idle.originSectors();

            // 1. The procedural field the radar will walk, read before anything is seated.
            int r = SHIPPED_RADAR_RADIUS;
            List<long[]> looks = new ArrayList<>();
            List<int[]> lookIndex = new ArrayList<>();
            List<List<long[]>> seatsOf = new ArrayList<>();
            List<long[]> every = new ArrayList<>();
            for (int i = -r; i <= r; i++) {
                for (int j = -r; j <= r; j++) {
                    for (int k = -r; k <= r; k++) {
                        long[] look = {home[0] + i * s, home[1] + j * s, home[2] + k * s};
                        List<long[]> seats = seatsOfLook(site, look);
                        looks.add(look);
                        lookIndex.add(new int[] {i, j, k});
                        seatsOf.add(seats);
                        every.addAll(seats);
                    }
                }
            }
            System.out.println("[one-address] " + every.size() + " seats across " + looks.size()
                    + " radar looks; territory " + s + " cells, neighbourhood reach " + reach);

            // 2. A seat P of some look's territory, and where to seat the system A.
            long[] lookT = null;
            long[] lookA = null;
            long[] p = null;
            long[] a = null;
            search:
            for (int t = 0; t < looks.size(); t++) {
                long[] l = looks.get(t);
                for (long[] seat : seatsOf.get(t)) {
                    for (int axis = 0; axis < 3; axis++) {
                        long d = seat[axis] - l[axis];
                        int step = Long.signum(d);
                        int next = lookIndex.get(t)[axis] + step;
                        if (step == 0 || next < -r || next > r || !lateralWithin(seat, l, axis, reach)) {
                            continue;
                        }
                        long[] l2 = l.clone();
                        l2[axis] += step * s;
                        // Far enough from P's own look that the look is outside A's box, and past
                        // the face into the next territory.
                        long boundary = step > 0 ? (Math.floorDiv(seat[axis], s) + 1L) * s
                                : Math.floorDiv(seat[axis], s) * s - 1L;
                        long delta = Math.max(Math.max(1L, reach + 1L - Math.abs(d)),
                                Math.abs(boundary - seat[axis]));
                        long[] seatA = seat.clone();
                        seatA[axis] += step * delta;
                        if (delta > reach || chebyshev(seatA, l) <= reach || chebyshev(seatA, l2) > reach
                                || Math.floorDiv(seatA[axis], s) != Math.floorDiv(l2[axis], s)
                                || !alone(seat, seatA, l, l2, seatsOf.get(t), every, reach)) {
                            continue;
                        }
                        lookT = l;
                        lookA = l2;
                        p = seat;
                        a = seatA;
                        break search;
                    }
                }
            }
            requireArranged("the procedural field around the observatory must hold a seat close enough to"
                    + " a territory face for a system seated past that face to reach it, with no other"
                    + " seat nearer: " + every.size() + " seats read", p != null);
            // `cell-info` writes anchor null for void, and absence is the answer there: a seat that
            // answers anything but itself — void or another system — is not the field's own.
            Reply seatP = cellInfo(p);
            requireArranged("seat P must be the procedural field's own and nobody's member yet: " + seatP,
                    key(p).equals(seatP.textOr("anchor", null)) && !seatP.bool("hasOverride"));
            System.out.println("[one-address] seat P " + key(p) + " of the look at " + key(lookT)
                    + "; system to stand at " + key(a) + ", looked at from " + key(lookA));

            // 3. Seat the system, and measure that the radar will register it.
            String seated = systemAt(a, "one-address", SUN_SIZE);
            requireArranged("the system must be seated where it was chosen: " + seated, key(a).equals(seated));
            // A null anchor (void) fails this as a mismatch, and absence is the answer that should.
            requireArranged("the look beside it must find the seated system: " + cellInfo(lookA),
                    seated.equals(cellInfo(lookA).textOr("anchor", null)));
            Reply sky = photometry(site, lookA);
            double magnitude = sky.element("systems", "anchor", seated).number("apparentMagnitude");
            requireArranged("the shipped aperture must register the seated star: m=" + magnitude
                    + " against " + sky.number("limit"), magnitude <= sky.number("limit"));
            System.out.println("[one-address] seated star m=" + magnitude + " against limit "
                    + sky.number("limit") + "; the look at P's territory reads " + photometry(site, lookT));

            // 4. Positions only, and the radar.
            Reply.of("stellurgytest telescope whole-system",
                    exec("stellurgytest telescope whole-system " + at(site) + " false"))
                    .requireOk("set the instrument to record positions only");
            localRadar(site);

            Reply held = recorded(site, a);
            Reply homeHeld = recorded(site, home);
            System.out.println("[one-address] the crystal's record of the seated system: " + held);
            System.out.println("[one-address] the crystal's record of the home system: " + homeHeld);
            assertEquals("the radar looked at the seated system and at the home system it stands in, and"
                            + " must have written both addresses. Seated: " + held + " Home: " + homeHeld,
                    "seated=true home=true",
                    "seated=" + held.bool("atAnchor") + " home=" + homeHeld.bool("atAnchor"));
            // Both counts in one verdict, so a red names every system that carries another cell.
            assertEquals("one system is ONE address: no record at another cell may stand under its name"
                            + " (seat " + key(p) + " lay in the seated system's neighbourhood). Seated: "
                            + held + " Home: " + homeHeld,
                    "seated=0 home=0",
                    "seated=" + held.integer("foreign") + " home=" + homeHeld.integer("foreign"));
        } finally {
            configure("telescopePassiveRadiusSteps", radiusBefore);
            releaseProceduralSky();
            releaseChunks();
        }
    }

    /** The seats the look at {@code look} enumerates, as production's detection stage lists them. */
    private List<long[]> seatsOfLook(FixtureSite site, long[] look) throws Exception {
        List<long[]> seats = new ArrayList<>();
        for (String element : photometry(site, look).objectArray("systems")) {
            seats.add(sectorsOf(Reply.of("stellurgytest telescope sees [systems]", element).text("anchor")));
        }
        return seats;
    }

    /** What the registry attributes the cell {@code sectors} to, and whether it holds a stored placement. */
    private Reply cellInfo(long[] sectors) throws Exception {
        return Reply.of("stellurgytest space cell-info",
                exec("stellurgytest space cell-info " + key(sectors))).requireOk("read the cell " + key(sectors));
    }

    private static String key(long[] sectors) {
        return GalacticCoord.ofSectorLocal(sectors[0], sectors[1], sectors[2], 0L, 0L, 0L).cellKey();
    }

    private static long[] sectorsOf(String cellKey) {
        GalacticCoord c = GalacticCoord.fromCellKey(cellKey);
        requireArranged("a seat must be named by a galactic cell key: " + cellKey, c != null);
        return new long[] {c.sectorX(), c.sectorY(), c.sectorZ()};
    }

    private static long chebyshev(long[] u, long[] v) {
        return Math.max(Math.abs(u[0] - v[0]), Math.max(Math.abs(u[1] - v[1]), Math.abs(u[2] - v[2])));
    }

    private static double distanceSq(long[] u, long[] v) {
        double x = u[0] - v[0];
        double y = u[1] - v[1];
        double z = u[2] - v[2];
        return x * x + y * y + z * z;
    }

    private static boolean lateralWithin(long[] seat, long[] look, int axis, long reach) {
        for (int other = 0; other < 3; other++) {
            if (other != axis && Math.abs(seat[other] - look[other]) > reach) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether no seat the radar could pin stands in the way of the arrangement: none nearer to
     * {@code p} than the system at {@code a} is, none nearer to the system's own look {@code lookA}
     * than the system is, and none outside {@code p}'s territory whose neighbourhood reaches
     * {@code p}'s look (a pin there would answer that look instead of its territory).
     */
    private static boolean alone(long[] p, long[] a, long[] lookP, long[] lookA, List<long[]> territory,
                                 List<long[]> every, long reach) {
        for (long[] z : every) {
            if (java.util.Arrays.equals(z, p)) {
                continue;
            }
            if (chebyshev(z, p) <= reach && distanceSq(z, p) <= distanceSq(a, p)) {
                return false;
            }
            if (chebyshev(z, lookA) <= reach && distanceSq(z, lookA) <= distanceSq(a, lookA)) {
                return false;
            }
            boolean ownTerritory = false;
            for (long[] mine : territory) {
                ownTerritory |= java.util.Arrays.equals(mine, z);
            }
            if (!ownTerritory && chebyshev(z, lookP) <= reach) {
                return false;
            }
        }
        return true;
    }
}
