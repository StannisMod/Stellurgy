package dev.stannismod.stellurgy.test.mixin;

import java.util.UUID;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.api.event.ShipLifecycleEvent;
import dev.stannismod.stellurgy.integration.vs.ShipMassTrigger;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * A ship's mass was MEASURED from its hull and handed to the physics record — {@code ship_mass_measured}.
 *
 * <p>Taken at the call to {@code ShipInertiaWriter.applyTo} in each of the trigger's two paths, which
 * is past every early return: a record here means a frame existed and the craft was there to take it,
 * which is what "the recompute ran and found a hull" means. {@code path} says which of the two it was
 * — {@code event} for the naming announcement (with its {@code cause}), {@code round} for the flight
 * computer's cadence — because a scenario that measures the cadence must not be satisfied by the
 * assembly's own recompute.</p>
 *
 * <p>Both paths declare the instrument at HEAD, so a window with no record separates "nothing was
 * measured" from "the trigger never ran".</p>
 *
 * <h2>What it is silent about</h2>
 *
 * <p>Whether the write TOOK: {@code applyTo} refuses a singular tensor, and that refusal is pinned by
 * the writer's unit test, not here. And the drift verdict, which is not the trigger's to make — it is
 * {@code ShipInertiaWriter.compare}'s, recorded by {@link MixinShipInertiaWriterEvents}.</p>
 */
@Mixin(value = ShipMassTrigger.class, remap = false)
public abstract class MixinShipMassTriggerEvents {

    private static final String INSTRUMENT = "ship_mass_events";

    private static final String APPLY_TO = "Ldev/stannismod/stellurgy/integration/vs/ShipInertiaWriter;applyTo"
            + "(Lorg/valkyrienskies/mod/common/ships/physics_data/ShipInertiaData;"
            + "Ldev/stannismod/stellurgy/ship/mass/ShipMassFrame;Ljava/lang/String;)Z";

    @Inject(method = "recompute", require = 1, at = @At("HEAD"))
    private static void stellurgyTest$recomputeEntered(ShipLifecycleEvent.ShipNamed event, CallbackInfo ci) {
        TestTrace.instrument(event.world, INSTRUMENT);
    }

    @Inject(method = "recompute", require = 1, at = @At(value = "INVOKE", target = APPLY_TO))
    private static void stellurgyTest$measuredOnEvent(ShipLifecycleEvent.ShipNamed event, CallbackInfo ci) {
        TestTrace.record(event.world, "ship_mass_measured", "\"ship\":\"" + event.shipUuid + "\""
                + ",\"path\":\"event\",\"cause\":\"" + event.cause + "\""
                + ",\"dim\":" + event.world.provider.getDimension());
    }

    @Inject(method = "backgroundRound", require = 1, at = @At("HEAD"))
    private static void stellurgyTest$roundEntered(World world, UUID shipUuid, CallbackInfo ci) {
        TestTrace.instrument(world, INSTRUMENT);
    }

    @Inject(method = "backgroundRound", require = 1, at = @At(value = "INVOKE", target = APPLY_TO))
    private static void stellurgyTest$measuredOnRound(World world, UUID shipUuid, CallbackInfo ci) {
        TestTrace.record(world, "ship_mass_measured", "\"ship\":\"" + shipUuid + "\""
                + ",\"path\":\"round\",\"dim\":" + world.provider.getDimension());
    }
}
