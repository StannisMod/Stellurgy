package zmaster587.advancedRocketry.test.mixin;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.valkyrienskies.mod.common.ships.QueryableShipData;
import org.valkyrienskies.mod.common.ships.ShipData;

import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * A ship EXISTS in the physics mod's registry — and stops existing — as events, on whichever side
 * the registry lives.
 *
 * <p>Every "wait for the assembly" in the suite polled {@code ship-count-all} for an INCREMENT: a
 * count, so it could not say which ship arrived nor name it. The registry's own {@code addShip} is
 * where a queued assembly becomes a ship, and the record carries the ship's physics id, its name and
 * the durable AR id it was bound with — so an arrangement can take the identity straight from the
 * event instead of a positional lookup at the build site a tick later.</p>
 *
 * <p>The registry is a vendored class the project compiles, and its names are not vanilla's, so
 * nothing here may be SRG-remapped.</p>
 */
@Mixin(value = QueryableShipData.class, remap = false)
public abstract class MixinQueryableShipDataEvents {

    private static final String INSTRUMENT = "ship_registry_events";

    @Inject(method = "addShip", at = @At("HEAD"), remap = false)
    private void arTest$added(ShipData ship, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        if (ship == null) {
            return;
        }
        TestTrace.recordHere("ship_spawned", "\"vsShip\":\"" + ship.getUuid() + "\",\"name\":\""
                + TestTrace.json(ship.getName()) + "\",\"arShip\":\"" + ship.getArDurableId() + "\"");
    }

    @Inject(method = "removeShip(Ljava/util/UUID;)V", at = @At("HEAD"), remap = false)
    private void arTest$removedByUuid(UUID uuid, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordHere("ship_removed", "\"vsShip\":\"" + uuid + "\"");
    }

    @Inject(method = "removeShip(Lorg/valkyrienskies/mod/common/ships/ShipData;)V", at = @At("HEAD"),
            remap = false)
    private void arTest$removedByData(ShipData ship, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordHere("ship_removed", "\"vsShip\":\"" + (ship == null ? "null" : ship.getUuid())
                + "\",\"name\":\"" + (ship == null ? "" : TestTrace.json(ship.getName())) + "\"");
    }
}
