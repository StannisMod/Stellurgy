package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The cursor acting on the world, as an event: {@code client_cursor_act}.
 *
 * <p>The HEAD of vanilla's three mouse acts — {@code clickMouse} (a blow, or the start of breaking a
 * block), {@code rightClickMouse} (use, place) and {@code middleClickMouse} (pick) — which the client
 * calls only when the binding behind them fired. Carries {@code button}: the {@code KeyBinding} code
 * of the mouse button whose act it is ({@code -100} left, {@code -99} right, {@code -98} middle).
 * Client log. Read by {@code HelmControlsClientGroupTest}.</p>
 *
 * <p>SILENT about the continued dig of a held left button ({@code sendClickBlockToController}), which
 * the same binding gates.</p>
 */
@Mixin(Minecraft.class)
public abstract class MixinClientCursorActs {

    private static final String INSTRUMENT = "client_cursor_acts";

    @Inject(method = "clickMouse", at = @At("HEAD"), require = 1)
    private void stellurgyTest$attack(CallbackInfo ci) {
        stellurgyTest$act(-100);
    }

    @Inject(method = "rightClickMouse", at = @At("HEAD"), require = 1)
    private void stellurgyTest$use(CallbackInfo ci) {
        stellurgyTest$act(-99);
    }

    @Inject(method = "middleClickMouse", at = @At("HEAD"), require = 1)
    private void stellurgyTest$pick(CallbackInfo ci) {
        stellurgyTest$act(-98);
    }

    private void stellurgyTest$act(int button) {
        Minecraft self = (Minecraft) (Object) this;
        if (self.world == null) {
            return;
        }
        TestTrace.instrument(self.world, INSTRUMENT);
        TestTrace.record(self.world, "client_cursor_act", "\"button\":" + button);
    }
}
