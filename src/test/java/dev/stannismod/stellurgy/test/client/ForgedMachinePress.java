package dev.stannismod.stellurgy.test.client;

import java.io.IOException;

import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;

import com.github.stannismod.forge.testing.client.ClientBot;

import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.network.PacketMachine;
import dev.stannismod.stellurgy.tile.weapon.TileWeaponConsole;

/**
 * A weapon-console press written by the real client for a console it need not be anywhere near — what
 * a modified client can send, since a machine packet's address is the client's to write.
 *
 * <p>The packet is production's own {@link PacketMachine}, sent by production's
 * {@link PacketHandler#sendToServer} on the client thread; only its ADDRESS is forged, from a console
 * that exists nowhere but in this call, standing in the client's current world at the given position.
 * A {@code TileWeaponConsole} writes no payload for a press, so the bytes are exactly a real button's
 * — for any machine whose press carries none (the fire-control sensor's mode button too): the server
 * resolves the machine from the address alone.</p>
 *
 * <p>Test source set: absent from a released jar.</p>
 */
public final class ForgedMachinePress {

    private ForgedMachinePress() {
    }

    /** Have {@code bot}'s client send press {@code packetId} to the weapon console at (x, y, z). */
    public static void send(ClientBot bot, int x, int y, int z, int packetId) throws IOException {
        bot.invokeStaticInt(ForgedMachinePress.class.getName(), "pressOnClient", x, y, z, packetId);
    }

    /** Runs in the client JVM, on its client thread — reached only through {@link #send}. */
    static void pressOnClient(int x, int y, int z, int packetId) {
        TileWeaponConsole addressed = new TileWeaponConsole();
        addressed.setWorld(Minecraft.getMinecraft().world);
        addressed.setPos(new BlockPos(x, y, z));
        PacketHandler.sendToServer(new PacketMachine(addressed, (byte) packetId));
    }
}
