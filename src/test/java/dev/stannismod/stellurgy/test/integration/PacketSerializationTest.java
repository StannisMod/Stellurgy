package dev.stannismod.stellurgy.test.integration;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.api.satellite.SatelliteProperties;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;
import dev.stannismod.stellurgy.network.*;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.util.Asteroid;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * Network packet wire-format decisions for packets that need
 * {@link MinecraftBootstrap#ensure()} because their write/readClient
 * pipelines touch {@link net.minecraft.nbt.NBTTagCompound} serialization of
 * vanilla / Stellurgy registry-backed objects.
 *
 * <p>Lighter packets that don't need MC bootstrap live in
 * {@code unit/PacketSerializationTest}.</p>
 */
public class PacketSerializationTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    private static ByteBuf newBuffer() {
        return Unpooled.buffer();
    }

    private static <T> T getField(Object target, String name) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                T value = (T) f.get(target);
                return value;
            } catch (NoSuchFieldException nope) {
                c = c.getSuperclass();
            } catch (Exception e) {
                throw new AssertionError("reflection get " + name + " failed", e);
            }
        }
        throw new AssertionError("field " + name + " not found on " + target.getClass());
    }

    // ---- PacketDimInfo --------------------------------------------------------

    /** Pins INV-NW-04 (NBT-envelope packets round-trip through writeCompoundTag and readCompoundTag). */
    @Pins("INV-NW-04")
    @Test
    public void packetDimInfoNullPropertiesIsDeleteSignal() {
        // ctor with null DimensionProperties -> wire format collapses to
        // {dimNumber, deleteDim=true}. executeClient interprets that as a delete.
        PacketDimInfo sent = new PacketDimInfo(99, null);
        ByteBuf buffer = newBuffer();
        sent.write(buffer);

        PacketDimInfo received = new PacketDimInfo();
        received.readClient(buffer);

        assertEquals(0, buffer.readableBytes());
        assertTrue("null dimProperties on send must round-trip as deleteDim=true",
                getField(received, "deleteDim"));
    }

    // ---- PacketSpaceStationInfo ----------------------------------------------

    /**
     * Deletion branch — server signals "remove this station". Wire is just
     * {@code int stationNumber + bool isBeingDeleted=true}. No further fields
     * are emitted, no further fields are read. Tripwire: if someone adds a
     * field after {@code isBeingDeleted} without gating it on the flag, this
     * test fails because readClient over-consumes the buffer.
     */
    @Test
    public void packetSpaceStationInfoDeletionBranch() {
        ByteBuf buffer = newBuffer();
        buffer.writeInt(4242);
        buffer.writeBoolean(true);              // isBeingDeleted

        PacketSpaceStationInfo received = new PacketSpaceStationInfo();
        received.readClient(buffer);

        assertEquals("deletion branch must consume exactly the 5 bytes written",
                0, buffer.readableBytes());
        assertEquals(4242, (int) PacketSerializationTest.<Integer>getField(received, "stationNumber"));
        assertEquals(true, (boolean) PacketSerializationTest.<Boolean>getField(received, "isBeingDeleted"));
    }

    // ---- PacketMoveRocketInSpace ---------------------------------------------

    /**
     * {@link PacketMoveRocketInSpace} is DEAD CODE: it has no
     * {@code addDiscriminator} registration in
     * {@code Stellurgy.serverStarting}, so it is never actually sent
     * over the wire. We still pin its current behaviour because it contains
     * TWO latent bugs that should fail
     * loudly when the packet is eventually wired up:
     *
     * <ol>
     *   <li><b>Inverted boolean</b>: {@code hasWorld = position.world == null}
     *       — i.e. {@code hasWorld=true} means "no world". The next line then
     *       does {@code if (hasWorld) writeInt(position.world.getId())},
     *       which NPEs on the very case the boolean was supposed to handle.
     *       And when {@code world != null}, the int is silently skipped, so
     *       the wire NEVER carries dimId. Same bug for {@code hasStar}.</li>
     *   <li><b>read(ByteBuf)</b>: uses {@code position.x = in.readDouble()}
     *       but {@code position} is null after no-arg ctor, so the server-side
     *       read path always NPEs. Doesn't matter while the packet is
     *       unregistered; will explode immediately when it is registered.</li>
     * </ol>
     *
     * The second is asserted here, and fails when (and only when) it is fixed —
     * the test then needs to be flipped manually. The first is not exercised:
     * it needs a {@code SpacePosition} backed by DimensionManager state.
     */
    @Test
    public void packetMoveRocketInSpaceDocumentsKnownBugs() throws Exception {
        // Bug #2: read(ByteBuf) on a freshly constructed packet always NPEs.
        PacketMoveRocketInSpace fresh = new PacketMoveRocketInSpace();
        ByteBuf buffer = newBuffer();
        buffer.writeDouble(1.0);  buffer.writeDouble(2.0);  buffer.writeDouble(3.0);
        buffer.writeBoolean(false); buffer.writeBoolean(false);

        boolean serverReadNpes = false;
        try {
            fresh.read(buffer);
        } catch (NullPointerException expected) {
            serverReadNpes = true;
        }
        assertTrue("PacketMoveRocketInSpace.read() must currently NPE on default-ctor "
                + "instance — fix the bug then flip this assertion",
                serverReadNpes);
    }

    // ── a delete flag short-circuits the rest of the wire ────────

    @Test
    public void packetDimInfoReadClientDeleteFlagSkipsNbtSection() {
        // The deleteDim=true branch is the "drop this dim" signal — readClient
        // must NOT try to read any NBT or customIcon bytes. Header-only wire
        // (5 bytes) parses cleanly and leaves artifacts empty / customIcon "".
        ByteBuf wire = newBuffer();
        wire.writeInt(42);          // dimNumber
        wire.writeBoolean(true);    // deleteDim

        PacketDimInfo packet = new PacketDimInfo();
        packet.readClient(wire); // must NOT throw

        assertEquals(42, (int) PacketSerializationTest.<Integer>getField(packet, "dimNumber"));
        assertEquals(true, (boolean) PacketSerializationTest.<Boolean>getField(packet, "deleteDim"));
        assertEquals(0, PacketSerializationTest.<java.util.List<?>>getField(packet, "artifacts").size());
        assertEquals("", PacketSerializationTest.<String>getField(packet, "customIcon"));
    }

    @Test
    public void packetSpaceStationInfoDeleteFlagSkipsPayload() {
        // deleteFlag=true must short-circuit before the try-block reads any
        // NBT/clazzId/fuelAmt bytes, preventing partial parses.
        ByteBuf wire = newBuffer();
        wire.writeInt(77);          // stationNumber
        wire.writeBoolean(true);    // isBeingDeleted

        PacketSpaceStationInfo packet = new PacketSpaceStationInfo();
        packet.readClient(wire); // must NOT throw

        assertEquals(77, (int) PacketSerializationTest.<Integer>getField(packet, "stationNumber"));
        assertEquals(true, (boolean) PacketSerializationTest.<Boolean>getField(packet, "isBeingDeleted"));
        assertNull(PacketSerializationTest.<Object>getField(packet, "nbt"));
        assertNull(PacketSerializationTest.<Object>getField(packet, "clazzId"));
        assertEquals(0, (int) PacketSerializationTest.<Integer>getField(packet, "fuelAmt"));
    }
}
