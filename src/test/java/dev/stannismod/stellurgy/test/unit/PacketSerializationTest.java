package dev.stannismod.stellurgy.test.unit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;
import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.api.satellite.SatelliteProperties;
import dev.stannismod.stellurgy.network.PacketAtmSync;
import dev.stannismod.stellurgy.network.PacketOxygenState;
import dev.stannismod.stellurgy.network.PacketStellarInfo;
import dev.stannismod.stellurgy.network.PacketSyncKnownPlanets;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Network packet round-trip — write/readClient symmetry.
 *
 * Each test:
 *   1. constructs a representative packet,
 *   2. writes it into a Netty {@link ByteBuf},
 *   3. reads into a fresh instance via the no-arg ctor + readClient,
 *   4. asserts every field is preserved.
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

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * <p>red-witnessed: one inversion per verdict on the readout, 2026-09-30. PRESSURE -
     * {@code PacketAtmSync#write} at {@code nbt.setShort("pressure", (short) summary.pressureCentiAtm());} writing half the pressure: "expected:&lt;850&gt; but was:&lt;425&gt;".
     * BREATHABLE - {@code PacketAtmSync#readClient} at {@code summary = new AtmosphereSummary(nbt.getShort("pressure"), nbt.getBoolean("breathable"),} reading the flag negated: "expected:&lt;false&gt; but
     * was:&lt;true&gt;". WARNING - {@code PacketAtmSync#readClient} at {@code nbt.getString("warning"), holding);} reading the warning under another key:
     * "expected:&lt;[msg.noOxygen]&gt; but was:&lt;[]&gt;". STATEMENTS IN ORDER -
     * {@code PacketAtmSync#readClient} at {@code holding.add(list.getStringTagAt(i));} prepending each statement instead of appending it: "the statements must
     * survive in order ... expected:&lt;[NOT_BREATHABLE, TOXIC]&gt; but was:&lt;[TOXIC,
     * NOT_BREATHABLE]&gt;". The readable-bytes check was not part of this change and is not
     * witnessed here.</p>
     */
    @Test
    public void packetAtmSyncRoundTrip() {
        // A readout, not a model: a pressure, whether it can be breathed, a warning to show, and the
        // statements that are true of the air — which is what a player reads as its name now that
        // nothing branches on one.
        dev.stannismod.stellurgy.atmosphere.AtmosphereSummary summary =
                new dev.stannismod.stellurgy.atmosphere.AtmosphereSummary(
                        850, false, "msg.noOxygen",
                        java.util.Arrays.asList("NOT_BREATHABLE", "TOXIC"));
        PacketAtmSync sent = new PacketAtmSync(summary);

        ByteBuf buffer = newBuffer();
        sent.write(buffer);

        PacketAtmSync received = new PacketAtmSync();
        received.readClient(buffer);

        assertEquals(0, buffer.readableBytes());
        dev.stannismod.stellurgy.atmosphere.AtmosphereSummary back =
                PacketSerializationTest.field(received, "summary");
        assertEquals(850, back.pressureCentiAtm());
        assertEquals(false, back.breathable());
        assertEquals("msg.noOxygen", back.warningKey());
        assertEquals("the statements must survive in order — they are the label a player reads",
                java.util.Arrays.asList("NOT_BREATHABLE", "TOXIC"), back.assertions());
    }

    @Test
    public void packetOxygenStateRoundTrip() {
        // PacketOxygenState carries no payload — write() must produce zero bytes
        // and readClient() must complete without throwing.
        PacketOxygenState sent = new PacketOxygenState();
        ByteBuf buffer = newBuffer();
        sent.write(buffer);

        assertEquals("PacketOxygenState carries no payload", 0, buffer.readableBytes());

        // Readability test: a fresh instance should accept the empty stream silently.
        // We deliberately do NOT call sent.readClient — that path uses
        // Minecraft.getMinecraft() which requires a running game.
    }

    @Test
    public void packetStellarInfoRoundTrip() throws Exception {
        StellarBody star = new StellarBody();
        star.setName("TestStar");
        star.setTemperature(80);
        star.setSize(1.5f);
        star.setBlackHole(false);
        star.setId(7);

        PacketStellarInfo sent = new PacketStellarInfo(7, star);
        ByteBuf buffer = newBuffer();
        sent.write(buffer);

        PacketStellarInfo received = new PacketStellarInfo();
        received.readClient(buffer);

        assertEquals(0, buffer.readableBytes());
        assertEquals(7, (int) PacketSerializationTest.<Integer>field(received, "starId"));
        assertEquals(false, (boolean) PacketSerializationTest.<Boolean>field(received, "removeStar"));

        // The packet stores the inner NBT and only re-hydrates the star inside
        // executeClient (which mutates DimensionManager). Round-trip the NBT to
        // verify it survived the wire.
        net.minecraft.nbt.NBTTagCompound nbt = field(received, "nbt");
        assertNotNull(nbt);

        StellarBody restored = new StellarBody();
        restored.readFromNBT(nbt);
        assertEquals("TestStar", restored.getName());
        assertEquals(80, restored.getTemperature());
        assertEquals(1.5f, restored.getSize(), 1e-6);
        assertEquals(7, restored.getId());
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
        assertEquals(99, (int) PacketSerializationTest.<Integer>field(received, "starId"));
        assertTrue("star=null on send must round-trip as removeStar=true",
                PacketSerializationTest.<Boolean>field(received, "removeStar"));
    }

    @Test
    public void packetSyncKnownPlanetsRoundTrip() throws Exception {
        // The 2-arg ctor pulls DimensionManager.getInstance().knownPlanets into the
        // payload — bypass it via no-arg ctor + reflection so we don't depend on
        // global state.
        PacketSyncKnownPlanets sent = new PacketSyncKnownPlanets();
        sent.stationId = 42;
        Set<Integer> planets = new HashSet<>();
        planets.add(2);
        planets.add(7);
        planets.add(11);
        setField(sent, "knownPlanets", planets);

        ByteBuf buffer = newBuffer();
        sent.write(buffer);

        PacketSyncKnownPlanets received = new PacketSyncKnownPlanets();
        received.readClient(buffer);

        assertEquals(0, buffer.readableBytes());
        assertEquals(42, received.stationId);

        Set<Integer> recvPlanets = field(received, "knownPlanets");
        assertNotNull(recvPlanets);
        assertEquals(planets, recvPlanets);
    }

    @Test
    public void packetSyncKnownPlanetsRoundTripEmpty() throws Exception {
        PacketSyncKnownPlanets sent = new PacketSyncKnownPlanets();
        sent.stationId = 1;
        setField(sent, "knownPlanets", new HashSet<Integer>());

        ByteBuf buffer = newBuffer();
        sent.write(buffer);

        PacketSyncKnownPlanets received = new PacketSyncKnownPlanets();
        received.readClient(buffer);

        assertEquals(0, buffer.readableBytes());
        Set<Integer> recvPlanets = field(received, "knownPlanets");
        assertEquals(0, recvPlanets.size());
    }

    @Test
    public void satellitePropertiesNbtSurvivesPacketBufferTransport() {
        // Satellite-bearing packets (PacketSatellite) ultimately serialize
        // SatelliteProperties via writeCompoundTag. Test the inner serialization is
        // wire-stable independent of the surrounding packet machinery.
        SatelliteProperties original = new SatelliteProperties(40, 800, "ar:test", 256, 1.5f);
        original.setId(0xFEEDL);

        net.minecraft.nbt.NBTTagCompound nbt = new net.minecraft.nbt.NBTTagCompound();
        original.writeToNBT(nbt);

        ByteBuf buffer = newBuffer();
        net.minecraft.network.PacketBuffer packetBuffer = new net.minecraft.network.PacketBuffer(buffer);
        packetBuffer.writeCompoundTag(nbt);

        net.minecraft.nbt.NBTTagCompound received;
        try {
            received = packetBuffer.readCompoundTag();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }

        SatelliteProperties restored = new SatelliteProperties();
        restored.readFromNBT(received);

        assertEquals(40, restored.getPowerGeneration());
        assertEquals(800, restored.getPowerStorage());
        assertEquals("ar:test", restored.getSatelliteType());
        assertEquals(256, restored.getMaxDataStorage());
        assertEquals(1.5f, restored.getWeight(), 1e-6);
        assertEquals(0xFEEDL, restored.getId());
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
