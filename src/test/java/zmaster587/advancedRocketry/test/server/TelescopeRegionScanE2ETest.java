package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.GameTicks;

import org.junit.Test;

import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.NavStatus;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.TelescopeReading;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The observatory's region survey, driven on a real server through the machine itself.
 *
 * <p>The unit tier pins the survey's arithmetic; this pins the MACHINE: that an observatory can be
 * aimed at a region, that it sweeps through it on the world's own clock when research is on and
 * resolves it outright when research is off, that stopping and re-aiming cost nothing but the cell in
 * flight, and that what it resolved is written onto the crystal sitting in the machine.</p>
 *
 * <p>Every number here is the SERVER's answer; the probes state their own side.</p>
 *
 * <p>Position-isolated at x=4300-4660 (clear of the observatory-multiblock fixtures at x=4000-4060).</p>
 */
public class TelescopeRegionScanE2ETest extends AbstractSharedServerTest {

    /**
     * How much WORLD a survey is allowed to make progress in before it is inspected - the old
     * 1 500 ms and 2 000 ms, said in the ticks the survey actually advances on.
     */
    private static final int MID_SURVEY_TICKS = 30;
    private static final int STARVED_SURVEY_TICKS = 40;

    /** World a whole survey is given to finish in - the old 40 x 250 ms and 60 x 250 ms. */
    private static final int SURVEY_TICKS = 200;
    private static final int SWEEP_TICKS = 300;

    private static final int CY = FixtureSite.OPEN_AIR_Y;
    private static final int CZ = 4300;

