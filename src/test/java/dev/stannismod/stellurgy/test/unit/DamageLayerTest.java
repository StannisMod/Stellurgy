package dev.stannismod.stellurgy.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import org.junit.Test;

import dev.stannismod.stellurgy.damage.BlockDamageSavedData;
import dev.stannismod.stellurgy.damage.DamageLayer;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The bookkeeping a relocation's damage carry is built out of, at the tier where it is cheap to check.
 *
 * <p>The e2e above it needs a real server, a ship and five minutes; these are the same rules stated
 * where a wrong answer costs a second. What they pin is deliberately narrow: which records a box
 * selects, what a move does to the destination, that a layer survives a round trip through NBT
 * and lands where its new origin says, and that a hole's provenance arrives under its own name
 * (INV-DMG-10). The behaviour of the relocation itself is not their business.</p>
 */
public class DamageLayerTest {

    /** Shipyard-scale coordinates, because that is where a ship's blocks actually live. */
    private static final int YARD_X = 5_119_888, YARD_Y = 96, YARD_Z = 40_112;

    @Test
    public void aBoxSelectsTheRecordsInsideItAndNoOthers() {
        BlockDamageSavedData data = new BlockDamageSavedData();
        data.setStage(new BlockPos(YARD_X, YARD_Y, YARD_Z), 2);
        data.setStage(new BlockPos(YARD_X + 3, YARD_Y + 1, YARD_Z - 2), 1);
        data.setStage(new BlockPos(YARD_X + 500, YARD_Y, YARD_Z), 3);   // another ship's yard

        List<BlockPos> inside = data.positionsIn(YARD_X - 64, YARD_Y - 64, YARD_Z - 64,
                YARD_X + 64, YARD_Y + 64, YARD_Z + 64);

        assertEquals("a 64-block box around one ship's yard must not reach the next one: " + inside,
                2, inside.size());
    }

    @Test
    public void aRecordAtShipyardScaleSurvivesBeingKeyed() {
        // The packed-position key has 26 bits for X and Z: a shipyard address is millions of blocks
        // out, which is exactly where a naive key would wrap and put the record somewhere else.
        BlockDamageSavedData data = new BlockDamageSavedData();
        BlockPos far = new BlockPos(YARD_X, YARD_Y, YARD_Z);
        data.setStage(far, 3);

        assertEquals("the stage did not come back from a shipyard-scale address", 3, data.getStage(far));
        assertEquals("the record is not selectable at the address it was written to", 1,
                data.positionsIn(YARD_X, YARD_Y, YARD_Z, YARD_X, YARD_Y, YARD_Z).size());
    }

    @Test
    public void movingARecordOntoAPositionThatHasNoneClearsTheDestination() {
        BlockDamageSavedData data = new BlockDamageSavedData();
        BlockPos stale = new BlockPos(10, 70, 10);
        BlockPos arriving = new BlockPos(20, 70, 20);
        data.setStage(stale, 4);

        // Nothing at `arriving`, so the move must ERASE what `stale` said — a block relocated onto a
        // position an earlier structure damaged must not inherit that damage.
        data.move(arriving, stale);

        assertEquals("the destination kept a record its arriving block never earned",
                0, data.getStage(stale));
    }

    @Test
    public void aRecordOutsideTheBLOCKBoundsIsStillCarried() {
        // The case that made this file exist. A capture's origin is its tight BLOCK bounds, and those
        // are drawn around blocks that still exist — so the record of a column that was shot away
        // entirely lies outside them. Selecting by the origin box drops exactly the records the carry
        // is for, while the cut that follows clears them anyway: the damage is destroyed by the act of
        // moving the ship.
        BlockDamageSavedData source = new BlockDamageSavedData();
        source.setStage(new BlockPos(YARD_X + 12, YARD_Y + 4, YARD_Z + 2), 4);

        DamageLayer layer = DamageLayer.harvest(source,
                YARD_X, YARD_Y, YARD_Z, YARD_X + 40, YARD_Y + 40, YARD_Z + 40,   // what the cut empties
                YARD_X, YARD_Y, YARD_Z);                                          // where the blocks start
        assertEquals("the record of a shot-away column was not captured", 1, layer.size());

        BlockDamageSavedData destination = new BlockDamageSavedData();
        layer.applyTo(destination, 100, 64, 100);
        assertEquals("the record did not keep its place relative to the blocks it travelled with",
                4, destination.getStage(new BlockPos(112, 68, 102)));
    }

