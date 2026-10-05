package dev.stannismod.stellurgy.atmosphere.hazard;

import java.util.function.IntSupplier;

import net.minecraft.util.DamageSource;
import dev.stannismod.stellurgy.api.atmosphere.AtmosphereHazard;

/**
 * One row: what a hazard does to somebody standing in it, and how often.
 * <p>
 * <b>A row is data, not behaviour.</b> Every one of these used to be a method on a class of its own,
 * and because they were methods they drifted: two rooms that differ only in whether the air can be
 * breathed ended up with different ignition, different narcosis and different nausea, none of it
 * intended. Written out as rows the differences are visible, and the ones that are still here are
 * marked as the deliberate exceptions they now are.
 * <p>
 * The amount of damage is a {@link IntSupplier} rather than a number because one of them is
 * configurable, and reading the config where the damage is DEALT is what keeps a changed setting from
 * being ignored until the next restart.
 */
public final class HazardEffect {

    /** Vanilla potion ids. Named because a bare 2 in a table is not a fact anybody can check. */
    static final int SLOWNESS = 2;
    static final int MINING_FATIGUE = 4;
    static final int JUMP_BOOST = 8;
    static final int NAUSEA = 9;

    /** How long an applied effect lasts. Longer than every period here, so nothing flickers. */
    static final int EFFECT_TICKS = 40;
    static final int NAUSEA_TICKS = 400;

    /** What a negative amplifier means: this row does not apply that effect at all. */
    static final int NONE = -1;

    private final AtmosphereHazard hazard;
    private final int periodTicks;
    private final DamageSource damage;
    private final IntSupplier damageAmount;
    private final int slowness;
    private final int miningFatigue;
    private final int nausea;
    private final boolean narcosis;
    private final boolean ignites;
    private final boolean announcesOxygenState;
    private final boolean requiresSuppliedOxygen;
    private final String messageKey;

    HazardEffect(AtmosphereHazard hazard, int periodTicks, DamageSource damage,
                 IntSupplier damageAmount, int slowness, int miningFatigue, int nausea,
                 boolean narcosis, boolean ignites, boolean announcesOxygenState,
                 boolean requiresSuppliedOxygen, String messageKey) {
        this.hazard = hazard;
        this.periodTicks = Math.max(1, periodTicks);
        this.damage = damage;
        this.damageAmount = damageAmount;
        this.slowness = slowness;
        this.miningFatigue = miningFatigue;
        this.nausea = nausea;
        this.narcosis = narcosis;
        this.ignites = ignites;
        this.announcesOxygenState = announcesOxygenState;
        this.requiresSuppliedOxygen = requiresSuppliedOxygen;
        this.messageKey = messageKey;
    }

    public AtmosphereHazard hazard() {
        return hazard;
    }

    /** Whether this row acts on this tick. The period is the row's, never a shared clock's. */
    public boolean firesOn(long worldTime) {
        return worldTime % periodTicks == 0L;
    }

    public DamageSource damage() {
        return damage;
    }

    public int damageAmount() {
        return damageAmount == null ? 0 : Math.max(0, damageAmount.getAsInt());
    }

    public int slowness() {
        return slowness;
    }

    public int miningFatigue() {
        return miningFatigue;
    }

    /**
     * How strong the nausea is, or {@link #NONE}. Whether it is applied at all is a separate
     * question the config answers, and it is asked at the moment of use rather than cached.
     */
    public int nausea() {
        return nausea;
    }

    /** Blocks jumping — the gas narcosis of a deep atmosphere. */
    public boolean narcosis() {
        return narcosis;
    }

    public boolean ignites() {
        return ignites;
    }

    /** Whether the client is told its air state changed, so the overlay can react. */
    public boolean announcesOxygenState() {
        return announcesOxygenState;
    }

    /**
     * Whether a suit worn against this has to carry the oxygen rather than take it from outside.
     * <p>
     * True only where there is no oxidiser at all to work with. Thin air is still air an extractor
     * can concentrate, which is why running out of tank matters on an airless moon and not in a room
     * that has merely gone stale.
     */
    public boolean requiresSuppliedOxygen() {
        return requiresSuppliedOxygen;
    }

    /** The lang key a player is shown while standing in this, unprotected. */
    public String messageKey() {
        return messageKey;
    }
}
