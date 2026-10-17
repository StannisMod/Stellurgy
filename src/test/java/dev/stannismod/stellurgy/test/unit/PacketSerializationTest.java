package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.api.satellite.SatelliteProperties;
import dev.stannismod.stellurgy.network.PacketAtmSync;
import dev.stannismod.stellurgy.network.PacketOxygenState;
import dev.stannismod.stellurgy.network.PacketStellarInfo;
import dev.stannismod.stellurgy.network.PacketSyncKnownPlanets;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

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

    @RedWitness(verdict = "pressure", member = "PacketAtmSync#write",
            at = "nbt.setInteger(\"pressure\", summary.pressureCentiAtm());",
            replacedBy = "nbt.setInteger(\"pressure\", summary.pressureCentiAtm() / 2);",
            fails = "expected:<850> but was:<425>", on = "2026-10-17")
    @RedWitness(verdict = "breathable", member = "PacketAtmSync#readClient", at = "nbt.getBoolean(\"breathable\")",
            replacedBy = "!nbt.getBoolean(\"breathable\")", fails = "expected:<false> but was:<true>", on = "2026-10-17")
    @RedWitness(verdict = "warning", member = "PacketAtmSync#readClient", at = "nbt.getString(\"warning\")",
            replacedBy = "nbt.getString(\"warn\")", fails = "expected:<[msg.noOxygen]> but was:<[]>", on = "2026-10-17")
    @RedWitness(verdict = "statements in order", member = "PacketAtmSync#readClient",
            at = "holding.add(list.getStringTagAt(i));", replacedBy = "holding.add(0, list.getStringTagAt(i));",
            fails = "the statements must survive in order", on = "2026-10-17")
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

    /**
     * A pressure past what a short holds reaches the client as itself. The model allows any
     * {@code int} of centi-atm ({@code AirState#getPressureCentiAtm} clamps only at
     * {@code Integer.MAX_VALUE}), so 400 atm — 40 000, above a short's 32 767 — must not arrive as a
     * wrapped negative.
     */
    @RedWitness(member = "PacketAtmSync#write", at = "nbt.setInteger(\"pressure\", summary.pressureCentiAtm());",
            replacedBy = "nbt.setShort(\"pressure\", (short) summary.pressureCentiAtm());",
            fails = "a 400 atm room must reach the client as 400 atm expected:<40000> but was:<-25536>", on = "2026-10-17")
    @Test
    public void packetAtmSyncCarriesAPressureAboveAShort() {
        int fourHundredAtm = 40_000;
        PacketAtmSync sent = new PacketAtmSync(new dev.stannismod.stellurgy.atmosphere.AtmosphereSummary(
                fourHundredAtm, false, "", java.util.Collections.<String>emptyList()));
        ByteBuf buffer = newBuffer();
        sent.write(buffer);
        PacketAtmSync received = new PacketAtmSync();
        received.readClient(buffer);

        dev.stannismod.stellurgy.atmosphere.AtmosphereSummary back =
                PacketSerializationTest.field(received, "summary");
        assertEquals("a 400 atm room must reach the client as 400 atm", fourHundredAtm, back.pressureCentiAtm());
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
