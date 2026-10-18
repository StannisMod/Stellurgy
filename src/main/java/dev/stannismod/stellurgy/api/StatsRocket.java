package dev.stannismod.stellurgy.api;

import dev.stannismod.stellurgy.api.fuel.FuelRegistry;
import dev.stannismod.stellurgy.api.fuel.FuelRegistry.FuelType;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;
import dev.stannismod.stellurgy.libvulpes.util.Vector3F;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The derived stat vector of one tier-1 rocket.
 *
 * <p><b>Units.</b> {@code mass} is a MASS, in kilograms — never a weight-at-1g. {@code thrust}
 * is a force, in newtons. Local weight is therefore {@code mass * STANDARD_GRAVITY *
 * gravitationalMultiplier}, and every gravity-dependent quantity (net acceleration, TWR, the
 * launch gate) derives from that one expression instead of assuming the rocket sits at one gee.
 */
public class StatsRocket {

    private static final String TAGNAME = "rocketStats";
    public static final int INVALID_SEAT = Integer.MIN_VALUE;

    /** The sentinel a fluid slot carries when nothing has been loaded into it yet. */
    private static final String NO_FLUID = "null";

    /** Standard gravity, m/s². One unit of {@code gravitationalMultiplier} is one standard gravity. */
    public static final float STANDARD_GRAVITY = 9.81f;

    /**
     * Free-fall acceleration in blocks per tick squared, at one standard gravity: vanilla's own
     * {@code motionY -= 0.08} (`EntityLivingBase.travel`).
     *
     * <p>This is 9.81 m/s² and not a stronger gravity, because one tick is <b>90.3 ms</b>, not 50:
     * {@code 0.08 / 0.0903² = 9.81}. Four unrelated vanilla constants agree with that tick length —
     * terminal velocity 3.92 blocks/tick = 43.4 m/s, the jump impulse 0.42 blocks/tick = 4.65 m/s,
     * walking 2.39 m/s, sprinting 3.11 m/s — so the game is SI-consistent at one metre per block and
     * only its SECOND differs from ours. Every derived readout in m/s² converts by dividing a
     * per-tick quantity by 0.0903², never by 0.05².</p>
     *
     * <p>A rocket, a player and a tier-2 ship all fall at this rate. They did not before: the classic
     * ascent used 0.05, the classic descent 0.04905, free flight 0.04 and a ship 0.0245, so a ship
     * descended out from under the crew standing on it.</p>
     */
    public static final double GRAVITY_BLOCKS_PER_TICK_SQUARED = 0.08D;

    /**
     * The newtons that one unit of the pre-3.0.0 dimensionless thrust rating maps onto
     * (5 tonnes-force). Empirical curves that were fitted against that rating — only the
     * exhaust scorch radius — normalise by this so their shape survives the move to SI.
     * Nothing else may read it: thrust is newtons everywhere else.
     */
    public static final float THRUST_RATING_UNIT_NEWTONS = 49050f;

    private final List<HashedBlockPosition> passengerSeats = new ArrayList<>();
    /**
     * The height this flight reaches orbit at, written by the launch; {@link #ORBIT_HEIGHT_UNSET} until
     * a launch has written it (it is not saved, so a rocket loaded mid-flight is unset too), and the
     * rocket then takes its world's own line.
     */
    public int orbitHeight;
    /** Sentinel for {@link #orbitHeight}: no launch has stated this flight's orbit height. */
    public static final int ORBIT_HEIGHT_UNSET = -1;
    public float injectionBurnLenghtMult;
    HashedBlockPosition pilotSeatPos;
    /** Engine thrust, newtons. A long: one nuclear core rates 4.9e7 N, so an int saturated at ~44 cores. */
    private long thrust;
    /** Dry mass, kilograms. Fuel mass is added by {@link #getMass()}. */
    private float mass;
    private float drillingPower;
    private String fuelFluid;
    private String oxidizerFluid;
    private String workingFluid;
    private int fuelMonopropellant;
    private int fuelNuclearWorkingFluid;
    private int fuelBipropellant;
    private int fuelOxidizer;
    private int fuelIon;
    private int fuelWarp;
    private int fuelImpulse;
    private int fuelCapacityMonopropellant;
    private int fuelCapacityBipropellant;
    private int fuelCapacityOxidizer;
    private int fuelCapacityNuclearWorkingFluid;
    private int fuelCapacityIon;
    private int fuelCapacityWarp;
    private int fuelCapacityImpulse;
    /** The tank role the engines feed from, or {@code null} for a craft with no engines. */
    @Nullable
    private FuelType engineFeed;
    /**
     * The fraction of its propellant's exhaust velocity the engine cluster reaches: the thrust-weighted
     * harmonic mean of the motors' cycle fractions, so the cluster's mass flow is the sum of theirs.
     */
    private float exhaustVelocityFactor = 1;
    /** Thermal power the counted nuclear core stacks deliver now, watts (C10 STAT-35, STAT-36). */
    private double coreThermalPower;
    /** Their temperature, kelvin; their mean when they differ. */
    private double coreTemperature;
    /** The nuclear nozzles' jet efficiency, thrust-weighted. */
    private float nuclearJetEfficiency;
    private List<Vector3F<Float>> engineLoc;
    private HashMap<String, Object> statTags;

