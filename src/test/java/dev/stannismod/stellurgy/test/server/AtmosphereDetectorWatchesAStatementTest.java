package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.PeriodicGateWindow;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static dev.stannismod.stellurgy.test.StellurgyTestConstants.ppm;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The atmosphere detector watches a STATEMENT about the air, not a name.
 *
 * <p>It used to compare the air against one of fourteen named atmospheres and emit redstone when they
 * were the same object, which made a player's wiring a function of the mod's vocabulary: "is this
 * Superheated with no oxygen" could be wired and "is this poisonous" could not, because somebody had
 * written a class for the first and not the second.</p>
 *
 * <p>Two scenarios, and the second is the one that could not exist before. The first shows the
 * detector TRACKING its statement as the air changes — a detector that simply latched on would pass a
 * one-shot check. The second wires a condition no named atmosphere expressed at all.</p>
 *
 * <p><b>The detector's output feeds nothing it watches.</b> A vent in its default redstone mode runs
 * only while powered, and the detector sits one block from the vent's floor, so its signal would
 * otherwise be what keeps the room a zone: the first "off" would let the vent drop the zone, and every
 * later reading would be about the vent rather than the detector. The vent is therefore powered by a
 * redstone block of its own, beneath it, which holds it on whatever the detector says.</p>
 */
public class AtmosphereDetectorWatchesAStatementTest extends AbstractSharedServerTest {

    /** The Y and Z every helper here builds on, from this scenario's own site (see {@link #stand}). */
    private int cy;
    private int cz;

    /** Ask for this scenario's site, prove its volume empty, and answer the X its room is centred on. */
    private int stand(String what) throws Exception {
        FixtureSite site = clearedSite(2, 6, what);
        cy = site.y + 2;
        cz = site.z + 2;
        return site.x + 2;
    }

    /**
     * The DOSE of vent ticks a room is given to finish becoming a zone.
     *
     * <p>Not a budget: a force-ticked tile does not move the world clock, so these ticks are the
     * only thing driving the fill here and the same dose does the same work on any box.</p>
     */
    private static final int SEAL_TICKS = 200;

    /** The air inside the room, one cell above the vent. */
    private int airY() {
        return cy + 1;
    }

    @LegacyWitness(prose = "one inversion per verdict. CONTROL — {@code AtmosphereAssertions#holdsAt} at {@code return !breathable(air, published);} holding"
            + " \"not breathable\" everywhere: \"breathable air must not satisfy \\\"not breathable\\\" (after a forced"
            + " sample): … expected:&lt;false&gt; but was:&lt;true&gt;\", 2026-09-30. UNBREATHABLE — the same"
            + " line never holding: \"air nobody can breathe must satisfy it (after a forced sample): …"
            + " expected:&lt;true&gt; but was:&lt;false&gt;\", 2026-09-30. OFF AGAIN — {@code TileAtmosphereDetector#statementHolds} at {@code AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world.provider.getDimension());} ({@code statementHolds}) latching true for a statement once it has"
            + " held: \"and refilling the room must switch it off again (after a forced sample): …"
            + " expected:&lt;false&gt; but was:&lt;true&gt;\", 2026-09-30. The sealed-zone premises are"
            + " arrangements and are not witnessed.")
    @Test
    public void theDetectorFollowsItsStatementAsTheAirChanges() throws Exception {
        int cx = stand("a sealed room with a detector in its wall watching \"not breathable\"");
        buildRoom(cx);
        placeDetector(cx);
        sealRoom(cx);
        setMode(cx, "NOT_BREATHABLE");

        // Ordinary air: the statement is false, so nothing is emitted. This is the control — a
        // detector stuck on would satisfy the interesting half below without watching anything.
        setAir(cx, 790_000, 210_000, 0);
        assertPowered(cx, false, "breathable air must not satisfy \"not breathable\"");

        // Breathe most of it away. Now the statement holds, and the detector must say so.
        setAir(cx, 790_000, 50_000, 160_000);
        assertPowered(cx, true, "air nobody can breathe must satisfy it");

        // And it must come back DOWN: a latch would pass everything above and still be broken.
        setAir(cx, 790_000, 210_000, 0);
        assertPowered(cx, false, "and refilling the room must switch it off again");
    }

