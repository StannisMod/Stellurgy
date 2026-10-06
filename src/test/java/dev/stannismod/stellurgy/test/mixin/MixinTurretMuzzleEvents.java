package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

import dev.stannismod.stellurgy.api.weapon.GunSpec;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.weapon.TurretFireControl;

/**
 * Where a body leaves a gun and what motion it inherits, as an event: {@code turret_muzzle}.
 *
 * <p>The RETURN of {@code TurretFireControl#muzzleOf}, the one place every weapon family asks "where,
 * along what, carrying what". Carries the gun's {@code x/y/z}, the {@code ship} it stands on (empty on
 * the ground), the world muzzle {@code px/py/pz}, the world direction {@code dx/dy/dz} and the
 * inherited motion {@code cx/cy/cz} — production's own numbers, in blocks per tick. A reader compares
 * the inherited motion against what the hull was measured doing. Server log. Read by
 * {@code TurretOnAShipE2ETest#aRoundFromAMovingHullCarriesTheHullsMotionPerTick}.</p>
 *
 * <p>SILENT about a gun that may not fire at all (a null muzzle: no clear line of fire, an unnamed ship,
 * an inoperable build).</p>
 */
@Mixin(value = TurretFireControl.class, remap = false)
public abstract class MixinTurretMuzzleEvents {

    private static final String INSTRUMENT = "turret_muzzle_events";

    @Inject(method = "muzzleOf", at = @At("RETURN"), require = 1)
    private static void stellurgyTest$muzzle(World world, BlockPos mountPos, String shipId, Vec3d localAim,
                                             GunSpec spec, int reach, Random random,
                                             CallbackInfoReturnable<TurretFireControl.Muzzle> cir) {
        TurretFireControl.Muzzle muzzle = cir.getReturnValue();
        if (world == null || world.isRemote || muzzle == null) {
            return;
        }
        TestTrace.instrument(world, INSTRUMENT);
        TestTrace.record(world, "turret_muzzle", "\"x\":" + mountPos.getX() + ",\"y\":" + mountPos.getY()
                + ",\"z\":" + mountPos.getZ()
                + ",\"ship\":\"" + (shipId == null ? "" : TestTrace.json(shipId)) + "\""
                + ",\"px\":" + muzzle.point.x + ",\"py\":" + muzzle.point.y + ",\"pz\":" + muzzle.point.z
                + ",\"dx\":" + muzzle.direction.x + ",\"dy\":" + muzzle.direction.y
                + ",\"dz\":" + muzzle.direction.z
                + ",\"cx\":" + muzzle.carried.x + ",\"cy\":" + muzzle.carried.y + ",\"cz\":" + muzzle.carried.z);
    }
}