    public StatsRocket() {
        thrust = 0;
        mass = 0;
        fuelFluid = "null";
        oxidizerFluid = "null";
        workingFluid = "null";
        fuelMonopropellant = 0;
        fuelBipropellant = 0;
        fuelOxidizer = 0;
        drillingPower = 0f;
        orbitHeight = ORBIT_HEIGHT_UNSET;
        injectionBurnLenghtMult = 1;
        pilotSeatPos = new HashedBlockPosition(0, 0, 0);
        pilotSeatPos.x = INVALID_SEAT;
        engineLoc = new ArrayList<>();
        statTags = new HashMap<>();
    }

	/*public StatsRocket(int thrust, int weight, int fuelRate, int fuel) {
		this.thrust = thrust;
		this.weight = weight;
		this.fuelLiquid = fuel;
		lastSeatX = -1;
		engineLoc = new ArrayList<Vector3F>();
	}*/

    public static StatsRocket createFromNBT(NBTTagCompound nbt) {
        if (nbt.hasKey(TAGNAME)) {
            // readFromNBT finds the stats compound under TAGNAME itself, as writeToNBT nests it
            StatsRocket statsRocket = new StatsRocket();
            statsRocket.readFromNBT(nbt);
            return statsRocket;
        }

        return new StatsRocket();
    }

    public int getSeatX() {
        return pilotSeatPos.x;
    }

    public int getSeatY() {
        return pilotSeatPos.y;
    }

    public int getSeatZ() {
        return pilotSeatPos.z;
    }

    public HashedBlockPosition getPassengerSeat(int index) {
        return passengerSeats.get(index);
    }

    public int getNumPassengerSeats() {
        return passengerSeats.size();
    }

    /**
     * Engine thrust in newtons: the motors' rated thrust after the config multiplier, which for nuclear
     * nozzles is further held to what their cores' power gives at the working fluid's exhaust velocity
     * (C10 STAT-30, STAT-36). A nuclear craft whose exhaust velocity cannot be found gives nothing.
     */
    public long getThrust() {
        double rated = thrust * StellurgyConfiguration.getCurrentConfig().rocketThrustMultiplier;
        if (engineFeed != FuelType.NUCLEAR_WORKING_FLUID) {
            return (long) rated;
        }
        java.util.OptionalDouble ve = getExhaustVelocity();
        if (!ve.isPresent() || !(nuclearJetEfficiency > 0)) {
            return 0;
        }
        return (long) Math.min(rated, 2 * nuclearJetEfficiency * coreThermalPower / ve.getAsDouble());
    }

    /** @param thrust the motors' rated thrust summed, newtons */
    public void setThrust(long thrust) {
        this.thrust = thrust;
    }

    /**
     * Effectively final, process lifetime: built once at class initialisation. How long one tier-1
     * flight tick lasts in the flight's own physics, seconds: the tick vanilla's
     * gravity of 0.08 blocks/tick² takes to be 9.81 m/s² ({@link #GRAVITY_BLOCKS_PER_TICK_SQUARED}). The
     * drain spends propellant over this same tick, so the impulse a tick gives the craft is the
     * impulse its propellant carries away (C10 STAT-33, STAT-24).
     */
    public static final double TICK_SECONDS = Math.sqrt(GRAVITY_BLOCKS_PER_TICK_SQUARED / STANDARD_GRAVITY);

    @Nullable
    public FuelType getEngineFeed() {
        return engineFeed;
    }

    public void setEngineFeed(@Nullable FuelType engineFeed) {
        this.engineFeed = engineFeed;
    }

    public float getExhaustVelocityFactor() {
        return exhaustVelocityFactor;
    }

    public void setExhaustVelocityFactor(float exhaustVelocityFactor) {
        this.exhaustVelocityFactor = exhaustVelocityFactor;
    }

    /** W. */
    public double getCoreThermalPower() {
        return coreThermalPower;
    }

    /** K. */
    public double getCoreTemperature() {
        return coreTemperature;
    }

    public float getNuclearJetEfficiency() {
        return nuclearJetEfficiency;
    }

    /** The nuclear part: the counted cores' power (W) and temperature (K), and the nozzles' jet efficiency. */
    public void setNuclear(double coreThermalPower, double coreTemperature, float nuclearJetEfficiency) {
        this.coreThermalPower = coreThermalPower;
        this.coreTemperature = coreTemperature;
        this.nuclearJetEfficiency = nuclearJetEfficiency;
    }

    /** @param coreThermalPower what the counted cores deliver now, W */
    public void setCoreThermalPower(double coreThermalPower) {
        this.coreThermalPower = coreThermalPower;
    }

