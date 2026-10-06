package dev.stannismod.stellurgy.test.client;

import java.io.IOException;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

import com.github.stannismod.forge.testing.client.ClientBot;

import dev.stannismod.stellurgy.libvulpes.interfaces.INetworkEntity;
import dev.stannismod.stellurgy.libvulpes.network.PacketEntity;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.test.Reply;

/**
 * A packet to a network entity, written by the real client for an entity it can see but need not be
 * anywhere near — what a modified client can send, since an entity packet's address is the client's
 * to write and nothing in the client stops it at a distance.
 *
 * <p>The packet is production's own {@link PacketEntity}, built from the client's own copy of the
 * entity and sent by production's {@link PacketHandler#sendToServer} on the client thread, so its
 * bytes are exactly the ones a key press or a GUI button would write for that packet id. Only the
 * moment is the test's.</p>
 *
 * <p>Test source set: absent from a released jar.</p>
 */
public final class ForgedEntityPress {

    private ForgedEntityPress() {
    }

    /**
     * Have {@code bot}'s client send packet {@code packetId} to the entity with id {@code entityId}.
     *
     * @return whether the client held that entity as a network entity — and so sent anything at all
     */
    public static boolean send(ClientBot bot, int entityId, int packetId) throws IOException {
        Reply reply = Reply.of(bot.invokeStaticInt(ForgedEntityPress.class.getName(), "pressOnClient",
                entityId, packetId).toString());
        return "true".equals(reply.text("returned"));
    }

    /** Runs in the client JVM, on its client thread — reached only through {@link #send}. */
    static boolean pressOnClient(int entityId, int packetId) {
        Entity entity = Minecraft.getMinecraft().world == null ? null
                : Minecraft.getMinecraft().world.getEntityByID(entityId);
        if (!(entity instanceof INetworkEntity)) {
            return false;
        }
        PacketHandler.sendToServer(new PacketEntity((INetworkEntity) entity, (byte) packetId));
        return true;
    }
}
