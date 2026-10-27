package dev.stannismod.stellurgy.satellite;

import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.SatelliteRegistry;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.api.satellite.SatelliteOrbit;
import dev.stannismod.stellurgy.api.satellite.SatelliteProperties;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.environment.BodyRotation;
import dev.stannismod.stellurgy.environment.MagnetosphereDerivation;
import dev.stannismod.stellurgy.environment.PlanetEnvironment;
import dev.stannismod.stellurgy.item.ItemSatellite;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.util.EmbeddedInventory;
import dev.stannismod.stellurgy.network.PacketSatellite;
import dev.stannismod.stellurgy.space.AbsolutePos;
import dev.stannismod.stellurgy.space.BlockDelta;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.SystemBodyKind;
import dev.stannismod.stellurgy.universe.UniverseRegistry;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.FMLCommonHandler;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A satellite's life on its orbit, brought up to a tick when someone asks: its body, its body's field, its
 * star and its parts handed to {@link SatelliteAccrual}, and what that changes written back — the dose
 * onto each part, the energy into the battery, the data into the buffer.
 *
 * <p>A satellite with no orbit is not in space: nothing shines on it and no field reaches it, so nothing
 * accrues. Mining missions and satellites still in a hatch are such.</p>
 */
public final class SatelliteLife {

    /** The key a part's absorbed dose is held under on its own item, Gy. The part carries its wear wherever it goes. */
    public static final String DOSE_KEY = "radiationDose";

    private SatelliteLife() {
    }

    /** How far a satellite's life has been brought, and its failure journal. Held by the satellite. */
    public static final class Progress {
        long segmentsDone;
        /** The energy below one whole unit the battery cannot hold, carried to the next segment. */
        double energyCarry;
        double collectionCarry;
        final FailureJournal journal = new FailureJournal();
        /** Whether the reason this satellite cannot be accrued has been logged; not persisted. */
        boolean refusalReported;
        /** The body's environment, and the inputs it was derived from; not persisted. */
        PlanetEnvironment environment;
        String environmentInputs;

        public FailureJournal journal() {
            return journal;
        }

        /** How many segments of its orbit the satellite's life has been taken over. */
        public long segmentsDone() {
            return segmentsDone;
        }

        public void writeToNBT(NBTTagCompound nbt) {
            nbt.setLong("segments", segmentsDone);
            nbt.setDouble("energyCarry", energyCarry);
            nbt.setDouble("collectionCarry", collectionCarry);
            NBTTagCompound journalNBT = new NBTTagCompound();
            journal.writeToNBT(journalNBT);
            nbt.setTag("journal", journalNBT);
        }

        public void readFromNBT(NBTTagCompound nbt) {
            segmentsDone = nbt.getLong("segments");
            energyCarry = nbt.getDouble("energyCarry");
            collectionCarry = nbt.getDouble("collectionCarry");
            journal.readFromNBT(nbt.getCompoundTag("journal"));
        }
    }

