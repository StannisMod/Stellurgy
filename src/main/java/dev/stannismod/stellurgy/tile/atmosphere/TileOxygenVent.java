package dev.stannismod.stellurgy.tile.atmosphere;

import io.netty.buffer.ByteBuf;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.relauncher.Side;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.api.StellurgyFluids;
import dev.stannismod.stellurgy.api.AreaBlob;
import dev.stannismod.stellurgy.api.util.IBlobHandler;
import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.atmosphere.AtmosphereHandler;
import dev.stannismod.stellurgy.api.atmosphere.Atmosphere;
import dev.stannismod.stellurgy.atmosphere.LifeSupportNetwork;
import dev.stannismod.stellurgy.subsystem.network.ISubsystemSink;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkDomain;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkManager;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.util.AudioRegistry;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.api.IToggleableMachine;
import dev.stannismod.stellurgy.libvulpes.block.BlockTile;
import dev.stannismod.stellurgy.libvulpes.client.RepeatingSound;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.*;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.network.PacketMachine;
import dev.stannismod.stellurgy.libvulpes.tile.TileInventoriedRFConsumerTank;
import dev.stannismod.stellurgy.libvulpes.util.FluidUtils;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;
import dev.stannismod.stellurgy.libvulpes.util.IAdjBlockUpdate;
import dev.stannismod.stellurgy.libvulpes.util.INetworkMachine;
import dev.stannismod.stellurgy.libvulpes.util.ZUtils.RedstoneState;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class TileOxygenVent extends TileInventoriedRFConsumerTank implements IBlobHandler, IModularInventory, INetworkMachine, IAdjBlockUpdate, IToggleableMachine, IButtonInventory, IToggleButton, ISubsystemSink {

    private final static byte PACKET_REDSTONE_ID = 2;
    private final static byte PACKET_TRACE_ID = 3;
    private final static byte PACKET_PRIORITY_ID = 4;

    /** Lowest and highest zone priority a player can dial in; 0 is the default everything starts at. */
    private final static int PRIORITY_MIN = -1;
    private final static int PRIORITY_MAX = 1;
    private boolean isSealed;
    private boolean firstRun;
    private boolean hasFluid;
    private boolean soundInit;
    private boolean allowTrace;
    private boolean blockUpdated;
    private int numScrubbers;
    private List<TileCO2Scrubber> scrubbers;
    private int radius = 0;
    private RedstoneState state;
    private ModuleRedstoneOutputButton redstoneControl;
    private ModuleToggleSwitch traceToggle;
    /** Gas contents read back from the save, waiting for the blob this vent will register. */
    private AirState pendingAirState;
    /**
     * Which zones the ventilation plant serves first when it cannot serve them all. Every zone
     * starts equal (0) — a ship where nothing is prioritised is one where the plant simply shares
     * what it has, which is the behaviour a player who never opens this screen should get.
     */
    private int zonePriority;
    /** How often a vent re-runs the flood fill that decides whether its room is closed. */
    private static final int SEAL_CHECK_TICKS = 100;
    /** How often an unsealed vent widens its diagnostic trace by one block. */
    private static final int TRACE_STEP_TICKS = 10;
    /** How often a scrubber cartridge is charged for the work it has been doing. */
    private static final int SCRUBBER_CHARGE_TICKS = 200;

    /** Ticks since this vent last let a breached zone's air out. */
    private int ticksSinceVenting;
    /**
     * The three periodic jobs below, each on its OWN counter rather than on a world-clock modulo.
     * <p>
     * A modulo of the world clock has two faults, and this file documents both one method further
     * down: every vent in the world fires on the same tick, and a tile the harness force-ticks sees
     * ONE world time across all of its ticks, so the job either never runs or runs every time. The
     * second fault reached a CONSUMABLE — cartridges were charged on a schedule no test could make
     * happen — and it also decided when a vent notices its hull has opened.
     * <p>
     * The seal check starts due, so a vent placed into a finished room answers on its first tick
     * instead of waiting out a period it happens to have started in the middle of.
     */
    private int ticksSinceSealCheck = SEAL_CHECK_TICKS;
    private int ticksSinceTraceStep;
    private int ticksSinceScrubberCharge;
    /**
     * This zone's hull is OPEN — not merely "the vent is not maintaining it right now".
     * <p>
     * The two are different facts and only one of them should cost the ship its air. A vent is
     * momentarily not maintaining a perfectly intact room on its first tick after every load (the
     * seal is deliberately dropped so it gets re-checked), while it is switched off, and while it is
     * browning out. None of those is a hole in the hull, and treating them as one drained a sealed,
     * powered, fuelled room on every chunk reload.
     * <p>
     * Set only where the room is actually gone: a seal that HELD and then found its zone empty, or a
     * seal check that ran and failed. Deliberately not persisted — after a load the check runs again
     * within a hundred ticks and answers for itself, which is a better authority than a saved bit.
     */
    private boolean breached;
    /**
     * This vent has held a seal at least once since it loaded.
     * <p>
     * It is what tells "the room opened" apart from "the room is not built yet", and both look
     * identical from a single tick: no seal, no zone, air in hand. A vent that has never sealed is
     * simply a machine somebody has just placed.
     */
    private boolean everSealed;
    private ModuleButton priorityButton;


    public TileOxygenVent() {
        super(1000, 2, 2000);
        isSealed = true;
        firstRun = true;
        hasFluid = true;
        soundInit = false;
        allowTrace = false;
        numScrubbers = 0;
        scrubbers = new LinkedList<>();
        state = RedstoneState.ON;
        redstoneControl = new ModuleRedstoneOutputButton(174, 4, PACKET_REDSTONE_ID, "", this);
        traceToggle = new ModuleToggleSwitch(80, 20, PACKET_TRACE_ID, LibVulpes.proxy.getLocalizedString("msg.vent.trace"), this, TextureResources.buttonGeneric, 80, 18, false);
    }

    public TileOxygenVent(int energy, int invSize, int tankSize) {
        super(energy, invSize, tankSize);
        isSealed = false;
        firstRun = false;
        hasFluid = true;
        soundInit = false;
        allowTrace = false;
        scrubbers = new LinkedList<>();
        state = RedstoneState.ON;
        redstoneControl = new ModuleRedstoneOutputButton(174, 4, 0, "", this);
        traceToggle = new ModuleToggleSwitch(80, 20, 5, LibVulpes.proxy.getLocalizedString("msg.vent.trace"), this, TextureResources.buttonGeneric, 80, 18, false);
    }

    @Override
    public boolean canPerformFunction() {
        return AtmosphereHandler.hasAtmosphereHandler(this.world);
    }

    @Override
    public World getWorldObj() {
        return getWorld();
    }

    @Override
    public void onAdjacentBlockUpdated() {
        blockUpdated = true; // the performFunction will take it from here
    }

    private void activateAdjBlocks() {
        numScrubbers = 0;
        numScrubbers = toggleAdjBlock(pos.add(1, 0, 0), true) ? numScrubbers + 1 : numScrubbers;
        numScrubbers = toggleAdjBlock(pos.add(-1, 0, 0), true) ? numScrubbers + 1 : numScrubbers;
        numScrubbers = toggleAdjBlock(pos.add(0, 1, 0), true) ? numScrubbers + 1 : numScrubbers;
        numScrubbers = toggleAdjBlock(pos.add(0, -1, 0), true) ? numScrubbers + 1 : numScrubbers;
        numScrubbers = toggleAdjBlock(pos.add(0, 0, 1), true) ? numScrubbers + 1 : numScrubbers;
        numScrubbers = toggleAdjBlock(pos.add(0, 0, -1), true) ? numScrubbers + 1 : numScrubbers;
    }

    private void deactivateAdjBlocks() {
        toggleAdjBlock(pos.add(1, 0, 0), false);
        toggleAdjBlock(pos.add(-1, 0, 0), false);
        toggleAdjBlock(pos.add(0, 1, 0), false);
        toggleAdjBlock(pos.add(0, -1, 0), false);
        toggleAdjBlock(pos.add(0, 0, 1), false);
        toggleAdjBlock(pos.add(0, 0, -1), false);
    }

    private boolean toggleAdjBlock(BlockPos pos, boolean on) {
        IBlockState state = this.world.getBlockState(pos);
        Block block = state.getBlock();
        if (block == StellurgyBlocks.blockCO2Scrubber) {
            ((BlockTile) block).setBlockState(world, state, pos, on);

            return true;
        }
        return false;
    }

    private void unregisterAtmosphereBlob() {
        if (world == null || world.isRemote) {
            return;
        }

        AtmosphereHandler atmhandler = AtmosphereHandler.getOxygenHandler(world);
        if (atmhandler != null) {
            atmhandler.unregisterBlob(this);
        }
    }

    @Override
    public void invalidate() {
        unregisterAtmosphereBlob();
        leaveVentilationNetwork();
        deactivateAdjBlocks();
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        unregisterAtmosphereBlob();
        leaveVentilationNetwork();
        super.onChunkUnload();
    }

    @Override
    public int getPowerPerOperation() {
        return (int) ((numScrubbers * 10 + 1) * StellurgyConfiguration.getCurrentConfig().oxygenVentPowerMultiplier);
    }

    @Override
    public boolean canFill(Fluid fluid) {
        return FluidUtils.areFluidsSameType(fluid, StellurgyFluids.fluidOxygen) && super.canFill(fluid);
    }

    public boolean isTurnedOn() {
        if (state == RedstoneState.OFF)
            return true;

        boolean state2 = world.isBlockIndirectlyGettingPowered(pos) > 0;

        if (state == RedstoneState.INVERTED)
            state2 = !state2;
        return state2;
    }

    @Override
    public void performFunction() {


        if (blockUpdated) { // this was moved from onAdjacentBlockUpdated(); to prevent crash
            if (isSealed)
                activateAdjBlocks();
            scrubbers.clear();
            TileEntity[] tiles = new TileEntity[6];
            tiles[0] = world.getTileEntity(pos.add(1, 0, 0));
            tiles[1] = world.getTileEntity(pos.add(-1, 0, 0));
            tiles[2] = world.getTileEntity(pos.add(0, 1, 0));
            tiles[3] = world.getTileEntity(pos.add(0, -1, 0));
            tiles[4] = world.getTileEntity(pos.add(0, 0, 1));
            tiles[5] = world.getTileEntity(pos.add(0, 0, -1));


            for (TileEntity tile : tiles) {
                if (tile instanceof TileCO2Scrubber && world.getBlockState(tile.getPos()).getBlock() == StellurgyBlocks.blockCO2Scrubber)
                    scrubbers.add((TileCO2Scrubber) tile);
            }
            blockUpdated = false;
        }


        /* NB: canPerformFunction returns false and must return true for performFunction to execute
         * if there is no O2 handler, this is why we can safely call AtmosphereHandler.getOxygenHandler
         * and not have to worry about an NPE being thrown
         */

        //IF first tick then register the blob and check for scrubbers

        if (!world.isRemote) {
            AtmosphereHandler atmhandler = AtmosphereHandler.getOxygenHandler(this.world);
            if (atmhandler == null)
                return;

            if (firstRun) {
                atmhandler.registerBlob(this, pos);

                // The blob exists only now, so this is the earliest the saved gases can go back
                // in. Cleared either way: a failed restore must not be retried every tick.
                if (pendingAirState != null) {
                    atmhandler.setAirState(this, pendingAirState);
                    pendingAirState = null;
                }

                onAdjacentBlockUpdated();
                //isSealed starts as true so we can accurately check for scrubbers, we now set it to false to force the tile to check for a seal on first run
                setSealed(false);
                firstRun = false;
            }

            // Observed every tick, never behind the `% 100` gate below. That gate is a world-clock
            // modulo, and a force-ticked tile sees the same world time on every one of its ticks —
            // the very trap `ventBreachedAir` keeps its own counter to avoid. A fact about whether
            // this vent has ever held a seal must not be reachable only on one tick in a hundred.
            everSealed |= isSealed;
            // A vent that HAS sealed here and now has no zone left has lost its room, whether it
            // notices while still flagged sealed or after something else has already cleared the
            // flag. Both orders happen: breaking the hull runs a block update that can unseal the
            // vent before its own tick comes round, which is why testing `isSealed` alone missed the
            // ordinary case of a player opening a door.
            if (everSealed && atmhandler.getBlobSize(this) == 0) {
                breached = true;
            }
            if (isSealed && atmhandler.getBlobSize(this) == 0) {
                deactivateAdjBlocks();
                setSealed(false);
            }

            if (isSealed && !isTurnedOn()) {
                atmhandler.clearBlob(this);

                deactivateAdjBlocks();

                setSealed(false);
            } else if (!isSealed && isTurnedOn() && hasEnoughEnergy(getPowerPerOperation())) {

                if (++ticksSinceSealCheck >= SEAL_CHECK_TICKS) {
                    ticksSinceSealCheck = 0;
                    // The check RAN, so its answer is worth acting on either way: sealed means the
                    // hull closed, failed means it is open. Before it runs there is no answer, which
                    // is why nothing above this line may conclude a breach.
                    boolean sealed = atmhandler.addBlock(this, new HashedBlockPosition(pos));
                    breached = !sealed;
                    everSealed |= sealed;
                    setSealed(sealed);
                }

                if (isSealed) {
                    activateAdjBlocks();
                } else if (allowTrace && ++ticksSinceTraceStep >= TRACE_STEP_TICKS) {
                    ticksSinceTraceStep = 0;
                    radius++;
                    if (radius > 128)
                        radius = 0;
                }
            }

            if (isSealed) {

                //If scrubbers exist and the config allows then use the cartridge
                if (StellurgyConfiguration.getCurrentConfig().scrubberRequiresCartrige) {
                    //TODO: could be optimized
                    if (++ticksSinceScrubberCharge >= SCRUBBER_CHARGE_TICKS) {
                        ticksSinceScrubberCharge = 0;
                        numScrubbers = 0;
                        for (TileCO2Scrubber scrubber : scrubbers) {
                            numScrubbers = scrubber.useCharge() ? numScrubbers + 1 : numScrubbers;
                        }
                    }

                }

                int amtToDrain = (int) Math.ceil((atmhandler.getBlobSize(this) * getGasUsageMultiplier()));
                FluidStack drainedFluid = this.drain(amtToDrain, false);

                if ((drainedFluid != null && drainedFluid.amount >= amtToDrain) || amtToDrain == 0) {
                    this.drain(amtToDrain, true);
                    replenishOxygen(atmhandler, amtToDrain);
                    if (!hasFluid) {
                        hasFluid = true;

                        activateAdjBlocks();

                        atmhandler.refreshDerivedAtmosphere(this);
                    }
                } else if (hasFluid) {
                    // NOT the planet's atmosphere. That was right while a zone had no contents of its
                    // own and "the vent stopped supplying" therefore meant "the room is outside
                    // again" — but a sealed room keeps the air it already holds when its tank runs
                    // dry, and the crew go on breathing it down. Asserting vacuum over a room full of
                    // air made the label disagree with the gas until something happened to derive it,
                    // and the block-level readers of that label (fire, combustion) have no crew
                    // walking past to trigger the refresh.
                    atmhandler.refreshDerivedAtmosphere(this);

                    deactivateAdjBlocks();

                    hasFluid = false;
                }
            }

        }
    }

    @Override
    public int getTraceDistance() {
        return allowTrace ? radius : -1;
    }

    /**
     * Sealed and actually supplying gas — the same two facts that make this vent publish a
     * breathable atmosphere for its zone. Life support may move that zone's gases exactly while
     * this holds.
     */
    @Override
    public boolean isMaintainingAtmosphere() {
        return isSealed && hasFluid;
    }

    // ─── ventilation network: this vent IS its zone's port ─────────────
    //
    // The vent already owns the zone — it defines it, maintains it and is the authority on whether
    // life support may touch it — so making it the network's sink is the whole of "one vent per
    // zone connects to the plant" (D127-5). No second object learns what a zone is.

    @Override
    public SubsystemNetworkDomain getNetworkDomain() {
        return LifeSupportNetwork.DOMAIN;
    }

    @Override
    public World getNodeWorld() {
        return world;
    }

    @Override
    public BlockPos getNodePos() {
        return pos;
    }

    /**
     * Regeneration work this zone could use this tick: all of its carbon dioxide, as an absolute
     * amount. A zone has no buffer to fill — it asks for what is wrong with its air, and the
     * network's supply and ducts decide how much of that it gets.
     */
    @Override
    public int getRequested() {
        AirState air = zoneAirForNetwork();
        return air == null ? 0 : LifeSupportNetwork.absolute(air.getCarbonDioxide(), zoneVolume());
    }

    @Override
    public int getFreeCapacity() {
        return getRequested();
    }

    /**
     * Which zones the plant serves first when it cannot serve them all. Set per vent from its own
     * screen; every zone starts equal, so a ship nobody has configured shares what there is.
     */
    @Override
    public int getPriority() {
        return zonePriority;
    }

    public int getZonePriority() {
        return zonePriority;
    }

    /** Steps up and wraps back to the bottom, so one button covers the whole range. */
    private void cycleZonePriority() {
        zonePriority = zonePriority >= PRIORITY_MAX ? PRIORITY_MIN : zonePriority + 1;
        markDirty();
    }

    private String priorityLabel() {
        String key = zonePriority > 0 ? "msg.vent.priority.high"
                : zonePriority < 0 ? "msg.vent.priority.low"
                : "msg.vent.priority.normal";
        return LibVulpes.proxy.getLocalizedString(key);
    }

    @Override
    public int receive(int amount) {
        AirState air = zoneAirForNetwork();
        if (air == null || amount <= 0)
            return 0;
        int volume = zoneVolume();
        long converted = air.regenerate(LifeSupportNetwork.partialPressure(amount, volume));
        if (converted <= 0L)
            return 0;

        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world);
        if (handler != null)
            handler.refreshDerivedAtmosphere(this);
        return LifeSupportNetwork.absolute(converted, volume);
    }

    /** The zone's gases, but only while this vent is actually maintaining them. */
    @Nullable
    private AirState zoneAirForNetwork() {
        if (world == null || world.isRemote || !isMaintainingAtmosphere()
                || !StellurgyConfiguration.getCurrentConfig().lifeSupportZones)
            return null;
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world);
        return handler == null ? null : handler.getAirState(this);
    }

    private int zoneVolume() {
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world);
        return handler == null ? 1 : Math.max(1, handler.getBlobSize(this));
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (world != null && !world.isRemote) {
            SubsystemNetworkManager.of(world).register(this);
            SubsystemNetworkManager.of(world).markDirty(LifeSupportNetwork.DOMAIN, world);
        }
    }

    private void leaveVentilationNetwork() {
        if (world != null && !world.isRemote) {
            SubsystemNetworkManager.of(world).unregister(this);
            SubsystemNetworkManager.of(world).markDirty(LifeSupportNetwork.DOMAIN, world);
        }
    }

    @Override
    public void update() {
        ventBreachedAir();
        if (canPerformFunction()) {

            if (hasEnoughEnergy(getPowerPerOperation())) {
                performFunction();
                if (!world.isRemote && isSealed) this.energy.extractEnergy(getPowerPerOperation(), false);
            } else
                notEnoughEnergyForFunction();
        } else
            radius = -1;
        if (!soundInit && world.isRemote) {
            LibVulpes.proxy.playSound(new RepeatingSound(AudioRegistry.airHissLoop, SoundCategory.BLOCKS, this));
        }
        soundInit = true;
    }


    /**
     * A breached zone loses its air to space instead of losing it to bookkeeping.
     * <p>
     * When a hull is opened the flood-fill drops the room's cells, but the gases are not the cells:
     * {@code AreaBlob.clearBlob()} empties the GRAPH while the blob keeps its {@code AirState}, and
     * the handler hands that state back by VENT rather than by membership. So the air outlives the
     * room by exactly as long as it takes to escape, which is the difference between a breach that
     * costs the ship a tankful and one that costs nothing because the air was deleted rather than
     * lost. Runs whatever the power state: a hole does not need electricity.
     * <p>
     * All three gases go together and proportionally — vacuum does not sort them.
     */
    /**
     * The oxygen the vent just spent BECOMES the room's oxygen.
     * <p>
     * <b>Without this the machine only ever set a LABEL.</b> It drained its tank, published
     * {@code PRESSURIZEDAIR} and left the zone's actual composition untouched — so a sealed, powered,
     * oxygen-fed room could sit at zero oxygen while calling itself pressurised, and the first
     * question anyone asked of the gas rather than of the name answered "unbreathable". A label is a
     * view of the state; it cannot stand in for it.
     * <p>
     * The amount is not a new tuning number: it is the fluid the vent already pays, converted at the
     * one exchange rate the rest of life support uses, so a bigger room costs proportionally more to
     * fill and the fuel economy is exactly what it was.
     * <p>
     * <b>It tops up to SEA LEVEL and stops there</b>, rather than to the safe band's ceiling. A vent
     * maintains a room; enriching one that is already breathable would walk it toward the toxic and
     * fire-prone end on its own, which is a thing a player asks the separator for on purpose and
     * never a thing the life-support machine should do behind their back. A room that needs nothing
     * therefore receives nothing — and, because gas arrives at the temperature it was stored at, a
     * hot room is not quietly chilled by a machine that had no work to do.
     */
    private void replenishOxygen(AtmosphereHandler atmhandler, int millibuckets) {
        if (millibuckets <= 0 || !StellurgyConfiguration.getCurrentConfig().lifeSupportZones)
            return;
        AirState air = atmhandler.getAirState(this);
        if (air == null)
            return;
        long missing = Math.min(AirState.earthLike().getOxygen() - air.getOxygen(),
                air.oxygenHeadroom());
        if (missing <= 0L)
            return;
        long denominator = (long) Math.max(1, atmhandler.getBlobSize(this))
                * StellurgyConfiguration.getCurrentConfig().lifeSupportFluidPerAtmBlock;
        if (denominator <= 0L)
            return;
        long admitted = Math.min((long) millibuckets * AirState.ONE_ATM / denominator, missing);
        if (admitted > 0L) {
            air.addOxygen(admitted, AirState.ambientKelvin());
            markDirty();
        }
    }

    private void ventBreachedAir() {
        if (world == null || world.isRemote || !breached || isMaintainingAtmosphere()
                || !StellurgyConfiguration.getCurrentConfig().lifeSupportZones)
            return;
        long ratePerSecond = StellurgyConfiguration.getCurrentConfig().lifeSupportBreachVentRate;
        if (ratePerSecond <= 0L)
            return;
        // Its own counter, never a world-clock modulo: that would wake every breached vent in the
        // world on one tick and would be invisible to a force-ticking harness.
        if (++ticksSinceVenting < 20)
            return;
        ticksSinceVenting = 0;

        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world);
        if (handler == null)
            return;
        AirState air = handler.getAirState(this);
        if (air == null || air.getTotalPressure() <= 0L)
            return;

        air.drawNitrogen(ratePerSecond);
        air.drawOxygen(ratePerSecond);
        air.drawCarbonDioxide(ratePerSecond);
        markDirty();
    }

    private void setSealed(boolean sealed) {
        boolean prevSealed = isSealed;
        if ((prevSealed != sealed)) {
            markDirty();
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 2);

            if (isSealed)
                radius = -1;
        }
        isSealed = sealed;
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, getBlockMetadata(), getUpdateTag());

    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        handleUpdateTag(pkt.getNbtCompound());
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        tag.setBoolean("isSealed", isSealed);

        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        isSealed = tag.getBoolean("isSealed");

        if (isSealed) {
            activateAdjBlocks();
        }
    }

    public float getGasUsageMultiplier() {
        return (float) (Math.max(0.01f - numScrubbers * 0.005f, 0) * StellurgyConfiguration.getCurrentConfig().oxygenVentConsumptionMult);
    }

    @Override
    public void notEnoughEnergyForFunction() {
        if (!world.isRemote) {
            AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(this.world);
            if (handler != null)
                handler.clearBlob(this);

            deactivateAdjBlocks();

            setSealed(false);
        }
    }


    @Override
    @Nonnull
    public int[] getSlotsForFace(@Nullable EnumFacing side) {
        return new int[]{};
    }

    @Override
    public boolean isItemValidForSlot(int slot, @Nonnull ItemStack itemStack) {
        return false;
    }

    @Override
    public boolean canBlobsOverlap(HashedBlockPosition blockPosition, AreaBlob blob) {
        return false;
    }

    @Override
    public int getMaxBlobRadius() {
        return StellurgyConfiguration.getCurrentConfig().oxygenVentSize;
    }

    @Override
    @Nonnull
    public HashedBlockPosition getRootPosition() {
        return new HashedBlockPosition(pos);
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        ArrayList<ModuleBase> modules = new ArrayList<>();

        modules.add(new ModuleSlotArray(52, 20, this, 0, 1));
        modules.add(new ModuleSlotArray(52, 57, this, 1, 2));
        modules.add(new ModulePower(18, 20, this));
        modules.add(new ModuleLiquidIndicator(32, 20, this));
        modules.add(redstoneControl);
        modules.add(traceToggle);
        priorityButton = new ModuleButton(80, 40, PACKET_PRIORITY_ID, priorityLabel(), this,
                TextureResources.buttonGeneric, 80, 18);
        modules.add(priorityButton);
        //modules.add(toggleSwitch = new ModuleToggleSwitch(160, 5, 0, "", this, TextureResources.buttonToggleImage, 11, 26, getMachineEnabled()));
        return modules;
    }

    @Override
    public void setInventorySlotContents(int slot, @Nonnull ItemStack stack) {
        super.setInventorySlotContents(slot, stack);

        while (FluidUtils.attemptDrainContainerIInv(inventory, this.tank, getStackInSlot(0), 0, 1)) ;
    }

    @Override
    public String getModularInventoryName() {
        return StellurgyBlocks.blockOxygenVent.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }

    @Override
    public boolean canFormBlob() {
        return isTurnedOn();
    }

    @Override
    public boolean isRunning() {
        return isSealed;
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        if (buttonId == PACKET_REDSTONE_ID) {
            state = redstoneControl.getState();
            PacketHandler.sendToServer(new PacketMachine(this, PACKET_REDSTONE_ID));
        }
        if (buttonId == PACKET_TRACE_ID) {
            allowTrace = traceToggle.getState();
            PacketHandler.sendToServer(new PacketMachine(this, PACKET_TRACE_ID));
        }
        if (buttonId == PACKET_PRIORITY_ID) {
            // Step the client's own copy so the label answers immediately, then tell the server —
            // which steps its own and is the one the network reads.
            cycleZonePriority();
            if (priorityButton != null)
                priorityButton.setText(priorityLabel());
            PacketHandler.sendToServer(new PacketMachine(this, PACKET_PRIORITY_ID));
        }
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        if (id == PACKET_REDSTONE_ID)
            out.writeByte(state.ordinal());
        else if (id == PACKET_TRACE_ID)
            out.writeBoolean(allowTrace);
        else if (id == PACKET_PRIORITY_ID)
            out.writeInt(zonePriority);
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId,
                                    NBTTagCompound nbt) {
        if (packetId == PACKET_REDSTONE_ID)
            nbt.setByte("state", in.readByte());
        else if (packetId == PACKET_TRACE_ID)
            nbt.setBoolean("trace", in.readBoolean());
        else if (packetId == PACKET_PRIORITY_ID)
            nbt.setInteger("zonePriority", in.readInt());
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id,
                               NBTTagCompound nbt) {
        if (id == PACKET_REDSTONE_ID)
            state = RedstoneState.values()[nbt.getByte("state")];
        else if (id == PACKET_TRACE_ID) {
            allowTrace = nbt.getBoolean("trace");
            if (!allowTrace)
                radius = -1;
        }
        else if (id == PACKET_PRIORITY_ID) {
            // The client sends the priority it now shows; the server clamps rather than trusts, so a
            // malformed packet cannot invent a tier that out-ranks every real one.
            zonePriority = Math.max(PRIORITY_MIN, Math.min(PRIORITY_MAX, nbt.getInteger("zonePriority")));
            markDirty();
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);

        state = RedstoneState.values()[nbt.getByte("redstoneState")];
        redstoneControl.setRedstoneState(state);
        allowTrace = nbt.getBoolean("allowtrace");

        // The zone itself is rebuilt from the world on load, but what was IN it is not derivable
        // from blocks — a cabin the crew had half-used would come back full of fresh air. Held
        // here until the blob exists (registerBlob happens on the first tick, not on load).
        if (nbt.hasKey("airState"))
            pendingAirState = AirState.readFromNBT(nbt.getCompoundTag("airState"));
        zonePriority = Math.max(PRIORITY_MIN, Math.min(PRIORITY_MAX, nbt.getInteger("zonePriority")));
    }

    /**
     * The gases in the zone this vent anchors: the live blob's if it has one, otherwise whatever
     * is still waiting to be restored into it. Null when this vent has never had a zone.
     */
    private AirState getZoneAirState() {
        if (world != null && !world.isRemote) {
            AtmosphereHandler atmhandler = AtmosphereHandler.getOxygenHandler(world);
            if (atmhandler != null) {
                AirState live = atmhandler.getAirState(this);
                if (live != null)
                    return live;
            }
        }
        return pendingAirState;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setByte("redstoneState", (byte) state.ordinal());
        nbt.setBoolean("allowtrace", allowTrace);
        nbt.setInteger("zonePriority", zonePriority);

        AirState air = getZoneAirState();
        if (air != null) {
            NBTTagCompound airTag = new NBTTagCompound();
            air.writeToNBT(airTag);
            nbt.setTag("airState", airTag);
        }
        return nbt;
    }

    @Override
    public boolean isEmpty() {
        return inventory.isEmpty();
    }

    @Override
    public void stateUpdated(ModuleBase module) {
        if (module.equals(traceToggle)) {
            allowTrace = ((ModuleToggleSwitch) module).getState();
            PacketHandler.sendToServer(new PacketMachine(this, PACKET_TRACE_ID));
        }
    }
}