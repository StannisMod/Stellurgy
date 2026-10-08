package dev.stannismod.stellurgy.tile.atmosphere;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import dev.stannismod.stellurgy.api.AreaBlob;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.atmosphere.AtmosphereHandler;
import dev.stannismod.stellurgy.atmosphere.LifeSupportNetwork;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IButtonInventory;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IModularInventory;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleButton;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.network.PacketMachine;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;
import dev.stannismod.stellurgy.libvulpes.util.INetworkMachine;
import dev.stannismod.stellurgy.subsystem.network.ISubsystemSink;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkDomain;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Tier 4: a room's place on the ventilation network.
 *
 * <p>It holds its room's zone ({@link SealedZone}) and is the network's sink there: the plant's
 * regeneration work reaches the room through it. It carries AIR WORK and no oxygen, so it has no
 * tank — gas reaches a room from a supply standing in it (a vent's tank, a separator combining), and
 * the network keeps that air fit to breathe.</p>
 *
 * <p>It draws {@code lifeSupportPortFePerTick} to hold its zone, and that power is not wired to it:
 * the plants on its network pay it through the ducts each tick. A port nobody paid this tick or the
 * last holds no zone, exactly as an unpowered vent holds none.</p>
 */
public class TileVentilationPort extends TileEntity implements ITickable, IZonePort, ISubsystemSink,
        LifeSupportNetwork.UpkeepConsumer, IModularInventory, IButtonInventory, INetworkMachine {

    private static final byte PACKET_PRIORITY_ID = 4;
    /** Lowest and highest zone priority a player can dial in; 0 is the default everything starts at. */
    private static final int PRIORITY_MIN = -1;
    private static final int PRIORITY_MAX = 1;

    private final SealedZone zone = new SealedZone(this, this::markDirty);
    /** The seal as the server last reported it; the client keeps no zone of its own. */
    private boolean clientSealed;
    /** The world time this port's running cost was last paid. Not persisted: payment is per tick. */
    private long paidAtWorldTime = Long.MIN_VALUE;
    /**
     * Which zones the plant serves first when it cannot serve them all. Every zone starts equal — a
     * ship where nothing is prioritised is one where the plant shares what it has.
     */
    private int zonePriority;
    private ModuleButton priorityButton;

    // ─── power, through the duct ──────────────────────────────────────

    @Override
    public void upkeepPaid() {
        if (world != null)
            paidAtWorldTime = world.getTotalWorldTime();
    }

    /**
     * Paid this tick or the last. The network may solve after this tile in a tick, so last tick's
     * payment still counts; a tile ticked without the world advancing keeps the payment it had.
     */
    public boolean isPowered() {
        return world != null && paidAtWorldTime >= world.getTotalWorldTime() - 1L;
    }

    // ─── the zone ──────────────────────────────────────────────────────

    @Override
    public void update() {
        if (world == null || world.isRemote)
            return;
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world);
        if (handler == null)
            return;
        if (!zone.isRegistered())
            zone.register(handler, pos);
        boolean was = zone.isSealed();
        if (zone.tick(handler, pos, canFormBlob()))
            sealChanged();
        if (was != zone.isSealed())
            SubsystemNetworkManager.of(world).markDirty(LifeSupportNetwork.DOMAIN, world);
    }

    private void sealChanged() {
        markDirty();
        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
    }

    @Override
    public boolean isSealed() {
        return world != null && world.isRemote ? clientSealed : zone.isSealed();
    }

    @Override
    public boolean checkSealNow() {
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world);
        if (handler == null || !zone.isRegistered())
            return false;
        boolean was = zone.isSealed();
        boolean sealed = zone.checkSeal(handler, pos);
        if (was != sealed)
            sealChanged();
        return sealed;
    }

    @Override
    public boolean isMaintainingAtmosphere() {
        return zone.isSealed();
    }

    /** Only while something has paid for it. */
    @Override
    public boolean canFormBlob() {
        return isPowered();
    }

    @Override
    public World getWorldObj() {
        return world;
    }

    @Override
    public boolean canBlobsOverlap(HashedBlockPosition blockPosition, AreaBlob blob) {
        return false;
    }

    /** The same bound as a vent's zone: it is a limit on the flood fill, not on the machine. */
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
    public int getTraceDistance() {
        return -1;
    }

    // ─── the network ───────────────────────────────────────────────────

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
     * amount. A zone has no buffer to fill — it asks for what is wrong with its air.
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

    @Override
    public int getPriority() {
        return zonePriority;
    }

    public int getZonePriority() {
        return zonePriority;
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
        markDirty();
        return LifeSupportNetwork.absolute(converted, volume);
    }

    /** The zone's gases, while there is a zone for the network to serve. */
    @Nullable
    private AirState zoneAirForNetwork() {
        if (world == null || world.isRemote || !zone.isSealed())
            return null;
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world);
        return handler == null ? null : handler.getAirState(this);
    }

    private int zoneVolume() {
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world);
        return handler == null ? 1 : Math.max(1, handler.getBlobSize(this));
    }

    // ─── lifecycle ─────────────────────────────────────────────────────

    @Override
    public void onLoad() {
        super.onLoad();
        if (world != null && !world.isRemote) {
            SubsystemNetworkManager.of(world).register(this);
            SubsystemNetworkManager.of(world).markDirty(LifeSupportNetwork.DOMAIN, world);
        }
    }

    @Override
    public void invalidate() {
        leave();
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        leave();
        super.onChunkUnload();
    }

    private void leave() {
        if (world == null || world.isRemote)
            return;
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world);
        if (handler != null)
            handler.unregisterBlob(this);
        SubsystemNetworkManager.of(world).unregister(this);
        SubsystemNetworkManager.of(world).markDirty(LifeSupportNetwork.DOMAIN, world);
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
        tag.setBoolean("isSealed", isSealed());
        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        clientSealed = tag.getBoolean("isSealed");
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        zonePriority = Math.max(PRIORITY_MIN, Math.min(PRIORITY_MAX, nbt.getInteger("zonePriority")));
        zone.readFromNBT(nbt);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setInteger("zonePriority", zonePriority);
        zone.writeToNBT(nbt, world != null && !world.isRemote ? AtmosphereHandler.getOxygenHandler(world) : null);
        return nbt;
    }

    // ─── screen: the zone's priority ───────────────────────────────────

    @Override
    public List<ModuleBase> getModules(int id, EntityPlayer player) {
        List<ModuleBase> modules = new ArrayList<>(1);
        priorityButton = new ModuleButton(48, 30, PACKET_PRIORITY_ID, priorityLabel(), this,
                TextureResources.buttonGeneric, 80, 18);
        modules.add(priorityButton);
        return modules;
    }

    @Override
    public String getModularInventoryName() {
        return StellurgyBlocks.blockVentilationPort.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
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
    public void onInventoryButtonPressed(int buttonId) {
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
        if (id == PACKET_PRIORITY_ID)
            out.writeInt(zonePriority);
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId, NBTTagCompound nbt) {
        if (packetId == PACKET_PRIORITY_ID)
            nbt.setInteger("zonePriority", in.readInt());
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
        if (id == PACKET_PRIORITY_ID) {
            // The client sends the priority it now shows; the server clamps rather than trusts, so a
            // malformed packet cannot invent a tier that out-ranks every real one.
            zonePriority = Math.max(PRIORITY_MIN, Math.min(PRIORITY_MAX, nbt.getInteger("zonePriority")));
            markDirty();
        }
    }
}
