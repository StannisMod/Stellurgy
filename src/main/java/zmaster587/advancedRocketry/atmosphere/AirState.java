package zmaster587.advancedRocketry.atmosphere;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.IAtmosphere;
import zmaster587.advancedRocketry.atmosphere.gas.Gas;
import zmaster587.advancedRocketry.atmosphere.gas.GasRegistry;
import zmaster587.advancedRocketry.atmosphere.gas.GasRole;

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
 * Units are <b>micro-atmospheres</b> (1_000_000 = 1 atm). That is fine enough for the trace
 * gases the life-support loop cares about — dangerous CO2 sits around 1% of an atmosphere — and
 * it converts to the pressure figure the rest of the mod already speaks: the atmosphere analyser
 * prints {@code pressure / 100f + " atm"}, so pressure is hundredths of an atmosphere and
 * {@link #getPressureCentiAtm()} divides by 10_000. {@link #earthLike()} totals exactly one
 * atmosphere, which is the constant the zone pressure used to be hard-coded to.
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
    public static final int ONE_ATM = 1_000_000;

    /** Below this total pressure the zone is not air at all, whatever its composition. */
    private static final int VACUUM_CEILING = ONE_ATM / 100;

    /**
     * What is in the air, by substance. Sparse on purpose: a vacuum holds an empty map, and most
     * rooms hold three entries, so nothing pays for the substances it does not contain.
     */
    private final Map<Gas, Integer> composition = new HashMap<>();
    /**
     * Kelvin. Held as thousandths so that a mix of two zones does not lose a degree to integer
     * truncation every time it happens — a room re-breathed a hundred times a minute would otherwise
     * cool by arithmetic alone.
     */
    private int temperatureMilliK;

    public AirState(int nitrogen, int oxygen, int carbonDioxide) {
        this(nitrogen, oxygen, carbonDioxide, ambientKelvin() * 1000);
    }

    public AirState(int nitrogen, int oxygen, int carbonDioxide, int temperatureMilliK) {
        set(GasRegistry.NITROGEN, nitrogen);
        set(GasRegistry.OXYGEN, oxygen);
        set(GasRegistry.CARBON_DIOXIDE, carbonDioxide);
        this.temperatureMilliK = Math.max(0, temperatureMilliK);
    }

    /** Write one substance's partial pressure, dropping the entry when it reaches nothing. */
    private void set(Gas gas, int amount) {
        if (gas == null) {
            // A substance this game does not know is DROPPED, not stored under a null key: a map that
            // accepted one would keep counting it toward the pressure while nothing could ever name,
            // draw or measure it again.
            return;
        }
        int clamped = Math.max(0, amount);
        if (clamped == 0) {
            composition.remove(gas);
        } else {
            composition.put(gas, clamped);
        }
    }

    /** How much of this substance is here. Zero for anything the air does not contain. */
    public int partialPressure(Gas gas) {
        Integer held = gas == null ? null : composition.get(gas);
        return held == null ? 0 : held;
    }

    /** Put this substance in, at its own temperature, mixing by the calorimeter rule. */
    public void add(Gas gas, int amount, double incomingKelvin) {
        if (gas == null || amount <= 0) {
            return;
        }
        mixIn(amount, incomingKelvin);
        set(gas, partialPressure(gas) + amount);
    }

    /** Take this substance out, and answer how much was actually there to take. */
    public int draw(Gas gas, int amount) {
        int taken = Math.min(Math.max(0, amount), partialPressure(gas));
        if (taken > 0) {
            set(gas, partialPressure(gas) - taken);
        }
        return taken;
    }

    /**
     * Everything present, by substance. The resource map and the state are the same object, so this
     * is also the answer to "what could be extracted here".
     */
    public Map<Gas, Integer> composition() {
        return java.util.Collections.unmodifiableMap(composition);
    }

    /** Total partial pressure of every substance in this role - how a predicate asks its question. */
    public int roleTotal(GasRole role) {
        int total = 0;
        for (Map.Entry<Gas, Integer> entry : composition.entrySet()) {
            if (entry.getKey().is(role)) {
                total += entry.getValue();
            }
        }
        return total;
    }

    /** What air sits at when nothing has happened to it — the cabin the rest of the mod reads. */
    public static int ambientKelvin() {
        return Math.max(1, ARConfiguration.getCurrentConfig().shipHeatAmbientKelvin);
    }

    /**
     * Breathable sea-level air. Totals exactly {@link #ONE_ATM}, so a zone that has never been
     * touched by life support reports the same pressure it reported before zones had contents.
     */
    public static AirState earthLike() {
        return new AirState(790_000, 210_000, 0);
    }

    public static AirState vacuum() {
        return new AirState(0, 0, 0);
    }

    public int getNitrogen() {
        return partialPressure(GasRegistry.NITROGEN);
    }

    public int getOxygen() {
        return partialPressure(GasRegistry.OXYGEN);
    }

    public int getCarbonDioxide() {
        return partialPressure(GasRegistry.CARBON_DIOXIDE);
    }

    public int getTotalPressure() {
        int total = 0;
        for (int amount : composition.values()) {
            total += amount;
        }
        return total;
    }

    /** The pressure figure the HUD, the analyser and {@code PacketAtmSync} speak: 100 = 1 atm. */
    public int getPressureCentiAtm() {
        return getTotalPressure() / (ONE_ATM / 100);
    }

    /**
     * Move oxygen into carbon dioxide, as breathing does. Both gases move by the same amount, so
     * the total pressure is unchanged — respiration rearranges air, it does not consume it.
     *
     * @param amount partial pressure to convert; clamped to the oxygen actually present
     * @return the amount actually converted, which is less than requested once the zone runs out
     */
    public int respire(int amount) {
        int converted = draw(GasRegistry.OXYGEN, amount);
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
    public int regenerate(int amount) {
        int converted = draw(GasRegistry.CARBON_DIOXIDE, amount);
        set(GasRegistry.OXYGEN, getOxygen() + converted);
        return converted;
    }

    /**
     * Take nitrogen out of the air, as a separator does when it pulls the diluent into a tank.
     *
     * @return the amount actually removed, clamped to what is present
     */
    public int drawNitrogen(int amount) {
        int taken = draw(GasRegistry.NITROGEN, amount);
        return taken;
    }

    /** Take carbon dioxide out of the air — the separator's main job, feeding regeneration. */
    public int drawCarbonDioxide(int amount) {
        int taken = draw(GasRegistry.CARBON_DIOXIDE, amount);
        return taken;
    }

    /** Take oxygen out of the air, e.g. to fill a tank with the pure gas. */
    public int drawOxygen(int amount) {
        int taken = draw(GasRegistry.OXYGEN, amount);
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
        if (getTotalPressure() <= 0)
            return ambientKelvin();
        return temperatureMilliK / 1000.0D;
    }

    /** The same reading in thousandths, which is what a probe and the NBT deal in. */
    public int getTemperatureMilliK() {
        return getTotalPressure() <= 0 ? ambientKelvin() * 1000 : temperatureMilliK;
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
        long perBlockAtOneAtm = Math.max(0, ARConfiguration.getCurrentConfig().lifeSupportAirHeatCapacity);
        if (perBlockAtOneAtm <= 0)
            return 0L;
        return (long) getTotalPressure() * Math.max(0, volumeBlocks) * perBlockAtOneAtm / ONE_ATM;
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

    public void addNitrogen(int amount, double incomingKelvin) {
        add(GasRegistry.NITROGEN, Math.max(0, amount), incomingKelvin);
    }

    public void addOxygen(int amount, double incomingKelvin) {
        add(GasRegistry.OXYGEN, Math.max(0, amount), incomingKelvin);
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
    private void mixIn(int amountArriving, double incomingKelvin) {
        if (amountArriving <= 0)
            return;
        int here = getTotalPressure();
        if (here <= 0) {
            temperatureMilliK = (int) Math.max(0, Math.round(incomingKelvin * 1000.0D));
            return;
        }
        double mixed = (here * getTemperatureKelvin() + (double) amountArriving * incomingKelvin)
                / (here + amountArriving);
        temperatureMilliK = (int) Math.max(0, Math.round(mixed * 1000.0D));
    }

    /**
     * How much oxygen this zone still has room for before it crosses the toxicity threshold.
     * <p>
     * This is the governor's whole job in one number: a combiner may push oxygen in only up to
     * here, so a mis-set pipe cannot enrich a cabin into a fire hazard. Returns 0 when the zone is
     * already at or above the ceiling, and treats an unconfigured band as no ceiling.
     */
    public int oxygenHeadroom() {
        ARConfiguration config = ARConfiguration.getCurrentConfig();
        if (config.lifeSupportMaxPartialO2 <= config.lifeSupportMinPartialO2)
            return Integer.MAX_VALUE;
        return Math.max(0, config.lifeSupportMaxPartialO2 - getOxygen());
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
        int needed = ARConfiguration.getCurrentConfig().lifeSupportCombustionMinPartialO2;
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
        ARConfiguration config = ARConfiguration.getCurrentConfig();
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
        for (Map.Entry<Gas, Integer> entry : composition.entrySet()) {
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
        for (Map.Entry<Gas, Integer> entry : composition.entrySet()) {
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
     * {@link AtmosphereType} singletons stay the interface, so nothing outside life support has
     * to learn about partial pressures.
     * <p>
     * <b>Every branch below now asks a PREDICATE rather than a field.</b> The singletons that come out
     * still carry flags of their own, and those flags are still assigned by hand — so a caller that
     * wants to know whether something can burn must ask {@link #allowsCombustion()} here rather than
     * the type it gets back. That gap closes when the types go; until then this is the honest seam.
     */
    public IAtmosphere deriveAtmosphere() {
        if (getTotalPressure() <= VACUUM_CEILING)
            return AtmosphereType.VACUUM;

        ARConfiguration config = ARConfiguration.getCurrentConfig();

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
                return breathableGas ? AtmosphereType.SUPERHEATED : AtmosphereType.SUPERHEATEDNOO2;
            if (config.shipHeatCrewVeryHotKelvin > 0 && kelvin >= config.shipHeatCrewVeryHotKelvin)
                return breathableGas ? AtmosphereType.VERYHOT : AtmosphereType.VERYHOTNOO2;
        }

        // An un-loaded config leaves both bounds at zero, which would otherwise read as "every
        // zone is oxygen-toxic". No usable band means no governor, not a hazard.
        if (config.lifeSupportMaxPartialO2 <= config.lifeSupportMinPartialO2)
            return AtmosphereType.PRESSURIZEDAIR;

        int oxidiser = roleTotal(GasRole.OXIDISER);
        if (oxidiser < config.lifeSupportMinPartialO2)
            return oxidiser <= 0 ? AtmosphereType.NOO2 : AtmosphereType.LOWOXYGEN;
        if (oxidiser > config.lifeSupportMaxPartialO2)
            return AtmosphereType.HIGHOXYGEN;

        return AtmosphereType.PRESSURIZEDAIR;
    }

    /**
     * The composition by SUBSTANCE NAME, so a zone holding something the three old keys could not
     * name survives a reload.
     */
    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound gases = new NBTTagCompound();
        for (Map.Entry<Gas, Integer> entry : composition.entrySet()) {
            gases.setInteger(entry.getKey().name(), entry.getValue());
        }
        nbt.setTag("gases", gases);
        nbt.setInteger("airK", temperatureMilliK);
    }

    public static AirState readFromNBT(NBTTagCompound nbt) {
        // A zone written before air had a temperature reads back 0, which is not a temperature any
        // room was ever at. Absent means ambient, not absolute zero.
        int written = nbt.getInteger("airK");
        AirState state = new AirState(0, 0, 0, written > 0 ? written : ambientKelvin() * 1000);
        if (nbt.hasKey("gases")) {
            NBTTagCompound gases = nbt.getCompoundTag("gases");
            for (String name : gases.getKeySet()) {
                // A gas the running game no longer knows is DROPPED rather than guessed at: a pack
                // that removed a substance did not mean "and replace it with something else".
                state.set(GasRegistry.byName(name), gases.getInteger(name));
            }
        } else {
            // A zone written before the composition was keyed by substance.
            state.set(GasRegistry.NITROGEN, nbt.getInteger("n2"));
            state.set(GasRegistry.OXYGEN, nbt.getInteger("o2"));
            state.set(GasRegistry.CARBON_DIOXIDE, nbt.getInteger("co2"));
        }
        return state;
    }

    @Override
    public String toString() {
        return "AirState" + composition + "[K=" + getTemperatureKelvin() + "]";
    }
}
