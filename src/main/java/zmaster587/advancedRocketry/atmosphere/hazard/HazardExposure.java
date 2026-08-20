package zmaster587.advancedRocketry.atmosphere.hazard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import zmaster587.advancedRocketry.api.atmosphere.AtmosphereHazard;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.network.PacketOxygenState;

/**
 * Everything one atmosphere is doing to the people in it, as a set of rows.
 * <p>
 * <b>The rows are applied together, not one after another.</b> Two hazards that both slow you down do
 * not slow you down twice — the stronger one is what you feel — so the effects are MERGED here and
 * applied once each. Vanilla would reach the same answer by discarding the weaker effect on arrival,
 * but it would post an event and a re-sync for each discarded one, and a room being two kinds of
 * dangerous should not be noisier than a room being one.
 * <p>
 * Damage is not merged: two different injuries are two different injuries, and each names its own
 * source so the death message says what actually happened.
 */
public final class HazardExposure {

    /** Nothing wrong with this air. */
    public static final HazardExposure NONE = new HazardExposure(Collections.<HazardEffect>emptyList());

    private final List<HazardEffect> rows;
    private final Set<AtmosphereHazard> hazards;
    private final boolean needsFullSuit;
    private final boolean needsSuppliedOxygen;

    HazardExposure(List<HazardEffect> rows) {
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
        Set<AtmosphereHazard> found = EnumSet.noneOf(AtmosphereHazard.class);
        boolean full = false;
        boolean supplied = false;
        for (HazardEffect row : this.rows) {
            found.add(row.hazard());
            full |= row.hazard().needsFullSuit();
            supplied |= row.requiresSuppliedOxygen();
        }
        this.hazards = Collections.unmodifiableSet(found);
        this.needsFullSuit = full;
        this.needsSuppliedOxygen = supplied;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * The rows themselves, read-only. What this air does is a description anything may look at —
     * a probe, a readout, a test that has to say the numbers did not move.
     */
    public java.util.List<HazardEffect> rows() {
        return rows;
    }

    /** What is wrong with this air, which is what a suit is asked about. */
    public Set<AtmosphereHazard> hazards() {
        return hazards;
    }

    /**
     * Whether a sealed face is not enough here.
     * <p>
     * The STRICTEST of the active hazards decides. That single rule reproduces every one of the
     * hand-written "does this need a full suit" answers it replaced, because a room that is both
     * airless and crushing was always the crushing one's problem.
     */
    public boolean needsFullSuit() {
        return needsFullSuit;
    }

    /**
     * Whether a suit here has to SUPPLY oxygen rather than concentrate what is already around.
     * <p>
     * This is what decides whether wearing one costs anything: a suit in thin-but-oxygenated air runs
     * its extractor and spends nothing, and a suit in air with no oxidiser at all is breathing from
     * its tank. Said of the hazard rather than of a combustion flag on a label — the label's flag is
     * assigned by hand, and this is a question about the air.
     */
    public boolean needsSuppliedOxygen() {
        return needsSuppliedOxygen;
    }

    /**
     * What to tell somebody standing here unprotected: the most severe hazard's own message.
     * Severity is the hazard enum's declaration order, and nothing else reads it.
     */
    public String messageKey() {
        HazardEffect worst = null;
        for (HazardEffect row : rows) {
            if (worst == null || row.hazard().ordinal() < worst.hazard().ordinal()) {
                worst = row;
            }
        }
        return worst == null ? "" : worst.messageKey();
    }

    /**
     * Do to this entity whatever the rows that fire on this tick say.
     * <p>
     * Immunity is NOT checked here — the caller decides whether this entity is exposed at all, because
     * that question involves the entity's gear and the atmosphere's whole hazard set rather than any
     * one row.
     */
    public void applyTo(EntityLivingBase entity) {
        long worldTime = entity.world.getTotalWorldTime();
        boolean announce = false;
        boolean ignite = false;
        Map<Integer, Integer> strongest = new HashMap<>();

        for (HazardEffect row : rows) {
            if (!row.firesOn(worldTime)) {
                continue;
            }
            if (row.damage() != null) {
                entity.attackEntityFrom(row.damage(), row.damageAmount());
            }
            ignite |= row.ignites();
            announce |= row.announcesOxygenState();
            keepStrongest(strongest, HazardEffect.SLOWNESS, row.slowness());
            keepStrongest(strongest, HazardEffect.MINING_FATIGUE, row.miningFatigue());
            if (row.narcosis()) {
                // Amplified far past anything that reads as a jump height: this is the absence of
                // jumping, not a small penalty on it.
                keepStrongest(strongest, HazardEffect.JUMP_BOOST, 150);
            }
            // Asked of the config HERE rather than remembered: six classes used to cache this into a
            // static at class-load, which made the setting depend on class-load order and unreachable
            // at runtime.
            if (ARConfiguration.getCurrentConfig().enableNausea) {
                keepStrongest(strongest, HazardEffect.NAUSEA, row.nausea());
            }
        }

        if (ignite) {
            entity.setFire(1);
        }
        for (Map.Entry<Integer, Integer> effect : strongest.entrySet()) {
            Potion potion = Potion.getPotionById(effect.getKey());
            if (potion == null) {
                continue;
            }
            int duration = effect.getKey() == HazardEffect.NAUSEA
                    ? HazardEffect.NAUSEA_TICKS : HazardEffect.EFFECT_TICKS;
            entity.addPotionEffect(new PotionEffect(potion, duration, effect.getValue()));
        }
        if (announce && entity instanceof EntityPlayer) {
            zmaster587.advancedRocketry.atmosphere.AtmosphereType
                    .sendToRealPlayer(new PacketOxygenState(), (EntityPlayer) entity);
        }
    }

    private static void keepStrongest(Map<Integer, Integer> into, int potionId, int amplifier) {
        if (amplifier == HazardEffect.NONE) {
            return;
        }
        Integer held = into.get(potionId);
        if (held == null || amplifier > held) {
            into.put(potionId, amplifier);
        }
    }
}
