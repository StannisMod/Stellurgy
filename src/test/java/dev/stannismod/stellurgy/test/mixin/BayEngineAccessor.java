package dev.stannismod.stellurgy.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import dev.stannismod.stellurgy.damage.repair.BayEngine;

/**
 * Read access to the energy a repair machine's {@link BayEngine} has banked toward its next step,
 * for the recorders of the machines that hold one ({@link MixinTileRepairBayEvents},
 * {@link MixinTileRocketServiceStationEvents}).
 *
 * <p>The engine keeps it private and answers nothing about it, because nothing in production asks.
 * This reads the field production already keeps; it adds no state and changes no answer.</p>
 */
@Mixin(value = BayEngine.class, remap = false)
public interface BayEngineAccessor {

    @Accessor(value = "banked", remap = false)
    int stellurgyTest$banked();
}
