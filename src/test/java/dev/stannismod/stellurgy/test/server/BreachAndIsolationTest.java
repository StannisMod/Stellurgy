package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static dev.stannismod.stellurgy.test.StellurgyTestConstants.ppm;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.arrange;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.ask;

/**
 * What happens when a hull is opened, and what the ship can do about it (D127-9 / D127-6).
 *
 * <p>The three scenarios are one mechanic seen from three sides. A breach must cost the ship its
 * air — <em>lose</em> it, not delete it, which is what happened before: the flood-fill dropped the
 * room's cells and the gases went with them in the same tick, so a hull breach was free. A breached
 * zone must also stop drawing from the plant, or the ship answers a hole by pumping its reserves
 * into space. And closing a bulkhead must actually divide the ship, or "isolate the section before
 * you patch it" is not a move a player can make.</p>
 */
public class BreachAndIsolationTest extends AbstractSharedServerTest {

    /** The Y and Z every helper here builds on, from this scenario's own site (see {@link #stand}). */
    private int cy;
    private int cz;

    /**
     * Ask for this scenario's site, prove its volume empty, and answer the X its first room is
     * centred on. The longest fixture here is the bulkhead hall, eleven blocks from its west wall to
     * its east one; everything stands five blocks up from one above the site.
     */
    private int stand(String what) throws Exception {
        FixtureSite site = clearedSite(8, 6, what);
        cy = site.y + 2;
        cz = site.z + 2;
        return site.x + 2;
    }

    /**
     * D127-9: the air leaves through the hole, over seconds, and it is really gone.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. NO LONGER A ZONE — {@code
     * AtmosphereBlob:221} ({@code clearBlob}) no longer emptying the graph: "a breached room is no
     * longer a zone — its cells are gone: … expected:&lt;0&gt; but was:&lt;19&gt;". IN NO ZONE —
     * {@code AtmosphereHandler:333} also answering the zone of a vent standing next to the
     * position: "the position is in no zone any more — that is what the breach did: …
     * \"airSource\":\"zone\"". STILL REACHABLE — {@code AtmosphereHandler:740} handing back no air
     * for a zone with no cells: "but the air must still be reachable from the VENT … \"ventHasAir\":false".
     * LEAVES — {@code TileOxygenVent:616} venting nothing: "the air must actually leave (before=100
     * after=100)". UNTIL VACUUM — {@code TileOxygenVent:613} stopping at half an atmosphere: "and keep
     * leaving until the room is vacuum: … expected:&lt;0&gt; but was:&lt;49&gt;". The two premises
     * at its head are arrangements and are not witnessed.</p>
     */
    @Test
    public void aBreachedRoomLosesItsAirToSpaceInsteadOfLosingItToBookkeeping() throws Exception {
        int cx = stand("a sealed room about to be breached");
        buildSealedRoom(cx);

        Reply sealed = ventInfo(cx);
        assertEquals("premise: the room must start as a live zone: " + sealed,
                "zone", sealed.text("airSource"));
        long pressureBefore = sealed.longInteger("ventAirPressure");
        assertTrue("premise: it must start pressurised: " + sealed, pressureBefore > 0);

        // Open the hull. The flood-fill drops the room's cells; the gases are not the cells.
        breach(cx);
        forceTick(cx, 5);

        Reply venting = ventInfo(cx);
        assertEquals("a breached room is no longer a zone — its cells are gone: " + venting,
                0, venting.integer("blobSize"));
        assertEquals("the position is in no zone any more — that is what the breach did: " + venting,
                "none", venting.text("airSource"));
        assertTrue("but the air must still be reachable from the VENT, because it is escaping "
                + "rather than being deleted: " + venting, venting.bool("ventHasAir"));

        // 20 ticks per drain step; well past what emptying takes at the default rate.
        forceTick(cx, 600);

        Reply emptied = ventInfo(cx);
        long pressureAfter = emptied.longInteger("ventAirPressure");
        assertTrue("the air must actually leave (before=" + pressureBefore + " after="
                + pressureAfter + "): " + emptied, pressureAfter < pressureBefore);
        assertEquals("and keep leaving until the room is vacuum: " + emptied, 0L, pressureAfter);
    }

