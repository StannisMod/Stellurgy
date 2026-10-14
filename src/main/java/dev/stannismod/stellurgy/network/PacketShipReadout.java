package dev.stannismod.stellurgy.network;

import java.io.IOException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import dev.stannismod.stellurgy.libvulpes.network.BasePacket;
import dev.stannismod.stellurgy.ship.control.ShipReadout;

/**
 * A ship's readout, sent to one player: the pilot at its helm, someone with its flight computer's
 * console open, or whoever pressed Scan on the assembler it stands on. Nobody else receives it, so a
 * ship nobody is looking at costs nothing on the wire.
 *
 * <p>Carries the primitives and the live flight slice; the client keeps them on its own copy of the
 * addressed tile ({@link IShipReadoutReceiver}), where the surfaces draw them from.</p>
 */
public class PacketShipReadout extends BasePacket {

    private BlockPos flightComputer;
    private ShipReadout readout;
    private boolean saturated;
    private double wheelFill;

    public PacketShipReadout() {
    }

    public PacketShipReadout(BlockPos flightComputer, ShipReadout readout, boolean saturated,
                             double wheelFill) {
        this.flightComputer = flightComputer;
        this.readout = readout;
        this.saturated = saturated;
        this.wheelFill = wheelFill;
    }

    @Override
    public void write(ByteBuf out) {
        out.writeLong(flightComputer.toLong());
        out.writeBoolean(saturated);
        out.writeDouble(wheelFill);
        try (ByteBufOutputStream stream = new ByteBufOutputStream(out)) {
            readout.write(stream);
        } catch (IOException e) {
            throw new IllegalStateException("could not encode a ship readout", e);
        }
    }

    @Override
    public void readClient(ByteBuf in) {
        flightComputer = BlockPos.fromLong(in.readLong());
        saturated = in.readBoolean();
        wheelFill = in.readDouble();
        try (ByteBufInputStream stream = new ByteBufInputStream(in)) {
            readout = ShipReadout.read(stream);
        } catch (IOException e) {
            throw new IllegalStateException("could not decode a ship readout", e);
        }
    }

    @Override
    public void read(ByteBuf in) {
        // server-bound direction does not exist
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void executeClient(EntityPlayer player) {
        TileEntity tile = Minecraft.getMinecraft().world == null ? null
                : Minecraft.getMinecraft().world.getTileEntity(flightComputer);
        if (tile instanceof IShipReadoutReceiver) {
            ((IShipReadoutReceiver) tile).acceptReadout(readout, saturated, wheelFill);
        }
    }

    @Override
    public void executeServer(EntityPlayerMP player) {
        // server-bound direction does not exist
    }
}