    /**
     * The exhaust velocity the engines give the propellant they are fed, m/s (C10 STAT-32): the
     * monopropellant's row, the pair's row, or for nuclear nozzles the core temperature and the
     * working fluid's molar mass — times the cluster's cycle fraction. Empty when the propellant is not
     * loaded or the table has no row for it. A nuclear craft whose working fluid is not loaded yet is
     * judged on the accepted fluid that gives it the least thrust — the fastest exhaust — so its
     * thrust is never promised on a fluid it may not carry.
     */
    public java.util.OptionalDouble getExhaustVelocity() {
        if (engineFeed == null) {
            return java.util.OptionalDouble.empty();
        }
        dev.stannismod.stellurgy.util.WeightEngine table = dev.stannismod.stellurgy.Stellurgy.weights();
        java.util.OptionalDouble ceiling;
        switch (engineFeed) {
            case LIQUID_MONOPROPELLANT:
                ceiling = isLoadedFluid(fuelFluid)
                        ? table.monopropellantExhaustVelocityOf(FluidRegistry.getFluid(fuelFluid))
                        : java.util.OptionalDouble.empty();
                break;
            case LIQUID_BIPROPELLANT:
                ceiling = isLoadedFluid(fuelFluid) && isLoadedFluid(oxidizerFluid)
                        ? table.pairOf(FluidRegistry.getFluid(fuelFluid), FluidRegistry.getFluid(oxidizerFluid))
                                .map(p -> java.util.OptionalDouble.of(p.exhaustVelocity))
                                .orElse(java.util.OptionalDouble.empty())
                        : java.util.OptionalDouble.empty();
                break;
            case NUCLEAR_WORKING_FLUID:
                ceiling = nuclearExhaustVelocity(table);
                break;
            default:
                ceiling = java.util.OptionalDouble.empty();
        }
        return ceiling.isPresent()
                ? java.util.OptionalDouble.of(ceiling.getAsDouble() * exhaustVelocityFactor)
                : java.util.OptionalDouble.empty();
    }

    private java.util.OptionalDouble nuclearExhaustVelocity(dev.stannismod.stellurgy.util.WeightEngine table) {
        if (!(coreTemperature > 0)) {
            return java.util.OptionalDouble.empty();
        }
        if (isLoadedFluid(workingFluid)) {
            java.util.OptionalDouble m = table.molarMassOf(FluidRegistry.getFluid(workingFluid));
            return m.isPresent()
                    ? java.util.OptionalDouble.of(dev.stannismod.stellurgy.api.propulsion.NuclearThermal
                            .exhaustVelocity(coreTemperature, m.getAsDouble()))
                    : java.util.OptionalDouble.empty();
        }
        double fastest = -1;
        for (Fluid fluid : FuelRegistry.instance.getFluids(FuelType.NUCLEAR_WORKING_FLUID)) {
            java.util.OptionalDouble m = table.molarMassOf(fluid);
            if (m.isPresent()) {
                fastest = Math.max(fastest, dev.stannismod.stellurgy.api.propulsion.NuclearThermal
                        .exhaustVelocity(coreTemperature, m.getAsDouble()));
            }
        }
        return fastest > 0 ? java.util.OptionalDouble.of(fastest) : java.util.OptionalDouble.empty();
    }

    /** The loaded pair's oxidizer-to-fuel ratio by mass; empty unless both halves of a known pair are loaded. */
    public java.util.OptionalDouble getMixtureRatio() {
        if (engineFeed != FuelType.LIQUID_BIPROPELLANT || !isLoadedFluid(fuelFluid) || !isLoadedFluid(oxidizerFluid)) {
            return java.util.OptionalDouble.empty();
        }
        return dev.stannismod.stellurgy.Stellurgy.weights()
                .pairOf(FluidRegistry.getFluid(fuelFluid), FluidRegistry.getFluid(oxidizerFluid))
                .map(p -> java.util.OptionalDouble.of(p.mixtureRatio))
                .orElse(java.util.OptionalDouble.empty());
    }

    /** The mass a burn at {@code forceNewtons} throws out in one tick, kg: {@code F·τ / v_e}; 0 with no {@code v_e}. */
    public static double ejectedMassPerTick(double forceNewtons, double exhaustVelocity) {
        return exhaustVelocity > 0 ? Math.max(0, forceNewtons) * TICK_SECONDS / exhaustVelocity : 0;
    }

    /** Dry mass in kilograms — the rocket with empty tanks. */
    public float getDryMass() {
        return mass;
    }

    /** True if the named fluid exists. The sentinel is answered without touching the registry:
     *  an empty rocket is the common case, and it lets the mass of one be taken before the fluid
     *  registry is up. */
    private static boolean isLoadedFluid(String name) {
        return name != null && !NO_FLUID.equals(name) && !name.isEmpty()
                && FluidRegistry.isFluidRegistered(name);
    }

    /** Wet mass in kilograms — dry mass plus the fuel and oxidizer currently carried. */
    public float getMass() {
        float fluidMass = 0;
        if (StellurgyConfiguration.getCurrentConfig().advancedWeightSystem) {
            if (isLoadedFluid(getFuelFluid())) {
                Fluid f = FluidRegistry.getFluid(getFuelFluid());
                fluidMass += dev.stannismod.stellurgy.Stellurgy.weights().getWeight(f, getFuelAmount(FuelType.LIQUID_MONOPROPELLANT));
                fluidMass += dev.stannismod.stellurgy.Stellurgy.weights().getWeight(f, getFuelAmount(FuelType.LIQUID_BIPROPELLANT));
            }
            if (isLoadedFluid(getOxidizerFluid())) {
                Fluid f = FluidRegistry.getFluid(getOxidizerFluid());
                fluidMass += dev.stannismod.stellurgy.Stellurgy.weights().getWeight(f, getFuelAmount(FuelType.LIQUID_OXIDIZER));
            }
            if (isLoadedFluid(getWorkingFluid())) {
                Fluid f = FluidRegistry.getFluid(getWorkingFluid());
                fluidMass += dev.stannismod.stellurgy.Stellurgy.weights().getWeight(f, getFuelAmount(FuelType.NUCLEAR_WORKING_FLUID));
            }
        }
        return mass + fluidMass;
    }