    @Test
    public void aCapturedLayerLandsAtItsNewOriginThroughNbt() {
        BlockDamageSavedData source = new BlockDamageSavedData();
        // Two records inside a capture whose origin is the yard corner; stages only — provenance has
        // its own method below.
        source.setStage(new BlockPos(YARD_X + 2, YARD_Y + 1, YARD_Z + 3), 2);
        source.setStage(new BlockPos(YARD_X + 5, YARD_Y + 4, YARD_Z + 1), 4);

        DamageLayer layer = DamageLayer.harvest(source, YARD_X, YARD_Y, YARD_Z,
                YARD_X + 20, YARD_Y + 20, YARD_Z + 20, YARD_X, YARD_Y, YARD_Z);
        assertEquals("the capture did not take both records", 2, layer.size());

        NBTTagCompound nbt = new NBTTagCompound();
        layer.writeToNBT(nbt);
        DamageLayer reloaded = DamageLayer.readFromNBT(nbt);
        assertEquals("the layer did not survive NBT", 2, reloaded.size());

        // Landed at a new origin, on the far side of the world from where it was captured.
        BlockDamageSavedData destination = new BlockDamageSavedData();
        reloaded.applyTo(destination, 100, 64, 100);
        assertEquals("the damaged block did not land at its offset from the new origin",
                2, destination.getStage(new BlockPos(102, 65, 103)));
        assertEquals("the second record did not land at its own offset",
                4, destination.getStage(new BlockPos(105, 68, 101)));

        // And an empty capture must say so as a value rather than as a malformed tag.
        assertTrue("an undamaged structure's layer is not empty",
                DamageLayer.readFromNBT(new NBTTagCompound()).isEmpty());
    }

    /**
     * Pins INV-DMG-10: a hole's provenance crosses a carry under the very name and meta it was written
     * with — a block the registry still holds, and one whose mod is gone, which is the case a lookup
     * through Forge's defaulted registry turns into air. The registry is bootstrapped so that a carry
     * which did look the name up would answer as it does in a game, not throw.
     *
     * red-witnessed: with {@code DamageLayer#applyTo} at {@code data.recordDestroyedName(pos, entry.originalBlock, entry.originalMeta);} replaced by {@code data.recordDestroyed(pos, net.minecraft.block.Block.REGISTRY.getObject(new net.minecraft.util.ResourceLocation(entry.originalBlock)), entry.originalMeta);}, fails: "INV-DMG-10: a hole of a block no longer registered must arrive under ITS name" (2026-10-08; expected removedmod:hull_plate, was minecraft:air).
     */
    @Test
    public void aHoleOfABlockNoLongerRegisteredKeepsItsNameAcrossACarry() {
        MinecraftBootstrap.ensure();
        BlockPos registeredHole = new BlockPos(YARD_X + 1, YARD_Y, YARD_Z + 1);
        BlockPos lostHole = new BlockPos(YARD_X + 3, YARD_Y + 2, YARD_Z + 1);
        BlockDamageSavedData source = new BlockDamageSavedData();
        source.recordDestroyedName(registeredHole, "minecraft:stone", 3);
        source.recordDestroyedName(lostHole, "removedmod:hull_plate", 5);

        DamageLayer layer = DamageLayer.harvest(source, YARD_X, YARD_Y, YARD_Z,
                YARD_X + 8, YARD_Y + 8, YARD_Z + 8, YARD_X, YARD_Y, YARD_Z);
        BlockDamageSavedData destination = new BlockDamageSavedData();
        layer.applyTo(destination, 100, 64, 100);

        BlockPos registeredLanded = new BlockPos(101, 64, 101);
        BlockPos lostLanded = new BlockPos(103, 66, 101);
        assertEquals("INV-DMG-10: a hole of a registered block must arrive under its own name",
                "minecraft:stone", destination.getDestroyedBlockName(registeredLanded));
        assertEquals("INV-DMG-10: and with its own meta", 3, destination.getDestroyedMeta(registeredLanded));
        assertEquals("INV-DMG-10: a hole of a block no longer registered must arrive under ITS name, the"
                + " only statement of what filled it", "removedmod:hull_plate", destination.getDestroyedBlockName(lostLanded));
        assertEquals("INV-DMG-10: and with its own meta", 5, destination.getDestroyedMeta(lostLanded));
    }
}
