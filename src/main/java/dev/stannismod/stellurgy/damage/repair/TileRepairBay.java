package dev.stannismod.stellurgy.damage.repair;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.capability.CapabilityWear;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The repair bay's controller: the rung of the repair ladder that works on its own, on the ship it
 * stands on, out of a reserve of finished blocks.
 *
 * <h3>What it serves</h3>
 * <p>Only the ship it stands on. Its work is every damaged position inside that ship's shipyard,
 * whichever home keeps the stage: a block the world's damage map records — stages on it, or a hole
 * with the record of what stood there — and a block that keeps its own wear in a tile, an engine
 * among them. A bay standing anywhere else answers {@link RepairOutcome#NO_STRUCTURE} and touches
 * nothing: a bay on a base reaching out to whatever is damaged nearby would mend a neighbour's
 * wall.</p>
 *
 * <h3>What it is paid in, and what it takes</h3>
 * <p>Finished blocks out of its reserve, and Forge Energy at the rate its size buys — the rung's one
 * law, which this bay runs through its {@link BayEngine} exactly as the rocket service station does.
 * A hole takes one block of the recorded kind and is filled with THAT block; a staged block is
 * restaged in place a stage at a time out of credit, so mending a crack never costs more than
 * replacing the block, and it leaves the block — and whatever it holds — where it is.</p>
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

    private static final int DATA_OUTCOME = 0;
    private static final int DATA_SIZE = 1;

    private final NonNullList<ItemStack> reserve = NonNullList.withSize(RESERVE_SLOTS, ItemStack.EMPTY);
    private final Charge charge = new Charge();
    private final IItemHandler reserveHandler = new InvWrapper(this);
    /** What this bay has paid for and put in: the rung's price and energy law, shared with the station. */
    private final BayEngine engine = new BayEngine();

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
            lookCountdown = world.rand.nextInt(LOOK_INTERVAL_TICKS);        }
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
        for (BlockPos candidate : damagedIn(data, yard)) {
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
            if (!engine.canPay(work.step, reserveHandler)) {
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

    /**
     * Every position of the ship whose shipyard is {@code yard} that may be work: what the damage
     * map records inside it, then the blocks that keep their own wear in a tile and have some.
     *
     * <p>A stage has two homes and both are walked, because a block that carries its own wear — an
     * engine, a tank — never appears in the map, and a pool drawn from the map alone would leave
     * the parts that matter most to a ship out of every bay's work.</p>
     *
     * <p>What it costs: the map's half walks the recorded positions, not the volume; the tile half
     * walks the tiles of the yard's chunks that are LOADED, read without loading any — so a look is
     * bounded by what this one ship holds, never by the world's tile list.</p>
     *
     * <p>The chunks are read straight off the provider's map, not through {@code getLoadedChunk}:
     * that getter also withdraws a pending unload of the chunk it returns, and a look over every
     * chunk of a hull that is being unloaded would keep the whole hull in memory.</p>
     */
    private Set<BlockPos> damagedIn(BlockDamageSavedData data, AxisAlignedBB yard) {
        // The yard box is exclusive at its maximum; both walks below are inclusive.
        int minX = (int) yard.minX, minY = (int) yard.minY, minZ = (int) yard.minZ;
        int maxX = (int) yard.maxX - 1, maxY = (int) yard.maxY - 1, maxZ = (int) yard.maxZ - 1;
        Set<BlockPos> found = new LinkedHashSet<>(data.positionsIn(minX, minY, minZ, maxX, maxY, maxZ));
        ChunkProviderServer chunks = ((WorldServer) world).getChunkProvider();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                Chunk chunk = chunks.id2ChunkMap.get(ChunkPos.asLong(cx, cz));
                if (chunk == null) {
                    continue;
                }
                for (TileEntity tile : chunk.getTileEntityMap().values()) {
                    BlockPos at = tile.getPos();
                    if (at.getY() >= minY && at.getY() <= maxY && at.getX() >= minX && at.getX() <= maxX
                            && at.getZ() >= minZ && at.getZ() <= maxZ
                            && CapabilityWear.get(tile) != null && DamageState.getStage(world, at) > 0) {
                        found.add(at);
                    }
                }
            }
        }
        return found;
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
        RepairOutcome step = engine.work(work.step, reserveHandler, this::draw, RepairBaySize.powerPerTick(size),
                () -> work.kind == Kind.HOLE ? rebuild(work) : restage(work));
        if (step == RepairOutcome.NO_MATERIALS || step == RepairOutcome.NO_CHARGE) {
            releaseJob();
            settle(step);
            return;
        }
        if (step != RepairOutcome.REPAIRED) {
            return;
        }
        markDirty();
        if (Work.at(world, data, job) == null) {
            releaseJob();
            lookCountdown = 0;
        }
    }

    /** Take up to {@code amount} out of the buffer toward the step in hand; answers what was taken. */
    private int draw(int amount) {
        int got = charge.spend(amount);
        if (got > 0) {
            markDirty();
        }
        return got;
    }

    /**
     * Fill a hole with the block its record names, out of the reserve, and spend the record in the
     * same step. The record is cleared only once the block is standing: a placement the world
     * refused leaves the record, the block and the reserve as they were.
     */
    private boolean rebuild(Work work) {
        int slot = BayEngine.slotOf(work.step.price(), reserveHandler);
        if (slot < 0 || !world.setBlockState(work.pos, work.place, 3)) {
            return false;
        }
        decrStackSize(slot, 1);
        BlockDamageSavedData.get(world).clear(work.pos);
        return true;
    }

    /** Take one stage off a staged block; the engine has already seen it paid for. */
    private boolean restage(Work work) {
        DamageState.setStage(world, work.pos, work.stage - 1);
        IBlockState state = world.getBlockState(work.pos);
        world.notifyBlockUpdate(work.pos, state, state, 3);
        return true;
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
        /** What one step of it costs; null for UNFILLABLE and UNPRICED, which have no price. */
        private final BayEngine.Step step;
        /** For a hole, what to put back. */
        private final IBlockState place;
        private final int stage;

        private Work(Kind kind, BlockPos pos, BayEngine.Step step, IBlockState place, int stage) {
            this.kind = kind;
            this.pos = pos;
            this.step = step;
            this.place = place;
            this.stage = stage;
        }

        /**
         * What the damaged position at {@code pos} asks of a bay, or null when it asks nothing — not
         * loaded, or its record says nothing a bay can act on.
         *
         * <p>A hole's record names its block by registry name; a name the registry no longer has
         * makes the hole unfillable, and its record stays as the only statement of what the hull
         * was.</p>
         *
         * <p>What a step costs is the engine's to say ({@link BayEngine.Step}); a block no finished
         * block stands for has no price a reserve could ever hold, which is a different answer from a
         * price that is merely not on hand.</p>
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
                if (block == null) {
                    return new Work(Kind.UNFILLABLE, pos, null, null, 0);
                }
                @SuppressWarnings("deprecation")
                IBlockState state = block.getStateFromMeta(data.getDestroyedMeta(pos));
                Optional<BayEngine.Step> step = BayEngine.Step.rebuild(block, state);
                return step.isPresent() ? new Work(Kind.HOLE, pos, step.get(), state, 0)
                        : new Work(Kind.UNPRICED, pos, null, null, 0);
            }
            int stage = DamageState.getStage(world, pos);
            if (stage <= 0) {
                return null;
            }
            Optional<BayEngine.Step> step = BayEngine.Step.restage(world, pos, here,
                    DamageState.getMaxStage(world, pos));
            return step.isPresent() ? new Work(Kind.STAGED, pos, step.get(), null, stage)
                    : new Work(Kind.UNPRICED, pos, null, null, 0);
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
        engine.writeTo(nbt);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        reserve.clear();
        ItemStackHelper.loadAllItems(nbt.getCompoundTag(NBT_RESERVE), reserve);
        charge.stored = nbt.getInteger(NBT_ENERGY);
        engine.readFrom(nbt);
    }

    // --- the reserve, as an inventory --------------------------------------------------------

    @Override
    public boolean isItemValidForSlot(int index, ItemStack stack) {
        return BayEngine.isFinishedBlock(stack);
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