    /**
     * D127-6, the automatic half, under the 2026-08-15 ruling that "the plant cuts a zone it cannot
     * keep safe" means disconnection from the network. A breached zone must stop asking, or the
     * plant spends the ship's reserves on a room that is open to space.
     *
     * <p>The probe answers {@code sinkRequested: 0} for a position that is in NO network at all, so
     * "requests nothing" is only a reading about the breached zone once {@code inNetwork} says the
     * vent is still a node — without it, a breach that tore the network apart would pass here.</p>
     *
     * <p>red-witnessed: with {@code TileOxygenVent:496} no longer withholding the zone's air from the
     * network once the vent stops maintaining it: "a breached zone must stop asking the plant for air
     * … \"sinkRequested\":124105", 2026-09-30. The two premises at its head are arrangements and are
     * not witnessed. The {@code inNetwork} verdict is not witnessed: it is read at the duct, so it
     * stays true after the vent itself has left the network.</p>
     */
    @Test
    public void aBreachedZoneStopsDrawingFromThePlant() throws Exception {
        int cx = stand("a sealed room on a ventilation network, about to be breached");
        buildSealedRoom(cx);
        placeDuct(cx + 1);
        placePlant(cx + 2);
        arrange("stellurgytest energy inject 0 " + (cx + 2) + " " + cy + " " + cz + " 1000000");
        // A vent asks its network for exactly its zone's carbon dioxide
        // (`TileOxygenVent.getRequested`), so a room of fresh air asks for nothing whether it is
        // sealed or open to space. Breathed-down air is what makes "it stopped asking" a reading.
        arrange("stellurgytest vent setair 0 " + cx + " " + cy + " " + cz
                + " " + ppm(790_000) + " " + ppm(60_000) + " " + ppm(150_000));
        arrange("stellurgytest subnet solve lifesupport 0 2");

        Reply connected = subnetInfo(cx + 1);
        assertEquals("premise: a sealed room must be a sink on the ventilation network: " + connected,
                1, connected.integer("sinks"));
        assertTrue("premise: and, holding stale air, it must be asking the plant for some: " + connected,
                connected.longInteger("sinkRequested") > 0);

        breach(cx);
        forceTick(cx, 5);
        arrange("stellurgytest subnet solve lifesupport 0 2");

        Reply afterBreach = subnetInfo(cx + 1);
        assertTrue("the vent must still be a node on the network after the breach: " + afterBreach,
                afterBreach.bool("inNetwork"));
        assertEquals("a breached zone must stop asking the plant for air — the vent is still a node, "
                + "but it requests nothing: " + afterBreach, 0L, afterBreach.longInteger("sinkRequested"));
    }

