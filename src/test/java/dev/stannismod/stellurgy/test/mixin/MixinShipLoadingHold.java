package dev.stannismod.stellurgy.test.mixin;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.valkyrienskies.mod.common.config.VSConfig;
import org.valkyrienskies.mod.common.ships.ship_world.WorldServerShipManager;

import dev.stannismod.stellurgy.test.trace.ShipLoadHold;

/**
 * At the substrate's load/unload decision, a ship counts as permanently loaded when the player's own
 * setting says so OR the test server holds ships loaded ({@link ShipLoadHold}). The player's setting
 * is read exactly as before, so with the hold off the decision is the shipped one.
 *
 * <p>No {@code require = 0}: a hold that silently failed to apply would unload every craft a headless
 * scenario assembles, and the scenario would fail far from the cause. Test source set.</p>
 */
// remap = false: the target is a Valkyrien Skies class, whose names are not SRG-remapped.
@Mixin(targets = "org.valkyrienskies.mod.common.ships.ship_world.WorldShipLoadingController", remap = false)
public abstract class MixinShipLoadingHold {

    @Shadow @Final private WorldServerShipManager shipManager;

    @Redirect(method = "determineLoadAndUnload",
            at = @At(value = "FIELD",
                    target = "Lorg/valkyrienskies/mod/common/config/VSConfig$ShipLoadingSettings;permanentlyLoaded:Z",
                    opcode = Opcodes.GETFIELD))
    private boolean stellurgyTest$permanentlyLoaded(VSConfig.ShipLoadingSettings settings) {
        return settings.permanentlyLoaded
                || ((ShipLoadHold) shipManager.getWorld().getMinecraftServer()).stellurgyTest$shipsHeldLoaded();
    }
}
