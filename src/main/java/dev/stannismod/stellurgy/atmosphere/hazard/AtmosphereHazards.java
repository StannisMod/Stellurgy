package dev.stannismod.stellurgy.atmosphere.hazard;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nonnull;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.Loader;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.EntityRocketBase;
import dev.stannismod.stellurgy.api.atmosphere.Atmosphere;
import dev.stannismod.stellurgy.api.atmosphere.AtmosphereHazard;
import dev.stannismod.stellurgy.api.capability.CapabilitySpaceArmor;
import dev.stannismod.stellurgy.atmosphere.AtmosphereHandler;
import dev.stannismod.stellurgy.atmosphere.RocketTransferGrace;
import dev.stannismod.stellurgy.entity.EntityElevatorCapsule;
import dev.stannismod.stellurgy.integration.MatterOvedriveIntegration;
import dev.stannismod.stellurgy.util.ItemAirUtils;

/**
 * What each atmosphere does to the people in it, in one table, plus the one question a suit is asked.
 * <p>
 * <b>This replaces fourteen classes that each carried a copy of the same method.</b> Every one of them
 * was the same shape — a period, a damage source, an amount, a handful of potions — and because each
 * was a method rather than a row they drifted apart in ways nobody chose. The rows below are the
 * fourteen cells decomposed into the five things that were actually varying, and where the old cells
 * disagreed with what that decomposition produces, the disagreement is a NAMED row rather than a
 * silent one. Each such row says which behaviour it is preserving and is a candidate for deletion on
 * its own merits.
 * <p>
 * The table is keyed by the atmosphere the rest of the mod still passes around. That is deliberate and
 * temporary: a zone derives one from its gases and a planet derives one from its density, and until
 * those two meet somewhere better, keying here is what keeps a SINGLE answer to "what is wrong with
 * this air" instead of one answer per caller.
 * <p>Every static field of this type is effectively final, process lifetime: built once at class initialisation, and holds an immutable value.</p>
 */
public final class AtmosphereHazards {

    private AtmosphereHazards() {
    }

    // ─── the rows ──────────────────────────────────────────────────────────────────────────────

    /** No air at all: the body is decompressing, not merely unsupplied. */
    private static final HazardEffect DECOMPRESSION = new HazardEffect(
            AtmosphereHazard.DECOMPRESSION, 10,
            AtmosphereHandler.vacuumDamage,
            () -> StellurgyConfiguration.getCurrentConfig().vacuumDamage,
            4, 4, 1, false, false, true, true, "msg.noOxygen");

    /** Air with no oxidiser in it at all. */
    private static final HazardEffect SUFFOCATION = new HazardEffect(
            AtmosphereHazard.SUFFOCATION, 10,
            AtmosphereHandler.lowOxygenDamage, () -> 1,
            4, 4, 1, false, false, true, true, "msg.noOxygen");

    /**
     * PRESERVED DISAGREEMENT — suffocating in a DENSE atmosphere has always carried stronger nausea
     * than suffocating anywhere else, and nothing about depth explains why. Kept so that the table
     * changes no behaviour on the day it lands; a candidate for deletion on its own.
     */
    private static final HazardEffect SUFFOCATION_WHERE_DENSE = new HazardEffect(
            AtmosphereHazard.SUFFOCATION, 10,
            AtmosphereHandler.lowOxygenDamage, () -> 1,
            4, 4, 2, false, false, true, true, "msg.noOxygen");

    /** Air with an oxidiser, but not enough of it. A suit here concentrates rather than supplies. */
    private static final HazardEffect THIN_AIR = new HazardEffect(
            AtmosphereHazard.SUFFOCATION, 20,
            AtmosphereHandler.lowOxygenDamage, () -> 1,
            2, 2, HazardEffect.NONE, false, false, true, false, "msg.noOxygen");

    /** Too much oxidiser. Slow, and it does not stop anything burning — that is the hazard. */
    private static final HazardEffect OXYGEN_TOXICITY = new HazardEffect(
            AtmosphereHazard.OXYGEN_TOXICITY, 40,
            AtmosphereHandler.oxygenToxicityDamage, () -> 1,
            HazardEffect.NONE, HazardEffect.NONE, HazardEffect.NONE, false, false, true, false,
            "msg.highOxygen");

    /** Dense enough to labour in. */
    private static final HazardEffect PRESSURE = new HazardEffect(
            AtmosphereHazard.PRESSURE, 20,
            null, null, 2, 2, HazardEffect.NONE, false, false, false, false, "msg.tooDense");

