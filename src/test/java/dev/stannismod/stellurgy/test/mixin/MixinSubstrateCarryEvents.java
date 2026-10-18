package dev.stannismod.stellurgy.test.mixin;

import dev.stannismod.stellurgy.integration.vs.DeckFrameTick;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import net.minecraft.entity.Entity;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.entity.EntityShipMovementData;
import org.valkyrienskies.mod.common.ships.entity_interaction.EntityDraggable;
import org.valkyrienskies.mod.common.ships.entity_interaction.IDraggable;

/**
 * What the physics substrate's own carry ({@code EntityDraggable}) does to a body a deck holds — the
 * second mover the deck frame must keep off it. Read by
 * {@code VSLivingBodyOnADeckTest#anArmorStandTheDeckTakesFromAWorldSupportIsNeverCarriedByTheSubstrate}.
 *
 * <h2>Two records, one per question</h2>
 *
 * <ul>
 *   <li>{@code substrate_carry_visit} — the carry looked at a body a deck holds
 *       ({@code DeckFrameTick.holds}), at the HEAD of its per-body step, with the association it
 *       found there: the ship it last touched ({@code touched}, {@code null} when none) and how many
 *       ticks ago ({@code ticksSince}), and whether its own update this tick ended on something
 *       ({@code onGround}). Written only for a held body, so it is the instrument's proof
 *       that it was looking at THAT body while the deck held it.</li>
 *   <li>{@code substrate_carry_applied} — the carry MOVED a body, at the RETURN of the one call that
 *       moves it ({@code applyAddedVelocity}), with the displacement that call reports it applied and
 *       whether a deck held the body at that moment ({@code held}). Written for every body, held or
 *       not, so a carry of a held body is never filtered out by the instrument's own reading.</li>
 * </ul>
 *
 * <p>The carry runs after every entity's own update in a world tick, so a held body's {@code visit}
 * comes after the deck has put it back in the world that tick. Test source set: absent from a
 * released jar; reaches from the tests into the product, which is the direction a test mixin may.</p>
 *
 * <p>Silent about a carry that moves a body by a position write of its own rather than through
 * {@code applyAddedVelocity}, and about the added YAW, which the carry writes into the rotation
 * fields without a call to observe.</p>
 */
@Mixin(value = EntityDraggable.class, remap = false)
public abstract class MixinSubstrateCarryEvents {

    private static final String INSTRUMENT = "substrate_carry_events";

    @Inject(method = "addEntityVelocityFromShipBelow", at = @At("HEAD"))
    private static void stellurgyTest$visit(Entity entity, CallbackInfo ci) {
        TestTrace.instrument(entity, INSTRUMENT);
        if (!DeckFrameTick.holds(entity)) {
            return;
        }
        EntityShipMovementData data = ((IDraggable) entity).getEntityShipMovementData();
        String touched = data == null || data.getLastTouchedShip() == null
                ? "null" : "\"" + data.getLastTouchedShip().getUuid() + "\"";
        TestTrace.record(entity, "substrate_carry_visit", "\"e\":" + entity.getEntityId()
                + ",\"heldShip\":\"" + DeckFrameTick.heldShipId(entity) + "\""
                + ",\"touched\":" + touched
                + ",\"ticksSince\":" + (data == null ? -1 : data.getTicksSinceTouchedShip())
                + ",\"onGround\":" + entity.onGround
                + ",\"ticks\":" + entity.ticksExisted);
    }

    @Inject(method = "applyAddedVelocity", at = @At("RETURN"))
    private static void stellurgyTest$applied(Vector3dc addedVelocity, Entity entity,
                                              CallbackInfoReturnable<Vector3dc> cir) {
        TestTrace.instrument(entity, INSTRUMENT);
        Vector3dc moved = cir.getReturnValue();
        TestTrace.record(entity, "substrate_carry_applied", "\"e\":" + entity.getEntityId()
                + ",\"held\":" + DeckFrameTick.holds(entity)
                + ",\"dx\":" + moved.x() + ",\"dy\":" + moved.y() + ",\"dz\":" + moved.z()
                + ",\"ticks\":" + entity.ticksExisted);
    }
}
