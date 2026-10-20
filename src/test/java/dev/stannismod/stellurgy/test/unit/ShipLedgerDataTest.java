package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.ShipLedger;
import dev.stannismod.stellurgy.space.ShipLedgerData;
import dev.stannismod.stellurgy.space.TransitRecord;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Pure persistence contract of {@link ShipLedgerData} (the durable backing for the ship ledger): a
 * ship in flight is carried by its transit record instead of by the settled list, a write that would store a ship in
 * NEITHER place is refused outright, the subsystem's own clock rides its own monotonic writer rather
 * than the fleet's snapshot, and a loaded store repopulates a live {@link ShipLedger}. No MC world —
 * the WSD's own {@code write/readFromNBT} + {@code replaceAll}/{@code loadInto} operate on in-memory
 * maps.
 */
public class ShipLedgerDataTest {

    /**
     * A space-clock value no fresh boot reaches — ~11.6 days at 20 tps. Deliberately not a small
     * number: reading back a small one would be indistinguishable from reading back a default.
     */
    private static final long CLOCK = 20_000_000L;

    /** How much NEWER the clock offered with a refused write is. Any non-zero value would do. */
    private static final long AGE = 500_000L;

    private static GalacticCoord coord(long sx, long sy, long sz, long lx, long ly, long lz) {
        return GalacticCoord.ofSectorLocal(sx, sy, sz, lx, ly, lz);
    }

    /** The in-flight record that carries {@code ship} — what makes it legal to leave the settled list. */
    private static TransitRecord flying(UUID ship, GalacticCoord target) {
        NBTTagCompound blocks = new NBTTagCompound();
        blocks.setInteger("blocks", 1);
        return new TransitRecord(ship.toString(), coord(0, 0, 0, 0, 0, 0), target, 10L, 0L, 0L, 0L, 1L,
                java.util.Collections.<UUID>emptyList(), blocks);
    }

    /** The whole store replaced from a live ledger with nothing in flight and no visits recorded. */
    private static List<UUID> store(ShipLedgerData data, ShipLedger live) {
        return data.replaceAll(live.snapshot(), java.util.Collections.<TransitRecord>emptyList(),
                java.util.Collections.<String, Long>emptyMap());
    }

    @Test
    public void settledEntriesSurviveWriteReadRoundTrip() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID flyer = UUID.randomUUID();
        GalacticCoord coordA = coord(3, 0, -1, 100, 0, 50);
        GalacticCoord coordB = coord(-7, 2, 0, 0, -40, 0);
        GalacticCoord flyingTo = coord(9, 0, 0, 0, 0, 0);

        ShipLedger live = new ShipLedger((shipId, coord) -> { });
        live.settle(a, coordA);
        live.settle(b, coordB);
        live.beginTransit(flyer, flyingTo); // in flight: belongs in the transit list, not this one

