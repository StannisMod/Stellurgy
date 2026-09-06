package zmaster587.advancedRocketry.test.mixin;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.api.EntityRocketBase;
import zmaster587.advancedRocketry.atmosphere.AtmosphereNeedsSuit;
import zmaster587.advancedRocketry.atmosphere.AtmosphereType;
import zmaster587.advancedRocketry.entity.EntityElevatorCapsule;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The suit gate's decision as an event: whether a suit-requiring atmosphere judged a PLAYER immune
 * to it, and by which short-circuit.
 *
 * <p>{@code suit_immunity_decided} IS the return of
 * {@link AtmosphereNeedsSuit#isImmune(EntityLivingBase)} — the one method every suit-requiring
 * atmosphere ({@code NOOXYGEN}, {@code LOWOXYGEN}, {@code VACUUM}, the hot and high-pressure
 * families) inherits and asks before it hurts a body. {@code immune} is the value handed back;
 * {@code creative} / {@code spectator} / {@code grace} / {@code riding} name the four answers that
 * were reached WITHOUT looking at a single armour piece (production's own short-circuits: the game
 * mode, the {@code arRocketTransferGrace} window a rocket transfer leaves on the entity, and a seat
 * in a rocket or an elevator capsule). An {@code immune:true} with all four {@code false} means the
 * SUIT answered — every required piece protected — and an {@code immune:false} means at least one
 * did not. The ONE leg the payload cannot name is the Matter Overdrive android check: production
 * skips it entirely unless that mod is loaded, and no harness world loads it, so it is inert here —
 * but were it present, an otherwise unexplained {@code immune:true} is where it would surface.</p>
 *
 * <p><b>Seam and side.</b> RETURN of {@code isImmune(EntityLivingBase)}, read off
 * {@code cir.getReturnValueZ()} — the method has THREE returns (the grace early-out, the Matter
 * Overdrive android early-out, and the final expression) and the record is taken at whichever one
 * fired. Safe at all three because nothing but the return value and the method's own argument is
 * read: no local is captured, so the armour locals that do not yet exist at the two early exits
 * cost nothing. Routed by the player's own world. The atmosphere tick that drives the damage path
 * runs on the server only, so the records a suit test waits on land in the SERVER log; the in-water
 * {@code LOWOXYGEN} check in {@code PlanetEventHandler.playerTick} is NOT side-gated, runs every
 * tick on BOTH sides, and writes each side's log.</p>
 *
 * <p><b>Observe only.</b> This mixin never calls {@code protectsFrom} nor touches the armour stacks:
 * that path COMMITS an air decrement per call, and an instrument that drained the suit it was
 * watching would be measuring itself. Everything here is read off the return value, the game mode
 * and one NBT long.</p>
 *
 * <p><b>Edge-only.</b> The gate is asked every 10 or 20 ticks per player by the atmosphere tick and
 * every tick, per side, while a player is in water — a per-call record would turn the 256-ring over
 * in seconds. So a record is taken only when, for that player and that atmosphere, the decision
 * CHANGED against the last one seen; the first decision ever seen for the pair is an edge. Keyed per
 * atmosphere, because one player is judged by several (the dimension's and the in-water
 * {@code LOWOXYGEN}) and two alternating answers must not read as a flapping suit.</p>
 *
 * <p><b>Silent about</b>: any non-player body (a mob's immunity goes through the same method and is
 * dropped here; players only, as the atmosphere-tick consumers need); the class-based
 * {@code isImmune(Class)} overload the spawn path uses (a different method, never woven); WHICH piece
 * failed and the air it had (that is the suit's own {@code suit_air_drained}); a decision that did
 * not change (a suited player held in vacuum for a minute yields ONE record); and the grace window's
 * remaining length. The instrument reports itself on every call, player or not, edge or not.</p>
 */
@Mixin(AtmosphereNeedsSuit.class)
public abstract class MixinAtmosphereNeedsSuitEvents {

    private static final String INSTRUMENT = "suit_immunity_events";

    // The last decision recorded per player, per atmosphere name. Weak on the player so a logout
    // does not pin the entry; the inner map is a plain HashMap keyed by the atmosphere's
    // unlocalized name (the singletons are never collected, so nothing to be weak about). A
    // private static is permitted on a mixin (the refusal is for NON-private ones), and a static
    // initialiser is merged — the neighbouring container gate carries the same shape.
    private static final WeakHashMap<EntityPlayer, HashMap<String, Boolean>> LAST_DECISION =
            new WeakHashMap<EntityPlayer, HashMap<String, Boolean>>();

    @Inject(method = "isImmune", at = @At("RETURN"))
    private void arTest$decided(EntityLivingBase body, CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrument(body, INSTRUMENT);
        if (!(body instanceof EntityPlayer) || body.world == null) {
            return;
        }
        EntityPlayer player = (EntityPlayer) body;
        boolean immune = cir.getReturnValueZ();
        String atmosphere = ((AtmosphereType) (Object) this).getUnlocalizedName();
        synchronized (LAST_DECISION) {
            HashMap<String, Boolean> byAtmosphere = LAST_DECISION.get(player);
            if (byAtmosphere == null) {
                byAtmosphere = new HashMap<String, Boolean>();
                LAST_DECISION.put(player, byAtmosphere);
            }
            Boolean last = byAtmosphere.get(atmosphere);
            if (last != null && last.booleanValue() == immune) {
                return;
            }
            byAtmosphere.put(atmosphere, Boolean.valueOf(immune));
        }
        boolean creative = player.capabilities.isCreativeMode;
        boolean spectator = player.isSpectator();
        // The same NBT read production's early-out makes at the method's HEAD — read-only, and the
        // clock has not moved between the two.
        boolean grace = player.getEntityData().getLong("arRocketTransferGrace")
                > player.world.getTotalWorldTime();
        // The fourth armour-free short-circuit. Without it an immune:true with creative, spectator
        // and grace all false could not be read as "the suit answered" — a passenger of a rocket or
        // of an elevator capsule reaches the same true without a single piece being consulted.
        boolean riding = player.getRidingEntity() instanceof EntityRocketBase
                || player.getRidingEntity() instanceof EntityElevatorCapsule;
        TestTrace.record(player, "suit_immunity_decided", "\"e\":" + player.getEntityId()
                + ",\"who\":\"" + TestTrace.json(player.getName())
                + "\",\"atmosphere\":\"" + TestTrace.json(atmosphere)
                + "\",\"immune\":" + immune
                + ",\"creative\":" + creative
                + ",\"spectator\":" + spectator
                + ",\"grace\":" + grace
                + ",\"riding\":" + riding);
    }
}
