package dev.stannismod.stellurgy.test.mixin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.stannismod.stellurgy.api.EntityRocketBase;
import dev.stannismod.stellurgy.api.atmosphere.AtmosphereHazard;
import dev.stannismod.stellurgy.atmosphere.RocketTransferGrace;
import dev.stannismod.stellurgy.atmosphere.hazard.AtmosphereHazards;
import dev.stannismod.stellurgy.atmosphere.hazard.HazardExposure;
import dev.stannismod.stellurgy.entity.EntityElevatorCapsule;
import dev.stannismod.stellurgy.test.trace.EntityTrace;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The suit gate's decision as an event: whether the air a body is standing in judged that PLAYER
 * immune to it, and by which short-circuit.
 *
 * <p>{@code suit_immunity_decided} IS the return of
 * {@link AtmosphereHazards#isImmune(HazardExposure, EntityLivingBase)} — the one method asked before
 * anything hurts a body, whatever the air is made of. {@code immune} is the value handed back;
 * {@code creative} / {@code spectator} / {@code grace} / {@code riding} name the four answers that
 * were reached WITHOUT looking at a single armour piece (production's own short-circuits: the game
 * mode, the grace window a rocket transfer leaves on the entity, and a seat aboard a rocket or an
 * elevator capsule).</p>
 *
 * <p><b>It used to be keyed by the ATMOSPHERE, because it wove into one.</b> The gate lived on
 * {@code AtmosphereNeedsSuit}, the base class of the suit-requiring atmospheres, and the instance it
 * was woven into named itself. There is no such class any more: what an atmosphere DOES is a set of
 * hazards read off its gas, and the same gate now answers for any air that carries any of them. So
 * the decision is keyed — and reported — by the HAZARD SET, which is the thing the answer actually
 * depends on. A player held in vacuum still yields one record, for the same reason as before.</p>
 *
 * <p><b>Seam and side.</b> RETURN of the static {@code isImmune}, server side. The instrument
 * reports itself on every call, player or not, edge or not.</p>
 *
 * <p><b>What it is silent about</b>: non-players (dropped here; players only, as the atmosphere-tick
 * consumers need); WHICH piece failed and the air it had (that is the suit's own
 * {@code suit_air_drained}); a decision that did not change; and the grace window's remaining
 * length.</p>
 */
@Mixin(AtmosphereHazards.class)
public abstract class MixinAtmosphereNeedsSuitEvents {

    private static final String INSTRUMENT = "suit_immunity_events";

    // The last decision recorded, per hazard set, is held BY the player
    // (EntityTrace.SuitDecisions) — created with the body and gone with it.

    @Inject(method = "isImmune", at = @At("RETURN"))
    private static void stellurgyTest$decided(HazardExposure exposure, EntityLivingBase body,
                                              CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrument(body, INSTRUMENT);
        if (!(body instanceof EntityPlayer) || body.world == null) {
            return;
        }
        EntityPlayer player = (EntityPlayer) body;
        boolean immune = cir.getReturnValueZ();
        String hazards = stellurgyTest$name(exposure);
        HashMap<String, Boolean> byAtmosphere = EntityTrace.memory(player,
                EntityTrace.SuitDecisions.class, EntityTrace.SuitDecisions::new).byAtmosphere;
        Boolean last = byAtmosphere.get(hazards);
        if (last != null && last.booleanValue() == immune) {
            return;
        }
        byAtmosphere.put(hazards, Boolean.valueOf(immune));
        boolean creative = player.capabilities.isCreativeMode;
        boolean spectator = player.isSpectator();
        // The same NBT read production's early-out makes at the method's HEAD — read-only, through
        // the same KEY constant, and the clock has not moved between the two.
        boolean grace = player.getEntityData().getLong(RocketTransferGrace.KEY)
                > player.world.getTotalWorldTime();
        // The fourth armour-free short-circuit. Without it an immune:true with creative, spectator
        // and grace all false could not be read as "the suit answered" — a passenger of a rocket or
        // of an elevator capsule reaches the same true without a single piece being consulted.
        boolean riding = player.getRidingEntity() instanceof EntityRocketBase
                || player.getRidingEntity() instanceof EntityElevatorCapsule;
        TestTrace.record(player, "suit_immunity_decided", "\"e\":" + player.getEntityId()
                + ",\"who\":\"" + TestTrace.json(player.getName())
                + "\",\"hazards\":\"" + TestTrace.json(hazards)
                + "\",\"immune\":" + immune
                + ",\"creative\":" + creative
                + ",\"spectator\":" + spectator
                + ",\"grace\":" + grace
                + ",\"riding\":" + riding);
    }

    /** A stable name for a hazard set: sorted, so the same air always reads the same way. */
    private static String stellurgyTest$name(HazardExposure exposure) {
        if (exposure == null || exposure.isEmpty()) {
            return "none";
        }
        List<String> names = new ArrayList<>();
        for (AtmosphereHazard hazard : exposure.hazards()) {
            names.add(hazard.name());
        }
        Collections.sort(names);
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            if (out.length() > 0) {
                out.append('+');
            }
            out.append(name);
        }
        return out.toString();
    }
}
