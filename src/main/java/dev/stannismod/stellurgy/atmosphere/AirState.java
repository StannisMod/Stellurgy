package dev.stannismod.stellurgy.atmosphere;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.atmosphere.Atmosphere;
import dev.stannismod.stellurgy.atmosphere.gas.Gas;
import dev.stannismod.stellurgy.atmosphere.gas.GasRegistry;
import dev.stannismod.stellurgy.atmosphere.gas.GasRole;

/**
 * The gas contents of one sealed zone: a partial pressure per SUBSTANCE, and a temperature.
 * <p>
 * <b>The composition is the model, and everything else about air is derived from it.</b> Whether a
 * room can be breathed, whether a fire can start in it, whether it is poisoning the crew or eating the
 * hull — all of those are predicates over these numbers, never flags stored beside them. A second
 * place that answered any of those questions would be a second source of truth, and the two would
 * disagree the moment somebody tuned one of them.
 * <p>
 * <b>What is present can be extracted.</b> The composition is keyed by {@link Gas}, and a gas carries
 * the fluid it becomes when it is taken out — so the map of what is here and the map of what can be
 * collected here are one object rather than two lists that drift apart.
 * <p>
 * <b>Units are nano-atmospheres in a {@code long}</b> (1_000_000_000 = 1 atm), and both ends of that
 * range are deliberate. A ceiling of some nine billion atmospheres holds a gas giant's depths; a
 * resolution of one part in a billion holds a trace that a coarser unit would round away — Mars's
 * 0.13% oxygen at 6 mbar is 7.8e-6 atm, which is eight whole units in millionths and four significant
 * digits here.
 * <p>
 * <b>What falls below one unit is ZERO, everywhere and consistently.</b> The icy moons' radiolytic
 * oxygen sits around 1e-11 atm and this model says there is none of it: an exosphere that thin is not
 * breathable, not harvestable and not a hazard, so "none" is the honest answer rather than a number
 * kept in one code path and dropped in another. The floor is a statement about the model, not a bug
 * in it.
 * <p>
 * The unit a HUMAN writes is coarser and stays that way: a config threshold and a gas's hazard limit
 * are authored in parts per million of an atmosphere, which is what an exposure limit is quoted in,
 * and {@link #PER_PPM} is the one bridge between the two. Pressure as the analyser and the HUD speak
 * it is hundredths of an atmosphere, which {@link #getPressureCentiAtm()} still answers.
 * {@link #earthLike()} totals exactly one atmosphere, which is the constant the zone pressure used to
 * be hard-coded to.
 * <p>
 * Nitrogen is inert: nothing produces or consumes it. It exists so that the oxygen fraction is a
 * quantity a governor can act on rather than a synonym for "how much gas is in the room".
 * <p>
 * <b>Air is also a heat reservoir.</b> It carries a temperature, and gas arriving from anywhere else
 * mixes into it by the calorimeter rule rather than replacing it. That is what makes a compartment
 * something a machine can warm and a chiller can draw on, and it is why the temperature lives HERE
 * rather than beside the gas: the air is the body that has it.
 */
public class AirState {

    /** One atmosphere, in the internal unit. */
    public static final long ONE_ATM = 1_000_000_000L;

    /**
     * One part per million of an atmosphere, in the internal unit.
     * <p>
     * The bridge between what a person writes and what the model stores. Exposure limits, config
     * thresholds and a planet's composition are all quoted in ppm because that is the unit the real
     * numbers come in; the state is finer so that a trace still has digits left. Every authored
     * number crosses here exactly once, at the boundary that reads it.
     */
    public static final long PER_PPM = ONE_ATM / 1_000_000L;

    /** Below this total pressure the zone is not air at all, whatever its composition. */
    private static final long VACUUM_CEILING = ONE_ATM / 100;

    /**
     * What is in the air, by substance. Sparse on purpose: a vacuum holds an empty map, and most
     * rooms hold three entries, so nothing pays for the substances it does not contain.
     */
    private final Map<Gas, Long> composition = new HashMap<>();
    /**
     * Kelvin. Held as thousandths so that a mix of two zones does not lose a degree to integer
     * truncation every time it happens — a room re-breathed a hundred times a minute would otherwise
     * cool by arithmetic alone.
     */
    private int temperatureMilliK;

    public AirState(long nitrogen, long oxygen, long carbonDioxide) {
        this(nitrogen, oxygen, carbonDioxide, ambientKelvin() * 1000);
    }

