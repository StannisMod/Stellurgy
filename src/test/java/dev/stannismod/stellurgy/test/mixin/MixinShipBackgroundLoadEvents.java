package dev.stannismod.stellurgy.test.mixin;

import dev.stannismod.stellurgy.test.trace.TestTrace;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.ships.ship_world.WorldServerShipManager;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * {@code ship_background_load_requested} — the ship manager was asked to load a ship IN THE BACKGROUND
 * and was not already doing so.
 *
 * <p>The request is renewed every tick for every unloaded ship in background range of a player or under
 * {@code permanentlyLoaded}, so the seam records the EDGE: a request for a ship that is neither queued for
 * a background load nor loading in the background. A request the manager already holds writes nothing, so
 * one record stands for a whole stretch of renewals.</p>
 *
 * <p>SILENT about the IMMEDIATE load queue, and about whether the background load ever completes — the
 * manager completes one only by an immediate load, which announces itself as a {@code LOADED} naming.</p>
 */
// remap = false: a Valkyrien Skies class, whose names are identical in dev and reobf.
@Mixin(value = WorldServerShipManager.class, remap = false)
public abstract class MixinShipBackgroundLoadEvents {

    private static final String INSTRUMENT = "ship_background_load_events";

    @Shadow @Final private WorldServer world;
    @Shadow @Final private LinkedHashSet<UUID> backgroundLoadQueue;
    @Shadow @Final private Set<UUID> loadingInBackground;

    @Inject(method = "queueShipLoadBackground", at = @At("HEAD"), require = 1)
    private void stellurgyTest$backgroundLoadRequested(UUID shipID, CallbackInfo ci) {
        TestTrace.instrument(world, INSTRUMENT);
        if (backgroundLoadQueue.contains(shipID) || loadingInBackground.contains(shipID)) {
            return;
        }
        TestTrace.record(world, "ship_background_load_requested", "\"ship\":\"" + shipID + "\""
                + ",\"dim\":" + world.provider.getDimension());
    }
}