    /**
     * D127-6, the manual half. This one asserts machinery that already existed rather than anything
     * added for life support: a closed airlock door counts as a sealing block, so it divides a hull
     * into separately-maintained volumes. Pinned because the whole isolation story rests on it, and
     * nothing said so.
     *
     * <p>red-witnessed: with {@code SealableBlockHandler:139} answering an airlock door as never
     * sealed: "a closed bulkhead must divide the hall … (51 → 51)", 2026-09-30. The two readings
     * before it (the open hall, the hall through the doorway) are arrangements and are not
     * witnessed.</p>
     */
    @Test
    public void aClosedBulkheadDividesTheHullIntoTwoZones() throws Exception {
        int west = stand("one long hall about to be divided by a bulkhead");
        int east = west + 6;

        // One long hall, a vent at each end, and a doorway between them.
        arrange("stellurgytest fill 0 " + (west - 2) + " " + (cy - 1) + " " + (cz - 2)
                + " " + (east + 2) + " " + cy + " " + (cz + 2) + " minecraft:stone");
        for (int yy = cy + 1; yy <= cy + 2; yy++) {
            arrange("stellurgytest fill 0 " + (west - 2) + " " + yy + " " + (cz - 2)
                    + " " + (east + 2) + " " + yy + " " + (cz + 2) + " minecraft:stone");
            arrange("stellurgytest fill 0 " + (west - 1) + " " + yy + " " + (cz - 1)
                    + " " + (east + 1) + " " + yy + " " + (cz + 1) + " minecraft:air");
        }
        arrange("stellurgytest fill 0 " + (west - 2) + " " + (cy + 3) + " " + (cz - 2)
                + " " + (east + 2) + " " + (cy + 3) + " " + (cz + 2) + " minecraft:stone");

        commissionVent(west);
        commissionVent(east);

        Reply open = ventInfo(west);
        int openHall = open.integer("blobSize");
        assertTrue("premise: one open hall must be one large zone: " + open, openHall > 0);

        // Wall the middle off, leaving one doorway OPEN, and measure the hall through it. That is
        // the control: the wall alone takes four cells out of the air and divides nothing, so the
        // bulkhead is judged against THIS figure — against the open hall, the wall's own stone would
        // pass the comparison with no door at all.
        int wall = (west + east) / 2;
        arrange("stellurgytest fill 0 " + wall + " " + (cy + 1) + " " + (cz - 1)
                + " " + wall + " " + (cy + 2) + " " + (cz - 1) + " minecraft:stone");
        arrange("stellurgytest fill 0 " + wall + " " + (cy + 1) + " " + (cz + 1)
                + " " + wall + " " + (cy + 2) + " " + (cz + 1) + " minecraft:stone");
        arrange("stellurgytest vent reseal 0 " + west + " " + cy + " " + cz);
        forceTick(west, 5);
        Reply gapped = ventInfo(west);
        int throughTheDoorway = gapped.integer("blobSize");
        requireArranged("premise: with the doorway open the hall must still be ONE zone, short only "
                        + "the wall's four cells (" + openHall + " → " + throughTheDoorway + "): " + gapped,
                throughTheDoorway == openHall - 4);

        // Now close a bulkhead in the doorway.
        arrange("stellurgytest fill 0 " + wall + " " + (cy + 1) + " " + cz
                + " " + wall + " " + (cy + 2) + " " + cz + " stellurgy:airlock_door");
        arrange("stellurgytest vent reseal 0 " + west + " " + cy + " " + cz);
        forceTick(west, 5);

        // A door that did NOT seal leaves one zone that has lost at most the door's own two cells;
        // anything smaller than that is the hall actually cut in two.
        Reply divided = ventInfo(west);
        int westZone = divided.integer("blobSize");
        assertTrue("a closed bulkhead must divide the hall — the west zone must be smaller than the "
                        + "hall through the open doorway was (" + throughTheDoorway + " → " + westZone
                        + "): " + divided,
                westZone > 0 && westZone < throughTheDoorway - 2);
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private void buildSealedRoom(int cx) throws Exception {
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
        commissionVent(cx);
    }

    private void commissionVent(int cx) throws Exception {
        place(cx, "stellurgy:oxygenVent");
        arrange("stellurgytest energy inject 0 " + cx + " " + cy + " " + cz + " 1000000");
        arrange("stellurgytest fluid inject 0 " + cx + " " + cy + " " + cz + " oxygen 16000");
        forceTick(cx, 1);
        arrange("stellurgytest vent reseal 0 " + cx + " " + cy + " " + cz);
        forceTick(cx, 5);
    }

    /** Open the ceiling. One block is a hull breach. */
    private void breach(int cx) throws Exception {
        arrange("stellurgytest fill 0 " + cx + " " + (cy + 3) + " " + cz + " "
                + cx + " " + (cy + 3) + " " + cz + " minecraft:air");
        arrange("stellurgytest vent reseal 0 " + cx + " " + cy + " " + cz);
    }

    private void placeDuct(int x) throws Exception {
        place(x, "stellurgy:ventilationDuct");
    }

    private void placePlant(int x) throws Exception {
        place(x, "stellurgy:lifeSupportPlant");
    }

    private void place(int x, String block) throws Exception {
        Reply resp = arrange("stellurgytest place 0 " + x + " " + cy + " " + cz + " " + block);
        assertTrue(block + " place failed: " + resp, resp.bool("placed"));
    }

    private void forceTick(int cx, int ticks) throws Exception {
        arrange("stellurgytest tile force-tick 0 " + cx + " " + cy + " " + cz + " " + ticks);
    }

    private Reply ventInfo(int cx) throws Exception {
        return ask("stellurgytest vent info 0 " + cx + " " + cy + " " + cz);
    }

    private Reply subnetInfo(int x) throws Exception {
        return ask("stellurgytest subnet info lifesupport 0 " + x + " " + cy + " " + cz);
    }
}
