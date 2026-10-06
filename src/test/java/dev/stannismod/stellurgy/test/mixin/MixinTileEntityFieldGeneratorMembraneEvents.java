package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.stannismod.stellurgy.affs.te.TileEntityFieldGenerator;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * A shield emitter's field coming up or going down, and a player it holds at its membrane, as events.
 *
 * <ul>
 *   <li><b>{@code field_power_changed}</b> — the RETURN of {@code refreshFieldPowerState} when it
 *       answers that the lit state CHANGED: {@code pos} and {@code powered}, the state it changed to.
 *       The one place that state is written on the server, so an arrangement waits for its shield to
 *       light on this record rather than sampling the tile.</li>
 *   <li><b>{@code shield_player_held}</b> — the RETURN of {@code tryAbsorbEntityImpact} for a PLAYER,
 *       when it answers {@code true}: this emitter decided to hold him at its membrane and paid for it,
 *       and he has been put back on the side it holds him on. {@code pos} is the emitter, {@code player}
 *       the player's name. Written once per tick he is held, so a player leaning on the membrane
 *       writes one a tick.</li>
 * </ul>
 *
 * <p>Server log. SILENT about every entity that is not a player, about an emitter that let a player
 * through (the method answers {@code false} for that and for "not touching me" alike — the two are
 * one answer at this seam), and about a client world, where neither method decides anything. Read by
 * {@code ShieldMembraneClientGroupTest}.</p>
 *
 * <p>The target is a class the project compiles, so nothing here is SRG-remapped.</p>
 */
@Mixin(value = TileEntityFieldGenerator.class, remap = false)
public abstract class MixinTileEntityFieldGeneratorMembraneEvents {

    private static final String POWER_INSTRUMENT = "field_power_events";
    private static final String HELD_INSTRUMENT = "shield_player_held_events";

    @Inject(method = "refreshFieldPowerState", at = @At("RETURN"), require = 1, remap = false)
    private void stellurgyTest$powerChanged(boolean syncSnapshot, CallbackInfoReturnable<Boolean> cir) {
        TileEntityFieldGenerator self = (TileEntityFieldGenerator) (Object) this;
        World world = self.getWorld();
        if (world == null || world.isRemote) {
            return;
        }
        TestTrace.instrument(world, POWER_INSTRUMENT);
        if (!Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        TestTrace.record(world, "field_power_changed", stellurgyTest$pos(self.getPos())
                + ",\"powered\":" + self.isFieldPowered());
    }

    @Inject(method = "tryAbsorbEntityImpact", at = @At("RETURN"), require = 1, remap = false)
    private void stellurgyTest$held(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        TileEntityFieldGenerator self = (TileEntityFieldGenerator) (Object) this;
        World world = self.getWorld();
        if (world == null || world.isRemote) {
            return;
        }
        TestTrace.instrument(world, HELD_INSTRUMENT);
        if (!(entity instanceof EntityPlayer) || !Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        TestTrace.record(world, "shield_player_held", stellurgyTest$pos(self.getPos())
                + ",\"player\":\"" + TestTrace.json(entity.getName()) + "\"");
    }

    @Unique
    private static String stellurgyTest$pos(BlockPos pos) {
        return "\"pos\":\"" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "\"";
    }
}
