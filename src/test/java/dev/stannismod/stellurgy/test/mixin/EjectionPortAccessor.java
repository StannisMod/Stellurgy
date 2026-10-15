package dev.stannismod.stellurgy.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import dev.stannismod.stellurgy.subsystem.ejection.EjectionPort;

/**
 * Read access to how hard an {@link EjectionPort} pushes a stack out of its mouth, for its recorder
 * ({@link MixinEjectionPortEvents}), which publishes it beside the release.
 *
 * <p>The port keeps it private and answers nothing about it, because nothing in production asks. This
 * reads the constant production already holds; it adds no state and changes no answer.</p>
 */
@Mixin(value = EjectionPort.class, remap = false)
public interface EjectionPortAccessor {

    @Accessor(value = "EJECT_SPEED", remap = false)
    static double stellurgyTest$ejectSpeed() {
        throw new AssertionError("replaced by the mixin");
    }
}
