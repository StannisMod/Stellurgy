package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.client.gui.GuiGameOver;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * Records the tick the death screen's buttons become clickable: {@code death_screen_ready}.
 *
 * <p>Vanilla enables them inside {@code updateScreen}, on the update whose counter reaches 20, and
 * {@code initGui} sets that counter back to 0 — and a dead client builds a FRESH death screen whenever
 * anything closes the current one ({@code Minecraft#displayGuiScreen(null)} with health at 0 opens a
 * new {@code GuiGameOver}). So "twenty ticks after the screen opened" names the wrong screen as soon
 * as the server moves the dead body and the client reloads its world; the record is taken where the
 * decision is made instead, on the screen that made it.</p>
 *
 * <p>Test-only: test source set, client mixin list, absent from a released jar.</p>
 */
@Mixin(GuiGameOver.class)
public abstract class MixinGuiGameOverReady {

    @Shadow
    private int enableButtonsTimer;

    @Inject(method = "updateScreen", at = @At("RETURN"))
    private void stellurgyTest$recordReady(CallbackInfo ci) {
        TestTrace.instrumentHere("death_screen_events");
        if (enableButtonsTimer == 20) {
            TestTrace.recordHere("death_screen_ready", "\"screen\":" + System.identityHashCode(this));
        }
    }
}
