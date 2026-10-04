package dev.stannismod.stellurgy.tile.atmosphere;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.api.StellurgyItems;
import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.atmosphere.AtmosphereHandler;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IModularInventory;
import dev.stannismod.stellurgy.libvulpes.tile.TileInventoriedRFConsumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tier 3 of the life-support progression: a powered block that regenerates the oxygen of the
 * sealed room it stands in, and ends the era of consumable scrubber cartridges.
 * <p>
 * It serves ONE zone — its own — and has no pipes: power in, solid carbon out. The whole-ship
 * ventilation network belongs to the centralised tier above this one. "Recirculator" names the
 * chemistry rather than any airflow: the Bosch reaction spends hydrogen and electrolysis of the
 * recovered water gives it back, so the hydrogen carrier recirculates inside the machine and the
 * net reaction is carbon dioxide in, oxygen and solid carbon out.
 */
public class TileAirRecirculator extends TileInventoriedRFConsumer implements IModularInventory {

    /** Carbon regenerated but not yet worth a whole item, in the same unit as partial pressure. */
    private long carbonBuffer;
    /**
     * Ticks since this machine last acted. Its OWN counter, deliberately not
     * {@code world.getTotalWorldTime() % 20}: a shared clock wakes every recirculator in the world
     * on the same tick, and it also makes the machine unreachable from a harness, because
     * force-ticking a tile does not advance world time.
     */
    private int ticksSinceOperation;

    public TileAirRecirculator() {
        super(10000, 1);
    }

    @Override
    @Nonnull
    public int[] getSlotsForFace(@Nullable EnumFacing side) {
        return new int[]{0};
    }

    @Override
    public boolean canInsertItem(int slot, @Nonnull ItemStack stack, @Nullable EnumFacing side) {
        // Output only: the dust leaves, nothing is fed in. A hopper pointing at this machine must
        // not be able to jam its one slot with something it will never consume.
        return false;
    }

    @Override
    public boolean isItemValidForSlot(int slot, @Nonnull ItemStack stack) {
        return false;
    }

    @Override
    public String getModularInventoryName() {
        return StellurgyBlocks.blockAirRecirculator.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(net.minecraft.entity.player.EntityPlayer entity) {
        return true;
    }

    @Override
    public java.util.List<dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase> getModules(int ID, net.minecraft.entity.player.EntityPlayer player) {
        java.util.List<dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase> modules = new java.util.ArrayList<>(2);
        modules.add(new dev.stannismod.stellurgy.libvulpes.inventory.modules.ModulePower(18, 20, this.energy));
        modules.add(new dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleSlotArray(80, 35, this, 0, 1));
        return modules;
    }

    @Override
    public int getPowerPerOperation() {
        return StellurgyConfiguration.getCurrentConfig().lifeSupportRecirculatorPower;
    }

    @Override
    public boolean canPerformFunction() {
        if (world.isRemote || !StellurgyConfiguration.getCurrentConfig().lifeSupportZones)
            return false;
        if (++ticksSinceOperation < 20)
            return false;
        if (!hasCarbonDioxideToProcess() || !hasRoomForDust()) {
            // Hold the counter at the threshold rather than resetting: a machine that was ready
            // and merely had nowhere to put its dust should resume the moment the slot clears,
            // not wait out another full second.
            ticksSinceOperation = 20;
            return false;
        }
        ticksSinceOperation = 0;
        return true;
    }

    @Override
    public void performFunction() {
        AirState air = getZoneAir();
        if (air == null)
            return;

        long regenerated = air.regenerate(StellurgyConfiguration.getCurrentConfig().lifeSupportRecirculatorRate);
        if (regenerated <= 0L)
            return;

        carbonBuffer += regenerated;
        emitDust();

        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world);
        BlockPos cell = findServedCell();
        if (handler != null && cell != null)
            handler.refreshDerivedAtmosphereAt(cell);
        markDirty();
    }

    /**
     * Turn buffered carbon into items. The buffer exists because a second of regeneration is worth
     * a fraction of a dust: without it the machine would either drop nothing or round its output
     * up every tick, and the carbon it vents would stop matching the carbon it removed.
     */
    private void emitDust() {
        long perDust = Math.max(1L, StellurgyConfiguration.getCurrentConfig().lifeSupportCarbonPerDust);
        while (carbonBuffer >= perDust) {
            ItemStack slot = getStackInSlot(0);
            if (slot.isEmpty()) {
                setInventorySlotContents(0, new ItemStack(StellurgyItems.itemCarbonDust, 1));
            } else if (slot.getCount() < slot.getMaxStackSize()) {
                slot.grow(1);
            } else {
                // Full. Stop converting rather than deleting the carbon: a blocked output backs
                // the machine up, which is the routing cost this tier is supposed to have.
                return;
            }
            carbonBuffer -= perDust;
        }
    }

    private boolean hasRoomForDust() {
        ItemStack slot = getStackInSlot(0);
        return slot.isEmpty() || (slot.getItem() == StellurgyItems.itemCarbonDust && slot.getCount() < slot.getMaxStackSize());
    }

    private boolean hasCarbonDioxideToProcess() {
        AirState air = getZoneAir();
        return air != null && air.getCarbonDioxide() > 0L;
    }

    /**
     * The air cell this machine serves: the first neighbouring position that belongs to a zone.
     * <p>
     * NOT this machine's own position. A zone is made of AIR cells, and the block occupying a
     * solid position is by definition not one of them — a recirculator set into a floor or a wall
     * would otherwise resolve no zone at all and sit idle forever, which is exactly how it first
     * failed. Serving whichever zone it touches also gives the natural behaviour for a machine
     * built into the partition between two rooms: it works on one of them, not on neither.
     */
    @Nullable
    private BlockPos findServedCell() {
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world);
        if (handler == null)
            return null;
        for (EnumFacing dir : EnumFacing.values()) {
            BlockPos side = pos.offset(dir);
            if (handler.getAirStateAt(side) != null)
                return side;
        }
        return null;
    }

    @Nullable
    private AirState getZoneAir() {
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world);
        BlockPos cell = findServedCell();
        return (handler == null || cell == null) ? null : handler.getAirStateAt(cell);
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        carbonBuffer = nbt.getLong("carbonBuffer");
        ticksSinceOperation = nbt.getInteger("opTicks");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setLong("carbonBuffer", carbonBuffer);
        nbt.setInteger("opTicks", ticksSinceOperation);
        return nbt;
    }
}