    /**
     * PRESERVED DISAGREEMENT — at the deepest rung, a breathable atmosphere INJURES and an airless
     * one does not, while the airless one blocks jumping and the breathable one does not. Depth is
     * depth either way; these two rows exist to keep both halves exactly as they were.
     */
    private static final HazardEffect CRUSHING_PRESSURE_WHERE_BREATHABLE = new HazardEffect(
            AtmosphereHazard.PRESSURE, 20,
            AtmosphereHandler.oxygenToxicityDamage, () -> 1,
            3, 3, HazardEffect.NONE, false, false, true, false, "msg.muchTooDense");

    private static final HazardEffect CRUSHING_PRESSURE_WHERE_AIRLESS = new HazardEffect(
            AtmosphereHazard.PRESSURE, 20,
            null, null, 3, 3, HazardEffect.NONE, true, false, false, false, "msg.muchTooDense");

    /**
     * PRESERVED DISAGREEMENT — hot air sets you alight only where you could have breathed it. The
     * airless rows below are the same heat with the ignition removed, which is not a distinction the
     * physics makes.
     */
    private static final HazardEffect HEAT = new HazardEffect(
            AtmosphereHazard.HEAT, 20,
            AtmosphereHandler.heatDamage, () -> 1,
            3, HazardEffect.NONE, HazardEffect.NONE, false, true, false, false, "msg.tooHot");

    private static final HazardEffect HEAT_WHERE_AIRLESS = new HazardEffect(
            AtmosphereHazard.HEAT, 20,
            AtmosphereHandler.heatDamage, () -> 1,
            3, HazardEffect.NONE, HazardEffect.NONE, false, false, false, false, "msg.tooHot");

    private static final HazardEffect SEARING_HEAT = new HazardEffect(
            AtmosphereHazard.HEAT, 20,
            AtmosphereHandler.heatDamage, () -> 4,
            3, HazardEffect.NONE, HazardEffect.NONE, false, true, false, false, "msg.tooHot");

    private static final HazardEffect SEARING_HEAT_WHERE_AIRLESS = new HazardEffect(
            AtmosphereHazard.HEAT, 20,
            AtmosphereHandler.heatDamage, () -> 4,
            3, HazardEffect.NONE, HazardEffect.NONE, false, false, false, false, "msg.tooHot");

    // ─── which rows each atmosphere raises ─────────────────────────────────────────────────────

    private static final Map<Atmosphere, HazardExposure> BY_ATMOSPHERE = byAtmosphere();

    private static Map<Atmosphere, HazardExposure> byAtmosphere() {
        Map<Atmosphere, HazardExposure> table = new HashMap<>();
        put(table, Atmosphere.VACUUM, DECOMPRESSION);
        put(table, Atmosphere.NOO2, SUFFOCATION);
        put(table, Atmosphere.LOWOXYGEN, THIN_AIR);
        put(table, Atmosphere.HIGHOXYGEN, OXYGEN_TOXICITY);
        put(table, Atmosphere.HIGHPRESSURE, PRESSURE);
        put(table, Atmosphere.SUPERHIGHPRESSURE, CRUSHING_PRESSURE_WHERE_BREATHABLE);
        put(table, Atmosphere.VERYHOT, HEAT);
        put(table, Atmosphere.SUPERHEATED, SEARING_HEAT);
        put(table, Atmosphere.HIGHPRESSURENOO2, SUFFOCATION_WHERE_DENSE, PRESSURE);
        put(table, Atmosphere.SUPERHIGHPRESSURENOO2, SUFFOCATION_WHERE_DENSE,
                CRUSHING_PRESSURE_WHERE_AIRLESS);
        put(table, Atmosphere.VERYHOTNOO2, SUFFOCATION, HEAT_WHERE_AIRLESS);
        put(table, Atmosphere.SUPERHEATEDNOO2, SUFFOCATION, SEARING_HEAT_WHERE_AIRLESS);
        // AIR and PRESSURIZEDAIR raise nothing, which is what makes them air.
        return Collections.unmodifiableMap(table);
    }

    private static void put(Map<Atmosphere, HazardExposure> table, Atmosphere atmosphere,
                            HazardEffect... rows) {
        table.put(atmosphere, new HazardExposure(Arrays.asList(rows)));
    }