    @LegacyWitness(prose = "with {@code AtmosphereAssertions#holdsAt} at {@code return air != null && air.isToxic();} calling any zone air toxic: \"clean air"
            + " must not read as poisonous (after a forced sample): … expected:&lt;false&gt; but"
            + " was:&lt;true&gt;\", 2026-09-30. POISONOUS — the same line never holding: \"a room can be"
            + " breathable and poisonous at once, and the detector must be able to wire the second (after a"
            + " forced sample): … expected:&lt;true&gt; but was:&lt;false&gt;\", 2026-09-30. The sealed-zone"
            + " premises are arrangements and are not witnessed.")
    @Test
    public void theDetectorCanWatchForSomethingNoNamedAtmosphereEverSaid() throws Exception {
        int cx = stand("a sealed room with a detector in its wall watching \"toxic\"");
        buildRoom(cx);
        placeDetector(cx);
        sealRoom(cx);
        setMode(cx, "TOXIC");

        // Perfectly breathable air — every named atmosphere in the old list would have called this
        // room fine, because none of them had anything to say about poison.
        setAir(cx, 790_000, 210_000, 0);
        assertPowered(cx, false, "clean air must not read as poisonous");

        // Now put carbon monoxide in it, well past its own exposure limit, and change nothing else.
        // The room is still breathable by the oxygen band; it is simply also poisonous.
        setAir(cx, 790_000, 210_000, 0, "carbonmonoxide=" + ppm(1_000));
        assertPowered(cx, true, "a room can be breathable and poisonous at once, and the"
                + " detector must be able to wire the second");
    }

    // ─── the rig ───────────────────────────────────────────────────────

    private void buildRoom(int cx) throws Exception {
        int by = cy;
        // Fresh territory: the seal is a flood fill over real blocks, and an unloaded chunk is not
        // one. Warmed first so that a room failing to seal means something about the room.
        arrange("stellurgytest chunk warmup 0 " + ((cx - 4) >> 4) + " " + ((cz - 4) >> 4)
                + " " + ((cx + 4) >> 4) + " " + ((cz + 4) >> 4)).requireOk();
        arrange("stellurgytest fill 0 " + (cx - 2) + " " + (by - 1) + " " + (cz - 2)
                + " " + (cx + 2) + " " + by + " " + (cz + 2) + " minecraft:stone").requireOk();
        for (int yy = by + 1; yy <= by + 2; yy++) {
            arrange("stellurgytest fill 0 " + (cx - 2) + " " + yy + " " + (cz - 2)
                    + " " + (cx + 2) + " " + yy + " " + (cz + 2) + " minecraft:stone").requireOk();
            arrange("stellurgytest fill 0 " + (cx - 1) + " " + yy + " " + (cz - 1)
                    + " " + (cx + 1) + " " + yy + " " + (cz + 1) + " minecraft:air").requireOk();
        }
        arrange("stellurgytest fill 0 " + (cx - 2) + " " + (by + 3) + " " + (cz - 2)
                + " " + (cx + 2) + " " + (by + 3) + " " + (cz + 2) + " minecraft:stone").requireOk();
    }

