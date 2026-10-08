package dev.stannismod.stellurgy.atmosphere.hazard;

import java.util.Collections;
import java.util.function.Supplier;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.atmosphere.AtmosphereHazard;
import dev.stannismod.stellurgy.atmosphere.AirState;

/**
 * Poisoning: a DOSE the body carries, not damage on a clock.
 * <p>
 * <b>The dose is concentration times time</b> (Haber's rule). Every second spent breathing air whose
 * toxic index is at least one adds that index to the dose — air twice past its limit poisons twice as
 * fast — and below the limit nothing is taken in, because the limit is by definition what a body
 * tolerates indefinitely. In clean air the body clears the dose with a half-life, so leaving does not
 * stop the harm at once: a heavy dose keeps hurting until enough of it has gone.
 * <p>
 * Past the threshold the dose injures every second, in proportion to how far past it is: at the
 * threshold one point, at twice the threshold two.
 * <p>
 * <b>The dose lives on the entity</b>, in its own persistent data, so it is saved with it and dies
 * with it. Only a whole sealed suit breathing from its own supply keeps the air out; a mask does not.
 */
public final class Poisoning {

    /** Where an entity's dose is kept, in limit-seconds. */
    public static final String DOSE_KEY = "stellurgyPoisonDose";

    /** The lang key shown to somebody breathing poisoned air. */
    public static final String MESSAGE_KEY = "msg.poison";

    /** The damage type, and through it the death message. */
    public static final String DAMAGE_TYPE = "Poison";

    /** The dose past which it injures: thirty seconds of air exactly at its limit. */
    static final double DOSE_THRESHOLD = 30.0D;

    /** How long clean air takes to clear half of a dose. */
    static final double HALF_LIFE_SECONDS = 300.0D;

    /** Below this nothing worth tracking is left, and the entity's data is cleared. */
    static final double DOSE_FLOOR = 0.001D;

    private static final int TICKS_PER_SECOND = 20;

    private Poisoning() {
    }

    /**
     * The dose one second later. Breathing air at or past its limit adds the air's toxic index; any
     * other second — clean air, or air kept out by a suit — clears it by one second of half-life.
     */
    public static double nextDose(double dose, double toxicIndex) {
        if (toxicIndex >= 1.0D) {
            return dose + toxicIndex;
        }
        return dose * Math.pow(0.5D, 1.0D / HALF_LIFE_SECONDS);
    }

    /** The damage this dose does in one second: none below the threshold, then dose / threshold. */
    public static float damageFor(double dose) {
        return dose >= DOSE_THRESHOLD ? (float) (dose / DOSE_THRESHOLD) : 0.0F;
    }

    /**
     * Advance this entity's dose by the air it is breathing, once a second.
     *
     * @param breathed the air at the entity's mouth, asked for only on the second it is needed; it
     *                 answers null where the entity breathes none (under water, in lava), which
     *                 clears the dose like clean air
     */
    public static void tick(EntityLivingBase entity, Supplier<AirState> breathed) {
        if (entity.world.getTotalWorldTime() % TICKS_PER_SECOND != 0L) {
            return;
        }
        NBTTagCompound data = entity.getEntityData();
        double dose = data.getDouble(DOSE_KEY);
        // With toxicity off nothing is taken in and nothing harms; a dose already carried clears as
        // it would in clean air, so turning the setting back on does not land a stored dose at once.
        boolean toxic = StellurgyConfiguration.getCurrentConfig().enableToxicity;
        AirState air = toxic ? breathed.get() : null;
        double index = air == null ? 0.0D : air.toxicIndex();
        // The suit is asked only when there is something to keep out: asking spends its air.
        if (index >= 1.0D && AtmosphereHazards.isImmune(exposure(), entity)) {
            index = 0.0D;
        }
        if (dose <= 0.0D && index < 1.0D) {
            return;
        }
        dose = nextDose(dose, index);
        if (dose < DOSE_FLOOR) {
            data.removeTag(DOSE_KEY);
            return;
        }
        data.setDouble(DOSE_KEY, dose);
        float damage = damageFor(dose);
        if (toxic && damage > 0.0F) {
            entity.attackEntityFrom(
                    new DamageSource(DAMAGE_TYPE).setDamageBypassesArmor().setDamageIsAbsolute(), damage);
        }
    }

    /** The hazard this raises — the one a suit is asked about while the air is poisoned. */
    public static AtmosphereHazard hazard() {
        return AtmosphereHazard.POISON;
    }

    /**
     * What a suit is asked to keep out: poison, which needs the whole suit and — where breathing needs
     * oxygen — its own supply.
     */
    private static HazardExposure exposure() {
        return AtmosphereHazards.asConfigured(new HazardExposure(Collections.singletonList(new HazardEffect(
                hazard(), TICKS_PER_SECOND, null, null,
                HazardEffect.NONE, HazardEffect.NONE, HazardEffect.NONE,
                false, false, false, true, MESSAGE_KEY))));
    }
}
