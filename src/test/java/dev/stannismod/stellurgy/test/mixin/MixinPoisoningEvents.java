package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.stannismod.stellurgy.atmosphere.hazard.AtmosphereHazards;
import dev.stannismod.stellurgy.atmosphere.hazard.HazardExposure;
import dev.stannismod.stellurgy.atmosphere.hazard.Poisoning;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * Every second a PLAYER breathes poisoned air, as an event: whether the suit kept it out, and the dose
 * the body came into that second carrying.
 *
 * <p>{@code poison_breathed} is written where {@link Poisoning#tick} asks the suit gate — the call it
 * makes only when the air's toxic index has reached one, so a record means the player WAS breathing
 * poison that second, and its absence over a window in which he stood in such air means the tick never
 * reached the question. {@code immune} is the gate's answer, handed back to production unchanged.
 * {@code dose} is the entity's stored dose read through {@link Poisoning#DOSE_KEY} before production
 * advances it — the value the gate's answer is about to act on — so a run of records says whether the
 * dose moved while the gate said one thing or the other. {@code worn} names the armour slots holding
 * something when the gate was asked, so the pieces an answer was given about are on the same record.</p>
 *
 * <p><b>Seam and side.</b> The {@code isImmune} call inside the static {@code Poisoning#tick}, server
 * side, a redirect that calls the original once and returns its answer. Not the RETURN of
 * {@code isImmune} itself: that method answers for every hazard set, and the poison question cannot be
 * told from the vacuum's there. The instrument reports itself on every call, player or not.</p>
 *
 * <p><b>What it is silent about</b>: non-players; a second in which the air was below its limit (the
 * gate is not asked, and a dose then only clears); the damage the dose does (vanilla's hurt event
 * carries that, source {@code Poison}); and the dose AFTER the second's advance, which the next
 * record carries.</p>
 */
@Mixin(Poisoning.class)
public abstract class MixinPoisoningEvents {

    private static final String INSTRUMENT = "poison_events";

    @Redirect(method = "tick", require = 1,
            at = @At(value = "INVOKE", target = "Ldev/stannismod/stellurgy/atmosphere/hazard/AtmosphereHazards;"
                    + "isImmune(Ldev/stannismod/stellurgy/atmosphere/hazard/HazardExposure;"
                    + "Lnet/minecraft/entity/EntityLivingBase;)Z"))
    private static boolean stellurgyTest$poisonGate(HazardExposure exposure, EntityLivingBase body) {
        boolean immune = AtmosphereHazards.isImmune(exposure, body);
        TestTrace.instrument(body, INSTRUMENT);
        if (body instanceof EntityPlayer && body.world != null) {
            TestTrace.record(body, "poison_breathed", "\"e\":" + body.getEntityId()
                    + ",\"who\":\"" + TestTrace.json(body.getName())
                    + "\",\"immune\":" + immune
                    + ",\"dose\":" + TestTrace.fmt(body.getEntityData().getDouble(Poisoning.DOSE_KEY))
                    + ",\"worn\":\"" + stellurgyTest$worn(body) + "\"");
        }
        return immune;
    }

    /**
     * The armour slots holding something at the moment the gate was asked, in slot order and joined by
     * {@code +} ({@code "FEET+LEGS+CHEST+HEAD"} for a whole suit, {@code "none"} for none) — so a reader
     * can tell which pieces the answer was given about without a second probe of the player.
     */
    private static String stellurgyTest$worn(EntityLivingBase body) {
        StringBuilder worn = new StringBuilder();
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            if (slot.getSlotType() == EntityEquipmentSlot.Type.ARMOR && !body.getItemStackFromSlot(slot).isEmpty()) {
                worn.append(worn.length() > 0 ? "+" : "").append(slot.name());
            }
        }
        return worn.length() == 0 ? "none" : worn.toString();
    }
}