    /** @param mass dry mass, kilograms */
    public void setMass(float mass) {
        this.mass = mass;
    }

    public String getFuelFluid() {
        return fuelFluid;
    }

    public void setFuelFluid(String fuelFluid) {
        this.fuelFluid = fuelFluid;
    }

    public String getOxidizerFluid() {
        return oxidizerFluid;
    }

    public void setOxidizerFluid(String oxidizerFluid) {
        this.oxidizerFluid = oxidizerFluid;
    }

    public String getWorkingFluid() {
        return workingFluid;
    }

    public void setWorkingFluid(String workingFluid) {
        this.workingFluid = workingFluid;
    }

    public float getDrillingPower() {
        return drillingPower;
    }

    public void setDrillingPower(float power) {
        drillingPower = power;
    }

    /**
     * The gravity actually seen by the flight model, in standard gravities. Reading it through
     * one method is what keeps the launch gate and the flight model from disagreeing about which
     * planet the rocket is on: {@code gravityAffectsFuel = false} pins both to one gee.
     */
    private static float effectiveGravityMultiplier(float gravitationalMultiplier) {
        return StellurgyConfiguration.getCurrentConfig().gravityAffectsFuel ? gravitationalMultiplier : 1f;
    }

    /** Local weight in newtons of a given mass — the force the engines have to beat to hover. */
    public static float weightNewtons(float massKg, float gravitationalMultiplier) {
        return massKg * STANDARD_GRAVITY * effectiveGravityMultiplier(gravitationalMultiplier);
    }

    /** Local weight of the wet rocket, newtons. */
    public float getWeightNewtons(float gravitationalMultiplier) {
        return weightNewtons(getMass(), gravitationalMultiplier);
    }

    /**
     * Net climb per tick at full thrust, in blocks per tick squared — the units the entity adds to its
     * motion. The net specific force {@code (thrust - weight) / mass} is expressed in standard
     * gravities and then scaled by what one standard gravity is worth per tick, so a rocket at TWR 0
     * falls at exactly the rate a player does.
     */
    private float netClimbPerTick(float massKg, float gravitationalMultiplier) {
        return (float) dev.stannismod.stellurgy.api.propulsion.ClassicAscent.climbPerTick(getThrust(), massKg,
                effectiveGravityMultiplier(gravitationalMultiplier));
    }

    public float getAcceleration(float gravitationalMultiplier) {
        return netClimbPerTick(getMass(), gravitationalMultiplier);
    }

    /** Acceleration with empty tanks (dry mass only) — the upper bound reached as fuel burns off. */
    public float getDryAcceleration(float gravitationalMultiplier) {
        return netClimbPerTick(getDryMass(), gravitationalMultiplier);
    }

    /** Thrust-to-weight ratio against the wet mass at the LOCAL gravity. 0 if massless. */
    public float getThrustToWeightRatio(float gravitationalMultiplier) {
        if (getMass() <= 0) {
            return 0;
        }
        float localWeight = getWeightNewtons(gravitationalMultiplier);
        if (localWeight <= 0) {
            // A body with no gravity: any thrust at all is enough to leave it.
            return getThrust() > 0 ? Float.POSITIVE_INFINITY : 0;
        }
        return getThrust() / localWeight;
    }

    /** True if the rocket clears the configured minimum thrust-to-weight ratio to launch
     *  from a body of the given gravity; the boundary is inclusive. When the advanced weight
     *  system is disabled the mass-based launch gate is off entirely (classic behaviour — no TWR
     *  check), so this returns true regardless of thrust or mass. This is the single source of
     *  truth for mass-based launch gating; callers must not re-derive the TWR check independently
     *  — a caller that judges a craft before launch (an assembler) asks this on the stats it means
     *  to judge.
     *  @param gravitationalMultiplier gravity of the body being launched from, in standard
     *                                 gravities — a light moon is easier to leave than Earth */
    public boolean canLaunch(float gravitationalMultiplier) {
        if (!StellurgyConfiguration.getCurrentConfig().advancedWeightSystem) {
            return true;
        }
        return getThrustToWeightRatio(gravitationalMultiplier) >= StellurgyConfiguration.getCurrentConfig().minLaunchTWR;
    }

    /**
     * A copy of these stats with every tank the craft has at its capacity — the craft as it will
     * stand when fully fuelled. A tank that has not yet been given a fluid is filled with the
     * heaviest fluid the fuel registry accepts for it, so a craft that clears the launch gate in
     * this state clears it with whatever fuel it can be given.
     */
    public StatsRocket withTanksFull() {
        StatsRocket full = copy();
        for (FuelType type : FuelType.values()) {
            full.setFuelAmount(type, getFuelCapacity(type));
        }
        full.nameUnnamedTanks();
        return full;
    }

