package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.api.satellite.SatelliteProperties;
import dev.stannismod.stellurgy.network.PacketAtmSync;
import dev.stannismod.stellurgy.network.PacketOxygenState;
import dev.stannismod.stellurgy.network.PacketStellarInfo;
import dev.stannismod.stellurgy.network.PacketSyncKnownPlanets;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Network packet wire decisions — what a packet's write/readClient pair decides beyond carrying
 * its fields.
 *
 * Production code dispatches read vs readClient based on side. Most Stellurgy packets are
 * server&rarr;client only (no executable {@code read} on server). We exercise the
 * client-bound path (write &rarr; readClient) here.
 *
 * Packets that pull state from {@code DimensionManager} / {@code SpaceObjectManager}
 * during executeClient are NOT exercised end-to-end here; that lives in the
 * scenario suite.
 */
public class PacketSerializationTest {

    private static ByteBuf newBuffer() {
        return Unpooled.buffer();
    }

    /**
     * Reflection helper — sets a private field on a packet instance so we can
     * exercise the round-trip without invoking constructors that touch global
     * registries (e.g. {@code PacketSyncKnownPlanets} pulls from
     * {@code DimensionManager.getInstance().knownPlanets}).
     */
    @SuppressWarnings("unchecked")
    private static <T> T getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    @Test
    public void packetStellarInfoRoundTripRemoveStar() throws Exception {
        // Setting star=null signals removal — the wire format must encode just the
        // id + removeStar=true and no NBT block.
        PacketStellarInfo sent = new PacketStellarInfo(99, null);
        ByteBuf buffer = newBuffer();
        sent.write(buffer);

        PacketStellarInfo received = new PacketStellarInfo();
        received.readClient(buffer);

        assertEquals(0, buffer.readableBytes());
        assertTrue("star=null on send must round-trip as removeStar=true",
                PacketSerializationTest.<Boolean>field(received, "removeStar"));
    }

    // PacketDimInfo / PacketSatellite / PacketStationUpdate / PacketConfigSync
    // round-trips require live DimensionManager / SatelliteRegistry / ISpaceObject /
    // StellurgyConfiguration state. They're covered end-to-end through the matching
    // scenario tests which exercise the same wire
    // format implicitly via /stellurgytest probes on real packets between client and
    // server.

    // ── malformed wire data ────────

    @Test
    public void packetSyncKnownPlanetsReadClientNegativeSizeReturnsEmptySet() throws Exception {
        // A hostile / corrupt sender could put size=-1 on the wire. The
        // for-loop guard (i < size) fails immediately so no further reads
        // happen. Crucial: no pre-allocated array sized to the (negative,
        // possibly-cast-to-huge) count, no IOOBE, no infinite loop —
        // knownPlanets ends up empty.
        ByteBuf wire = newBuffer();
        wire.writeInt(7);             // stationId
        wire.writeInt(-1);            // size — hostile

        PacketSyncKnownPlanets packet = new PacketSyncKnownPlanets();
        packet.readClient(wire); // must NOT throw

        assertEquals(7, packet.stationId);
        Set<Integer> known = field(packet, "knownPlanets");
        assertNotNull(known);
        assertEquals("negative-size header must produce an empty set, not crash",
                0, known.size());
    }

    // Convenience to keep callsites clean without leaking the throws clause.
    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(target);
        } catch (Exception e) {
            throw new AssertionError("Reflection failed reading field " + name, e);
        }
    }
}