    /**
     * Sealed LAST, after the detector is already part of the wall. Sealing first and then punching a
     * block into the volume leaves the zone covering a different set of cells than the test thinks —
     * which is how this test first failed, silently, by sampling a position in no zone at all.
     */
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advance(client(), GameTicks.server(), SEAL_TICKS);",
            why = "SEAL_TICKS is a DOSE of the SERVER'S OWN clock, not a budget for this box to be quick. The fill"
                    + " that makes a room a zone is paced by world time, and a force-ticked tile does not move"
                    + " world time — which is why ticking the vent alone never seals it, and why an earlier"
                    + " version of this that force-ticked in a poll loop only worked by accident, on the real"
                    + " seconds that elapsed between its polls. The clock is advanced explicitly instead, so"
                    + " the same dose does the same work everywhere, and the state is read ONCE.")
    private void sealRoom(int cx) throws Exception {
        // The vent's own power, under its floor and out of the room, so the detector's signal — which
        // reaches the vent through the floor block beneath the detector — is never the thing that
        // decides whether the room is a zone. See the class note.
        arrange("stellurgytest fill 0 " + cx + " " + (cy - 1) + " " + cz
                + " " + cx + " " + (cy - 1) + " " + cz + " minecraft:redstone_block").requireOk();
        arrange("stellurgytest place 0 " + cx + " " + cy + " " + cz + " stellurgy:oxygenVent").requireOk();
        arrange("stellurgytest energy inject 0 " + cx + " " + cy + " " + cz + " 1000000");
        GameTicks.advance(client(), GameTicks.server(), SEAL_TICKS);
        Reply air = read("stellurgytest atmosphere get 0 " + cx + " " + airY() + " " + cz);
        assertTrue("premise: the room must actually be a sealed zone with gases in it: " + air,
                air.has("gases"));
    }

    /** Part of the room's shell, with air on the inward side for it to read. */
    private void placeDetector(int cx) throws Exception {
        arrange("stellurgytest place 0 " + (cx + 1) + " " + airY() + " " + cz
                + " stellurgy:oxygenDetection").requireOk();
    }

    private void setMode(int cx, String assertion) throws Exception {
        arrange("stellurgytest atmosphere detector-set-mode 0 " + (cx + 1) + " " + airY()
                + " " + cz + " " + assertion).requireOk();
    }

    private void setAir(int cx, int n2Ppm, int o2Ppm, int co2Ppm, String... extraGases)
            throws Exception {
        StringBuilder cmd = new StringBuilder("stellurgytest vent setair 0 " + cx + " " + cy + " " + cz
                + " " + ppm(n2Ppm) + " " + ppm(o2Ppm) + " " + ppm(co2Ppm));
        for (String gas : extraGases) {
            cmd.append(' ').append(gas);
        }
        arrange(cmd.toString());
    }

    private Reply output(int cx) throws Exception {
        return read("stellurgytest atmosphere detector-output 0 " + (cx + 1) + " " + airY() + " " + cz);
    }

    /**
     * The detector reads the air on its own schedule, so the answer is asserted after a stated
     * amount of the detector's OWN world — never the instant the air changed. An immediate reading
     * passes or fails on how long the previous command happened to take, which is how this test
     * first went green by accident: two diagnostic calls added to a failure message ran BEFORE the
     * comparison and supplied exactly the delay it needed.
     */
    private void assertPowered(int cx, boolean expected, String why) throws Exception {
        // The air the detector is about to judge must still be the ROOM's: a position that has
        // dropped out of the zone reads the overworld's open air, and a verdict on that is a verdict
        // about whatever unsealed the room.
        Reply air = read("stellurgytest atmosphere get 0 " + cx + " " + airY() + " " + cz);
        requireArranged("the room must still be a sealed zone when the detector is asked about it: "
                + air, air.has("gases"));
        // MADE to sample, not waited for. Production gates the detector's sample on
        // `world.getTotalWorldTime() % 10`, so what it reports is whatever it decided the last time that
        // gate happened to fire — which, right after the air was changed, is about the OLD air.
        // `detector-force-sample` runs the same sample loop and the same setState production runs,
        // once, on demand. One call, one read, and no budget anywhere: the answer does not depend
        // on how fast this box is.
        arrange("stellurgytest atmosphere detector-force-sample 0 "
                + (cx + 1) + " " + airY() + " " + cz).requireOk();
        Reply out = output(cx);
        assertEquals(why + " (after a forced sample): " + out, expected, out.bool("powered"));
    }

    /**
     * The world ticks this many times while the detector is watched: long enough for at least three openings of a
     * gate whose period is up to a quarter of it, which is the evidence an even spacing needs.
     */
    private static final int WATCHED_TICKS = 100;

    /**
     * C16 CLOCK-9 on the detector: its sampling gate counts the world's TOTAL time, so on a world whose time of day
     * stands still — the overworld's body locked to its star for the window — it still samples, at one even spacing
     * of total time. A gate on the time of day would there open never, or on every tick.
     */
    @Pins("CLOCK-9")
    @RedWitness(verdict = "C16 CLOCK-9", member = "TileAtmosphereDetector#update",
            at = "if (!world.isRemote && world.getTotalWorldTime() % 10 == 0) {",
            replacedBy = "if (!world.isRemote && world.getWorldTime() % 10 == 0) {",
            fails = "at one even spacing of total time longer than a tick", on = "2026-10-26")
    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advanceWorld(client(), 0, WATCHED_TICKS);",
            why = "the measurement is the spacing of the gate's openings over an interval of the world's own ticks;"
                    + " no single record says a gate opens evenly, only the openings recorded across the window do")
    @Test
    public void theDetectorSamplesOnTotalTimeWhereTheTimeOfDayStandsStill() throws Exception {
        FixtureSite site = clearedSite(0, 2, "a lone detector on a world whose time of day stands still");
        int x = site.x;
        int y = site.y + 1;
        int z = site.z;
        arrange("stellurgytest place 0 " + x + " " + y + " " + z + " stellurgy:oxygenDetection").requireOk();
        arrange("stellurgytest chunk forceload 0 " + (x >> 4) + " " + (z >> 4)).requireOk();
        arrange("stellurgytest arrange tidal-lock 0 true").requireOk();
        try {
            Events events = new Events(command -> String.join("\n", client().read(command)),
                    ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());
            long mark = events.markInstrumented();
            GameTicks.advanceWorld(client(), 0, WATCHED_TICKS);
            String reply = events.since(mark, "periodic_gate_opened");
            Events.assertInstrumentRan(reply, "periodic_gate_events", "the detector's update must be watched before"
                    + " an absent opening can mean its gate stayed shut");
            PeriodicGateWindow window = PeriodicGateWindow.of(reply, "atmosphere_detector", 0, x, y, z);
            requireArranged("the overworld's time of day must stand still while its body is locked: " + window,
                    window.timeOfDayStoodStill());
            assertTrue("C16 CLOCK-9: on a world whose time of day stands still the detector's gate still opens, at"
                            + " one even spacing of total time longer than a tick; openings: " + window,
                    window.openings.size() >= 3 && window.evenSpacing() > 1);
        } finally {
            arrange("stellurgytest arrange tidal-lock 0 false").requireOk();
            arrange("stellurgytest chunk release 0 " + (x >> 4) + " " + (z >> 4)).requireOk();
            arrange("stellurgytest place 0 " + x + " " + y + " " + z + " minecraft:air").requireOk();
        }
    }
}