    /**
     * Gives every tank that has no fluid chosen yet the heaviest fluid the fuel registry accepts for it
     * — the same choice {@link #withTanksFull} judges a craft by — so contents put in without naming a
     * fluid weigh and burn as that fluid.
     */
    public void nameUnnamedTanks() {
        if (NO_FLUID.equals(fuelFluid)) {
            FuelType fuelType = getFuelCapacity(FuelType.LIQUID_MONOPROPELLANT) > 0
                    ? FuelType.LIQUID_MONOPROPELLANT : FuelType.LIQUID_BIPROPELLANT;
            fuelFluid = heaviestFluidName(fuelType, fuelFluid);
        }
        if (NO_FLUID.equals(oxidizerFluid)) {
            oxidizerFluid = heaviestFluidName(FuelType.LIQUID_OXIDIZER, oxidizerFluid);
        }
        if (NO_FLUID.equals(workingFluid)) {
            workingFluid = heaviestFluidName(FuelType.NUCLEAR_WORKING_FLUID, workingFluid);
        }
    }

    /** The name of the densest fluid the registry accepts as {@code type}, or {@code none} when it
     *  accepts no fluid of that type — a tank no fluid fits stays as empty as it was. */
    private static String heaviestFluidName(FuelType type, String none) {
        Fluid heaviest = dev.stannismod.stellurgy.Stellurgy.weights().heaviestFluidOf(type);
        return heaviest == null ? none : heaviest.getName();
    }

    public List<Vector3F<Float>> getEngineLocations() {
        return engineLoc;
    }

    public boolean isNuclear() {
        return engineFeed == FuelType.NUCLEAR_WORKING_FLUID;
    }

    /**
     * The usable propellant aboard, kg: the monopropellant or the working fluid as loaded, and for a
     * pair the mass that burns before EITHER half runs out at the pair's mixture ratio. Zero when the
     * propellant has no exhaust velocity — it cannot burn at all.
     */
    public double getUsablePropellantMass() {
        if (engineFeed == null || !getExhaustVelocity().isPresent()) {
            return 0;
        }
        dev.stannismod.stellurgy.util.WeightEngine table = dev.stannismod.stellurgy.Stellurgy.weights();
        switch (engineFeed) {
            case LIQUID_MONOPROPELLANT:
                return table.getWeight(FluidRegistry.getFluid(fuelFluid), getFuelAmount(FuelType.LIQUID_MONOPROPELLANT));
            case NUCLEAR_WORKING_FLUID:
                return isLoadedFluid(workingFluid)
                        ? table.getWeight(FluidRegistry.getFluid(workingFluid), getFuelAmount(FuelType.NUCLEAR_WORKING_FLUID))
                        : 0;
            case LIQUID_BIPROPELLANT: {
                double of = getMixtureRatio().orElse(0);
                double fuel = table.getWeight(FluidRegistry.getFluid(fuelFluid), getFuelAmount(FuelType.LIQUID_BIPROPELLANT));
                double ox = table.getWeight(FluidRegistry.getFluid(oxidizerFluid), getFuelAmount(FuelType.LIQUID_OXIDIZER));
                return of > 0 ? Math.min(fuel * (1 + of), ox * (1 + of) / of) : 0;
            }
            default:
                return 0;
        }
    }

    /**
     * The classic ascent from world Y {@code fromY} to {@code lineY} with the propellant aboard, run by
     * the flight's own law ({@link dev.stannismod.stellurgy.api.propulsion.ClassicAscent}): its rated
     * thrust, its exhaust velocity, its wet mass, and the gravity the flight reads (C10 STAT-34).
     *
     * @param gravity the world's gravitational multiplier, as the flight reads it
     */
    public dev.stannismod.stellurgy.api.propulsion.ClassicAscent.Forecast forecastAscent(double fromY, double lineY,
                                                                                         float gravity) {
        return dev.stannismod.stellurgy.api.propulsion.ClassicAscent.run(fromY, lineY, getThrust(),
                getExhaustVelocity().orElse(0), getMass(), getUsablePropellantMass(),
                effectiveGravityMultiplier(gravity), gravity);
    }

    public void setSeatLocation(int x, int y, int z) {
        pilotSeatPos.x = x;
        pilotSeatPos.y = (short) y;
        pilotSeatPos.z = z;
    }

    public void addPassengerSeat(int x, int y, int z) {
        if (!hasSeat())
            setSeatLocation(x, y, z);
        passengerSeats.add(new HashedBlockPosition(x, y, z));
    }

    /**
     * Adds an engine location to the given coordinates
     * the engine location is only currently used to track the location for spawning particle effects
     *
     * @param x
     * @param y
     * @param z
     */
    public void addEngineLocation(float x, float y, float z) {
        //We want to be in the center of the block
        //System.out.println("ADD engine at "+x+":"+y+":"+z);
        engineLoc.add(new Vector3F<>(x, y, z));
    }

    /**
     * Removes all engine locations
     */
    public void clearEngineLocations() {
        engineLoc.clear();
    }

