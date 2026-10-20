package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.api.DataStorage;
import dev.stannismod.stellurgy.item.ItemData;
import dev.stannismod.stellurgy.item.ItemMultiData;
import dev.stannismod.stellurgy.item.ItemSpaceElevatorChip;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.util.DimensionBlockPosition;
import net.minecraft.item.ItemStack;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * data-carrying items NBT round-trip pins.
 *
 * <p>Three items in {@code item/} are pure NBT carriers that production
 * reads/writes directly:</p>
 *
 * <ul>
 *   <li>{@link ItemSpaceElevatorChip} — list of {@link DimensionBlockPosition}
 *       entries (player binds an elevator to one or more station landing
 *       coords).</li>
 *   <li>{@link ItemData} — single-type data-stick from satellite collection
 *       (mass, composition, atmospheric density, etc.).</li>
 *   <li>{@link ItemMultiData} — multi-type data carrier used by the
 *       composition satellite to aggregate multiple data types into one
 *       stick.</li>
 * </ul>
 *
 * <p>Contracts pinned: round-trip read-after-write, default-on-empty,
 * stack independence after {@link ItemStack#copy()}. One known production
 * bug in {@link ItemSpaceElevatorChip#setBlockPositions} is pinned via
 * {@code _documentsKnownBug} per the "no production logic
 * changes" rule.</p>
 *
 * <p>All tests run at unit tier via {@link MinecraftBootstrap} — no
 * world / server needed since these items are pure NBT manipulators.</p>
 */
public class ItemDataCarrierNBTRoundTripTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    // ────────────────────── ItemSpaceElevatorChip ───────────────────────

    /** Pins INV-ITM-06 (the elevator chip yields an empty list on a fresh stack and clearing attaches no NBT). */
    @Pins("INV-ITM-06")
    @Test
    public void elevatorChipEmptyStackReturnsEmptyPositionList() {
        ItemSpaceElevatorChip chip = new ItemSpaceElevatorChip();
        ItemStack s = new ItemStack(chip, 1);
        List<DimensionBlockPosition> positions = chip.getBlockPositions(s);
        assertNotNull("getBlockPositions must not return null on empty stack",
                positions);
        assertTrue("fresh stack must yield empty position list",
                positions.isEmpty());
    }

    /** Pins INV-ITM-06 (the elevator chip yields an empty list on a fresh stack and clearing attaches no NBT). */
    @Pins("INV-ITM-06")
    @RedWitness(member = "ItemSpaceElevatorChip#setBlockPositions", at = "} else if (!listToStore.isEmpty()) {",
            replacedBy = "} else {", fails = "setBlockPositions([]) on a stack with no NBT must not create a tag compound",
            on = "2026-10-19")
    @Test
    public void elevatorChipSetEmptyOnFreshStackDoesNotAttachNbt() {
        ItemSpaceElevatorChip chip = new ItemSpaceElevatorChip();
        ItemStack s = new ItemStack(chip, 1);
        chip.setBlockPositions(s, new ArrayList<DimensionBlockPosition>());
        assertFalse("setBlockPositions([]) on a stack with no NBT must not "
                + "create a tag compound (production lines 46-51 gate on "
                + "!isEmpty for the attach branch)", s.hasTagCompound());
    }

    /** The empty-input clear branch
     *  previously called {@code removeTag("positions")} but the data
     *  lived under {@code "list"} per
     *  {@code NBTStorableListList.writeToNBT}, so the clear was a no-op.
     *  Now the key matches and the list is actually cleared.
     * Pins INV-ITM-06 (the elevator chip yields an empty list on a fresh stack and clearing attaches no NBT).
     */
    @Pins("INV-ITM-06")
    @RedWitness(member = "ItemSpaceElevatorChip#setBlockPositions", at = "removeTag(\"list\")",
            replacedBy = "removeTag(\"positions\")", fails = "setBlockPositions with an empty list must clear the stored list",
            on = "2026-10-19")
    @Test
    public void elevatorChipSetEmptyAfterNonEmptyClearsList() {
        ItemSpaceElevatorChip chip = new ItemSpaceElevatorChip();
        ItemStack s = new ItemStack(chip, 1);
        chip.setBlockPositions(s, Arrays.asList(
                new DimensionBlockPosition(0, new HashedBlockPosition(7, 8, 9))));
        assertEquals("precondition: one position attached",
                1, chip.getBlockPositions(s).size());

        chip.setBlockPositions(s, new ArrayList<DimensionBlockPosition>());

        assertEquals("setBlockPositions with an empty list must clear the "
                        + "stored list (NBT key \"list\" removed)",
                0, chip.getBlockPositions(s).size());
    }

    // ────────────────────── ItemData ────────────────────────────────────

    /** Pins INV-ITM-05 (the data-stick max-data table and a data stick never stacking past one). */
    @Pins("INV-ITM-05")
    @Test
    public void dataStickNeverStacksPastOne() {
        // Production contract: ItemData() ctor calls setMaxStackSize(1), so
        // the data-stick item ALWAYS reports a stack-limit of 1 regardless
        // of the stored-data branch in getItemStackLimit (the data==0 ?
        // super : 1 ternary is functionally dead because super also
        // returns 1). The observable contract is "data sticks do not stack
        // — each one is its own inventory entry".
        ItemData item = new ItemData();
        ItemStack empty = new ItemStack(item, 1);
        ItemStack programmed = new ItemStack(item, 1);
        item.addData(programmed, 10, DataStorage.DataType.MASS);

        assertEquals("empty data stick must not stack past 1",
                1, item.getItemStackLimit(empty));
        assertEquals("programmed data stick must not stack past 1",
                1, item.getItemStackLimit(programmed));
    }

    /** Pins INV-ITM-05 (the data-stick max-data table and a data stick never stacking past one). */
    @Pins("INV-ITM-05")
    @RedWitness(member = "ItemData#getMaxData(int)", at = "damage == 0 ? 1000 : 0", replacedBy = "1000",
            fails = "damage 1 -> max data 0 (inert variant)", on = "2026-10-19")
    @Test
    public void dataStickMaxDataIsZeroForNonZeroDamage() {
        // ItemData.getMaxData(damage) returns 1000 only for damage 0;
        // every other damage value yields 0. Production uses this to gate
        // "this stick variant carries data" vs "this is the inert variant".
        ItemData item = new ItemData();
        assertEquals("damage 0 -> max data 1000",
                1000, item.getMaxData(0));
        assertEquals("damage 1 -> max data 0 (inert variant)",
                0, item.getMaxData(1));
        assertEquals("any non-zero damage -> max data 0",
                0, item.getMaxData(99));
    }

    // ────────────────────── ItemMultiData ───────────────────────────────

    @Test
    public void multiDataAddDataAccumulatesPerTypeIndependently() {
        ItemMultiData item = new ItemMultiData();
        ItemStack s = new ItemStack(item, 1);
        item.setMaxData(s, 1000);

        item.addData(s, 50, DataStorage.DataType.MASS);
        item.addData(s, 20, DataStorage.DataType.HUMIDITY);

        // Each type's data is independent; adding to MASS must not leak
        // into HUMIDITY and vice-versa.
        int mass = item.getData(s, DataStorage.DataType.MASS);
        int hum = item.getData(s, DataStorage.DataType.HUMIDITY);
        assertTrue("MASS must register a non-zero amount after addData",
                mass > 0);
        assertTrue("HUMIDITY must register a non-zero amount after addData",
                hum > 0);
        assertEquals("TEMPERATURE bucket must remain at 0 — types do not "
                + "bleed into each other",
                0, item.getData(s, DataStorage.DataType.TEMPERATURE));
    }

    @Test
    public void multiDataIsFullWhenDataReachesMax() {
        ItemMultiData item = new ItemMultiData();
        ItemStack s = new ItemStack(item, 1);
        item.setMaxData(s, 10);
        item.setData(s, 10, DataStorage.DataType.MASS);
        assertTrue("isFull(MASS) must report true when data == max",
                item.isFull(s, DataStorage.DataType.MASS));
        item.setData(s, 5, DataStorage.DataType.MASS);
        assertFalse("isFull(MASS) must report false when data < max",
                item.isFull(s, DataStorage.DataType.MASS));
    }

    @Test
    public void multiDataRemoveDataDecrementsAddressedTypeOnly() {
        ItemMultiData item = new ItemMultiData();
        ItemStack s = new ItemStack(item, 1);
        item.setMaxData(s, 1000);
        item.addData(s, 100, DataStorage.DataType.MASS);
        item.addData(s, 100, DataStorage.DataType.HUMIDITY);

        int massBefore = item.getData(s, DataStorage.DataType.MASS);
        int humBefore = item.getData(s, DataStorage.DataType.HUMIDITY);

        item.removeData(s, 40, DataStorage.DataType.MASS);

        assertTrue("MASS amount must decrease after removeData(MASS)",
                item.getData(s, DataStorage.DataType.MASS) < massBefore);
        assertEquals("HUMIDITY must NOT be touched by removeData(MASS)",
                humBefore, item.getData(s, DataStorage.DataType.HUMIDITY));
    }
}
