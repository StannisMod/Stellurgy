package dev.stannismod.stellurgy.subsystem.heat;

import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;

/**
 * What a passive sensor sees when it looks at a ship, and the reason it is TWO numbers rather than
 * one.
 * <p>
 * Space is cold and a working ship glows, so heat is the marker nothing can stop emitting. But a
 * detector and a seeker are not asking the same question, and collapsing them into a single
 * "signature" number would throw away the whole build trade:
 * <ul>
 * <li><b>"Is there something out there?"</b> A detector collects flux, which falls off as
 *     {@code P/d^2}. The term is TOTAL RADIATED POWER, and the range it is seen from goes as the
 *     square root of it - doubling what a ship sheds extends that range by only about 1.41x.</li>
 * <li><b>"Can a seeker resolve and track it?"</b> That is RADIANCE, the fourth-power law per unit of
 *     surface, which depends on TEMPERATURE ALONE and not at all on area. A compact array driven hot
 *     by a chiller is a blinding point source; a large cool array sheds the same total power at a
 *     fraction of the radiance and is far harder to lock at range.</li>
 * </ul>
 * <p>
 * So a ship can be loud and hard to hit, or quiet and easy to hit, and the two builds that do it are
 * the compact hot array and the sprawling cool one. That difference is the mechanic; a single number
 * cannot express it.
 * <p>
 * <b>Radiance is quoted as what ONE cell sheds</b>, not in watts per steradian. It is the same
 * reference-point curve everything else in this subsystem is quoted on ({@link
 * HeatNetwork#cellPowerAt}), so a hull, a radiator and a thrown slug are directly comparable and no
 * second constant enters. Carrying the true radiometric factor would change no comparison this
 * mechanic makes.
 * <p>
 * <b>What is summed is GROSS emission, never net rejection.</b> A ship parked in a star may be taking
 * in more than it sheds - its net rejection is negative - while glowing hard enough to be seen from
 * anywhere in the cell. Netting the environment off would make a ship invisible exactly where it is
 * brightest, and would contradict radiance being a function of temperature alone.
 * <p>
 * This class PRODUCES the signature. Who consumes it - a sensor turning power into a detection range,
 * a seeker turning radiance into a lock - belongs to those systems, which is why the sensor supplies
 * its own quality in {@link #detectionRangeBlocks(double)} rather than being told a range by the
 * thermal model.
 * <p>Every static field of this type is effectively final, process lifetime: built once at class initialisation, and holds an immutable value.</p>
 */
public final class ThermalSignature {

    /**
     * The array the reference range is quoted against: the intended steady state of a built-up ship,
     * an 8x8 plate at the temperature its own config states a cell's power at.
     * <p>
     * Naming the subject rather than writing a bare number is deliberate - the same discipline the
     * emergency-dump clause is checked under. Change what the intended steady state is and this
     * changes with it; a raw constant would silently keep quoting a ship nobody builds.
     */
    private static final int REFERENCE_ARRAY_CELLS = 8 * 8;

    /** Nothing is emitting: no power, and no temperature to lock onto. */
    public static final ThermalSignature NONE = new ThermalSignature(0.0D, 0.0D);

    private final double radiatedPower;
    private final double peakKelvin;

    private ThermalSignature(double radiatedPower, double peakKelvin) {
        this.radiatedPower = Math.max(0.0D, radiatedPower);
        this.peakKelvin = Math.max(0.0D, peakKelvin);
    }

    /**
     * What a surface of {@code cells} at {@code kelvin} shows: the power is the whole surface, the
     * radiance is the temperature. This is the only way to make one, so nothing can contribute power
     * without also declaring how hot it is - which is what keeps the two terms from being set
     * independently and drifting apart.
     */
    public static ThermalSignature surface(double cells, double kelvin) {
        if (cells <= 0.0D || kelvin <= 0.0D) {
            return NONE;
        }
        return new ThermalSignature(HeatNetwork.cellPowerAt(kelvin) * cells, kelvin);
    }

    /**
     * Two emitting surfaces seen as one object: the powers add, and the radiance is the HOTTEST of
     * them.
     * <p>
     * The maximum and not an average, because a seeker resolves the brightest thing it can see. A
     * ship whose radiators are shut but whose drive housing is glowing is locked on the housing, and
     * averaging that away against a hull's worth of cold plating would hide it.
     */
    public ThermalSignature plus(ThermalSignature other) {
        if (other == null) {
            return this;
        }
        return new ThermalSignature(radiatedPower + other.radiatedPower,
                Math.max(peakKelvin, other.peakKelvin));
    }

    /** Total power leaving every emitting surface, in heat units per tick. The DETECTION term. */
    public double radiatedPower() {
        return radiatedPower;
    }

    /** The hottest emitting surface, in kelvin - what the radiance below is a function of. */
    public double peakKelvin() {
        return peakKelvin;
    }

    /**
     * The LOCK term: what one cell of the hottest surface sheds, which is the fourth-power law on
     * this subsystem's own curve. Independent of how much surface there is, on purpose.
     */
    public double radiance() {
        return HeatNetwork.cellPowerAt(peakKelvin);
    }

    /**
     * How far away a sensor of this quality finds this object, given the range at which the same
     * sensor finds the reference ship.
     * <p>
     * The SENSOR supplies its quality and the signature supplies the law - range goes as the square
     * root of power - so a better dish sees further without anyone re-deriving the falloff, and there
     * is exactly one place where that square root lives.
     */
    public double detectionRangeBlocks(double sensorRangeAtReference) {
        double reference = referencePower();
        if (sensorRangeAtReference <= 0.0D || reference <= 0.0D || radiatedPower <= 0.0D) {
            return 0.0D;
        }
        return sensorRangeAtReference * Math.sqrt(radiatedPower / reference);
    }

    /** What the reference array sheds per tick - the power a sensor's quoted range is against. */
    public static double referencePower() {
        return HeatNetwork.cellPowerAt(
                Math.max(1, StellurgyConfiguration.getCurrentConfig().shipHeatRadiatorReferenceKelvin))
                * REFERENCE_ARRAY_CELLS;
    }

    /** The whole ship at this position - see {@link ThermalBody}. */
    public static ThermalSignature ofBodyAt(World world, BlockPos anchor) {
        ThermalBody body = ThermalBody.at(world, anchor);
        return body == null ? NONE : body.signature();
    }

    /**
     * A thrown slug's own signature, and the reason one is a decoy rather than a litter problem.
     * <p>
     * It is charged to just under its material's melting point - a thousand kelvin past anything a
     * radiator array runs at - and the law is quartic, so ONE cell of white-hot iron outshines
     * sixty-four cells of working radiator on both terms at once. A seeker takes it by a wide margin
     * and a detector sees it too, which is what makes a lump of metal worth throwing and also why
     * firing the dump is the least quiet thing a ship can do. None of that is written down: it is
     * one temperature being far above another on a fourth-power curve.
     * <p>
     * The surface is the one this subsystem already cools a loose slug through, so what a seeker sees
     * and what the slug actually spends can never disagree.
     */
    public static ThermalSignature ofSlug(ItemStack stack) {
        return surface(HotSlugPhysics.RADIATING_CELLS, HotSlugPhysics.temperatureOf(stack));
    }

    @Override
    public String toString() {
        return "ThermalSignature[power=" + radiatedPower + ", peak=" + peakKelvin + "K, radiance="
                + radiance() + "]";
    }
}