    public AirState(long nitrogen, long oxygen, long carbonDioxide, int temperatureMilliK) {
        set(GasRegistry.NITROGEN, nitrogen);
        set(GasRegistry.OXYGEN, oxygen);
        set(GasRegistry.CARBON_DIOXIDE, carbonDioxide);
        this.temperatureMilliK = Math.max(0, temperatureMilliK);
    }

    /** Write one substance's partial pressure, dropping the entry when it reaches nothing. */
    private void set(Gas gas, long amount) {
        if (gas == null) {
            // A substance this game does not know is DROPPED, not stored under a null key: a map that
            // accepted one would keep counting it toward the pressure while nothing could ever name,
            // draw or measure it again.
            return;
        }
        long clamped = Math.max(0L, amount);
        if (clamped == 0L) {
            composition.remove(gas);
        } else {
            composition.put(gas, clamped);
        }
    }

    /** How much of this substance is here. Zero for anything the air does not contain. */
    public long partialPressure(Gas gas) {
        Long held = gas == null ? null : composition.get(gas);
        return held == null ? 0L : held;
    }

    /** Put this substance in, at its own temperature, mixing by the calorimeter rule. */
    public void add(Gas gas, long amount, double incomingKelvin) {
        if (gas == null || amount <= 0L) {
            return;
        }
        mixIn(amount, incomingKelvin);
        set(gas, partialPressure(gas) + amount);
    }

    /** Take this substance out, and answer how much was actually there to take. */
    public long draw(Gas gas, long amount) {
        long taken = Math.min(Math.max(0L, amount), partialPressure(gas));
        if (taken > 0L) {
            set(gas, partialPressure(gas) - taken);
        }
        return taken;
    }

    /**
     * Everything present, by substance. The resource map and the state are the same object, so this
     * is also the answer to "what could be extracted here".
     */
    public Map<Gas, Long> composition() {
        return java.util.Collections.unmodifiableMap(composition);
    }

    /** Total partial pressure of every substance in this role - how a predicate asks its question. */
    public long roleTotal(GasRole role) {
        long total = 0L;
        for (Map.Entry<Gas, Long> entry : composition.entrySet()) {
            if (entry.getKey().is(role)) {
                total += entry.getValue();
            }
        }
        return total;
    }

    /** What air sits at when nothing has happened to it — the cabin the rest of the mod reads. */
    public static int ambientKelvin() {
        return Math.max(1, StellurgyConfiguration.getCurrentConfig().shipHeatAmbientKelvin);
    }

    /**
     * Breathable sea-level air. Totals exactly {@link #ONE_ATM}, so a zone that has never been
     * touched by life support reports the same pressure it reported before zones had contents.
     */
    public static AirState earthLike() {
        return new AirState(790_000 * PER_PPM, 210_000 * PER_PPM, 0L);
    }

    public static AirState vacuum() {
        return new AirState(0L, 0L, 0L);
    }

    public long getNitrogen() {
        return partialPressure(GasRegistry.NITROGEN);
    }

    public long getOxygen() {
        return partialPressure(GasRegistry.OXYGEN);
    }

    public long getCarbonDioxide() {
        return partialPressure(GasRegistry.CARBON_DIOXIDE);
    }

    public long getTotalPressure() {
        long total = 0L;
        for (long amount : composition.values()) {
            total += amount;
        }
        return total;
    }

    /** The pressure figure the HUD, the analyser and {@code PacketAtmSync} speak: 100 = 1 atm. */
    public int getPressureCentiAtm() {
        return (int) Math.min(Integer.MAX_VALUE, getTotalPressure() / (ONE_ATM / 100L));
    }

    /**
     * Move oxygen into carbon dioxide, as breathing does. Both gases move by the same amount, so
     * the total pressure is unchanged — respiration rearranges air, it does not consume it.
     *
     * @param amount partial pressure to convert; clamped to the oxygen actually present
     * @return the amount actually converted, which is less than requested once the zone runs out
     */
    public long respire(long amount) {
        long converted = draw(GasRegistry.OXYGEN, amount);
        set(GasRegistry.CARBON_DIOXIDE, getCarbonDioxide() + converted);
        return converted;
    }

