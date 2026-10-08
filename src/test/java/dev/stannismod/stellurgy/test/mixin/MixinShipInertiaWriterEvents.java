package dev.stannismod.stellurgy.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.ships.physics_data.ShipInertiaData;

import dev.stannismod.stellurgy.integration.vs.ShipInertiaWriter;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The authoritative hull pass was COMPARED with the running total — {@code ship_mass_compared}, with
 * the verdict production reached.
 *
 * <p>{@code compare} is where the drift is decided, so its return value is the answer itself:
 * {@code agrees} is that verdict, written on every record, and {@code drift} is the production
 * description (sign and all) — empty when they agree. A record that carries its own verdict needs no
 * second record to be read against: the absence of a "drift" record would be a conclusion from
 * silence, and this makes it a field. The verdict is a boolean rather than a null {@code drift}
 * because a reader cannot tell a JSON null from a field that was never written.</p>
 *
 * <p>{@code ship} is the name the caller handed in, which the mass trigger fills with the physics
 * identity. Server thread only (the trigger runs inside the ship manager's tick), hence
 * {@code recordServer}.</p>
 */
@Mixin(value = ShipInertiaWriter.class, remap = false)
public abstract class MixinShipInertiaWriterEvents {

    private static final String INSTRUMENT = "ship_mass_compare";

    @Inject(method = "compare", require = 1, at = @At("RETURN"))
    private static void stellurgyTest$compared(ShipInertiaData record, ShipMassFrame authority, String shipName,
                                               CallbackInfoReturnable<ShipInertiaWriter.Drift> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        ShipInertiaWriter.Drift drift = cir.getReturnValue();
        TestTrace.recordServer("ship_mass_compared", "\"ship\":\"" + shipName + "\""
                + ",\"agrees\":" + (drift == null)
                + ",\"drift\":\"" + (drift == null ? "" : TestTrace.json(drift.toString())) + "\"");
    }
}
