package dev.stannismod.stellurgy.test.server;

// migrated to AbstractSharedServerTest
import dev.stannismod.stellurgy.test.EnergyStore;
import dev.stannismod.stellurgy.test.FluidStored;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import dev.stannismod.stellurgy.test.FixtureSite;

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
        String place = String.join("\n", client().execute(
                "stellurgytest place 0 1200 64 1200 libvulpes:forgepowerinput"));
        assertTrue("could not place libvulpes:forgepowerinput: " + place,
                Reply.of(place).bool("placed"));

        EnergyStore initial = EnergyStore.at(
                        cmd -> String.join("\n", client().execute(cmd)), 0, 1200, 64, 1200)
                .requireEnergy("placed block missing IEnergyStorage");
        long storedInit = initial.stored();
        long capacity = initial.capacity();
        assertTrue("placed block capacity unreasonable: " + initial.raw(), capacity > 0L);

        String inj1 = String.join("\n",
                client().execute("stellurgytest energy inject 0 1200 64 1200 5000"));
        assertTrue("inject 5000 failed: " + inj1, Reply.of(inj1).ok());
        long accepted1 = parseLong(ACCEPTED, inj1);
        long expectedAccept1 = Math.min(5000L, capacity - storedInit);
        assertEquals("accepted ≠ expected: " + inj1, expectedAccept1, accepted1);
        long storedAfter1 = parseLong(INJ_STORED, inj1);
        assertEquals("stored did not advance correctly: " + inj1,
                storedInit + accepted1, storedAfter1);

        String inj2 = String.join("\n", client().execute(
                "stellurgytest energy inject 0 1200 64 1200 " + capacity));
        long accepted2 = parseLong(ACCEPTED, inj2);
        long storedAfter2 = parseLong(INJ_STORED, inj2);
        assertEquals("battery not at cap after overflow: " + inj2, capacity, storedAfter2);
        assertEquals("overflow accepted wrong: " + inj2,
                capacity - storedAfter1, accepted2);

        String inj3 = String.join("\n", client().execute(
                "stellurgytest energy inject 0 1200 64 1200 1000 true"));
        long accepted3 = parseLong(ACCEPTED, inj3);
        long storedAfter3 = parseLong(INJ_STORED, inj3);
        assertEquals("simulate=true mutated stored: " + inj3, capacity, storedAfter3);
        assertEquals("simulate at-cap accepted should be 0: " + inj3, 0L, accepted3);
    }

    private static long parseLong(String field, String s) {
        return (long) Reply.of(s).number(field);
    }

}
