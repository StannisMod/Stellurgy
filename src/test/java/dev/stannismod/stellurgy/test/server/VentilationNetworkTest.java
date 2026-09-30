package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.StellurgyTestConstants.ppm;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.arrange;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.ask;

/**
 * Tier 4: a central plant regenerating a room it does not stand in, through ducts.
 *
 * <p>This is also the subsystem-network primitive's first test as a PRIMITIVE. Until ventilation
 * existed the solver was exercised only through the shield domain, so "domains do not merge" was an
 * assumption about code nobody had run twice. The second scenario here is that assumption made
 * falsifiable: it swaps one duct for a shield cable and requires the air to stop moving.</p>
 */
public class VentilationNetworkTest extends AbstractSharedServerTest {

    /** The Y and Z every helper here builds on, from this scenario's own site (see {@link #stand}). */
    private int cy;
    private int cz;

    /** The second room of the priority scenario stands this far along from the first. */
    private static final int SECOND_ROOM_OFFSET = 8;

    /**
     * Ask for this scenario's site, prove its volume empty, and answer the X its (first) room is
     * centred on. Wide enough for two rooms side by side with a duct run under both.
     */
    private int stand(String what) throws Exception {
        FixtureSite site = clearedSite(8, 6, what);
        cy = site.y + 2;
        cz = site.z + 2;
        return site.x + 2;
    }

    /**
     * Regeneration arrives from three blocks away, over ducts the plant never has to know about.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. CLEARS — {@code TileOxygenVent:483}
     * regenerating nothing: "the plant must clear the room's CO2 through the ducts (removed 0)". ONE
     * FOR ONE — the same method drawing back half the oxygen it returned: "and every unit of CO2 it
     * took must come back to the room as oxygen". DUST IN THE PLANT — {@code TileLifeSupportPlant:116}
     * no longer turning the carbon into dust: "`slots` holds no element whose `item` is
     * stellurgy:carbondust — it holds 0". The three network premises are arrangements and are not
     * witnessed.</p>
     */
    @Test
    public void aCentralPlantRegeneratesARoomItDoesNotStandIn() throws Exception {
        int cxPlant = stand("a stale room ducted to a plant that stands outside it");
        buildStaleRoom(cxPlant);
        // Sea-level oxygen on top of the carbon dioxide. The room's own vent tops oxygen up only
        // while it is BELOW sea level (`TileOxygenVent.replenishOxygen`: `missing` is the gap to
        // `AirState.earthLike()`), so from here on every unit of oxygen that appears is the plant's —
        // which is what lets the oxygen coming back be read exactly instead of as a floor.
        arrange("stellurgytest vent setair 0 " + cxPlant + " " + cy + " " + cz
                + " " + ppm(640_000) + " " + ppm(210_000) + " " + ppm(150_000));

        // Duct run leaving the vent, then the plant at the far end: the plant touches no zone.
        placeDuct(cxPlant + 1);
        placeDuct(cxPlant + 2);
        placeDuct(cxPlant + 3);
        placePlant(cxPlant + 4);
        injectEnergyAt(cxPlant + 4, 1_000_000);

        Reply net = subnetInfo(cxPlant + 2);
        assertEquals("premise: the vent must have joined the ventilation network as its zone's sink: "
                + net, 1, net.integer("sinks"));
        assertEquals("premise: the plant must be its source: " + net, 1, net.integer("sources"));
        assertEquals("premise: three ducts between them: " + net, 3, net.integer("cables"));

        // Drive the network's own tick. Waiting on wall-clock does NOT work here: a probe runs on
        // the server thread and holds the tick loop while it waits, so 300 ticks of waiting bought
        // four solves. Long enough for a whole dust: the DUCT is the bottleneck by design (6000 a
        // tick against the plant's 12000), so carbon accrues at the rate the pipe allows.
        // The baseline is taken HERE: the network also solves on the world's own ticks, so the air
        // has already moved by however long the setup took.
        Reply before = ventInfo(cxPlant);
        solve(300);

        Reply after = ventInfo(cxPlant);
        long co2Removed = before.longInteger("airCO2") - after.longInteger("airCO2");
        long o2Returned = after.longInteger("airO2") - before.longInteger("airO2");
        assertTrue("the plant must clear the room's CO2 through the ducts (removed " + co2Removed
                + "): " + before + " → " + after, co2Removed > 0);
        // EXACT: regeneration turns carbon dioxide into oxygen one for one (`AirState.regenerate`),
        // and nothing else adds or takes oxygen here — no crew, and a vent that tops up only below
        // sea level. A plant that kept the carbon and voided the oxygen fails this, where a floor
        // on the oxygen did not.
        assertEquals("and every unit of CO2 it took must come back to the room as oxygen: " + before
                + " → " + after, co2Removed, o2Returned);

        Reply slot = ask("stellurgytest hatch read 0 " + (cxPlant + 4) + " " + cy + " " + cz);
        assertTrue("the carbon it took out of that room must appear in the PLANT's slot, not the "
                + "room's: " + slot + " | air=" + after + " | network=" + subnetInfo(cxPlant + 2),
                slot.element("slots", "item", "stellurgy:carbondust").integer("count") >= 1);
    }

