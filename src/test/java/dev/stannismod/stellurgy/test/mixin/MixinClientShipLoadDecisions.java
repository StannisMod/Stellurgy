package dev.stannismod.stellurgy.test.mixin;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.valkyrienskies.mod.common.ships.ship_world.IPhysObjectWorld;
import org.valkyrienskies.mod.common.ships.ship_world.WorldClientShipManager;

import dev.stannismod.stellurgy.test.trace.ShipLoadDecisionHold;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The client's ship manager, at the two seams where it learns and decides what is loaded.
 *
 * <ul>
 *   <li>{@code client_ship_load_queued} — the HEAD of {@code queueShipLoad} / {@code queueShipUnload}:
 *       an instruction from the server has reached the client and been queued, BEFORE anything is
 *       decided on it. Payload: {@code vsShip} (the physics uuid), {@code load} (true for a load).</li>
 *   <li>The decision pass, {@code loadAndUnloadShips}: skipped at its HEAD while a
 *       {@link ShipLoadDecisionHold} is held, and reported at its RETURN to a released one.</li>
 * </ul>
 *
 * <p>SILENT about what a pass decided: the effects are {@code ship_loaded} / {@code ship_unloaded}
 * ({@code MixinPhysicsObjectEvents}), recorded where the physics object is built or unloaded.</p>
 *
 * <p>The target is a vendored class the project compiles, with no vanilla names, so nothing here may
 * be SRG-remapped. Client only: the class is the client's manager. Test source set.</p>
 */
@Mixin(value = WorldClientShipManager.class, remap = false)
public abstract class MixinClientShipLoadDecisions {

    private static final String QUEUE_INSTRUMENT = "client_ship_load_queue";
    private static final String DECISION_INSTRUMENT = "client_ship_load_decisions";

    @Inject(method = "queueShipLoad", at = @At("HEAD"), remap = false, require = 1)
    private void stellurgyTest$loadQueued(UUID shipID, CallbackInfo ci) {
        queued(shipID, true);
    }

    @Inject(method = "queueShipUnload", at = @At("HEAD"), remap = false, require = 1)
    private void stellurgyTest$unloadQueued(UUID shipID, CallbackInfo ci) {
        queued(shipID, false);
    }

    private static void queued(UUID shipID, boolean load) {
        TestTrace.instrumentHere(QUEUE_INSTRUMENT);
        // NO-READER-YET: read by VSDeckCaptureAndDismountTest's lagging-client reload scenario, written
        // in the same change as this recorder.
        TestTrace.recordHere("client_ship_load_queued", "\"vsShip\":\"" + shipID + "\",\"load\":" + load);
    }

    @Inject(method = "loadAndUnloadShips", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void stellurgyTest$holdDecisions(CallbackInfo ci) {
        TestTrace.instrumentHere(DECISION_INSTRUMENT);
        if (ShipLoadDecisionHold.deferPass()) {
            ci.cancel();
        }
    }

    @Inject(method = "loadAndUnloadShips", at = @At("RETURN"), remap = false, require = 1)
    private void stellurgyTest$decisionsRan(CallbackInfo ci) {
        ShipLoadDecisionHold.passRan((IPhysObjectWorld) (Object) this);
    }
}