    /**
     * What this air can do to the people in it — a description of the AIR, which a detector reads:
     * air with no oxygen lacks it whatever the configuration says. What it actually does to somebody
     * is {@link #effectOn}. Never null: unknown air does nothing.
     */
    public static HazardExposure exposureOf(Atmosphere atmosphere) {
        HazardExposure found = atmosphere == null ? null : BY_ATMOSPHERE.get(atmosphere);
        return found == null ? HazardExposure.NONE : found;
    }

    /**
     * What this air DOES to a living thing as the server is configured: with {@code breathingRequiresO2}
     * off, a body needs no oxygen, so the air's oxygen hazards fall away and no suit spends its tank.
     */
    public static HazardExposure effectOn(Atmosphere atmosphere) {
        return asConfigured(exposureOf(atmosphere));
    }

    /** {@code exposure} as it acts on a body under the current {@code breathingRequiresO2}. */
    static HazardExposure asConfigured(HazardExposure exposure) {
        return StellurgyConfiguration.getCurrentConfig().breathingRequiresO2
                ? exposure : exposure.withoutOxygenNeed();
    }

    // ─── who is exposed ────────────────────────────────────────────────────────────────────────

    /**
     * Whether this atmosphere can do nothing to this entity.
     * <p>
     * <b>All of it or none of it.</b> An entity that meets the strictest requirement among the active
     * hazards is untouched; one that does not takes everything the air has. Per-hazard protection —
     * a mask that stops the suffocation while the heat still burns — is the shape this is heading
     * for, and it is deliberately NOT taken here: it is a live behavioural change for anyone wearing
     * a partial suit, and it belongs in a change that is about that rather than in one that moves
     * effects into a table.
     */
    public static boolean isImmune(HazardExposure exposure, EntityLivingBase entity) {
        return immune(exposure, entity, true);
    }

    /**
     * The same question, asked for nothing: no suit spends anything to answer it. For a caller that
     * wants to KNOW whether somebody is protected rather than protect him — the air is not acting on
     * him, so nothing is owed. A tank with air left answers yes.
     */
    public static boolean wouldBeImmune(HazardExposure exposure, EntityLivingBase entity) {
        return immune(exposure, entity, false);
    }

    private static boolean immune(HazardExposure exposure, EntityLivingBase entity, boolean commit) {
        if (exposure.isEmpty()) {
            return true;
        }
        // The KEY, not a copy of it: the window is written by RocketTransferGrace and read here, and
        // a literal on one side of that pair is a rename away from a gate that never opens.
        if (entity.getEntityData().getLong(RocketTransferGrace.KEY) > entity.world.getTotalWorldTime()) {
            return true;
        }
        if (Loader.isModLoaded("matteroverdrive")
                && MatterOvedriveIntegration.isAndroidNeedNoOxygen(entity)) {
            return true;
        }
        if (entity instanceof EntityPlayer
                && (((EntityPlayer) entity).capabilities.isCreativeMode
                || ((EntityPlayer) entity).isSpectator())) {
            return true;
        }
        if (entity.getRidingEntity() instanceof EntityRocketBase
                || entity.getRidingEntity() instanceof EntityElevatorCapsule) {
            return true;
        }

        // The chest is asked LAST and nothing is asked twice: answering costs a tick of the suit's
        // air, so a check that ran it before a cheaper one had failed would drain the tank for a
        // verdict already decided.
        if (exposure.needsFullSuit()
                && !(protects(exposure, entity, EntityEquipmentSlot.LEGS, commit)
                && protects(exposure, entity, EntityEquipmentSlot.FEET, commit))) {
            return false;
        }
        return protects(exposure, entity, EntityEquipmentSlot.HEAD, commit)
                && protects(exposure, entity, EntityEquipmentSlot.CHEST, commit);
    }

    private static boolean protects(HazardExposure exposure, EntityLivingBase entity,
                                    EntityEquipmentSlot slot, boolean commit) {
        return protects(exposure, entity.getItemStackFromSlot(slot), commit);
    }

    private static boolean protects(HazardExposure exposure, @Nonnull ItemStack stack, boolean commit) {
        if (ItemAirUtils.INSTANCE.isStackValidAirContainer(stack)
                && new ItemAirUtils.ItemAirWrapper(stack)
                .protectsFrom(exposure.hazards(), exposure.needsSuppliedOxygen(), stack, commit)) {
            return true;
        }
        return !stack.isEmpty()
                && stack.hasCapability(CapabilitySpaceArmor.PROTECTIVEARMOR, null)
                && stack.getCapability(CapabilitySpaceArmor.PROTECTIVEARMOR, null)
                .protectsFrom(exposure.hazards(), exposure.needsSuppliedOxygen(), stack, commit);
    }
}
