package dev.stannismod.stellurgy.probe;

import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.satellite.SatelliteOrbit;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.environment.BodyRotation;
import dev.stannismod.stellurgy.environment.PlanetEnvironment;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.network.PacketProbeFall;
import dev.stannismod.stellurgy.network.PacketTransmitters;
import dev.stannismod.stellurgy.satellite.SatelliteAccrual;
import dev.stannismod.stellurgy.satellite.SatelliteLife;
import dev.stannismod.stellurgy.satellite.SatellitePlacement;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.UniverseRegistry;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import dev.stannismod.stellurgy.world.provider.WorldProviderSpace;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import net.minecraftforge.fml.common.Loader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SplittableRandom;

/**
 * Drives the first probe's one fall: starts the delay at the save's first login, lets it in at the first
 * night past the delay around a player standing on a planet, brings it down when its trajectory ends, lays
 * its crater and wreck once their ground is loaded, and tells every client what it needs to draw the fall
 * and to hear the wreck. Holds nothing: the state is {@link ProbeFall}'s and {@link TransmitterRegistry}'s.
 */
public final class ProbeFallDriver {

    /**
     * How many landing points are tried for dry land before the probe lands on the first one drawn. Each
     * try that passes the biome test generates one chunk, once per save. A bound on work.
     */
    private static final int LANDING_TRIES = 64;