    /**
     * INV-NET-01, made falsifiable. The same layout with the middle duct replaced by a shield cable:
     * the two subsystems are laid through one another and must not conduct for each other. The test
     * above is this one's positive control — without it, "no source on the vent's side" would also be
     * what a rig whose ducts never joined anything looks like.
     *
     * <p>{@code subnet info} answers {@code sources:0} for a position in no network at all, so the
     * vent's half is first required to BE a network — a sink with its duct — before "no source on its
     * side" says anything about the cable.</p>
     *
     * <p>red-witnessed: with {@code SubsystemNetworkManager:134} letting other domains' cables into
     * the life-support graph: "the vent's ventilation network must end at the shield cable, with no
     * source on its side: … \"sources\":1", 2026-09-30. The sink premise is an arrangement and is not
     * witnessed.</p>
     *
     * <p>Not asserted: the room's carbon dioxide and oxygen after a solve, because with no source on
     * the vent's network nothing can regenerate the room or draw from it through the network, so
     * neither reading could go red while the verdict above holds.</p>
     */
    @Test
    public void aShieldCableIsNotADuctAndCarriesNoAir() throws Exception {
        int cxIsolation = stand("a stale room whose duct run is broken by a shield cable");
        buildStaleRoom(cxIsolation);

        placeDuct(cxIsolation + 1);
        place(cxIsolation + 2, cy, "affs:shield_cable");
        placeDuct(cxIsolation + 3);
        placePlant(cxIsolation + 4);
        injectEnergyAt(cxIsolation + 4, 1_000_000);

        Reply net = subnetInfo(cxIsolation + 1);
        assertEquals("premise: the vent's side must be a network of its own, with the vent as its "
                + "sink: " + net, 1, net.integer("sinks"));
        assertEquals("the vent's ventilation network must end at the shield cable, with no source "
                + "on its side: " + net, 0, net.integer("sources"));
    }

