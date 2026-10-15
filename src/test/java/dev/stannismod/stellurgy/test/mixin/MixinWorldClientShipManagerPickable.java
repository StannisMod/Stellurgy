package dev.stannismod.stellurgy.test.mixin;

import com.github.stannismod.forge.testing.client.bridge.ForgeTestClientBootstrap;
import dev.stannismod.stellurgy.test.trace.ClientShipPickable;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;
import org.valkyrienskies.mod.common.ships.ship_world.WorldClientShipManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The half of {@link ClientShipPickable} that the client's ship manager completes: a queued ship LOADED
 * into the manager this tick, whose claimed chunks had already arrived.
 *
 * <p>The ids are taken from the load queue at the head of the pass and looked up at its tail, because
 * the pass empties the queue and skips an id it cannot resolve — only an id the manager actually holds
 * afterwards was loaded. The snapshot is a field of the manager, which lives with its client world.</p>
 *
 * <p>Observation: reads the queue and the loaded map, changes neither. Client. Test source set.</p>
 */
@Mixin(value = WorldClientShipManager.class, remap = false)
public abstract class MixinWorldClientShipManagerPickable {

    @Shadow
    @Final
    private World world;

    @Shadow
    @Final
    private Map<UUID, PhysicsObject> loadedShips;

    @Shadow
    @Final
    private LinkedHashSet<UUID> loadQueue;

    /** Written at the head of each load pass, read at its tail; the manager's own lifetime. */
    @Unique
    private List<UUID> stellurgyTest$queuedThisPass;

    @Inject(method = "loadAndUnloadShips", at = @At("HEAD"), remap = false, require = 1)
    private void stellurgyTest$pickableQueued(CallbackInfo ci) {
        ForgeTestClientBootstrap.noteInstrumentEntered(ClientShipPickable.RECORD);
        stellurgyTest$queuedThisPass = loadQueue.isEmpty() ? null : new ArrayList<>(loadQueue);
    }

    @Inject(method = "loadAndUnloadShips", at = @At("TAIL"), remap = false, require = 1)
    private void stellurgyTest$pickableLoaded(CallbackInfo ci) {
        if (stellurgyTest$queuedThisPass == null) {
            return;
        }
        for (UUID id : stellurgyTest$queuedThisPass) {
            PhysicsObject ship = loadedShips.get(id);
            if (ship != null) {
                ClientShipPickable.recordIfWhole(world, ship, "ship_loaded");
            }
        }
        stellurgyTest$queuedThisPass = null;
    }
}