    /**
     * The reverse of {@link #respire}: carbon dioxide becomes oxygen again, the carbon leaving the
     * air as a solid. One molecule of CO2 yields one of O2, so total pressure is unchanged here
     * too — the carbon that departs was never contributing pressure on its own.
     *
     * @param amount partial pressure to regenerate; clamped to the CO2 actually present
     * @return the amount actually converted, which is the carbon the caller must now deal with
     */
    public long regenerate(long amount) {
        long converted = draw(GasRegistry.CARBON_DIOXIDE, amount);
        set(GasRegistry.OXYGEN, getOxygen() + converted);
        return converted;
    }

    /**
     * Take nitrogen out of the air, as a separator does when it pulls the diluent into a tank.
     *
     * @return the amount actually removed, clamped to what is present
     */
    public long drawNitrogen(long amount) {
        long taken = draw(GasRegistry.NITROGEN, amount);
        return taken;
    }

    /** Take carbon dioxide out of the air — the separator's main job, feeding regeneration. */
    public long drawCarbonDioxide(long amount) {
        long taken = draw(GasRegistry.CARBON_DIOXIDE, amount);
        return taken;
    }

    /** Take oxygen out of the air, e.g. to fill a tank with the pure gas. */
    public long drawOxygen(long amount) {
        long taken = draw(GasRegistry.OXYGEN, amount);
        return taken;
    }

    /**
     * The temperature of this zone's air, in kelvin.
     * <p>
     * A zone holding no gas has none of its own and reports the ambient the rest of the system reads:
     * there is no body there to be hot, and a vacuum that remembered what it held before it was opened
     * would hand the next thing that looked at it a number about a room that no longer exists.
     */
    public double getTemperatureKelvin() {
        if (getTotalPressure() <= 0L)
            return ambientKelvin();
        return temperatureMilliK / 1000.0D;
    }

    /** The same reading in thousandths, which is what a probe and the NBT deal in. */
    public int getTemperatureMilliK() {
        return getTotalPressure() <= 0L ? ambientKelvin() * 1000 : temperatureMilliK;
    }

    /**
     * How much heat this zone's air absorbs per kelvin, given how many blocks it fills.
     * <p>
     * Proportional to pressure AND volume, because those two are what say how much gas is actually
     * there. A half-pressurised room therefore holds half the heat and swings twice as fast for the
     * same energy, which is the physics rather than a rule anyone had to add. The unit is the heat
     * unit per kelvin — the same currency a coolant loop's capacity is quoted in, because there is
     * only one.
     *
     * @param volumeBlocks the zone's size, as the flood-fill measured it
     */
    public long getHeatCapacity(int volumeBlocks) {
        long perBlockAtOneAtm = Math.max(0, StellurgyConfiguration.getCurrentConfig().lifeSupportAirHeatCapacity);
        if (perBlockAtOneAtm <= 0)
            return 0L;
        // Reduced to ppm BEFORE the multiply, which does two things at once: it keeps the product
        // inside a long for a gas giant's pressure across a station's volume, and it leaves this
        // arithmetic identical to what it was when ppm was the whole model. Air a thousand times
        // thinner than one ppm holds no measurable heat, and reading its capacity as zero is the same
        // floor the composition itself has.
        return getTotalPressure() / PER_PPM * Math.max(0, volumeBlocks) * perBlockAtOneAtm / 1_000_000L;
    }

    /**
     * Nitrogen arriving at a stated temperature, mixed in by the calorimeter rule.
     * <p>
     * The temperature is an ARGUMENT and there is deliberately no overload without it. Gas coming out
     * of a tank or down a duct from somewhere else is at its own temperature, and a signature that let
     * a caller omit it would mix silently at the room's own reading — which is the same class of
     * defect as a machine being allowed to declare how much heat it removes. A caller that really is
     * moving the room's own air says so by passing {@link #getTemperatureKelvin()}.
     */
    /**
     * Take heat OUT of this air, and answer how much was actually taken.
     * <p>
     * The amount asked for is the caller's business — a chiller's throughput, say — and what comes
     * back is what the air could actually give up, which is what the caller may then move. Reporting
     * the difference rather than swallowing it is the whole of conservation on this seam: heat that
     * was not taken from here must not turn up somewhere else.
     * <p>
     * Air with no gas in it, or a zone in a world where air carries no heat at all, gives up nothing:
     * there is no body there to cool. Nothing stops the air being driven BELOW the cabin temperature —
     * that is what an air conditioner does — and nothing needs to, because the price is Carnot: the
     * colder the air gets relative to where the heat is going, the more work each unit costs.
     *
     * @param volumeBlocks the zone's size, as the flood-fill measured it
     */
    public long removeHeat(long amount, int volumeBlocks) {
        long capacity = getHeatCapacity(volumeBlocks);
        if (amount <= 0L || capacity <= 0L)
            return 0L;
        // Absolute zero is the floor, and it is a floor on the ENERGY that can be removed rather than
        // a rule about temperature: taking more than the air has leaves it colder than anything is.
        long available = (long) ((double) temperatureMilliK / 1000.0D * capacity);
        long taken = Math.min(amount, Math.max(0L, available));
        if (taken <= 0L)
            return 0L;
        double dropped = getTemperatureKelvin() - (double) taken / capacity;
        temperatureMilliK = (int) Math.max(0L, Math.round(dropped * 1000.0D));
        return taken;
    }

