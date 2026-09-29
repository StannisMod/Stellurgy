package zmaster587.advancedRocketry.test.mixin;

import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.valkyrienskies.mod.common.entity.EntityShipMovementData;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.ships.entity_interaction.IDraggable;

import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * A loose body came to rest on a ship — the moment the substrate starts calling it a passenger.
 *
 * <h2>What it answers, and what asked before</h2>
 *
 * <p>Two scenarios about a hull that leaves a body behind need the SAME precondition: the body must
 * actually be associated with the ship, or the hazard they introduce has no path to their subject
 * and everything they measure is a measurement of nothing. Both asked for it by polling
 * {@code artest vs player-ship-data} until {@code lastTouchedShip} came back non-null — and the
 * association only lives twenty ticks, so every probe call they spent looking for it was spent out
 * of the window they then needed for the experiment. The record is written by the tick that forms
 * the association, so a test links on it and arrives at its hazard with the window intact.</p>
 *
 * <h2>The seam</h2>
 *
 * <p>{@code Entity.move}'s RETURN — the same point the substrate's own {@code MixinEntityIntrinsic}
 * assigns the data at. Its injection carries {@code priority = 1}, so it is applied first and its
 * callback runs first, and this one reads what it has just written. Were that order ever to invert,
 * this would read the previous move's data and the record would arrive one move late rather than be
 * wrong — a delay of a tick against a twenty-tick window.</p>
 *
 * <p>Read through {@link IDraggable}, which is the substrate's own accessor for the field rather
 * than a re-derivation of which ship a body is standing on: this observes the association
 * production formed, not one computed here from positions, which is the distinction that lets the
 * scenarios' precondition mean anything.</p>
 *
 * <h2>Edge only, and what it is silent about</h2>
 *
 * <p>{@code move} runs for every entity every tick, and the substrate re-asserts the association on
 * each of them, so only a CHANGE of the associated ship is recorded: nothing is written while a
 * body stays on the hull it is already on. The release — the association lapsing back to none — is
 * tracked so that stepping back onto the same hull announces itself again, but it is not recorded:
 * no test asks for it, and {@code ticksSinceTouchedShip} on the probe already answers "how long
 * ago" for one that does.</p>
 *
 * <p>SILENT, therefore, about: how long an association lasts, a body dragged by a ship it touched
 * earlier, a client-side association (the record is routed by the entity's own world, so one would
 * land in the client log rather than be dropped), and a body moved by a position write — a teleport
 * does not route through {@code move} at all, which is precisely the fact one of the two scenarios
 * is built on.</p>
 *
 * <h2>Why a mixin against code we compile is allowed here</h2>
 *
 * <p>The distinction is DIRECTION, as {@code MixinEntityPositionWriters} puts it: this reaches from
 * the TESTS into the product and ships with them — it lives in the test source set, is queued only
 * by the harness coremod, and is absent from a released jar. A production patch against a vendored
 * tree would be indirection where an edit would do; an observation point that must not exist in the
 * shipped game is the opposite case.</p>
 */
@Mixin(Entity.class)
public abstract class MixinEntityShipTouchEvents {

    private static final String INSTRUMENT = "entity_ship_touch_events";

    /** The ship this body was last ANNOUNCED on, so only a change is recorded. */
    @Unique
    private UUID arTest$announcedShip = null;

    @Inject(method = "move", at = @At("RETURN"))
    private void arTest$shipTouchChanged(MoverType type, double dx, double dy, double dz,
                                         CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        Entity self = (Entity) (Object) this;
        if (self.world == null) {
            return;
        }
        EntityShipMovementData data = ((IDraggable) (Object) this).getEntityShipMovementData();
        ShipData touched = data == null ? null : data.getLastTouchedShip();
        UUID now = touched == null ? null : touched.getUuid();
        if (now == null ? arTest$announcedShip == null : now.equals(arTest$announcedShip)) {
            return;
        }
        arTest$announcedShip = now;
        if (now == null) {
            return; // the association lapsed: tracked so a re-touch announces, not itself an event
        }
        TestTrace.record(self, "entity_touched_ship",
                "\"entity\":" + self.getEntityId()
                        + ",\"vsShip\":\"" + now + "\""
                        + ",\"dim\":" + self.world.provider.getDimension()
                        + ",\"x\":" + TestTrace.fmt(self.posX)
                        + ",\"y\":" + TestTrace.fmt(self.posY)
                        + ",\"z\":" + TestTrace.fmt(self.posZ));
    }
}
