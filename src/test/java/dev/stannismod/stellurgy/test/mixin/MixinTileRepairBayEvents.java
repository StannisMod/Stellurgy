package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.stannismod.stellurgy.damage.RepairOutcome;
import dev.stannismod.stellurgy.damage.repair.TileRepairBay;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * A repair bay's two decisions nothing else announces, as events.
 *
 * <ul>
 *   <li>{@code repair_bay_outcome} — at the HEAD of {@code TileRepairBay#settle}, the one place a
 *       bay's answer changes: {@code bay} is the controller's position, {@code from} what it said
 *       before ({@code none} before its first look) and {@code to} what it says now. Recorded only
 *       when the two differ, so a bay that keeps giving one answer writes it once.</li>
 *   <li>{@code repair_bay_rebuilt} — at the RETURN of {@code TileRepairBay#rebuild}: {@code pos} is
 *       the hole the bay was working, {@code placed} whether a block was put there, and
 *       {@code block} what stands there now, and {@code reserve} how many items the bay's reserve
 *       holds as the rebuild returns.</li>
 * </ul>
 *
 * <p>A stage a bay takes off a block is NOT recorded here: it goes through
 * {@code DamageState.setStage}, whose own record ({@code block_stage_set}) already says it. Server
 * log, filed against the bay's world.</p>
 *
 * <p>Read by the repair bay methods of {@code RepairWelderE2ETest}.</p>
 */
@Mixin(TileRepairBay.class)
public abstract class MixinTileRepairBayEvents {

    private static final String INSTRUMENT = "repair_bay_events";

    @Shadow
    private RepairOutcome outcome;

    @Shadow
    private BlockPos job;

    @Inject(method = "settle", at = @At("HEAD"), require = 1)
    private void stellurgyTest$settled(RepairOutcome next, CallbackInfo ci) {
        TileEntity self = (TileEntity) (Object) this;
        World world = self.getWorld();
        if (world == null || world.isRemote) {
            return;
        }
        TestTrace.instrument(world, INSTRUMENT);
        if (outcome == next) {
            return;
        }
        TestTrace.record(world, "repair_bay_outcome", "\"dim\":" + world.provider.getDimension()
                + ",\"bay\":\"" + at(self.getPos()) + "\""
                + ",\"from\":\"" + (outcome == null ? "none" : outcome.name()) + "\""
                + ",\"to\":\"" + next.name() + "\"");
    }

    @Inject(method = "rebuild", at = @At("RETURN"), require = 1)
    private void stellurgyTest$rebuilt(CallbackInfoReturnable<Boolean> cir) {
        TileEntity self = (TileEntity) (Object) this;
        World world = self.getWorld();
        if (world == null || world.isRemote || job == null) {
            return;
        }
        TestTrace.instrument(world, INSTRUMENT);
        IInventory reserve = (IInventory) self;
        int held = 0;
        for (int i = 0; i < reserve.getSizeInventory(); i++) {
            held += reserve.getStackInSlot(i).getCount();
        }
        TestTrace.record(world, "repair_bay_rebuilt", "\"dim\":" + world.provider.getDimension()
                + ",\"bay\":\"" + at(self.getPos()) + "\""
                + ",\"pos\":\"" + at(job) + "\""
                + ",\"placed\":" + cir.getReturnValue()
                + ",\"block\":\"" + world.getBlockState(job).getBlock().getRegistryName() + "\""
                + ",\"reserve\":" + held);
    }

    private static String at(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