    /**
     * How much energy this air still has to give up, in the same units {@link #removeHeat} takes.
     * <p>
     * A machine that wants to cool a room asks this FIRST and pays for what it can actually move.
     * Paying for THROUGHPUT instead is how a chiller ends up charging its loop full price for air
     * that has nothing left to give — a pure heater with a cold room attached.
     */
    public long availableHeat(int volumeBlocks) {
        long capacity = getHeatCapacity(volumeBlocks);
        if (capacity <= 0L)
            return 0L;
        return Math.max(0L, (long) ((double) temperatureMilliK / 1000.0D * capacity));
    }

    /**
     * Put energy INTO this air. The mirror of {@link #removeHeat}, and it had been missing.
     * <p>
     * <b>Without it a compartment could only ever get colder.</b> Every reader of this class asked
     * the air to give heat up — a chiller breathing the room, gas arriving cooler — and nothing could
     * put any back, so the temperature the crew hazards are keyed on could never rise and the two
     * rungs that read it were unreachable knobs describing a mechanic that did not run.
     * <p>
     * Air with no gas in it takes nothing: a vacuum has no body to warm, which is why a breached
     * compartment does not heat up no matter what is glowing inside it.
     *
     * @param volumeBlocks the zone's size, as the flood-fill measured it
     * @return the energy actually accepted
     */
    public long addHeat(long amount, int volumeBlocks) {
        long capacity = getHeatCapacity(volumeBlocks);
        if (amount <= 0L || capacity <= 0L)
            return 0L;
        double raised = getTemperatureKelvin() + (double) amount / capacity;
        // The same ceiling `int` milli-kelvin can hold; past it the air is a plasma and this model
        // has stopped describing anything, so it saturates rather than wrapping negative.
        long milli = Math.min((long) Integer.MAX_VALUE, Math.round(raised * 1000.0D));
        temperatureMilliK = (int) Math.max(0L, milli);
        return amount;
    }

    public void addNitrogen(long amount, double incomingKelvin) {
        add(GasRegistry.NITROGEN, Math.max(0L, amount), incomingKelvin);
    }

    public void addOxygen(long amount, double incomingKelvin) {
        add(GasRegistry.OXYGEN, Math.max(0L, amount), incomingKelvin);
    }

    /**
     * `T = (C_here·T_here + C_in·T_in) / (C_here + C_in)` — two bodies in contact end up at one
     * temperature, weighted by how much of each there is.
     * <p>
     * Weighted by PRESSURE alone rather than by the full capacity, which is the same answer: the
     * volume and the per-block constant are common to both sides of the fraction and cancel. Gas
     * arriving into a vacuum simply brings its own temperature, which is the degenerate case of the
     * same formula rather than a branch anyone had to think about.
     */
    private void mixIn(long amountArriving, double incomingKelvin) {
        if (amountArriving <= 0L)
            return;
        long here = getTotalPressure();
        if (here <= 0L) {
            temperatureMilliK = (int) Math.max(0, Math.round(incomingKelvin * 1000.0D));
            return;
        }
        double mixed = ((double) here * getTemperatureKelvin() + (double) amountArriving * incomingKelvin)
                / ((double) here + (double) amountArriving);
        temperatureMilliK = (int) Math.max(0, Math.round(mixed * 1000.0D));
    }

