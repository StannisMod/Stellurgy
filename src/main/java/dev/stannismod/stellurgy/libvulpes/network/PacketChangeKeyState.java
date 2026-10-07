package dev.stannismod.stellurgy.libvulpes.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import dev.stannismod.stellurgy.libvulpes.util.InputSyncHandler;

/**
 * A client's key going up or down. It speaks only for the sender, so the one thing the server checks
 * is that it decodes: a body shorter than a key and a state is dropped, logged.
 */
public class PacketChangeKeyState extends BasePacket {

	/** An int key and a boolean state. */
	private static final int BODY_BYTES = Integer.BYTES + 1;

	int key;
	boolean state;
	/** Whether {@link #read} found a whole body. */
	private boolean decoded;

	public PacketChangeKeyState(int key, boolean state) {
		this.key = key;
		this.state = state;
	}

	public PacketChangeKeyState() {}

	@Override
	public void write(ByteBuf out) {
		out.writeInt(key);
		out.writeBoolean(state);
	}

	@Override
	public void readClient(ByteBuf in) {
		in.skipBytes(in.readableBytes());
	}

	@Override
	public void read(ByteBuf in) {
		if (in.readableBytes() < BODY_BYTES) {
			in.skipBytes(in.readableBytes());
			return;
		}
		key = in.readInt();
		state = in.readBoolean();
		decoded = true;
	}

	@Override
	public void executeClient(EntityPlayer thePlayer) {

	}

	@Override
	public void executeServer(EntityPlayerMP player) {
		if (!decoded) {
			PacketSenderCheck.refuse(player, "key-state packet", "its body is shorter than " + BODY_BYTES + " bytes");
			return;
		}
		InputSyncHandler.updateKeyPress(player, key, state);
	}

}
