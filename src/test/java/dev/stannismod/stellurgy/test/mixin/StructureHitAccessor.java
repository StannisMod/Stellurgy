package dev.stannismod.stellurgy.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.util.math.BlockPos;

/**
 * Read access to a structure crossing's own answer — how far along the segment the first solid
 * block lies, and which block it is — for {@link MixinShotSubstrateCrossingEvents}.
 *
 * <p>{@code StructureCrossing.Hit} is package-private in the projectile package and its fields with
 * it, so a recorder in the test tree cannot name them. This reads the two fields production already
 * keeps; it adds no state and changes no answer.</p>
 */
@Mixin(targets = "dev.stannismod.stellurgy.projectile.StructureCrossing$Hit", remap = false)
public interface StructureHitAccessor {

    @Accessor(value = "distance", remap = false)
    double stellurgyTest$distance();

    @Accessor(value = "block", remap = false)
    BlockPos stellurgyTest$block();
}
