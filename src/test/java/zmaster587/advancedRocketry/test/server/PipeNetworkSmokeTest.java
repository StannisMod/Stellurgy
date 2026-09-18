package zmaster587.advancedRocketry.test.server;

// migrated to AbstractSharedServerTest
import zmaster587.advancedRocketry.test.EnergyStore;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * energy / data / fluid network transport.
 *
 * Validates the Forge {@code IEnergyStorage} contract on
 * {@code libvulpes:forgepowerinput} — the foundation every pipe network proxies
 * through.
 */
public class PipeNetworkSmokeTest extends AbstractSharedServerTest {

    private static final String ACCEPTED = "accepted";
    private static final String INJ_STORED = "stored";

    @Test
    public void forgeEnergyStorageContractMatches() throws Exception {
        // LEFT RAW: the subject of this line IS the error shape, which `EnergyStore` refuses.
        String empty = String.join("\n", client().execute("artest energy stored 0 1200 64 1200"));
        assertTrue("expected 'no tile entity': " + empty, empty.contains("\"no tile entity\""));

        String place = String.join("\n", client().execute(
                "artest place 0 1200 64 1200 libvulpes:forgepowerinput"));
        assertTrue("could not place libvulpes:forgepowerinput: " + place,
                Reply.of(place).bool("placed", false));

        EnergyStore initial = EnergyStore.at(
                        cmd -> String.join("\n", client().execute(cmd)), 0, 1200, 64, 1200)
                .requireEnergy("placed block missing IEnergyStorage");
        long storedInit = initial.stored();
        long capacity = initial.capacity();
        assertTrue("placed block capacity unreasonable: " + initial.raw(), capacity > 0L);

        String inj1 = String.join("\n",
                client().execute("artest energy inject 0 1200 64 1200 5000"));
        assertTrue("inject 5000 failed: " + inj1, Reply.of(inj1).ok());
        long accepted1 = parseLong(ACCEPTED, inj1);
        long expectedAccept1 = Math.min(5000L, capacity - storedInit);
        assertEquals("accepted ≠ expected: " + inj1, expectedAccept1, accepted1);
        long storedAfter1 = parseLong(INJ_STORED, inj1);
        assertEquals("stored did not advance correctly: " + inj1,
                storedInit + accepted1, storedAfter1);

        String inj2 = String.join("\n", client().execute(
                "artest energy inject 0 1200 64 1200 " + capacity));
        long accepted2 = parseLong(ACCEPTED, inj2);
        long storedAfter2 = parseLong(INJ_STORED, inj2);
        assertEquals("battery not at cap after overflow: " + inj2, capacity, storedAfter2);
        assertEquals("overflow accepted wrong: " + inj2,
                capacity - storedAfter1, accepted2);

        String inj3 = String.join("\n", client().execute(
                "artest energy inject 0 1200 64 1200 1000 true"));
        long accepted3 = parseLong(ACCEPTED, inj3);
        long storedAfter3 = parseLong(INJ_STORED, inj3);
        assertEquals("simulate=true mutated stored: " + inj3, capacity, storedAfter3);
        assertEquals("simulate at-cap accepted should be 0: " + inj3, 0L, accepted3);
    }

    /**
     * wireless transceiver pairing. Place two transceivers
     * 50 blocks apart, pair them via the probe (mirrors the player-side
     * linker-item flow), and confirm both end up on the same
     * {@code networkID}.
     */
    @Test
    public void wirelessTransceiverPairsAndTransmits() throws Exception {
        int x1 = 1300, x2 = 1350, y = 65, z = 1200;
        ok(client().execute(
                "artest place 0 " + x1 + " " + y + " " + z + " advancedrocketry:wirelessTransciever"));
        ok(client().execute(
                "artest place 0 " + x2 + " " + y + " " + z + " advancedrocketry:wirelessTransciever"));

        // Pre-pairing — each transceiver carries the default sentinel.
        String pre1 = String.join("\n", client().execute(
                "artest pipe wireless-info 0 " + x1 + " " + y + " " + z));
        String pre2 = String.join("\n", client().execute(
                "artest pipe wireless-info 0 " + x2 + " " + y + " " + z));
        assertEquals("transceiver A starts unpaired (networkID=-1): " + pre1,
                -1, extractInt(pre1, "networkID"));
        assertEquals("transceiver B starts unpaired (networkID=-1): " + pre2,
                -1, extractInt(pre2, "networkID"));

        String pair = String.join("\n", client().execute(
                "artest pipe wireless-pair 0 " + x1 + " " + y + " " + z + " "
                        + x2 + " " + y + " " + z));
        assertTrue("wireless-pair probe failed: " + pair, Reply.of(pair).ok());
        int sharedId = extractInt(pair, "sharedNetworkId");
        // NetworkRegistry hashes network IDs and may return negative values;
        // the only invariant we care about is "not the unpaired sentinel".
        assertTrue("shared networkID must be assigned (not -1 sentinel): " + pair,
                sharedId != -1);

        // Post-pairing — both endpoints must report the same networkID.
        String post1 = String.join("\n", client().execute(
                "artest pipe wireless-info 0 " + x1 + " " + y + " " + z));
        String post2 = String.join("\n", client().execute(
                "artest pipe wireless-info 0 " + x2 + " " + y + " " + z));
        assertEquals("A and B must share the same networkID after pairing",
                sharedId, extractInt(post1, "networkID"));
        assertEquals("A and B must share the same networkID after pairing",
                sharedId, extractInt(post2, "networkID"));
    }