    /**
     * The ratified priority mechanic (maintainer, 2026-08-15: assignment through the vent's own
     * screen, every zone equal by default). Two rooms on one plant, and deliberately less supply
     * than either of them alone could absorb: under a real deficit the high-priority room must be
     * served and the normal one must not, rather than both getting half.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. SERVED — {@code
     * SubsystemNetworkManager:384} negating each sink's priority: "the prioritised room must be
     * served (before=149526317 after=149526317)". NOT A SHARE — a pass before the priority tiers
     * ({@code SubsystemNetworkManager:407}) that opens every sink to a small fixed share: "and under a
     * deficit the normal-priority room must get nothing, not a share: … \"airCO2\":133579125".</p>
     */
    @Test
    public void underADeficitTheHigherPriorityZoneIsServedFirst() throws Exception {
        int roomA = stand("two stale rooms on one plant, one of them prioritised");
        int roomB = roomA + SECOND_ROOM_OFFSET;
        String plantRateBefore = arrange("stellurgytest config get lifeSupportPlantRate").text("value");
        try {
            buildStaleRoom(roomA);
            buildStaleRoom(roomB);

            // Duct the two vents together UNDER the floor, so the run never touches either sealed
            // volume, with the plant in the middle of it.
            for (int x = roomA; x <= roomB; x++) {
                if (x == roomA + 4) {
                    place(x, cy - 1, "stellurgy:lifeSupportPlant");
                } else {
                    place(x, cy - 1, "stellurgy:ventilationDuct");
                }
            }
            injectEnergyAt(roomA + 4, cy - 1, 1_000_000);

            // Less than one room can take: 3000 a tick against a duct that would pass 6000.
            arrange("stellurgytest config set lifeSupportPlantRate 60000");

            Reply high = arrange("stellurgytest vent priority 0 " + roomA + " " + cy + " " + cz + " 1");
            assertEquals("priority set failed: " + high, 1, high.integer("priority"));

            // Measure from a snapshot taken HERE, not from the value setair wrote: the server ticks
            // between commands and solves the network as it goes, so anything asserted against the
            // authored figure is really asserting how long the setup took.
            long baseA = ventInfo(roomA).longInteger("airCO2");
            long baseB = ventInfo(roomB).longInteger("airCO2");

            solve(300);

            Reply a = ventInfo(roomA);
            Reply b = ventInfo(roomB);
            assertTrue("the prioritised room must be served (before=" + baseA + " after="
                    + a.longInteger("airCO2") + "): " + a, a.longInteger("airCO2") < baseA);
            assertEquals("and under a deficit the normal-priority room must get nothing, not a "
                    + "share: " + b, baseB, b.longInteger("airCO2"));
        } finally {
            arrange("stellurgytest config set lifeSupportPlantRate " + plantRateBefore);
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────

    /** A sealed, maintained room whose air has been breathed down. */
    private void buildStaleRoom(int cx) throws Exception {
        arrange("stellurgytest fill 0 " + (cx - 2) + " " + (cy - 1) + " " + (cz - 2)
                + " " + (cx + 2) + " " + cy + " " + (cz + 2) + " minecraft:stone");
        for (int yy = cy + 1; yy <= cy + 2; yy++) {
            arrange("stellurgytest fill 0 " + (cx - 2) + " " + yy + " " + (cz - 2)
                    + " " + (cx + 2) + " " + yy + " " + (cz + 2) + " minecraft:stone");
            arrange("stellurgytest fill 0 " + (cx - 1) + " " + yy + " " + (cz - 1)
                    + " " + (cx + 1) + " " + yy + " " + (cz + 1) + " minecraft:air");
        }
        arrange("stellurgytest fill 0 " + (cx - 2) + " " + (cy + 3) + " " + (cz - 2)
                + " " + (cx + 2) + " " + (cy + 3) + " " + (cz + 2) + " minecraft:stone");

        place(cx, cy, "stellurgy:oxygenVent");
        injectEnergyAt(cx, 1_000_000);
        arrange("stellurgytest fluid inject 0 " + cx + " " + cy + " " + cz + " oxygen 16000");

        arrange("stellurgytest tile force-tick 0 " + cx + " " + cy + " " + cz + " 1");
        arrange("stellurgytest vent reseal 0 " + cx + " " + cy + " " + cz);
        arrange("stellurgytest tile force-tick 0 " + cx + " " + cy + " " + cz + " 5");

        arrange("stellurgytest vent setair 0 " + cx + " " + cy + " " + cz
                + " " + ppm(790_000) + " " + ppm(60_000) + " " + ppm(150_000));
    }

    private void placeDuct(int x) throws Exception {
        place(x, cy, "stellurgy:ventilationDuct");
    }

    private void placePlant(int x) throws Exception {
        place(x, cy, "stellurgy:lifeSupportPlant");
    }

    private void place(int x, int y, String block) throws Exception {
        Reply resp = arrange("stellurgytest place 0 " + x + " " + y + " " + cz + " " + block);
        assertTrue(block + " place failed at " + x + "," + y + ": " + resp, resp.bool("placed"));
    }

    private void injectEnergyAt(int x, int amount) throws Exception {
        injectEnergyAt(x, cy, amount);
    }

    private void injectEnergyAt(int x, int y, int amount) throws Exception {
        arrange("stellurgytest energy inject 0 " + x + " " + y + " " + cz + " " + amount);
    }

    private void solve(int ticks) throws Exception {
        Reply solved = arrange("stellurgytest subnet solve lifesupport 0 " + ticks);
        assertEquals("solve failed: " + solved, ticks, solved.integer("ticksSolved"));
    }

    private Reply ventInfo(int cx) throws Exception {
        return ask("stellurgytest vent info 0 " + cx + " " + cy + " " + cz);
    }

    private Reply subnetInfo(int x) throws Exception {
        return ask("stellurgytest subnet info lifesupport 0 " + x + " " + cy + " " + cz);
    }
}