    /**
     * @return a duplicate of the rocket stats
     */
    public StatsRocket copy() {
        StatsRocket stat = new StatsRocket();

        stat.thrust = this.thrust;
        stat.mass = this.mass;
        stat.fuelFluid = this.fuelFluid;
        stat.oxidizerFluid = this.oxidizerFluid;
        stat.workingFluid = this.workingFluid;
        stat.drillingPower = this.drillingPower;
        stat.engineFeed = this.engineFeed;
        stat.exhaustVelocityFactor = this.exhaustVelocityFactor;
        stat.coreThermalPower = this.coreThermalPower;
        stat.coreTemperature = this.coreTemperature;
        stat.nuclearJetEfficiency = this.nuclearJetEfficiency;

        for (FuelType type : FuelType.values()) {
            stat.setFuelAmount(type, this.getFuelAmount(type));
            stat.setFuelCapacity(type, this.getFuelCapacity(type));
        }

        stat.pilotSeatPos = new HashedBlockPosition(this.pilotSeatPos.x, this.pilotSeatPos.y, this.pilotSeatPos.z);
        stat.passengerSeats.addAll(passengerSeats);
        stat.engineLoc = new ArrayList<>(engineLoc);
        stat.statTags = new HashMap<>(statTags);
        return stat;
    }

    /**
     * @param type type of fuel to check
     * @return the amount of fuel of the type currently contained in the stat
     */
    public int getFuelAmount(@Nullable FuelRegistry.FuelType type) {
        if (type != null) {
            switch (type) {
                case WARP:
                    return fuelWarp;
                case IMPULSE:
                    return fuelImpulse;
                case ION:
                    return fuelIon;
                case LIQUID_MONOPROPELLANT:
                    return fuelMonopropellant;
                case LIQUID_BIPROPELLANT:
                    return fuelBipropellant;
                case LIQUID_OXIDIZER:
                    return fuelOxidizer;
                case NUCLEAR_WORKING_FLUID:
                    return fuelNuclearWorkingFluid;
            }
        }

        return 0;
    }

    /**
     * @param type
     * @return the largest amount of fuel of the type that can be stored in the stat
     */
    public int getFuelCapacity(@Nullable FuelRegistry.FuelType type) {
        if (type != null) {
            switch (type) {
                case WARP:
                    return fuelCapacityWarp;
                case IMPULSE:
                    return fuelCapacityImpulse;
                case ION:
                    return fuelCapacityIon;
                case LIQUID_MONOPROPELLANT:
                    return fuelCapacityMonopropellant;
                case LIQUID_BIPROPELLANT:
                    return fuelCapacityBipropellant;
                case LIQUID_OXIDIZER:
                    return fuelCapacityOxidizer;
                case NUCLEAR_WORKING_FLUID:
                    return fuelCapacityNuclearWorkingFluid;
            }
        }

        return 0;
    }

    /**
     * Sets the amount of a given fuel type in the stat
     *
     * @param type
     * @param amt
     */
    public void setFuelAmount(@Nonnull FuelRegistry.FuelType type, int amt) {
        switch (type) {
            case WARP:
                fuelWarp = amt;
                break;
            case IMPULSE:
                fuelImpulse = amt;
                break;
            case ION:
                fuelIon = amt;
                break;
            case LIQUID_MONOPROPELLANT:
                fuelMonopropellant = amt;
                break;
            case LIQUID_BIPROPELLANT:
                fuelBipropellant = amt;
                break;
            case LIQUID_OXIDIZER:
                fuelOxidizer = amt;
                break;
            case NUCLEAR_WORKING_FLUID:
                fuelNuclearWorkingFluid = amt;
        }
    }

    /**
     * Sets the fuel capacity of the fuel type in this stat
     *
     * @param type
     * @param amt
     */
    public void setFuelCapacity(@Nonnull FuelRegistry.FuelType type, int amt) {
        switch (type) {
            case WARP:
                fuelCapacityWarp = amt;
                break;
            case IMPULSE:
                fuelCapacityImpulse = amt;
                break;
            case ION:
                fuelCapacityIon = amt;
                break;
            case LIQUID_MONOPROPELLANT:
                fuelCapacityMonopropellant = amt;
                break;
            case LIQUID_BIPROPELLANT:
                fuelCapacityBipropellant = amt;
                break;
            case LIQUID_OXIDIZER:
                fuelCapacityOxidizer = amt;
                break;
            case NUCLEAR_WORKING_FLUID:
                fuelCapacityNuclearWorkingFluid = amt;
        }
    }