    private String exec(String command) throws Exception {
        return join(client().execute(command));
    }

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
        exec("artest config set planetsMustBeDiscovered " + research);
        exec("artest config set telescopeScanBaseTicks " + ticksPerStep);
        // An aperture that sees essentially anything, so what a fixture finds is decided by where it
        // put the fixture and never by how bright the sky happened to draw it. A survey's photometry
        // is pinned in the unit tier, where a star's luminosity can be stated.
        exec("artest config set telescopeLimitingMagnitude 30");
        exec("artest config set telescopeConeHalfAngleDegrees 20");
        exec("artest config set telescopeScanMaxCells 1000");
        exec("artest config set telescopeScanCellsPerStep " + cellsPerStep);
        exec("artest config set telescopePassiveRadiusSteps 1");
    }

    /** What the instrument is doing right now. */
    private TelescopeReading scope(int x) throws Exception {
        return TelescopeReading.at(this::exec, where(x));
    }

    /** How far apart, in cells, the looks of a directed survey stand in THIS server's universe. */
    private long stride(int x) throws Exception {
        long stride = TelescopeReading.of(exec("artest telescope scan " + where(x) + " 1 0 0 1"))
                .requireOk("could not aim the instrument to read its stride")
                .stride();
        exec("artest telescope abort " + where(x));
        return stride;
    }

    private String where(int x) {
        return "0 " + x + " " + CY + " " + CZ;
    }

    /** An observatory with a blank crystal in it, and the cell it stands in. */
    private long[] observatoryWithCrystal(int x) throws Exception {
        String placed = exec("artest telescope place " + where(x));
        assertTrue("could not place an observatory: " + placed, Reply.of(placed).ok());
        String crystal = exec("artest telescope crystal " + where(x));
        assertEquals("the crystal must start blank, or every count afterwards means nothing",
                0, Reply.of("artest telescope crystal", crystal).integer("addresses"));
        return scope(x).originSectors();
    }

    /** Put a system with a planet in it at a cell, so what the instrument finds is determinate. */
    private void systemAt(long sx, long sy, long sz) throws Exception {
        String system = exec("artest telescope system " + sx + " " + sy + " " + sz);
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

    /** Poll the machine until its survey is finished. Bounded in the ticks a survey advances on. */
    private TelescopeReading awaitSurveyComplete(int x) throws Exception {
        final TelescopeReading[] info = new TelescopeReading[1];
        boolean finished = GameTicks.until(client(), GameTicks.server(), SURVEY_TICKS, () -> {
            info[0] = scope(x);
            return !info[0].scanning;
        });
        if (!finished) {
            throw new AssertionError("the survey never finished: " + info[0].raw());
        }
        return info[0];
    }

    @Test
    public void withoutResearchWhatTheInstrumentReachesIsResolvedOutright() throws Exception {
        final int x = 4300;
        surveySetup(false, 2, 120);
        long[] home = observatoryWithCrystal(x);
        systemNearTheLookAt(x, home, 4);

        TelescopeReading.of(exec("artest telescope scan " + where(x) + " 1 0 0 4"))
                .requireOk("the survey did not start");

        TelescopeReading done = awaitSurveyComplete(x);
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

        TelescopeReading started = TelescopeReading.of(exec("artest telescope scan " + where(x) + " 1 0 0 4"))
                .requireOk("the survey did not start");
        assertTrue("a region worth sweeping must hold more than one cell: " + started.raw(),
                started.cells() > 1);
        assertEquals("a fresh survey has resolved nothing yet", 0, started.cellsDone());

        long total = started.cells();
        final long[] seen = {0};
        GameTicks.until(client(), GameTicks.server(), SWEEP_TICKS, () -> {
            TelescopeReading info = scope(x);
            if (!info.scanning) {
                seen[0] = total;
                return true;
            }
            long done = info.cellsDone();
            assertTrue("a sweep must never go backwards: " + done + " after " + seen[0],
                    done >= seen[0]);
            seen[0] = done;
            return seen[0] >= total;
        });
        assertTrue("the sweep never advanced through its region (" + seen[0] + "/" + total + ")",
                seen[0] >= total);
    }

    @Test
    public void stoppingASurveyIsFreeAndKeepsWhatWasAlreadyLearned() throws Exception {
        final int x = 4380;
        surveySetup(true, 1, 180);
        long[] home = observatoryWithCrystal(x);
        systemNearTheLookAt(x, home, 3);

        exec("artest telescope scan " + where(x) + " 1 0 0 3");
        TelescopeReading before = scope(x);
        assertTrue("the survey must be running before it can be stopped: " + before.raw(),
                before.scanning);
        long learned = before.addressesOnCrystal();

        TelescopeReading stopped = TelescopeReading.of(exec("artest telescope abort " + where(x)))
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

        TelescopeReading first = TelescopeReading.of(exec("artest telescope scan " + where(x) + " 1 0 0 3"))
                .requireOk("the first survey did not start");
        long learned = first.addressesOnCrystal();

        TelescopeReading second = TelescopeReading.of(exec("artest telescope scan " + where(x) + " 0 0 1 5"))
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

        TelescopeReading passive = TelescopeReading.of(exec("artest telescope passive " + where(x)))
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
        TelescopeReading done = awaitSurveyComplete(x);
        assertTrue("the radar must resolve the system the observatory is standing in: " + done.raw(),
                done.addressesOnCrystal() >= 1);
    }

    @Test
    public void aSurveyInFlightSurvivesItsChunkBeingUnloaded() throws Exception {
        final int x = 4540;
        // Research on, one cell a step, a step long enough that the sweep is certainly mid-region
        // when the chunk goes away.
        surveySetup(true, 1, 30);
        observatoryWithCrystal(x);

        TelescopeReading.of(exec("artest telescope scan " + where(x) + " 1 0 0 3"))
                .requireOk("the survey did not start");
        // A survey advances per TICK, so how far it gets is a number of ticks. The old wall-clock
        // pause gave it fewer of them on a busy box - which made "still scanning" easier to satisfy
        // exactly when the machine was slowest, i.e. the test got weaker under load.
        GameTicks.advance(client(), GameTicks.server(), MID_SURVEY_TICKS);
        TelescopeReading before = scope(x);
        assertTrue("the survey must still be running to be interrupted: " + before.raw(),
                before.scanning);
        long doneBefore = before.cellsDone();
        String region = before.regionMin();

        String cycled = exec("artest chunk cycle 0 " + (x >> 4) + " " + (CZ >> 4));
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
        exec("artest config set telescopeSurveyDataPerStep 50");
        try {
            observatoryWithCrystal(x);
            TelescopeReading.of(exec("artest telescope scan " + where(x) + " 1 0 0 2"))
                    .requireOk("the survey did not start");

            GameTicks.advance(client(), GameTicks.server(), STARVED_SURVEY_TICKS);
            TelescopeReading after = scope(x);
            assertTrue("an instrument with no data must still be waiting, not finished: "
                    + after.raw(), after.scanning);
            assertEquals("and must not have resolved a single cell on credit",
                    0, after.cellsDone());
        } finally {
            exec("artest config set telescopeSurveyDataPerStep 0");
        }
    }

    @Test
    public void whatTheTelescopeWroteIsWhatAShipCanBeAimedBy() throws Exception {
        final int x = 4620;
        surveySetup(false, 4, 3);
        long[] home = observatoryWithCrystal(x);
        systemNearTheLookAt(x, home, 3);

        exec("artest telescope scan " + where(x) + " 1 0 0 3");
        TelescopeReading surveyed = awaitSurveyComplete(x);
        assertTrue("the survey must have written something to hand over: " + surveyed.raw(),
                surveyed.addressesOnCrystal() >= 1);

        // Carry the crystal to a navigation computer, the way a player would.
        int navX = x + 4;
        String placed = exec("artest nav place 0 " + navX + " " + CY + " " + CZ);
        assertTrue("could not place a navigation computer: " + placed, Reply.of(placed).ok());
        String handed = exec("artest telescope handover " + where(x) + " " + navX + " " + CY + " " + CZ);
        assertTrue("the crystal did not reach the console: " + handed, Reply.of(handed).ok());
        // `handover` answers the stack it MOVED, not the instrument's state, so it is read as its
        // own two-field reply rather than as a telescope reading.
        assertTrue("and it must arrive holding what the telescope wrote: " + handed,
                Reply.of("artest telescope handover", handed).integer("addresses") >= 1);

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
                idle.reachLy >= 4d);
        assertTrue("and must buy more than one star's territory: " + idle.reachSteps,
                idle.reachSteps >= 2);

        TelescopeReading aimed = TelescopeReading.of(
                        exec("artest telescope scan " + where(x) + " 1 0 0 " + idle.reachSteps))
                .requireOk("the survey did not start");
        assertTrue("an aim at the horizon must land an interstellar distance away: "
                        + aimed.distanceLy() + " ly",
                aimed.distanceLy() >= 4d);
        exec("artest telescope abort " + where(x));
    }

    @Test
    public void aFartherRegionIsALongerSurveyOnTheRealClock() throws Exception {
        final int x = 4500;
        surveySetup(true, 2, 40);
        observatoryWithCrystal(x);

        long nearTicks = TelescopeReading.of(exec("artest telescope scan " + where(x) + " 1 0 0 2"))
                .requireOk("the near survey did not start")
                .estimatedTicks();
        exec("artest telescope abort " + where(x));

        long farTicks = TelescopeReading.of(exec("artest telescope scan " + where(x) + " 1 0 0 20"))
                .requireOk("the far survey did not start")
                .estimatedTicks();
        exec("artest telescope abort " + where(x));

        assertTrue("a farther region must be a longer survey: near=" + nearTicks + " far=" + farTicks,
                farTicks > nearTicks);
    }
}
