package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.world.BlockEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.damage.DamageInvalidationHandler;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The damage map being told that a PLAYER changed a block, as an event:
 * {@code damage_invalidation_heard}, with {@code cause} ({@code break} or {@code place}), {@code dim}
 * and {@code pos}.
 *
 * <p>The HEAD of the two subscribers, which is where the bus hands the map a player's break or
 * placement — so the record says the event ARRIVED, and says nothing about what the handler then did
 * with it; that is read off the map itself. A cancelled event never reaches these subscribers (they
 * sit at the lowest priority and do not receive cancelled events), so a vetoed break writes no
 * record. Server log, filed against the world the block is in. Read by
 * {@code APlayersHandRepairsE2ETest}.</p>
 *
 * <p>SILENT about a block changed by anything that fires neither event — the damage engine, a
 * relocation's cut, a probe's fill — and about a client world.</p>
 */
@Mixin(DamageInvalidationHandler.class)
public abstract class MixinDamageInvalidationHandlerEvents {

    private static final String INSTRUMENT = "damage_invalidation_events";

    @Inject(method = "onBlockBroken", at = @At("HEAD"), require = 1, remap = false)
    private void stellurgyTest$broken(BlockEvent.BreakEvent event, CallbackInfo ci) {
        stellurgyTest$heard("break", event.getWorld(), event.getPos());
    }

    @Inject(method = "onBlockPlaced", at = @At("HEAD"), require = 1, remap = false)
    private void stellurgyTest$placed(BlockEvent.PlaceEvent event, CallbackInfo ci) {
        stellurgyTest$heard("place", event.getWorld(), event.getPos());
    }

    @Unique
    private static void stellurgyTest$heard(String cause, World world, BlockPos pos) {
        if (world == null || pos == null || world.isRemote) {
            return;
        }
        TestTrace.instrument(world, INSTRUMENT);
        TestTrace.record(world, "damage_invalidation_heard", "\"cause\":\"" + cause + "\""
                + ",\"dim\":" + world.provider.getDimension()
                + ",\"pos\":\"" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "\"");
    }
}
