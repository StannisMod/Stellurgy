package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.event.world.ExplosionEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.affs.te.TileEntityFieldGenerator;
import dev.stannismod.stellurgy.affs.world.ForceFieldExplosionHandler;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The shield's answer to a real explosion, as two events around the one handler that decides it.
 *
 * <ul>
 *   <li><b>{@code shield_explosion_heard}</b> — the HEAD of {@code onExplosionDetonate}: {@code at}
 *       (the blast's centre, {@code x,y,z}), {@code candidates} (every block the blast would take, as
 *       {@code "x,y,z"}, before any emitter has taken anything out) and {@code emitters} — every
 *       emitter registered in the blast's world, each as {@code pos}, {@code powered} (the lit-ness
 *       the handler is about to ask) and {@code radius} (the radius it projects in its current
 *       condition). Read at the moment of the decision, so a reader's "lit and shrunk" is the state
 *       the decision was taken in, not one read a probe call earlier or later.</li>
 *   <li><b>{@code shield_explosion_decided}</b> — the RETURN of the same call: {@code at} and
 *       {@code destroyed}, the blocks still in the blast once every emitter has taken out what it
 *       protects. That removal is where production COMBINES the emitters, so a block that is absent
 *       here and present in {@code candidates} was saved by some emitter's decision.</li>
 * </ul>
 *
 * <p>Server log, filed against the blast's world. SILENT about a client world, and about every
 * Detonate subscriber other than this handler: a block another mod saves after it is still listed
 * in {@code destroyed}. Read by {@code ShieldDamageDegradesTest}.</p>
 */
@Mixin(ForceFieldExplosionHandler.class)
public abstract class MixinForceFieldExplosionHandlerEvents {

    private static final String INSTRUMENT = "shield_explosion_events";

    @Inject(method = "onExplosionDetonate", at = @At("HEAD"), require = 1, remap = false)
    private static void stellurgyTest$heard(ExplosionEvent.Detonate event, CallbackInfo ci) {
        World world = event.getWorld();
        if (world == null || world.isRemote) {
            return;
        }
        TestTrace.instrument(world, INSTRUMENT);
        StringBuilder emitters = new StringBuilder();
        for (TileEntityFieldGenerator emitter : TileEntityFieldGenerator.loadedIn(world)) {
            if (emitters.length() > 0) {
                emitters.append(',');
            }
            BlockPos pos = emitter.getPos();
            emitters.append("{\"pos\":\"").append(pos.getX()).append(',').append(pos.getY()).append(',')
                    .append(pos.getZ()).append("\",\"powered\":").append(emitter.isFieldPowered())
                    .append(",\"radius\":").append(emitter.getRadius()).append('}');
        }
        TestTrace.record(world, "shield_explosion_heard", stellurgyTest$at(event)
                + ",\"candidates\":" + stellurgyTest$positions(event.getAffectedBlocks())
                + ",\"emitters\":[" + emitters + "]");
    }

    @Inject(method = "onExplosionDetonate", at = @At("RETURN"), require = 1, remap = false)
    private static void stellurgyTest$decided(ExplosionEvent.Detonate event, CallbackInfo ci) {
        World world = event.getWorld();
        if (world == null || world.isRemote) {
            return;
        }
        TestTrace.instrument(world, INSTRUMENT);
        TestTrace.record(world, "shield_explosion_decided", stellurgyTest$at(event)
                + ",\"destroyed\":" + stellurgyTest$positions(event.getAffectedBlocks()));
    }

    @Unique
    private static String stellurgyTest$at(ExplosionEvent.Detonate event) {
        Vec3d at = event.getExplosion().getPosition();
        return "\"at\":\"" + at.x + "," + at.y + "," + at.z + "\"";
    }

    @Unique
    private static String stellurgyTest$positions(Iterable<BlockPos> blocks) {
        StringBuilder out = new StringBuilder("[");
        for (BlockPos pos : blocks) {
            if (out.length() > 1) {
                out.append(',');
            }
            out.append('"').append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ())
                    .append('"');
        }
        return out.append(']').toString();
    }
}
