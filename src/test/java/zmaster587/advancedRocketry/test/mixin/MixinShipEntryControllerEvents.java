package zmaster587.advancedRocketry.test.mixin;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.space.ShipEntryController;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * Every answer the space-entry gate gives, as an event: {@code entry_decided} with the decision's
 * own name.
 *
 * <p>The controller funnels all eight outcomes of {@code requestEntry} — no id, already entering,
 * already in space, cooldown, no position, pool full, cut failed, started — through one private
 * method that records the verdict and answers the caller. Recording there reads production's
 * decision at the one place it is made; a test that used to poll the ledger's size and the chat for
 * a refusal line now awaits this record and reads WHICH decision it was.</p>
 */
@Mixin(ShipEntryController.class)
public abstract class MixinShipEntryControllerEvents {

    @Inject(method = "decided", at = @At("HEAD"))
    private void arTest$decided(ShipEntryController.Decision decision, UUID shipId, long now,
                                CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere("entry_events");
        TestTrace.recordServer("entry_decided", "\"ship\":\"" + shipId + "\",\"decision\":\""
                + decision + "\"");
    }
}
