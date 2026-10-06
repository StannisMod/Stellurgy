package dev.stannismod.stellurgy.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import dev.stannismod.stellurgy.projectile.LayerCrossing;
import dev.stannismod.stellurgy.projectile.Shot;
import dev.stannismod.stellurgy.projectile.ShotSubstrate;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * What one crossing of a shot's step FOUND, before anything is decided from it.
 *
 * <p>A round that crossed a wall spending nothing and a round whose impact was refused leave the
 * same picture behind — the wall unmarked, the budget whole. They differ in one question inside
 * {@code ShotSubstrate#step}: which layer answered a distance along the segment, and which answered
 * first. That question is {@code LayerCrossing.along}, called once per crossing, and this records
 * its answer each time it is asked.</p>
 *
 * <ul>
 *   <li>{@code shot_crossing_decided} — every return of {@code LayerCrossing.along} inside
 *       {@code step}. Payload: {@code shot} (the shot's id), {@code age}, {@code hullAsked} (the hull
 *       the question was narrowed to, or {@code null}), {@code fromX..fromZ} / {@code toX..toZ} (the
 *       segment asked about, world frame), {@code radius}, {@code field} and {@code structure} (the
 *       shield layer's and the structure layer's distance along the segment in blocks, {@code -1}
 *       when that layer found nothing — kept apart rather than collapsed into a verdict, because the
 *       verdict is what a red is about), {@code fieldFirst}, and {@code block} ({@code "x,y,z name"}
 *       of the first solid block, or {@code null}).</li>
 * </ul>
 *
 * <p>The instrument {@code shot_crossing_decisions} is declared on every call, whether or not
 * anything was found. SILENT about the held beam's crossings ({@code HeldBeam} asks the same
 * question from its own method) and about what the step then did with the answer.</p>
 *
 * <p>The target is a class the project compiles, with no vanilla member in the redirected call, so
 * nothing here is SRG-remapped.</p>
 */
@Mixin(value = ShotSubstrate.class, remap = false)
public abstract class MixinShotSubstrateCrossingEvents {

    private static final String INSTRUMENT = "shot_crossing_decisions";

    @Redirect(method = "step",
            at = @At(value = "INVOKE",
                    target = "Ldev/stannismod/stellurgy/projectile/LayerCrossing;along("
                            + "Lnet/minecraft/world/World;Lnet/minecraft/util/math/Vec3d;"
                            + "Lnet/minecraft/util/math/Vec3d;DLjava/lang/String;)"
                            + "Ldev/stannismod/stellurgy/projectile/LayerCrossing$First;",
                    remap = false),
            require = 1, remap = false)
    private static LayerCrossing.First stellurgyTest$crossingDecided(World world, Vec3d from, Vec3d to,
                                                                     double radius, String hullAsked,
                                                                     World stepWorld, Shot shot) {
        // The original question, asked once: this observes the answer and must not ask twice.
        LayerCrossing.First first = LayerCrossing.along(world, from, to, radius, hullAsked);
        if (world == null || world.isRemote) {
            return first;
        }
        TestTrace.instrument(world, INSTRUMENT);
        Object structure = first.structure;
        double structureDistance = -1.0D;
        String block = "null";
        if (structure != null) {
            StructureHitAccessor hit = (StructureHitAccessor) structure;
            structureDistance = hit.stellurgyTest$distance();
            BlockPos at = hit.stellurgyTest$block();
            block = "\"" + at.getX() + "," + at.getY() + "," + at.getZ() + " "
                    + world.getBlockState(at).getBlock().getRegistryName() + "\"";
        }
        TestTrace.record(world, "shot_crossing_decided", "\"shot\":" + shot.getId()
                + ",\"age\":" + shot.getAge()
                + ",\"hullAsked\":" + (hullAsked == null ? "null" : "\"" + TestTrace.json(hullAsked) + "\"")
                + ",\"fromX\":" + from.x + ",\"fromY\":" + from.y + ",\"fromZ\":" + from.z
                + ",\"toX\":" + to.x + ",\"toY\":" + to.y + ",\"toZ\":" + to.z
                + ",\"radius\":" + radius
                + ",\"field\":" + (first.isField() ? first.distance : -1.0D)
                + ",\"structure\":" + structureDistance
                + ",\"fieldFirst\":" + first.isField()
                + ",\"block\":" + block);
        return first;
    }
}