    /**
     * @param type type of fuel
     * @param amt  amount of fuel to add
     * @return amount of fuel added
     */
    public int addFuelAmount(@Nonnull FuelRegistry.FuelType type, int amt) {
        switch (type) {
            case WARP:
                int maxAddWarp = fuelCapacityWarp - fuelWarp;
                int amountToAddWarp = Math.min(amt, maxAddWarp);
                fuelWarp += amountToAddWarp;
                return amountToAddWarp;
            case IMPULSE:
                int maxAddImpulse = fuelCapacityImpulse - fuelImpulse;
                int amountToAddImpulse = Math.min(amt, maxAddImpulse);
                fuelImpulse += amountToAddImpulse;
                return amountToAddImpulse;
            case ION:
                int maxAddIon = fuelCapacityIon - fuelIon;
                int amountToAddIon = Math.min(amt, maxAddIon);
                fuelIon += amountToAddIon;
                return amountToAddIon;
            case LIQUID_MONOPROPELLANT:
                int maxAddMonopropellant = fuelCapacityMonopropellant - fuelMonopropellant;
                int amountToAddMonopropellant = Math.min(amt, maxAddMonopropellant);
                fuelMonopropellant += amountToAddMonopropellant;
                return amountToAddMonopropellant;
            case LIQUID_BIPROPELLANT:
                int maxAddBipropellant = fuelCapacityBipropellant - fuelBipropellant;
                int amountToAddBipropellant = Math.min(amt, maxAddBipropellant);
                fuelBipropellant += amountToAddBipropellant;
                return amountToAddBipropellant;
            case LIQUID_OXIDIZER:
                int maxAddOxidizer = fuelCapacityOxidizer - fuelOxidizer;
                int amountToAddOxidizer = Math.min(amt, maxAddOxidizer);
                fuelOxidizer += amountToAddOxidizer;
                return amountToAddOxidizer;
            case NUCLEAR_WORKING_FLUID:
                int maxAddNuclearWorkingFluid = fuelCapacityNuclearWorkingFluid - fuelNuclearWorkingFluid;
                int amountToAddNuclearWorkingFluid = Math.min(amt, maxAddNuclearWorkingFluid);
                fuelNuclearWorkingFluid += amountToAddNuclearWorkingFluid;
                return amountToAddNuclearWorkingFluid;
        }
        return 0;
    }

    /**
     * @return true if a seat exists on this stat
     */
    public boolean hasSeat() {
        return pilotSeatPos.x != INVALID_SEAT;
    }

    /**
     * resets all values to default
     */
    public void reset() {
        thrust = 0;
        mass = 0;
        fuelFluid = "null";
        oxidizerFluid = "null";
        workingFluid = "null";
        drillingPower = 0f;
        clearEngines();

        for (FuelType type : FuelType.values()) {
            setFuelAmount(type, 0);
            setFuelCapacity(type, 0);
        }

        fuelMonopropellant = 0;
        fuelBipropellant = 0;
        fuelOxidizer = 0;
        pilotSeatPos.x = INVALID_SEAT;
        clearEngineLocations();
        passengerSeats.clear();
        statTags.clear();
    }
    public void reset_no_fuel() {
        thrust = 0;
        mass = 0;
        drillingPower = 0f;
        clearEngines();

        pilotSeatPos.x = INVALID_SEAT;
        clearEngineLocations();
        passengerSeats.clear();
        statTags.clear();
    }

    private void clearEngines() {
        engineFeed = null;
        exhaustVelocityFactor = 1;
        coreThermalPower = 0;
        coreTemperature = 0;
        nuclearJetEfficiency = 0;
    }

    public void setStatTag(String str, float value) {
        statTags.put(str, value);
    }

    public void setStatTag(String str, int value) {
        statTags.put(str, value);
    }

    /**
     * @param str name of the tag to get
     * @return the value of the tag as float or int, or 0 if tag does not exist
     */
    public Object getStatTag(String str) {
        Object obj = statTags.get(str);
        return obj == null ? 0 : obj;
    }

    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound stats = new NBTTagCompound();

        stats.setLong("thrust", this.thrust);
        stats.setFloat("mass", this.mass);
        stats.setFloat("drillingPower", this.drillingPower);
        stats.setString("fuelFluid", this.fuelFluid);
        stats.setString("oxidizerFluid", this.oxidizerFluid);
        stats.setString("workingFluid", this.workingFluid);

        stats.setInteger("fuelMonopropellant", this.fuelMonopropellant);
        stats.setInteger("fuelBipropellant", this.fuelBipropellant);
        stats.setInteger("fuelOxidizer", this.fuelOxidizer);
        stats.setInteger("fuelImpulse", this.fuelImpulse);
        stats.setInteger("fuelIon", this.fuelIon);
        stats.setInteger("fuelNuclearWorkingFluid", this.fuelNuclearWorkingFluid);
        stats.setInteger("fuelWarp", this.fuelWarp);

        stats.setInteger("fuelCapacityMonopropellant", this.fuelCapacityMonopropellant);
        stats.setInteger("fuelCapacityBipropellant", this.fuelCapacityBipropellant);
        stats.setInteger("fuelCapacityOxidizer", this.fuelCapacityOxidizer);
        stats.setInteger("fuelCapacityImpulse", this.fuelCapacityImpulse);
        stats.setInteger("fuelCapacityIon", this.fuelCapacityIon);
        stats.setInteger("fuelCapacityNuclearWorkingFluid", this.fuelCapacityNuclearWorkingFluid);
        stats.setInteger("fuelCapacityWarp", this.fuelCapacityWarp);

        stats.setInteger("engineFeed", engineFeed == null ? -1 : engineFeed.id);
        stats.setFloat("exhaustVelocityFactor", this.exhaustVelocityFactor);
        stats.setDouble("coreThermalPower", this.coreThermalPower);
        stats.setDouble("coreTemperature", this.coreTemperature);
        stats.setFloat("nuclearJetEfficiency", this.nuclearJetEfficiency);