    /** The rules the configuration states. */
    public static SatelliteAccrual.Rules rules() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        return new SatelliteAccrual.Rules(config.satelliteRadiationWear, config.satelliteRadiationWearScale);
    }

    /** The dose a part has absorbed, Gy; zero for a part that has taken none. */
    public static double doseOf(ItemStack part) {
        return part.hasTagCompound() ? part.getTagCompound().getDouble(DOSE_KEY) : 0d;
    }

    static void setDose(ItemStack part, double doseGy) {
        if (!part.hasTagCompound()) {
            part.setTagCompound(new NBTTagCompound());
        }
        part.getTagCompound().setDouble(DOSE_KEY, doseGy);
    }

    /** What {@code stack}, sitting in chassis slot {@code slot}, is to the dose; empty for an empty slot or an unknown item. */
    public static Optional<SatelliteAccrual.Part> partOf(int slot, ItemStack stack) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        SatelliteProperties props = SatelliteRegistry.getSatelliteProperty(stack);
        if (props == null) {
            return Optional.empty();
        }
        int flag = props.getPropertyFlag();
        SatelliteAccrual.PartKind kind;
        if (SatelliteProperties.Property.MAIN.isOfType(flag) || SatelliteProperties.Property.DATA.isOfType(flag)) {
            kind = SatelliteAccrual.PartKind.ELECTRONICS;
        } else if (SatelliteProperties.Property.POWER_GEN.isOfType(flag)) {
            kind = SatelliteAccrual.PartKind.POWER_GENERATION;
        } else if (SatelliteProperties.Property.SHIELD.isOfType(flag)) {
            kind = SatelliteAccrual.PartKind.SHIELD;
        } else {
            kind = SatelliteAccrual.PartKind.INERT;
        }
        return Optional.of(new SatelliteAccrual.Part(slot, kind, SatelliteProperties.Property.MAIN.isOfType(flag),
                props.getPowerGeneration(), props.getMaxDataStorage(), doseOf(stack)));
    }

    /** Whether the part {@code stack} still does its job under the configured rules. */
    public static boolean works(ItemStack stack) {
        Optional<SatelliteAccrual.Part> part = partOf(0, stack);
        return !part.isPresent() || part.get().works(rules());
    }

    /**
     * The parts of {@code satellite}'s chassis as the dose sees them; empty for a satellite not built on a
     * chassis, which has no parts to wear.
     */
    public static List<SatelliteAccrual.Part> partsOf(SatelliteBase satellite) {
        List<SatelliteAccrual.Part> parts = new ArrayList<>();
        ItemStack chassis = satellite.getItemStackFromSatellite();
        if (chassis.getItem() instanceof ItemSatellite) {
            EmbeddedInventory inv = ((ItemSatellite) chassis.getItem()).readInvFromNBT(chassis);
            for (int slot = 0; slot < inv.getSizeInventory(); slot++) {
                partOf(slot, inv.getStackInSlot(slot)).ifPresent(parts::add);
            }
        }
        return parts;
    }

    /** Whether {@code satellite}'s primary function still works: a satellite whose primary function has stopped is dead in orbit. */
    public static boolean functioning(SatelliteBase satellite) {
        SatelliteAccrual.Rules rules = rules();
        for (SatelliteAccrual.Part part : partsOf(satellite)) {
            if (part.primary) {
                return part.works(rules);
            }
        }
        return false;
    }

    /** Bring {@code satellite} up to the space clock's {@code now}. Server only; nothing happens on a client copy. */
    public static void bringUpTo(SatelliteBase satellite, long now) {
        Optional<SatelliteOrbit> orbit = satellite.getOrbit();
        if (!orbit.isPresent() || FMLCommonHandler.instance().getEffectiveSide().isClient()) {
            return;
        }
        Progress progress = satellite.lifeProgress();
        long step = SatelliteAccrual.segmentTicks(orbit.get());
        if (orbit.get().epochTick() + (progress.segmentsDone + 1L) * step > now) {
            return;
        }
        DimensionProperties props = DimensionManager.getInstance().getDimensionPropertiesOrNull(satellite.getDimensionId());
        UniverseRegistry reg = UniverseRegistry.get(FMLCommonHandler.instance().getMinecraftServerInstance());
        Optional<SystemBody> body = props == null ? Optional.empty() : SatellitePlacement.bodyOf(reg, props.getId());
        if (!body.isPresent()) {
            if (!progress.refusalReported) {
                progress.refusalReported = true;
                Stellurgy.logger.warn("Satellite {} is not accrued: the universe names no body for world {}",
                        satellite.getId(), satellite.getDimensionId());
            }
            return;
        }
        SatelliteAccrual.Body about = new SatelliteAccrual.Body(orbit.get(), environmentOf(satellite, props, body.get(), reg),
                tick -> starDirection(reg, body.get(), tick),
                body.get().radiusEarths() * AstronomicalBodyHelper.EARTH_RADIUS_METRES);

        ItemStack chassis = satellite.getItemStackFromSatellite();
        EmbeddedInventory inv = chassis.getItem() instanceof ItemSatellite
                ? ((ItemSatellite) chassis.getItem()).readInvFromNBT(chassis) : null;
        List<SatelliteAccrual.Part> parts = new ArrayList<>();
        if (inv != null) {
            for (int slot = 0; slot < inv.getSizeInventory(); slot++) {
                partOf(slot, inv.getStackInSlot(slot)).ifPresent(parts::add);
            }
        }

        double energy = satellite.storedEnergy() + progress.energyCarry;
        SatelliteAccrual.State state = new SatelliteAccrual.State(progress.segmentsDone, energy,
                satellite.energyCapacity(), satellite.bufferedData(), progress.collectionCarry, progress.journal,
                satellite.doseReadings());
        boolean partStopped = SatelliteAccrual.advance(about, parts, satellite.payload(), state, rules(),
                satellite.getId(), now);

        progress.segmentsDone = state.segmentsDone;
        progress.collectionCarry = state.collectionCarry;
        int whole = (int) Math.floor(state.energy);
        progress.energyCarry = state.energy - whole;
        satellite.setStoredEnergy(whole);
        satellite.setBufferedData(state.data);

        if (inv != null) {
            for (SatelliteAccrual.Part part : parts) {
                ItemStack stack = inv.getStackInSlot(part.slot);
                if (doseOf(stack) != part.doseGy) {
                    setDose(stack, part.doseGy);
                }
            }
            ((ItemSatellite) chassis.getItem()).writeInvToNBT(chassis, inv);
            if (partStopped) {
                SatelliteProperties summed = SatelliteAssembly.propertiesOf(inv, satellite.getProperties().getSatelliteType());
                summed.setId(satellite.getId());
                ((ItemSatellite) chassis.getItem()).setSatellite(chassis, summed);
                satellite.setProperties(chassis);
                PacketHandler.sendToAll(new PacketSatellite(satellite));
            }
        }
    }

    /**
     * The environment {@code satellite}'s parts are exposed to — its body's, as this class derives it for
     * the accrual; empty where the universe names no body for its world or the body cannot be described.
     * Server only.
     */
    public static Optional<PlanetEnvironment> environmentAbout(SatelliteBase satellite) {
        DimensionProperties props = DimensionManager.getInstance().getDimensionPropertiesOrNull(satellite.getDimensionId());
        UniverseRegistry reg = UniverseRegistry.get(FMLCommonHandler.instance().getMinecraftServerInstance());
        Optional<SystemBody> body = props == null ? Optional.empty() : SatellitePlacement.bodyOf(reg, props.getId());
        return body.flatMap(b -> environmentOf(satellite, props, b, reg));
    }

    /**
     * The environment about {@code body}, derived from its bulk, spin, kind, distance from its star and the
     * world's seed; kept on the satellite while its inputs stay the same. Empty, with the reason logged
     * once, when the body cannot be described.
     */
    private static Optional<PlanetEnvironment> environmentOf(SatelliteBase satellite, DimensionProperties props,
                                                             SystemBody body, UniverseRegistry reg) {
        Progress progress = satellite.lifeProgress();
        String inputs = environmentInputs(props, body, reg);
        if (inputs.equals(progress.environmentInputs)) {
            return Optional.of(progress.environment);
        }
        Optional<PlanetEnvironment> derived = bodyEnvironment(props, body, reg);
        if (!derived.isPresent()) {
            if (!progress.refusalReported) {
                progress.refusalReported = true;
                Stellurgy.logger.warn("Satellite {}: no environment for world {} — mass {}, radius {}, rotation {}, "
                                + "distance {} AU cannot describe a body; its parts take no dose and a dosimeter reads nothing",
                        satellite.getId(), props.getId(), body.massEarths(), body.radiusEarths(), props.rotationalPeriod,
                        distanceAu(props));
            }
            return Optional.empty();
        }
        progress.environment = derived.get();
        progress.environmentInputs = inputs;
        return derived;
    }

    /**
     * The environment about {@code body}, the world {@code props} describes, derived from its bulk, spin,
     * kind, distance from its star and the world's seed — the one every object on an orbit about it is
     * exposed to. Empty when those cannot describe a body. Derived afresh on every call.
     */
    public static Optional<PlanetEnvironment> bodyEnvironment(DimensionProperties props, SystemBody body,
                                                              UniverseRegistry reg) {
        double distanceAu = distanceAu(props);
        if (!(body.massEarths() > 0d) || !(body.radiusEarths() > 0d) || props.rotationalPeriod <= 0 || !(distanceAu > 0d)) {
            return Optional.empty();
        }
        BodyRotation rotation = SatellitePlacement.rotationOf(props);
        // The dynamo is driven by the body's turn against the stars, not by its day: the sidereal period.
        long spinTicks = Double.isInfinite(rotation.siderealTicks()) ? 0L : Math.round(Math.abs(rotation.siderealTicks()));
        return Optional.of(PlanetEnvironment.of(
                MagnetosphereDerivation.derive(body.massEarths(), body.radiusEarths(), spinTicks,
                        props.isGasGiant(), props.hasAtmosphere(), distanceAu, environmentSeed(props, reg)),
                rotation.siderealTicks(), rotation.angleAt(0L)));
    }

    private static double distanceAu(DimensionProperties props) {
        return props.getSolarOrbitalDistance() / (double) AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;
    }

    private static long environmentSeed(DimensionProperties props, UniverseRegistry reg) {
        return reg.worldSeed() * 0x9E3779B97F4A7C15L + props.getId();
    }

    private static String environmentInputs(DimensionProperties props, SystemBody body, UniverseRegistry reg) {
        return body.massEarths() + "/" + body.radiusEarths() + "/" + props.rotationalPeriod + "/"
                + props.isGasGiant() + "/" + props.hasAtmosphere() + "/" + distanceAu(props) + "/"
                + environmentSeed(props, reg);
    }

    /**
     * Toward the nearest star from {@code body}'s centre at {@code tick}, in the orbit's frame (z along
     * the spin axis): the cell's axes carried back — x stays x, the cell's Y is the spin axis, its Z is −y.
     */
    public static Optional<Vector3dc> starDirection(UniverseRegistry reg, SystemBody body, long tick) {
        AbsolutePos at = body.absoluteAt(tick);
        BlockDelta nearest = null;
        for (SystemBody b : reg.systemBodiesAt(body.name())) {
            if (b.kind() == SystemBodyKind.STAR) {
                BlockDelta toStar = b.absoluteAt(tick).minus(at);
                if (nearest == null || toStar.length() < nearest.length()) {
                    nearest = toStar;
                }
            }
        }
        if (nearest == null || nearest.isZero()) {
            return Optional.empty();
        }
        return Optional.of(new Vector3d(nearest.dx(), -nearest.dz(), nearest.dy()).normalize());
    }
}