    private ProbeFallDriver() {
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) {
            return;
        }
        MinecraftServer server = event.player.getServer();
        if (server == null) {
            return;
        }
        ProbeFall.get(server).loggedIn(server.getWorld(0).getTotalWorldTime());
        tell((EntityPlayerMP) event.player);
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            tell((EntityPlayerMP) event.player);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || server.getWorld(0) == null) {
            return;
        }
        ProbeFall data = ProbeFall.get(server);
        switch (data.phase()) {
            case WAITING:
                tryEntry(server, data);
                break;
            case FALLING:
                ProbeFall.Fall fall = data.fall().get();
                World world = net.minecraftforge.common.DimensionManager.getWorld(fall.dimension);
                if (world != null && ProbeFall.elapsed(fall, world) >= fall.placement().ticks()) {
                    data.touchDown();
                    TransmitterRegistry.get(world).add(ProbeImpact.wreckAt(fall.landing));
                    broadcastTransmitters(world);
                    Stellurgy.logger.info("The first probe is down at {} in world {}", fall.landing, fall.dimension);
                }
                break;
            case DOWN:
                if (!data.laid()) {
                    ProbeFall.Fall down = data.fall().get();
                    World ground = net.minecraftforge.common.DimensionManager.getWorld(down.dimension);
                    if (ground != null && ground.isAreaLoaded(down.landing, ProbeImpact.RADIUS + 2)) {
                        ProbeImpact.lay(ground, down.landing, down.record());
                        data.markLaid();
                    }
                }
                break;
            default:
                break;
        }
    }

    /** A transmitter in {@code world} at {@code pos} has stopped: its registry and every listener there are told. */
    static void transmitterStopped(World world, BlockPos pos) {
        if (TransmitterRegistry.get(world).remove(pos)) {
            broadcastTransmitters(world);
        }
    }

    /** Lets the probe in now, if it is due anywhere: the first player in the list standing under a night sky on a planet. */
    private static void tryEntry(MinecraftServer server, ProbeFall data) {
        long now = server.getWorld(0).getTotalWorldTime();
        long delay = StellurgyConfiguration.getCurrentConfig().firstProbeDelayTicks;
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            World world = player.world;
            Optional<DimensionProperties> props = planetOf(world);
            if (!props.isPresent() || !FirstProbe.due(data.firstLoginTick(), now, delay, world.getCelestialAngle(1f))) {
                continue;
            }
            Optional<ProbeFall.Fall> fall = fallAround((WorldServer) world, props.get(), player);
            if (fall.isPresent() && data.enter(fall.get())) {
                PacketProbeFall packet = PacketProbeFall.of(fall.get());
                for (EntityPlayer listener : world.playerEntities) {
                    PacketHandler.sendToPlayer(packet, listener);
                }
                Stellurgy.logger.info("The first probe enters the air of world {} around {}: it lands at {} in {} ticks",
                        fall.get().dimension, player.getName(), fall.get().landing, fall.get().placement().ticks());
                return;
            }
        }
    }

    /** The world's planet, when it is one the probe can fall on: a body with a size and an atmosphere line, not space. */
    private static Optional<DimensionProperties> planetOf(World world) {
        if (world.provider instanceof WorldProviderSpace) {
            return Optional.empty();
        }
        DimensionProperties props = DimensionManager.getInstance().getDimensionPropertiesOrNull(world.provider.getDimension());
        if (props == null || !(props.getRadius() > 0d) || !props.orbitLine().isPresent()) {
            return Optional.empty();
        }
        return Optional.of(props);
    }

    /** The fall around {@code player}: where it lands, along which heading, and the record it carries. */
    private static Optional<ProbeFall.Fall> fallAround(WorldServer world, DimensionProperties props, EntityPlayerMP player) {
        int dim = world.provider.getDimension();
        SplittableRandom draw = new SplittableRandom(world.getSeed() * 0x9E3779B97F4A7C15L + dim);
        BlockPos landing = null;
        BlockPos firstDrawn = null;
        for (int i = 0; i < LANDING_TRIES && landing == null; i++) {
            double[] offset = FirstProbe.landingOffset(draw);
            int x = (int) Math.floor(player.posX + offset[0]);
            int z = (int) Math.floor(player.posZ + offset[1]);
            Biome biome = world.getBiomeProvider().getBiome(new BlockPos(x, 0, z));
            boolean wetBiome = BiomeDictionary.hasType(biome, BiomeDictionary.Type.OCEAN)
                    || BiomeDictionary.hasType(biome, BiomeDictionary.Type.RIVER);
            if (wetBiome && firstDrawn != null) {
                continue;
            }
            BlockPos top = topOf(world, x, z);
            if (firstDrawn == null) {
                firstDrawn = top;
            }
            if (!wetBiome && !world.getBlockState(top).getMaterial().isLiquid()) {
                landing = top;
            }
        }
        if (landing == null) {
            landing = firstDrawn;
            Stellurgy.logger.warn("The first probe found no dry land in {} tries around {} in world {}: it lands in the water at {}",
                    LANDING_TRIES, player.getName(), dim, landing);
        }
        OptionalInt line = props.orbitLine();
        double lineAltitude = line.getAsInt() - (landing.getY() + 1d);
        if (!(lineAltitude > 0d)) {
            Stellurgy.logger.warn("The first probe cannot enter world {}: its atmosphere line {} is not above the ground at {}",
                    dim, line.getAsInt(), landing);
            return Optional.empty();
        }
        double radiusM = props.getRadius() * AstronomicalBodyHelper.EARTH_RADIUS_METRES;
        ProbeFall.Fall fall = new ProbeFall.Fall(dim, world.getTotalWorldTime(), landing, draw.nextDouble() * 2d * Math.PI,
                radiusM, props.getGravitationalMultiplier() * FirstProbe.STANDARD_GRAVITY_MS2,
                props.getAtmosphereDensity(), lineAltitude, player.getName(), recordAbout(world, props, radiusM));
        try {
            fall.placement();
        } catch (IllegalStateException refused) {
            Stellurgy.logger.error("The first probe cannot fall on world {}: {}", dim, refused.getMessage());
            return Optional.empty();
        }
        return Optional.of(fall);
    }

    /** The top block of the ground at a column, generating its chunk if nobody has. */
    private static BlockPos topOf(World world, int x, int z) {
        world.getChunkFromChunkCoords(x >> 4, z >> 4);
        BlockPos above = world.getTopSolidOrLiquidBlock(new BlockPos(x, 0, z));
        return above.down();
    }

    /**
     * The record the probe carries: its life on its orbit about this world's body. A world the universe
     * names no body for gives an empty record, and says so.
     */
    private static NBTTagCompound recordAbout(World world, DimensionProperties props, double radiusM) {
        UniverseRegistry reg = UniverseRegistry.get(world);
        Optional<SystemBody> body = SatellitePlacement.bodyOf(reg, props.getId());
        NBTTagCompound nbt = new NBTTagCompound();
        if (!body.isPresent()) {
            Stellurgy.logger.warn("The first probe carries no record: the universe names no body for world {}", props.getId());
            return nbt;
        }
        Optional<PlanetEnvironment> environment = SatelliteLife.bodyEnvironment(props, body.get(), reg);
        BodyRotation turn = SatellitePlacement.rotationOf(props);
        SatelliteOrbit orbit = FirstProbe.orbit(world.getSeed() ^ props.getId(), radiusM,
                SatelliteOrbit.EARTH_MU_M3_S2 * body.get().massEarths(), turn);
        SatelliteAccrual.Body about = new SatelliteAccrual.Body(orbit, environment,
                tick -> SatelliteLife.starDirection(reg, body.get(), tick), radiusM);
        ProbeRecord.lived(about, turn, SatelliteLife.rules(), 0L).writeToNBT(nbt);
        nbt.setTag(ProbeRecord.PACK_KEY, packAdditions().writeToNBT());
        return nbt;
    }

    /**
     * What a pack adds to the record, read from its file in the mod's config folder; nothing when there is no
     * file. A file that cannot be read adds nothing and says why: the core record is whole without it.
     */
    private static ProbePack packAdditions() {
        File file = new File(new File(Loader.instance().getConfigDir(), StellurgyConfiguration.configFolder), ProbePack.FILE);
        if (!file.isFile()) {
            return ProbePack.NONE;
        }
        try (Reader in = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            return ProbePack.parse(in);
        } catch (IOException | RuntimeException unreadable) {
            Stellurgy.logger.error("The first probe's pack file {} is not read, the record carries the core alone: {}",
                    file, unreadable.getMessage());
            return ProbePack.NONE;
        }
    }

    /** Tells one player what his world has: its transmitters, and the fall if it is coming down there. */
    private static void tell(EntityPlayerMP player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        PacketHandler.sendToPlayer(PacketTransmitters.of(player.world), player);
        ProbeFall data = ProbeFall.get(server);
        if (data.phase() == ProbeFall.Phase.FALLING && data.fall().get().dimension == player.world.provider.getDimension()) {
            PacketHandler.sendToPlayer(PacketProbeFall.of(data.fall().get()), player);
        }
    }

    private static void broadcastTransmitters(World world) {
        PacketTransmitters packet = PacketTransmitters.of(world);
        for (EntityPlayer player : world.playerEntities) {
            PacketHandler.sendToPlayer(packet, player);
        }
    }
}
