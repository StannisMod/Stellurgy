package dev.stannismod.stellurgy.damage.repair;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBlockSpecial;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.damage.BlockDamageSavedData;
import dev.stannismod.stellurgy.damage.DamageState;
import dev.stannismod.stellurgy.damage.RepairOutcome;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import dev.stannismod.stellurgy.libvulpes.api.IUniversalEnergy;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IDataSync;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IModularInventory;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModulePower;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleSlotArray;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleSync;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleText;
import dev.stannismod.stellurgy.libvulpes.util.MachineReach;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The repair bay's controller: the rung of the repair ladder that works on its own, on the ship it
 * stands on, out of a reserve of finished blocks.
 *
 * <h3>What it serves</h3>
 * <p>Only the ship it stands on. Its work is every damaged position the world's damage map holds
 * inside that ship's shipyard — a block with stages on it, or a hole with the record of what stood
 * there. A bay standing anywhere else answers {@link RepairOutcome#NO_STRUCTURE} and touches
 * nothing: a bay on a base reaching out to whatever is damaged nearby would mend a neighbour's
 * wall. Blocks that keep their own wear in a tile are not in the map, and so are not in its work.</p>
 *
 * <h3>What it is paid in</h3>
 * <p>Finished blocks, and only those: that is what separates this rung from a fabricator that eats
 * raw materials. A hole takes one block of the recorded kind and is filled with THAT block. A
 * staged block draws one block of its own kind and turns it into as many stages of credit as the
 * block has, held by this bay; restaging spends that credit a stage at a time, so mending a crack
 * never costs more than replacing the block, and it leaves the block — and whatever it holds —
 * where it is.</p>
 *
 * <h3>Time and energy</h3>
 * <p>Work is Forge Energy actually delivered: a stage costs a fixed amount, a hole a block's full
 * run of stages, and how fast the energy goes in is the size law in {@link RepairBaySize}. What has
 * been put in toward the next step is kept, saved with the bay, and spent by whichever job comes
 * next — so a bay that was unloaded or unpowered resumes, and is never billed for work it did not
 * do.</p>
 *
 * <h3>Sharing a hull</h3>
 * <p>Any number of bays may stand on one ship. Each CLAIMS the position it works, in the world's
 * damage map, and skips what another holds; so two bays never pay for one stage twice, and two bays
 * drain the work twice as fast. A bay that stops gives its claim back.</p>
 *
 * <h3>It always says why</h3>
 * <p>What it is doing is a {@link RepairOutcome} on the bay, shown on its screen: working, nothing
 * to do, nothing on hand to pay with, no energy, a hole it may not fill, or not on a ship.</p>
 */
public class TileRepairBay extends TileEntity implements ITickable, IInventory, IModularInventory, IDataSync {

    /** Slots in the reserve: one row of the machine screen. A layout number, not a balance one. */
    public static final int RESERVE_SLOTS = 8;
    /**
     * How often an idle bay looks for work, in ticks. A bound on the cost of looking, which walks
     * the bay's frames and every damage record of its world; a bay that has work does not wait on
     * it.
     */
    public static final int LOOK_INTERVAL_TICKS = 20;

    private static final String NBT_RESERVE = "reserve";
    private static final String NBT_ENERGY = "energy";
    private static final String NBT_CREDITS = "credits";
    private static final String NBT_CREDIT_ITEM = "item";
    private static final String NBT_CREDIT_STAGES = "stages";
    private static final String NBT_BANKED = "banked";

    private static final int DATA_OUTCOME = 0;
    private static final int DATA_SIZE = 1;

    private final NonNullList<ItemStack> reserve = NonNullList.withSize(RESERVE_SLOTS, ItemStack.EMPTY);
    private final Charge charge = new Charge();
    private final IItemHandler reserveHandler = new InvWrapper(this);
    /** Stages paid for and not yet spent, by the item that paid for them. */
    private final Map<String, Integer> credits = new HashMap<>();
    /** Energy already put toward the next step of work. */
    private int banked;
    /** The fraction of a unit of energy this tick's rate left over; under one, so never saved. */
    private double carry;

    /** The position this bay holds a claim on and is working, or null. Never saved: claims are not. */
    private BlockPos job;
    private int lookCountdown;
    /** As of the last look; on the client, as the screen was last told. */
    private int size = 1;
    /** Null until the first look; on the client, as the screen was last told. */
    private RepairOutcome outcome;

    /** What this bay is doing, or null before it has looked for the first time. */
    @Nullable
    public RepairOutcome getOutcome() {
        return outcome;
    }

    @Override
    public void onLoad() {
        // A random phase, so the bays of a hull that load in one tick do not all look in one tick.
        if (world != null && !world.isRemote) {
            lookCountdown = world.rand.nextInt(LOOK_INTERVAL_TICKS);
        }
    }

    @Override
    public void update() {
        if (world.isRemote) {
            return;
        }
        if (job != null) {
            work();
            return;
        }
        if (lookCountdown > 0) {
            lookCountdown--;
            return;
        }
        lookCountdown = LOOK_INTERVAL_TICKS;
        look();
    }

    /** Find a position of this bay's ship to work, claim it, and say what this bay is doing. */
    private void look() {
        size = RepairBaySize.of(world, pos);
        String shipId = VSIntegration.registeredShipIdManagingBlock(world, pos);
        // Null when this block belongs to no registered ship.
        AxisAlignedBB yard = shipId == null ? null : VSIntegration.shipyardBoundsOf(world, UUID.fromString(shipId));
        if (yard == null) {
            settle(RepairOutcome.NO_STRUCTURE);
            return;
        }
        BlockDamageSavedData data = BlockDamageSavedData.get(world);
        long now = world.getTotalWorldTime();
        boolean lacked = false;
        boolean unfillable = false;
        boolean unpriced = false;
        // The yard box is exclusive at its maximum; the record walk is inclusive.
        for (BlockPos candidate : data.positionsIn((int) yard.minX, (int) yard.minY, (int) yard.minZ,
                (int) yard.maxX - 1, (int) yard.maxY - 1, (int) yard.maxZ - 1)) {
            Work work = Work.at(world, data, candidate);
            if (work == null) {
                continue;
            }
            if (work.kind == Kind.UNFILLABLE) {
                unfillable = true;
                continue;
            }
            if (work.kind == Kind.UNPRICED) {
                unpriced = true;
                continue;
            }
            if (!canPay(work)) {
                lacked = true;
                continue;
            }
            if (!data.claim(candidate, this, now)) {
                continue;
            }
            if (charge.stored <= 0) {
                data.release(candidate, this);
                settle(RepairOutcome.NO_CHARGE);
                return;
            }
            job = candidate;
            settle(RepairOutcome.REPAIRING);
            return;
        }
        settle(lacked ? RepairOutcome.NO_MATERIALS
                : unfillable ? RepairOutcome.UNFILLABLE
                : unpriced ? RepairOutcome.NO_RECIPE
                : RepairOutcome.UNDAMAGED);
    }

    /** One tick of the claimed job: put energy in, and take a step once enough is in. */
    private void work() {
        BlockDamageSavedData data = BlockDamageSavedData.get(world);
        if (!data.claim(job, this, world.getTotalWorldTime())) {
            job = null;
            return;
        }
        Work work = Work.at(world, data, job);
        if (work == null || work.kind == Kind.UNFILLABLE || work.kind == Kind.UNPRICED) {
            releaseJob();
            lookCountdown = 0;
            return;
        }
        if (!canPay(work)) {
            releaseJob();
            settle(RepairOutcome.NO_MATERIALS);
            return;
        }
        int cost = work.energyCost();
        int needed = cost - banked;
        if (needed > 0) {
            carry += RepairBaySize.powerPerTick(size);
            int want = (int) Math.min(needed, Math.floor(carry));
            int got = charge.spend(want);
            // Never let power that found nothing to do pile up into a later burst.
            carry = Math.min(carry - got, 1.0D);
            banked += got;
            if (got > 0) {
                markDirty();
            }
            if (want > 0 && got == 0) {
                releaseJob();
                settle(RepairOutcome.NO_CHARGE);
                return;
            }
        }
        if (banked < cost) {
            return;
        }
        boolean applied = work.kind == Kind.HOLE ? rebuild(work) : restage(work);
        if (!applied) {
            return;
        }
        banked -= cost;
        markDirty();
        if (Work.at(world, data, job) == null) {
            releaseJob();
            lookCountdown = 0;
        }
    }

    /**
     * Fill a hole with the block its record names, out of the reserve, and spend the record in the
     * same step. The record is cleared only once the block is standing: a placement the world
     * refused leaves the record, the block and the reserve as they were.
     */
    private boolean rebuild(Work work) {
        int slot = reserveSlotOf(work.price);
        if (slot < 0 || !world.setBlockState(work.pos, work.place, 3)) {
            return false;
        }
        decrStackSize(slot, 1);
        BlockDamageSavedData.get(world).clear(work.pos);
        return true;
    }

    /** Take one stage off a staged block, paid from credit, or from one block drawn into credit. */
    private boolean restage(Work work) {
        String key = creditKey(work.price);
        int held = credits.containsKey(key) ? credits.get(key) : 0;
        if (held <= 0) {
            int slot = reserveSlotOf(work.price);
            if (slot < 0) {
                return false;
            }
            decrStackSize(slot, 1);
            held = work.maxStage;
        }
        credits.put(key, held - 1);
        DamageState.setStage(world, work.pos, work.stage - 1);
        IBlockState state = world.getBlockState(work.pos);
        world.notifyBlockUpdate(work.pos, state, state, 3);
        return true;
    }

    private boolean canPay(Work work) {
        if (work.kind == Kind.STAGED) {
            Integer held = credits.get(creditKey(work.price));
            if (held != null && held > 0) {
                return true;
            }
        }
        return reserveSlotOf(work.price) >= 0;
    }

    private int reserveSlotOf(ItemStack wanted) {
        for (int i = 0; i < reserve.size(); i++) {
            ItemStack held = reserve.get(i);
            if (!held.isEmpty() && ItemStack.areItemsEqual(held, wanted)) {
                return i;
            }
        }
        return -1;
    }

    private static String creditKey(ItemStack price) {
        return price.getItem().getRegistryName() + "@" + price.getMetadata();
    }

    private void settle(RepairOutcome next) {
        if (outcome != next) {
            outcome = next;
            markDirty();
        }
    }

    private void releaseJob() {
        if (job != null && world != null && !world.isRemote) {
            BlockDamageSavedData.get(world).release(job, this);
        }
        job = null;
    }

    @Override
    public void invalidate() {
        releaseJob();
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        releaseJob();
        super.onChunkUnload();
    }

    // --- what one damaged position asks of a bay ---------------------------------------------

    private enum Kind { STAGED, HOLE, UNFILLABLE, UNPRICED }

    private static final class Work {
        private final Kind kind;
        private final BlockPos pos;
        /** The item that pays for it; empty for UNFILLABLE and UNPRICED. */
        private final ItemStack price;
        /** For a hole, what to put back. */
        private final IBlockState place;
        private final int stage;
        private final int maxStage;

        private Work(Kind kind, BlockPos pos, ItemStack price, IBlockState place, int stage, int maxStage) {
            this.kind = kind;
            this.pos = pos;
            this.price = price;
            this.place = place;
            this.stage = stage;
            this.maxStage = maxStage;
        }

        /** Energy one step of this work costs: a stage, or for a hole a block's full run of them. */
        private int energyCost() {
            int perStage = StellurgyConfiguration.getCurrentConfig().repairBayEnergyPerStage;
            return kind == Kind.HOLE ? perStage * DamageState.DEFAULT_MAX_STAGE : perStage;
        }

        /**
         * What the damaged position at {@code pos} asks of a bay, or null when it asks nothing — not
         * loaded, or its record says nothing a bay can act on.
         *
         * <p>A hole's record names its block by registry name, and the block registry answers a
         * name it does not know with AIR rather than with nothing, so AIR here is "that block no
         * longer exists": the hole is unfillable, and its record stays as the only statement of
         * what the hull was.</p>
         */
        @Nullable
        private static Work at(net.minecraft.world.World world, BlockDamageSavedData data, BlockPos pos) {
            if (!world.isBlockLoaded(pos)) {
                return null;
            }
            IBlockState here = world.getBlockState(pos);
            if (here.getBlock().isAir(here, world, pos)) {
                String name = data.getDestroyedBlockName(pos);
                if (name == null) {
                    return null;
                }
                Block block = BlockDamageSavedData.blockFromName(name);
                if (block == null || block == net.minecraft.init.Blocks.AIR) {
                    return new Work(Kind.UNFILLABLE, pos, ItemStack.EMPTY, null, 0, 0);
                }
                @SuppressWarnings("deprecation")
                IBlockState state = block.getStateFromMeta(data.getDestroyedMeta(pos));
                Item item = Item.getItemFromBlock(block);
                if (item == Items.AIR) {
                    return new Work(Kind.UNPRICED, pos, ItemStack.EMPTY, null, 0, 0);
                }
                return new Work(Kind.HOLE, pos, new ItemStack(item, 1, block.damageDropped(state)), state,
                        0, 0);
            }
            int stage = DamageState.getStage(world, pos);
            if (stage <= 0) {
                return null;
            }
            ItemStack price = here.getBlock().getItem(world, pos, here);
            if (price.isEmpty()) {
                return new Work(Kind.UNPRICED, pos, ItemStack.EMPTY, null, 0, 0);
            }
            return new Work(Kind.STAGED, pos, price, null, stage, DamageState.getMaxStage(world, pos));
        }
    }

    // --- energy ------------------------------------------------------------------------------

    /**
     * The bay's buffer. Fillable from outside, never drained from outside: energy leaves only as
     * work. The capacity is read from the config each time, so a retuned buffer applies to bays
     * already built.
     */
    private final class Charge implements IUniversalEnergy, IEnergyStorage {
        private int stored;

        private int capacity() {
            return StellurgyConfiguration.getCurrentConfig().repairBayEnergyCapacity;
        }

        private int spend(int amount) {
            int taken = Math.max(0, Math.min(amount, stored));
            stored -= taken;
            return taken;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return acceptEnergy(maxReceive, simulate);
        }

        @Override
        public int acceptEnergy(int amt, boolean simulate) {
            int accepted = Math.max(0, Math.min(amt, capacity() - stored));
            if (!simulate && accepted > 0) {
                stored += accepted;
                markDirty();
            }
            return accepted;
        }

        @Override
        public int extractEnergy(int amt, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return stored;
        }

        @Override
        public int getUniversalEnergyStored() {
            return stored;
        }

        @Override
        public int getMaxEnergyStored() {
            return capacity();
        }

        /** The screen's copy, told by the server; the server's own buffer is never set this way. */
        @Override
        public void setEnergyStored(int amt) {
            stored = amt;
        }

        @Override
        public void setMaxEnergyStored(int max) {
            // The capacity is the config's, not this buffer's.
        }

        @Override
        public boolean canReceive() {
            return true;
        }

        @Override
        public boolean canExtract() {
            return false;
        }
    }

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
        return capability == CapabilityEnergy.ENERGY || capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY
                || super.hasCapability(capability, facing);
    }

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityEnergy.ENERGY) {
            return (T) charge;
        }
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return (T) reserveHandler;
        }
        return super.getCapability(capability, facing);
    }

    // --- persistence -------------------------------------------------------------------------

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setTag(NBT_RESERVE, ItemStackHelper.saveAllItems(new NBTTagCompound(), reserve));
        nbt.setInteger(NBT_ENERGY, charge.stored);
        NBTTagList list = new NBTTagList();
        for (Map.Entry<String, Integer> credit : credits.entrySet()) {
            if (credit.getValue() > 0) {
                NBTTagCompound tag = new NBTTagCompound();
                tag.setString(NBT_CREDIT_ITEM, credit.getKey());
                tag.setInteger(NBT_CREDIT_STAGES, credit.getValue());
                list.appendTag(tag);
            }
        }
        nbt.setTag(NBT_CREDITS, list);
        nbt.setInteger(NBT_BANKED, banked);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        reserve.clear();
        ItemStackHelper.loadAllItems(nbt.getCompoundTag(NBT_RESERVE), reserve);
        charge.stored = nbt.getInteger(NBT_ENERGY);
        credits.clear();
        NBTTagList list = nbt.getTagList(NBT_CREDITS, 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            credits.put(tag.getString(NBT_CREDIT_ITEM), tag.getInteger(NBT_CREDIT_STAGES));
        }
        banked = nbt.getInteger(NBT_BANKED);
    }

    // --- the reserve, as an inventory --------------------------------------------------------

    /** The reserve holds finished blocks and nothing else. */
    @Override
    public boolean isItemValidForSlot(int index, ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof ItemBlock || item instanceof ItemBlockSpecial;
    }

    @Override
    public int getSizeInventory() {
        return reserve.size();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : reserve) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getStackInSlot(int index) {
        return reserve.get(index);
    }

    @Override
    public ItemStack decrStackSize(int index, int count) {
        ItemStack taken = ItemStackHelper.getAndSplit(reserve, index, count);
        if (!taken.isEmpty()) {
            markDirty();
        }
        return taken;
    }

    @Override
    public ItemStack removeStackFromSlot(int index) {
        return ItemStackHelper.getAndRemove(reserve, index);
    }

    @Override
    public void setInventorySlotContents(int index, ItemStack stack) {
        reserve.set(index, stack);
        if (stack.getCount() > getInventoryStackLimit()) {
            stack.setCount(getInventoryStackLimit());
        }
        markDirty();
    }

    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public boolean isUsableByPlayer(EntityPlayer player) {
        return MachineReach.reaches(player, this);
    }

    @Override
    public void openInventory(EntityPlayer player) {
    }

    @Override
    public void closeInventory(EntityPlayer player) {
    }

    @Override
    public int getField(int id) {
        return 0;
    }

    @Override
    public void setField(int id, int value) {
    }

    @Override
    public int getFieldCount() {
        return 0;
    }

    @Override
    public void clear() {
        reserve.clear();
        markDirty();
    }

    @Override
    public String getName() {
        return getBlockType().getUnlocalizedName() + ".name";
    }

    @Override
    public boolean hasCustomName() {
        return false;
    }

    @Override
    public ITextComponent getDisplayName() {
        return new TextComponentTranslation(getName());
    }

    // --- the screen --------------------------------------------------------------------------

    @Override
    public List<ModuleBase> getModules(int id, EntityPlayer player) {
        List<ModuleBase> modules = new ArrayList<>();
        modules.add(new ModulePower(8, 18, charge));
        modules.add(new ModuleSlotArray(26, 18, this, 0, RESERVE_SLOTS));
        modules.add(new ModuleSync(DATA_OUTCOME, this));
        modules.add(new ModuleSync(DATA_SIZE, this));
        modules.add(new ModuleText(26, 44, "", 0x404040) {
            @Override
            @SideOnly(Side.CLIENT)
            public void renderBackground(GuiContainer gui, int x, int y, int mouseX, int mouseY, FontRenderer font) {
                setText(statusLine());
                super.renderBackground(gui, x, y, mouseX, mouseY, font);
            }
        });
        return modules;
    }

    /** The screen's two lines: what the bay is doing, and how big it is. */
    private String statusLine() {
        String doing = outcome == null ? ""
                : new TextComponentTranslation("msg.repairbay." + outcome.name().toLowerCase(java.util.Locale.ROOT))
                .getUnformattedText();
        return doing + "\n" + new TextComponentTranslation("msg.repairbay.size", size).getUnformattedText();
    }

    @Override
    public String getModularInventoryName() {
        return getBlockType().getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer player) {
        return MachineReach.reaches(player, this);
    }

    @Override
    public int getData(int id) {
        if (id == DATA_OUTCOME) {
            return outcome == null ? -1 : outcome.ordinal();
        }
        return size;
    }

    @Override
    public void setData(int id, int value) {
        if (id == DATA_OUTCOME) {
            outcome = value < 0 || value >= RepairOutcome.values().length ? null : RepairOutcome.values()[value];
        } else {
            size = value;
        }
    }
}