    /**
     * How much oxygen this zone still has room for before it crosses the toxicity threshold.
     * <p>
     * This is the governor's whole job in one number: a combiner may push oxygen in only up to
     * here, so a mis-set pipe cannot enrich a cabin into a fire hazard. Returns 0 when the zone is
     * already at or above the ceiling, and treats an unconfigured band as no ceiling.
     */
    public long oxygenHeadroom() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        if (config.lifeSupportMaxPartialO2 <= config.lifeSupportMinPartialO2)
            return Long.MAX_VALUE;
        return Math.max(0L, config.lifeSupportMaxPartialO2 - getOxygen());
    }

    /**
     * Whether there is enough oxidiser here for anything to burn.
     * <p>
     * <b>This is decided by the OXIDISER and never by breathability</b>, which are two different
     * questions about the same gas: a room too thin to breathe can still light a torch, and a room
     * thin enough to smother a flame is long past being survivable. They were one boolean for years,
     * assigned from the BREATHING band, which is why air nobody could breathe still burned.
     * <p>
     * It asks for a ROLE rather than for oxygen, so an atmosphere whose oxidiser is not oxygen needs
     * no code written for it - only a registry row.
     */
    public boolean allowsCombustion() {
        if (getTotalPressure() <= VACUUM_CEILING) {
            return false;
        }
        long needed = StellurgyConfiguration.getCurrentConfig().lifeSupportCombustionMinPartialO2;
        // A threshold of zero is no rung at all, the same reading every other threshold in this
        // system gets: an unloaded config may not turn every vacuum into a firebox.
        return needed > 0 && roleTotal(GasRole.OXIDISER) >= needed;
    }

    /**
     * Whether a person could breathe this without help - the band, the pressure, and nothing about
     * fire.
     */
    public boolean isBreathableAir() {
        if (getTotalPressure() <= VACUUM_CEILING) {
            return false;
        }
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        // No usable band means no governor, never a hazard - the same reading the derive below uses.
        return config.lifeSupportMaxPartialO2 <= config.lifeSupportMinPartialO2
                || roleTotal(GasRole.OXIDISER) >= config.lifeSupportMinPartialO2;
    }

    /**
     * The poison that is furthest past its own limit, or null when nothing here is.
     * <p>
     * Each substance is judged against ITS OWN threshold, so nothing has to know which gas it is
     * looking at, and a lungful of good air is no defence against something toxic mixed into it.
     * Nothing consumes this yet - the hazard table that will is a later slice - but the predicate
     * belongs with the state that answers it.
     */
    public Gas worstToxin() {
        Gas worst = null;
        double worstExcess = 1.0D;
        for (Map.Entry<Gas, Long> entry : composition.entrySet()) {
            Gas gas = entry.getKey();
            if (!gas.is(GasRole.TOXIC) || gas.hazardThreshold() <= 0) {
                continue;
            }
            double excess = (double) entry.getValue() / gas.hazardThreshold();
            if (excess >= 1.0D && excess > worstExcess - 1e-9D) {
                worst = gas;
                worstExcess = excess;
            }
        }
        return worst;
    }

    /** Whether anything here is past its own poisoning limit. */
    public boolean isToxic() {
        return worstToxin() != null;
    }

    /**
     * How hard this air is working on metal, as a multiple of the substances' own limits.
     * <p>
     * Corrosion needs three things and this reads all of them: something corrosive, something to
     * dissolve it into, and heat to run the reaction. A dry cold trace of sulphur dioxide is a smell;
     * the same gas wet and warm is an acid. Zero when nothing here attacks anything.
     */
    public double corrosionIndex() {
        double attack = 0.0D;
        for (Map.Entry<Gas, Long> entry : composition.entrySet()) {
            Gas gas = entry.getKey();
            if (gas.is(GasRole.CORROSIVE) && gas.hazardThreshold() > 0) {
                attack += (double) entry.getValue() / gas.hazardThreshold();
            }
        }
        if (attack <= 0.0D) {
            return 0.0D;
        }
        // Water is the solvent that turns the gas into an acid, and warmth is the rate. Both are
        // multipliers on an attack that exists without them rather than gates that switch it off:
        // dry hot sulphur still etches, just slowly.
        double wet = 1.0D + (double) roleTotal(GasRole.SOLVENT) / ONE_ATM;
        double warm = Math.max(0.5D, getTemperatureKelvin() / Math.max(1, ambientKelvin()));
        return attack * wet * warm;
    }

    /**
     * Which registered atmosphere this zone presents to everything downstream — tick damage, the
     * suit immunity check, the sync packet, the detector. The gas state is the model; the
     * {@link Atmosphere} singletons stay the interface, so nothing outside life support has
     * to learn about partial pressures.
     * <p>
     * <b>Every branch below now asks a PREDICATE rather than a field.</b> The singletons that come out
     * still carry flags of their own, and those flags are still assigned by hand — so a caller that
     * wants to know whether something can burn must ask {@link #allowsCombustion()} here rather than
     * the type it gets back. That gap closes when the types go; until then this is the honest seam.
     */
    public Atmosphere deriveAtmosphere() {
        if (getTotalPressure() <= VACUUM_CEILING)
            return Atmosphere.VACUUM;

        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();

        // Heat comes FIRST, and above every question about oxygen. A room that is cooking the people
        // in it is not made safe by having the right gas mix, and the existing hostile types already
        // say both things at once: the NoO2 variants are hot AND unbreathable, which is why the
        // temperature picks the rung and the oxygen picks the variant rather than the two competing.
        // The same suit that protects against a scorching planet protects here - there is no second
        // damage path, by contract.
        // An unusable oxygen band means "no governor", exactly as it does on the cold path below -
        // never "and also you cannot breathe": a missing config may not invent a second hazard on
        // top of the heat.
        boolean breathableGas = isBreathableAir();
        double kelvin = getTemperatureKelvin();
        // A threshold of zero is no rung at all, never a rung every room trips: the numbers are
        // config, and a config that has not been loaded reads as zeros. The heat flag disables the
        // whole ladder for the same reason it disables the loops - with it off nothing warms a room,
        // and a hazard nothing can cause must not be reachable by leftover state either.
        if (config.shipHeat) {
            if (config.shipHeatCrewSuperheatedKelvin > 0
                    && kelvin >= config.shipHeatCrewSuperheatedKelvin)
                return breathableGas ? Atmosphere.SUPERHEATED : Atmosphere.SUPERHEATEDNOO2;
            if (config.shipHeatCrewVeryHotKelvin > 0 && kelvin >= config.shipHeatCrewVeryHotKelvin)
                return breathableGas ? Atmosphere.VERYHOT : Atmosphere.VERYHOTNOO2;
        }

        // An un-loaded config leaves both bounds at zero, which would otherwise read as "every
        // zone is oxygen-toxic". No usable band means no governor, not a hazard.
        if (config.lifeSupportMaxPartialO2 <= config.lifeSupportMinPartialO2)
            return Atmosphere.PRESSURIZEDAIR;

        long oxidiser = roleTotal(GasRole.OXIDISER);
        if (oxidiser < config.lifeSupportMinPartialO2)
            return oxidiser <= 0L ? Atmosphere.NOO2 : Atmosphere.LOWOXYGEN;
        if (oxidiser > config.lifeSupportMaxPartialO2)
            return Atmosphere.HIGHOXYGEN;

        return Atmosphere.PRESSURIZEDAIR;
    }

    /**
     * The composition by SUBSTANCE NAME, so a zone holding something the three old keys could not
     * name survives a reload.
     */
    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound gases = new NBTTagCompound();
        for (Map.Entry<Gas, Long> entry : composition.entrySet()) {
            gases.setLong(entry.getKey().name(), entry.getValue());
        }
        nbt.setTag("gases", gases);
        nbt.setInteger("airK", temperatureMilliK);
    }

    public static AirState readFromNBT(NBTTagCompound nbt) {
        // A zone written before air had a temperature reads back 0, which is not a temperature any
        // room was ever at. Absent means ambient, not absolute zero.
        int written = nbt.getInteger("airK");
        AirState state = new AirState(0L, 0L, 0L, written > 0 ? written : ambientKelvin() * 1000);
        if (nbt.hasKey("gases")) {
            NBTTagCompound gases = nbt.getCompoundTag("gases");
            for (String name : gases.getKeySet()) {
                // A gas the running game no longer knows is DROPPED rather than guessed at: a pack
                // that removed a substance did not mean "and replace it with something else".
                state.set(GasRegistry.byName(name), gases.getLong(name));
            }
        } else {
            // A zone written before the composition was keyed by substance, in the millionths this
            // model used then. Scaled rather than read raw: the same room must come back at the same
            // pressure, not a thousandth of it.
            state.set(GasRegistry.NITROGEN, nbt.getInteger("n2") * PER_PPM);
            state.set(GasRegistry.OXYGEN, nbt.getInteger("o2") * PER_PPM);
            state.set(GasRegistry.CARBON_DIOXIDE, nbt.getInteger("co2") * PER_PPM);
        }
        return state;
    }

    @Override
    public String toString() {
        return "AirState" + composition + "[K=" + getTemperatureKelvin() + "]";
    }
}
