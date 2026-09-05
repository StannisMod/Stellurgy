package zmaster587.advancedRocketry.test.mixin;

import java.util.UUID;

import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.command.test.TestEventLog;
import zmaster587.advancedRocketry.space.LoginRestore;
import zmaster587.advancedRocketry.space.ShipAboardTag;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * Where a returning player was PUT, and why — the login restore's own verdict, as an event.
 *
 * <p>{@code LoginRestore.resolve} is a pure function from the player's aboard record to a
 * {@code Placement} carrying the dimension, the reason and whether he counts as aboard; the login
 * hook then applies it. Recording the RETURN of the pure function reads production's decision at the
 * one place it is made, with no local to capture and no second derivation. A relog scenario can then
 * assert the restore fell INSIDE the flight (`transit_departed → login_restored →
 * hyperspace_arrival_cut`) and read where the restore placed him, instead of inferring both from the
 * client's dimension after the fact.</p>
 */
@Mixin(LoginRestore.class)
public abstract class MixinLoginRestoreEvents {

    private static final String INSTRUMENT = "login_restore_events";

    @Inject(method = "resolve", at = @At("RETURN"))
    private static void arTest$resolved(ShipAboardTag.Aboard tag, LoginRestore.Ops ops, UUID playerId,
                                        CallbackInfoReturnable<LoginRestore.Placement> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        LoginRestore.Placement p = cir.getReturnValue();
        String payload = "\"player\":\"" + playerId + "\",\"tagged\":" + (tag != null)
                + ",\"posture\":\"" + (tag == null ? "none" : tag.posture) + "\"";
        if (p == null) {
            payload += ",\"placement\":\"null\"";
        } else {
            payload += ",\"reason\":\"" + p.reason + "\",\"dim\":" + p.dimension + ",\"aboard\":"
                    + p.aboard + ",\"ship\":\"" + p.shipId + "\",\"y\":" + TestTrace.fmt(p.y);
        }
        WorldServer overworld = DimensionManager.getWorld(0);
        TestEventLog.record("server", overworld == null ? 0L : overworld.getTotalWorldTime(),
                "login_restored", payload);
    }
}