    /**
     * inventory hatch accepts items and surfaces them via the
     * standard hatch read probe (same code path libVulpes machines use to
     * iterate input hatches). Round-trips the item through the hatch's
     * IInventory.
     */
    @Test
    public void inventoryHatchAcceptsAndExportsItems() throws Exception {
        int hx = 1400, hy = FixtureSite.OPEN_AIR_Y, hz = 1200;
        ok(client().execute("artest place 0 " + hx + " " + hy + " " + hz
                + " advancedrocketry:invhatch"));

        // Slot 0 — 16 sticks.
        ok(client().execute("artest hatch fill 0 " + hx + " " + hy + " " + hz
                + " 0 minecraft:stick 16 0"));

        String read = String.join("\n", client().execute(
                "artest hatch read 0 " + hx + " " + hy + " " + hz));
        // THE slot this test filled — addressed by its index, then read. Asking whether some slot
        // holds sticks and some slot holds 16 is satisfied by one stick beside sixteen of
        // something else.
        Reply filled = Reply.of("artest hatch read", read).element("slots", "slot", "0");
        assertEquals("slot 0 must hold the deposited sticks: " + read,
                "minecraft:stick", filled.text("item"));
        assertEquals("slot 0 must hold all sixteen of them: " + read, 16, filled.integer("count"));

        // Overwrite slot 0 with a different stack — verify the hatch
        // accepts replacement (export semantics: it can be cleared and
        // re-filled, mirroring how multiblock controllers pull from it).
        ok(client().execute("artest hatch fill 0 " + hx + " " + hy + " " + hz
                + " 0 minecraft:cobblestone 64 0"));
        String read2 = String.join("\n", client().execute(
                "artest hatch read 0 " + hx + " " + hy + " " + hz));
        Reply replaced = Reply.of("artest hatch read", read2).element("slots", "slot", "0");
        assertEquals("slot 0 must hold the replacement stack: " + read2,
                "minecraft:cobblestone", replaced.text("item"));
        assertEquals("slot 0 must hold all sixty-four: " + read2, 64, replaced.integer("count"));
        assertTrue("old stick stack must be gone after replacement: " + read2,
                !Reply.of("artest hatch read", read2)
                        .holdsElement("slots", "item", "minecraft:stick"));
    }

    /**
     * fluid hatch accepts fluid via the standard fluid inject
     * probe and surfaces it via fluid stored. AR registers a pressurised
     * tank (advancedrocketry:liquidTank) that exposes the fluid-handler
     * capability the same way libVulpes' fluid hatch does.
     */
    @Test
    public void fluidHatchAcceptsAndExportsFluids() throws Exception {
        int fx = 1500, fy = FixtureSite.OPEN_AIR_Y, fz = 1200;
        ok(client().execute("artest place 0 " + fx + " " + fy + " " + fz
                + " advancedrocketry:liquidTank"));

        String injected = String.join("\n", client().execute(
                "artest fluid inject 0 " + fx + " " + fy + " " + fz + " water 8000"));
        assertTrue("fluid inject must succeed: " + injected, Reply.of(injected).ok());
        int amount = extractInt(injected, "filled");
        assertTrue("hatch must accept some water: " + injected, amount > 0);

        String stored = String.join("\n", client().execute(
                "artest fluid stored 0 " + fx + " " + fy + " " + fz));
        Reply.of(stored).element("tanks", "fluid", "water");
        Reply.of(stored).element("tanks", "amount", String.valueOf(amount));
    }

    private static long parseLong(String field, String s) {
        return (long) Reply.of(s).number(field);
    }

    private static int extractInt(String haystack, String field) {
        return Reply.of(haystack).integerOr(field, -1);
    }

    private void ok(java.util.List<String> response) {
        String joined = String.join("\n", response);
        assertTrue("probe call failed: " + joined, Reply.of(joined).ok());
    }
}