        NBTTagCompound dynStats = new NBTTagCompound();
        for (String key : statTags.keySet()) {
            Object obj = statTags.get(key);

            if (obj instanceof Float)
                dynStats.setFloat(key, (float) obj);
            else if (obj instanceof Integer)
                dynStats.setInteger(key, (int) obj);
        }
        if (!dynStats.hasNoTags())
            stats.setTag("dynStats", dynStats);

        stats.setInteger("playerXPos", pilotSeatPos.x);
        stats.setInteger("playerYPos", pilotSeatPos.y);
        stats.setInteger("playerZPos", pilotSeatPos.z);

        if (!engineLoc.isEmpty()) {
            // I make a little hack here to pass double positions by *2 in write and /2 in read method
            int[] locs = new int[engineLoc.size() * 3];

            for (int i = 0; (i / 3) < engineLoc.size(); i += 3) {
                Vector3F<Float> vec = engineLoc.get(i / 3);
                locs[i] = (int)(vec.x*2);
                locs[i + 1] = (int)(vec.y*2);
                locs[i + 2] = (int)(vec.z*2);
            }
            stats.setIntArray("engineLoc", locs);
        }

        if (!passengerSeats.isEmpty()) {
            int[] locs = new int[passengerSeats.size() * 3];

            for (int i = 0; (i / 3) < passengerSeats.size(); i += 3) {
                HashedBlockPosition vec = passengerSeats.get(i / 3);
                locs[i] = vec.x;
                locs[i + 1] = vec.y;
                locs[i + 2] = vec.z;

            }
            stats.setIntArray("passengerSeats", locs);
        }

        nbt.setTag(TAGNAME, stats);
    }

    public void readFromNBT(NBTTagCompound nbt) {
this.reset();
        if (nbt.hasKey(TAGNAME)) {
            NBTTagCompound stats = nbt.getCompoundTag(TAGNAME);
            this.thrust = stats.getLong("thrust");
            this.mass = stats.getFloat("mass");
            this.fuelFluid = stats.getString("fuelFluid");
            this.oxidizerFluid = stats.getString("oxidizerFluid");
            this.workingFluid = stats.getString("workingFluid");
            this.drillingPower = stats.getFloat("drillingPower");

            this.fuelMonopropellant = stats.getInteger("fuelMonopropellant");
            this.fuelBipropellant = stats.getInteger("fuelBipropellant");
            this.fuelOxidizer = stats.getInteger("fuelOxidizer");
            this.fuelImpulse = stats.getInteger("fuelImpulse");
            this.fuelIon = stats.getInteger("fuelIon");
            this.fuelNuclearWorkingFluid = stats.getInteger("fuelNuclearWorkingFluid");
            this.fuelWarp = stats.getInteger("fuelWarp");

            this.fuelCapacityMonopropellant = stats.getInteger("fuelCapacityMonopropellant");
            this.fuelCapacityBipropellant = stats.getInteger("fuelCapacityBipropellant");
            this.fuelCapacityOxidizer = stats.getInteger("fuelCapacityOxidizer");
            this.fuelCapacityImpulse = stats.getInteger("fuelCapacityImpulse");
            this.fuelCapacityIon = stats.getInteger("fuelCapacityIon");
            this.fuelCapacityNuclearWorkingFluid = stats.getInteger("fuelCapacityNuclearWorkingFluid");
            this.fuelCapacityWarp = stats.getInteger("fuelCapacityWarp");

            int feed = stats.getInteger("engineFeed");
            this.engineFeed = feed >= 0 && feed < FuelType.values().length ? FuelType.getById(feed) : null;
            this.exhaustVelocityFactor = stats.getFloat("exhaustVelocityFactor");
            this.coreThermalPower = stats.getDouble("coreThermalPower");
            this.coreTemperature = stats.getDouble("coreTemperature");
            this.nuclearJetEfficiency = stats.getFloat("nuclearJetEfficiency");

            if (stats.hasKey("dynStats")) {
                NBTTagCompound dynStats = stats.getCompoundTag("dynStats");


                for (String key : dynStats.getKeySet()) {
                    Object obj = dynStats.getTag(key);

                    if (obj instanceof NBTTagFloat)
                        setStatTag(key, dynStats.getFloat(key));
                    else if (obj instanceof NBTTagInt)
                        setStatTag(key, dynStats.getInteger(key));
                }
            }

            pilotSeatPos.x = stats.getInteger("playerXPos");
            pilotSeatPos.y = (short) stats.getInteger("playerYPos");
            pilotSeatPos.z = stats.getInteger("playerZPos");

            if (stats.hasKey("engineLoc")) {
                int[] locations = stats.getIntArray("engineLoc");

                for (int i = 0; i < locations.length; i += 3) {

                    this.addEngineLocation((float)locations[i]/2, (float)locations[i + 1]/2, (float)locations[i + 2]/2);
                }
            }

            if (stats.hasKey("passengerSeats")) {
                int[] locations = stats.getIntArray("passengerSeats");

                for (int i = 0; i < locations.length; i += 3) {

                    this.addPassengerSeat(locations[i], locations[i + 1], locations[i + 2]);
                }
            }
        }
    }
}
