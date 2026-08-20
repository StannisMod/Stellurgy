package zmaster587.advancedRocketry.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.network.PacketBuffer;
import zmaster587.advancedRocketry.atmosphere.AtmosphereHandler;
import zmaster587.advancedRocketry.atmosphere.AtmosphereSummary;
import zmaster587.libVulpes.network.BasePacket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * What the air around you looks like, for drawing.
 * <p>
 * <b>It used to send the atmosphere's NAME</b>, and the client looked that name up in a registry to
 * get an object it then asked questions of. Two things were wrong with that. The client held a model
 * it could interrogate, so a mechanic could quietly come to depend on the client's answer; and
 * behaviour keyed on a string, which is the coupling the whole gas model exists to remove.
 * <p>
 * What crosses now is a finished readout — a pressure, whether it can be breathed, a warning to show,
 * and the statements that are true of it, which is what a player reads as its name. None of it is
 * decided client-side, so staleness costs a lagging line of text and nothing else.
 */
public class PacketAtmSync extends BasePacket {

    private AtmosphereSummary summary;

    public PacketAtmSync(AtmosphereSummary summary) {
        this.summary = summary;
    }

    public PacketAtmSync() {
        this.summary = AtmosphereSummary.UNKNOWN;
    }

    @Override
    public void write(ByteBuf out) {
        NBTTagCompound nbt = new NBTTagCompound();

        nbt.setShort("pressure", (short) summary.pressureCentiAtm());
        nbt.setBoolean("breathable", summary.breathable());
        nbt.setString("warning", summary.warningKey());
        // The statements travel by NAME, never by ordinal: inserting one in the middle of the enum
        // would otherwise re-label every readout in flight.
        NBTTagList holding = new NBTTagList();
        for (String assertion : summary.assertions()) {
            holding.appendTag(new NBTTagString(assertion));
        }
        nbt.setTag("holds", holding);

        new PacketBuffer(out).writeCompoundTag(nbt);
    }

    @Override
    public void readClient(ByteBuf in) {
        PacketBuffer packetBuffer = new PacketBuffer(in);

        try {
            NBTTagCompound nbt = packetBuffer.readCompoundTag();
            List<String> holding = new ArrayList<>();
            NBTTagList list = nbt.getTagList("holds", 8);
            for (int i = 0; i < list.tagCount(); i++) {
                holding.add(list.getStringTagAt(i));
            }
            summary = new AtmosphereSummary(nbt.getShort("pressure"), nbt.getBoolean("breathable"),
                    nbt.getString("warning"), holding);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void read(ByteBuf in) {
        //Do nothing on server, we don't want hackers now do we ;)
    }

    @Override
    public void executeClient(EntityPlayer thePlayer) {
        AtmosphereHandler.currentSummary = summary;
    }

    @Override
    public void executeServer(EntityPlayerMP player) {

    }

}