        ShipLedgerData src = new ShipLedgerData();
        assertTrue("nothing is dropped: the flying ship is carried by its record",
                src.replaceAll(live.snapshot(),
                        java.util.Collections.singletonList(flying(flyer, flyingTo)),
                        java.util.Collections.<String, Long>emptyMap()).isEmpty());
        assertEquals("only the two settled ships are in the settled list", 2, src.snapshot().size());
    }

    @Test
    public void theStoreWritesNoSlotDimension() {
        // A slot dim is minted per boot and re-used as cells come and go, so an id written here names
        // a different cell — or no world at all — on the next start. Persisting one is persisting an
        // answer that expires with the process, and the reader has no way to tell an expired one from
        // a live one. The coordinate is what survives a restart; the dimension is re-derived from it.
        UUID a = UUID.randomUUID();
        ShipLedger live = new ShipLedger((shipId, coord) -> { });
        live.settle(a, coord(3, 0, -1, 100, 0, 50));

        ShipLedgerData data = new ShipLedgerData();
        store(data, live);
        NBTTagCompound nbt = data.writeToNBT(new NBTTagCompound());

        NBTTagCompound ship = nbt.getTagList("ships", 10).getCompoundTagAt(0);
        assertEquals("sanity: the one settled ship was written", 1,
                nbt.getTagList("ships", 10).tagCount());
        assertTrue("its galactic coordinate IS written — that is what survives a restart",
                ship.hasKey("galacticCoord"));
        assertFalse("no per-boot slot dimension may be written alongside it", ship.hasKey("slotDim"));
    }

    @Test
    public void anInFlightShipIsCarriedByItsTransitRecordAndNotByTheSettledList() {
        UUID flyer = UUID.randomUUID();
        GalacticCoord flyingTo = coord(1, 0, 0, 0, 0, 0);
        ShipLedger live = new ShipLedger((shipId, coord) -> { });
        live.beginTransit(flyer, flyingTo);

        ShipLedgerData data = new ShipLedgerData();
        assertTrue("the write is applied", data.replaceAll(live.snapshot(),
                java.util.Collections.singletonList(flying(flyer, flyingTo)),
                java.util.Collections.<String, Long>emptyMap()).isEmpty());
        assertTrue("a ship in flight is not in the settled list", data.snapshot().isEmpty());

        NBTTagCompound nbt = data.writeToNBT(new NBTTagCompound());
        ShipLedgerData dst = new ShipLedgerData();
        dst.readFromNBT(nbt);
        assertTrue("and it is still not in the settled list after the round-trip",
                dst.snapshot().isEmpty());
        assertEquals("it comes back as the in-flight jump it is", 1, dst.loadTransits().size());
        assertEquals(flyer.toString(), dst.loadTransits().get(0).shipId);
    }

    /**
     * The contract that a lost fleet was made of: a ship the ledger no longer calls settled, and that
     * no in-flight jump carries either, would be stored in NEITHER list — so this write is refused and
     * the store is left alone.
     *
     * <p>This is exactly the state a save point reaches when the transit half of the gather fails: the
     * live ledger says the ship is flying, the transit records say nothing, and the old code obliged by
     * writing an empty fleet over a good one. There is no ordering of a clear-then-refill that survives
     * it, which is why the store no longer offers one.</p>
     */
    @Test
    public void aWriteThatWouldStoreAFlyingShipNowhereIsRefused() {
        UUID settled = UUID.randomUUID();
        UUID flyer = UUID.randomUUID();
        GalacticCoord home = coord(4, 0, 0, 0, 0, 0);

        ShipLedgerData data = new ShipLedgerData();
        ShipLedger before = new ShipLedger((shipId, coord) -> { });
        before.settle(settled, home);
        before.settle(flyer, home);
        assertTrue("arrangement: a good fleet is stored first", store(data, before).isEmpty());
        NBTTagCompound good = data.writeToNBT(new NBTTagCompound());

        ShipLedger during = new ShipLedger((shipId, coord) -> { });
        during.settle(settled, home);
        during.beginTransit(flyer, coord(5, 0, 0, 0, 0, 0)); // flying, and nothing carries it

        List<UUID> dropped = store(data, during);
        assertEquals("the write names the ship it would have lost", 1, dropped.size());
        assertEquals(flyer, dropped.get(0));
        assertEquals("and it changed nothing: both ships are still stored", 2, data.snapshot().size());
        assertEquals("byte for byte, the store is what it was before the refused write",
                good.toString(), data.writeToNBT(new NBTTagCompound()).toString());
    }

    /**
     * A save with no clock in it reads back as tick zero — the pre-0.1.0 / brand-new-world case.
     *
     * <p>This is the READ side of the schema, not a field default: it goes through the same
     * {@code readFromNBT} a world on disk does, against NBT that genuinely lacks the key. The store
     * has to answer something, and zero is where a new clock starts, so a world that never stored one
     * begins rather than jumping.</p>
     */
    @Test
    public void aSaveWithNoClockInItReadsBackAsTickZero() {
        ShipLedgerData legacy = new ShipLedgerData();
        legacy.setClock(CLOCK); // it must be capable of holding a non-zero clock first
        assertEquals("arrangement: the store holds a clock before the key is taken away", CLOCK,
                legacy.clock());

        NBTTagCompound nbt = legacy.writeToNBT(new NBTTagCompound());
        assertTrue("arrangement: the schema key must be there to remove", nbt.hasKey("spaceClock"));
        nbt.removeTag("spaceClock");

        legacy.readFromNBT(nbt);
        assertEquals("a save written before the subsystem owned a clock has none to give back, and "
                + "zero is where a new clock starts", 0L, legacy.clock());
    }

    /**
     * A REFUSED fleet write must not roll the clock back with it.
     *
     * <p>The tempting design is the opposite — bundle the clock into the same all-or-nothing write,
     * so a pass that keeps an older fleet keeps the older clock. It is wrong, because the fleet is
     * not the only thing this clock dates. A jump capacitor's {@code since} lives in TILE NBT and a
     * memory crystal's {@code observedTick} in ITEM NBT, and Minecraft commits both to disk BEFORE a
     * world-save event ever reaches this store. A clock rolled back under them comes back EARLIER
     * than stamps already written, elapsed time against those stamps goes negative, and the code
     * that consumes it reads that as "no time has passed" and stops accruing — a world of capacitors
     * that never charge, for however long it takes the clock to catch up.</p>
     *
     * <p>So the clock is monotonic and the fleet is atomic. The price is that a refused pass can
     * leave a clock at most one save cycle ahead of a stale fleet: a cell reads a cycle older, a jump
     * lands a cycle sooner. That is the cheaper of the two errors by a wide margin.</p>
     */
    @Test
    public void aRefusedFleetWriteDoesNotRollTheClockBack() {
        UUID settled = UUID.randomUUID();
        UUID flyer = UUID.randomUUID();
        GalacticCoord home = coord(4, 0, 0, 0, 0, 0);

        ShipLedgerData data = new ShipLedgerData();
        ShipLedger before = new ShipLedger((shipId, coord) -> { });
        before.settle(settled, home);
        before.settle(flyer, home);
        assertTrue("arrangement: a good fleet is stored first", store(data, before).isEmpty());
        data.setClock(CLOCK);

        // The clock keeps being written every pass, because it is not part of the snapshot.
        data.setClock(CLOCK + AGE);

        // The state a failed gather leaves behind: flying, and nothing carrying it.
        ShipLedger during = new ShipLedger((shipId, coord) -> { });
        during.settle(settled, home);
        during.beginTransit(flyer, coord(5, 0, 0, 0, 0, 0));
        assertEquals("arrangement: the fleet write must actually be refused", 1,
                store(data, during).size());

        assertEquals("a refused FLEET write may not drag the clock backwards with it: stamps outside "
                + "this store are already on disk at the newer tick, and a clock behind them makes "
                + "their elapsed time negative", CLOCK + AGE, data.clock());
        assertEquals("...and the fleet it declined to replace is untouched", 2, data.snapshot().size());
    }

    @Test
    public void loadIntoRepopulatesALiveLedger() {
        UUID a = UUID.randomUUID();
        GalacticCoord coordA = coord(2, -3, 4, 10, 20, -30);

        ShipLedger source = new ShipLedger((shipId, coord) -> { });
        source.settle(a, coordA);

        ShipLedgerData data = new ShipLedgerData();
        store(data, source);
        // Round-trip through NBT so we exercise the on-disk shape, not just the in-memory copy.
        ShipLedgerData restored = new ShipLedgerData();
        restored.readFromNBT(data.writeToNBT(new NBTTagCompound()));

        ShipLedger live = new ShipLedger((shipId, coord) -> { });
        assertEquals("starts empty", 0, live.size());
        restored.loadInto(live);

        assertEquals("one settled ship restored into the live ledger", 1, live.size());
        assertNotNull("ship A restored", live.get(a));
        assertFalse("live ledger snapshot is a copy", live.snapshot() == restored.snapshot());
    }
}
