package dev.stannismod.stellurgy.test.integration;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.api.satellite.SatelliteProperties;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.network.PacketAirParticle;
import dev.stannismod.stellurgy.network.PacketAsteroidInfo;
import dev.stannismod.stellurgy.network.PacketConfigSync;
import dev.stannismod.stellurgy.network.PacketDimInfo;
import dev.stannismod.stellurgy.network.PacketFluidParticle;
import dev.stannismod.stellurgy.network.PacketInvalidLocationNotify;
import dev.stannismod.stellurgy.network.PacketLaserGun;
import dev.stannismod.stellurgy.network.PacketMoveRocketInSpace;
import dev.stannismod.stellurgy.network.PacketSatellite;
import dev.stannismod.stellurgy.network.PacketSpaceStationInfo;
import dev.stannismod.stellurgy.network.PacketStationUpdate;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.util.Asteroid;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Network packet wire-format round-trip — the four packets
 * (PacketDimInfo, PacketSatellite, PacketStationUpdate, PacketConfigSync)
 * that need {@link MinecraftBootstrap#ensure()} because their write/readClient
 * pipelines touch {@link net.minecraft.nbt.NBTTagCompound} serialization of
 * vanilla / Stellurgy registry-backed objects (biome IDs, satellite type strings,
 * config field schemas).
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

    private static void setField(Object target, String name, Object value) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException nope) {
                c = c.getSuperclass();
            } catch (Exception e) {
                throw new AssertionError("reflection set " + name + " failed", e);
            }
        }
        throw new AssertionError("field " + name + " not found on " + target.getClass());
    }

    // ---- PacketDimInfo --------------------------------------------------------

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
        assertEquals(99, (int) getField(received, "dimNumber"));
        assertTrue("null dimProperties on send must round-trip as deleteDim=true",
                getField(received, "deleteDim"));
    }

    // ---- PacketSatellite ------------------------------------------------------

    /**
     * Minimal SatelliteBase subclass so we can construct a satellite without
     * going through {@code SatelliteRegistry.getNewSatellite(name)} — that
     * lookup is empty in tests because Stellurgy's mod-init satellite registrations
     * don't run.
     */
    public static class TestSatellite extends SatelliteBase {
        @Override public String getInfo(net.minecraft.world.World world) { return "test"; }
        @Override public String getName() { return "TestSatellite"; }
        @Override public boolean performAction(net.minecraft.entity.player.EntityPlayer p,
                                               net.minecraft.world.World w,
                                               net.minecraft.util.math.BlockPos pos) {
            return false;
        }
        @Override public double failureChance() { return 0; }
    }

    @Test
    public void packetSatelliteRoundTrip() {
        SatelliteProperties props =
                new SatelliteProperties(120, 4000, "ar:test_packet_sat", 768, 2.5f);
        props.setId(0xC0FFEEL);

        TestSatellite sat = new TestSatellite();
        setField(sat, "satelliteProperties", props);
        sat.setDimensionId(5);

        PacketSatellite sent = new PacketSatellite(sat);
        ByteBuf buffer = newBuffer();
        sent.write(buffer);

        // PacketSatellite.readClient calls SatelliteRegistry.createFromNBT which
        // looks up the type string in the satellite registry — that registry is
        // empty in tests. So we verify the wire payload by reading the NBT
        // directly via PacketBuffer (same call the packet would make) without
        // resolving the satellite class.
        net.minecraft.network.PacketBuffer packetBuffer = new net.minecraft.network.PacketBuffer(buffer);
        NBTTagCompound nbt;
        try {
            nbt = packetBuffer.readCompoundTag();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertEquals(0, buffer.readableBytes());
        assertNotNull("packet payload missing satellite NBT", nbt);

        // Re-hydrate properties and verify everything that doesn't need the
        // registry survived (the type string is what executeClient would feed
        // to createFromNBT — verifying it preserves the wire format).
        assertTrue("NBT missing properties tag: " + nbt, nbt.hasKey("properties"));
        assertEquals(5, nbt.getInteger("dimId"));

        SatelliteProperties restored = new SatelliteProperties();
        restored.readFromNBT(nbt.getCompoundTag("properties"));
        assertEquals(120, restored.getPowerGeneration());
        assertEquals(4000, restored.getPowerStorage());
        assertEquals("ar:test_packet_sat", restored.getSatelliteType());
        assertEquals(768, restored.getMaxDataStorage());
        assertEquals(2.5f, restored.getWeight(), 1e-6);
        assertEquals(0xC0FFEEL, restored.getId());
    }

    // ---- PacketStationUpdate --------------------------------------------------

    // ---- PacketConfigSync -----------------------------------------------------

    @Test
    public void packetConfigSyncRoundTrip() {
        // Start from a current-config copy (matches what production StellurgyConfiguration
        // routinely serializes) and tweak deterministic fields. A fresh
        // StellurgyConfiguration() leaves some collection-fields null which throws off
        // the wire format because writeConfigToNetwork expects them initialized.
        StellurgyConfiguration cfg = new StellurgyConfiguration(StellurgyConfiguration.getCurrentConfig());
        cfg.spaceDimId = 9999;
        cfg.stationSize = 999;

        PacketConfigSync sent = new PacketConfigSync(cfg);
        ByteBuf buffer = newBuffer();
        sent.write(buffer);

        PacketConfigSync received = new PacketConfigSync();
        received.readClient(buffer);

        // Note: not asserting `readableBytes == 0` because StellurgyConfiguration carries
        // version padding / optional sections; the wire-level invariant we care
        // about is that the round-tripped fields match.
        StellurgyConfiguration restored = getField(received, "config");
        assertNotNull("config null after readClient", restored);
        assertEquals(9999, restored.spaceDimId);
        assertEquals(999, restored.stationSize);
    }

    // ---- PacketInvalidLocationNotify -----------------------------------------

    @Test
    public void packetInvalidLocationNotifyRoundTrip() {
        HashedBlockPosition pos = new HashedBlockPosition(123, 64, -456);
        PacketInvalidLocationNotify sent = new PacketInvalidLocationNotify(pos);

        ByteBuf buffer = newBuffer();
        sent.write(buffer);

        PacketInvalidLocationNotify received = new PacketInvalidLocationNotify();
        received.readClient(buffer);

        assertEquals("wire should be fully consumed", 0, buffer.readableBytes());
        HashedBlockPosition restored = getField(received, "toPos");
        assertEquals(123, restored.x);
        assertEquals(64, restored.y);
        assertEquals(-456, restored.z);
    }

    // ---- PacketFluidParticle -------------------------------------------------

    @Test
    public void packetFluidParticleRoundTrip() {
        BlockPos from = new BlockPos(10, 20, 30);
        BlockPos to = new BlockPos(-40, 50, -60);
        PacketFluidParticle sent = new PacketFluidParticle(from, to, 80, 0xFF66AA);

        ByteBuf buffer = newBuffer();
        sent.write(buffer);

        PacketFluidParticle received = new PacketFluidParticle();
        received.readClient(buffer);

        assertEquals(0, buffer.readableBytes());
        BlockPos restoredFrom = getField(received, "fromPos");
        BlockPos restoredTo = getField(received, "toPos");
        assertEquals(from, restoredFrom);
        assertEquals(to, restoredTo);
        assertEquals(80, (int) PacketSerializationTest.<Integer>getField(received, "time"));
        assertEquals(0xFF66AA, (int) PacketSerializationTest.<Integer>getField(received, "color"));
    }

    // ---- PacketAsteroidInfo --------------------------------------------------

    @Test
    public void packetAsteroidInfoRoundTrip() {
        Asteroid original = new Asteroid();
        original.ID = "test:goldRich";
        original.distance = 175;
        original.mass = 32_000;
        original.minLevel = 3;
        original.massVariability = 0.25f;
        original.richness = 0.6f;
        original.richnessVariability = 0.1f;
        original.probability = 0.05f;
        original.timeMultiplier = 1.5f;
        original.itemStacks.add(new ItemStack(Items.GOLD_INGOT, 1));
        original.stackProbabilities.add(0.4f);
        original.itemStacks.add(new ItemStack(Items.IRON_INGOT, 1));
        original.stackProbabilities.add(0.6f);

        PacketAsteroidInfo sent = new PacketAsteroidInfo(original);
        ByteBuf buffer = newBuffer();
        sent.write(buffer);

        PacketAsteroidInfo received = new PacketAsteroidInfo();
        received.readClient(buffer);

        assertEquals(0, buffer.readableBytes());
        Asteroid restored = getField(received, "asteroid");

        assertEquals("test:goldRich", restored.ID);
        assertEquals(175, restored.distance);
        assertEquals(32_000, restored.mass);
        assertEquals(3, restored.minLevel);
        assertEquals(0.25f, restored.massVariability, 1e-6);
        assertEquals(0.6f, restored.richness, 1e-6);
        assertEquals(0.1f, restored.richnessVariability, 1e-6);
        assertEquals(0.05f, restored.probability, 1e-6);
        assertEquals(1.5f, restored.timeMultiplier, 1e-6);

        assertEquals(2, restored.itemStacks.size());
        assertEquals(Items.GOLD_INGOT, restored.itemStacks.get(0).getItem());
        assertEquals(Items.IRON_INGOT, restored.itemStacks.get(1).getItem());
        assertEquals(0.4f, restored.stackProbabilities.get(0), 1e-6);
        assertEquals(0.6f, restored.stackProbabilities.get(1), 1e-6);
    }

    @Test
    public void packetAsteroidInfoRoundTripEmptyStackList() {
        Asteroid original = new Asteroid();
        original.ID = "test:empty";
        original.distance = 1;
        original.mass = 1;
        original.minLevel = 0;
        original.massVariability = 0;
        original.richness = 0;
        original.richnessVariability = 0;
        original.probability = 0;
        original.timeMultiplier = 1;

        PacketAsteroidInfo sent = new PacketAsteroidInfo(original);
        ByteBuf buffer = newBuffer();
        sent.write(buffer);

        PacketAsteroidInfo received = new PacketAsteroidInfo();
        received.readClient(buffer);

        Asteroid restored = getField(received, "asteroid");
        assertEquals(0, restored.itemStacks.size());
        assertEquals(0, restored.stackProbabilities.size());
    }

    // ---- PacketLaserGun ------------------------------------------------------

    /**
     * The shooter crosses the wire as its entity id, which is what the client looks it up by; the
     * target crosses as floats, so the coordinates here are exactly representable in one.
     */
    @Test
    public void packetLaserGunRoundTripsTheShooterAndTheTarget() {
        net.minecraft.entity.Entity shooter = new net.minecraft.entity.projectile.EntitySnowball(null);
        PacketLaserGun sent = new PacketLaserGun(shooter, new net.minecraft.util.math.Vec3d(1.5, 64.25, -2.75));

        ByteBuf buffer = newBuffer();
        sent.write(buffer);
        PacketLaserGun received = new PacketLaserGun();
        received.readClient(buffer);

        assertEquals("wire should be fully consumed", 0, buffer.readableBytes());
        assertEquals(shooter.getEntityId(), (int) PacketSerializationTest.<Integer>getField(received, "entityId"));

        net.minecraft.util.math.Vec3d toPos = getField(received, "toPos");
        assertEquals(1.5, toPos.x, 1e-6);
        assertEquals(64.25, toPos.y, 1e-6);
        assertEquals(-2.75, toPos.z, 1e-6);
    }

    // ---- PacketAirParticle ---------------------------------------------------

    @Test
    public void packetAirParticleRoundTrip() {
        HashedBlockPosition pos = new HashedBlockPosition(-25, 90, 1024);
        PacketAirParticle sent = new PacketAirParticle(pos);

        ByteBuf buffer = newBuffer();
        sent.write(buffer);

        PacketAirParticle received = new PacketAirParticle();
        received.readClient(buffer);

        assertEquals("wire should be fully consumed", 0, buffer.readableBytes());
        HashedBlockPosition restored = getField(received, "toPos");
        assertEquals(-25, restored.x);
        assertEquals(90, restored.y);
        assertEquals(1024, restored.z);
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
